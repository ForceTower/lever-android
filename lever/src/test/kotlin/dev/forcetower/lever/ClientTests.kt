package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** The client core: staging, the commit gate, updates, and close (plan 0003 M4). */
internal class ClientTests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    private fun TestScope.harness(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher =
            kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
    ): TestHarness = TestHarness(testScheduler, dispatcher = dispatcher).also { harnesses.add(it) }

    /** Every client here is driven by hand: no transport, no runtime work. */
    private fun TestHarness.cacheOnlyClient() = client(configuration(automaticUpdates = false))

    private fun representation(
        version: Int,
        values: Map<String, WireValue>,
        etag: String? = null,
        fetchedAt: Long = 1_755_100_000,
    ) = Representation(version, values, etag, fetchedAt, null)

    private val flag = LeverKey.boolean("flag", default = false)
    private val retries = LeverKey.int("retries", default = 1)

    // MARK: staging

    @Test
    fun `staging does not change reads until activation`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()

        assertFalse(client[flag])
        assertNull(client.activatedVersion)

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        assertFalse(client[flag], "a staged payload must not reach reads")
        assertNull(client.activatedVersion)

        assertTrue(client.activate())
        assertTrue(client[flag])
        assertEquals(1, client.activatedVersion)
        client.close()
    }

    @Test
    fun `activating nothing is a no-op`() = runTest {
        val client = harness().cacheOnlyClient()
        assertFalse(client.activate())
        assertNull(client.activatedVersion)
        client.close()
    }

    @Test
    fun `a metadata-only commit advances version and etag silently`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        val updates = mutableListOf<LeverUpdate>()
        backgroundScope.launch { client.updates.collect { updates.add(it) } }
        settle()

        client.stage(representation(1, mapOf("flag" to wireBool(true)), etag = "\"a\""))
        assertTrue(client.activate())

        // Same values, new version and ETag: a commit, not an observable change.
        client.stage(representation(2, mapOf("flag" to wireBool(true)), etag = "\"b\""))
        assertFalse(client.activate())
        settle()

        assertEquals(2, client.activatedVersion)
        assertEquals(1, updates.size)
        assertEquals(LeverUpdate(1, setOf("flag")), updates.single())
        assertTrue(harness.sink.contains(LeverLogLevel.DEBUG, "committed version=2 changed=0"))

        // …and it survives a relaunch, ETag included.
        val restarted = harness.cacheOnlyClient()
        assertEquals(2, restarted.activatedVersion)
        assertEquals("\"b\"", restarted.newestRepresentation()?.representation?.etag)
        client.close()
        restarted.close()
    }

    @Test
    fun `activating version zero commits like any other`() = runTest {
        val client = harness().cacheOnlyClient()
        client.stage(representation(0, emptyMap()))
        assertFalse(client.activate(), "no values changed")
        assertEquals(0, client.activatedVersion, "version 0 must not stick at null")
        client.close()
    }

    @Test
    fun `changedKeys is the exact raw diff`() = runTest {
        val client = harness().cacheOnlyClient()
        val updates = mutableListOf<LeverUpdate>()
        backgroundScope.launch { client.updates.collect { updates.add(it) } }
        settle()

        client.stage(
            representation(
                1,
                mapOf("kept" to wireBool(true), "changed" to wireNumber("1"), "removed" to wireString("x")),
            )
        )
        assertTrue(client.activate())
        client.stage(
            representation(
                2,
                mapOf("kept" to wireBool(true), "changed" to wireNumber("2"), "added" to wireString("y")),
            )
        )
        assertTrue(client.activate())
        settle()

        assertEquals(setOf("changed", "added", "removed"), updates.last().changedKeys)
        assertEquals(2, updates.last().version)
        client.close()
    }

    @Test
    fun `numbers that differ only in spelling are not a change`() = runTest {
        val client = harness().cacheOnlyClient()
        client.stage(representation(1, mapOf("retries" to wireNumber("3"))))
        assertTrue(client.activate())
        client.stage(representation(2, mapOf("retries" to wireNumber("3.0"))))
        assertFalse(client.activate(), "3 and 3.0 are the same JSON number")
        client.close()
    }

    // MARK: reads

    @Test
    fun `reads serve the cached snapshot before any coroutine runs`() = runTest {
        val harness = harness()
        harness.seedCache(7, mapOf("flag" to wireBool(true)))

        val client = harness.cacheOnlyClient()
        // No advanceUntilIdle: the floor is structural, not scheduled.
        assertTrue(client[flag])
        assertEquals(7, client.activatedVersion)
        client.close()
    }

    @Test
    fun `one wire key decodes correctly through two kotlin types`() = runTest {
        val client = harness().cacheOnlyClient()
        client.stage(
            representation(1, mapOf("paywall" to wireValue("json", """{"headline":"a","cta":"b"}""")))
        )
        client.activate()

        val asPaywall = LeverKey.json("paywall", Paywall("fallback", "none"))
        val asMap = LeverKey.json("paywall", emptyMap<String, String>())
        assertEquals(Paywall("a", "b"), client[asPaywall])
        assertEquals(mapOf("headline" to "a", "cta" to "b"), client[asMap])
        // …and again, now that both are memoized.
        assertEquals(Paywall("a", "b"), client[asPaywall])
        assertEquals(mapOf("headline" to "a", "cta" to "b"), client[asMap])
        client.close()
    }

    @Test
    fun `absence and mismatch log once per key, version, and type`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        client.stage(representation(1, mapOf("flag" to wireString("nope"))))
        client.activate()

        repeat(5) {
            assertFalse(client[flag])
            assertEquals(1, client[retries])
        }
        assertEquals(1, harness.sink.count(LeverLogLevel.WARN, "type mismatch key=flag"))
        assertEquals(1, harness.sink.count(LeverLogLevel.DEBUG, "key absent key=retries"))

        // A new version re-opens the dedupe.
        client.stage(representation(2, mapOf("flag" to wireString("still nope"))))
        client.activate()
        assertFalse(client[flag])
        assertEquals(2, harness.sink.count(LeverLogLevel.WARN, "type mismatch key=flag"))

        // Two keys over one wire name dedupe separately.
        client.stage(representation(3, mapOf("flag" to wireString("text"))))
        client.activate()
        repeat(3) {
            assertFalse(client[flag])
            assertEquals(0, client[LeverKey.int("flag", default = 0)])
        }
        assertEquals(3, harness.sink.count(LeverLogLevel.WARN, "key=flag wire=string as=boolean"))
        assertEquals(1, harness.sink.count(LeverLogLevel.WARN, "key=flag wire=string as=int"))
        client.close()
    }

    @Test
    fun `a sink that reads while handling a message never deadlocks`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        val readFromSink = AtomicInteger()
        val readFromAnotherThread = AtomicInteger()

        harness.sink.onLog = {
            // Same thread: the snapshot is already swapped when the log fires.
            if (client[flag]) readFromSink.incrementAndGet()
            // Another thread: this can only complete if no lock is held during
            // the callout (spec 0002 §4.1).
            val other = Thread { if (client[flag]) readFromAnotherThread.incrementAndGet() }
            other.start()
            other.join(5_000)
            assertFalse(other.isAlive, "a read on another thread blocked during a sink callout")
        }

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        assertTrue(client.activate())
        assertTrue(readFromSink.get() > 0)
        assertTrue(readFromAnotherThread.get() > 0)
        harness.sink.onLog = null
        client.close()
    }

    // MARK: the commit gate

    @Test
    fun `activate is a durability boundary`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()

        client.stage(representation(9, mapOf("flag" to wireBool(true))))
        assertTrue(client.activate())

        // No dispatcher advanced, no coroutine run: the write already happened.
        val afterProcessDeath = harness.cacheOnlyClient()
        assertEquals(9, afterProcessDeath.activatedVersion)
        assertTrue(afterProcessDeath[flag])

        client.close()
        afterProcessDeath.close()
    }

    @Test
    fun `activation from the runtime dispatcher does not deadlock`() = runTest {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val runtimeDispatcher = executor.asCoroutineDispatcher()
        val harness = harness(dispatcher = runtimeDispatcher)
        val client = harness.cacheOnlyClient()

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        // This is what nudge auto-activation does: activate *on* the runtime
        // thread, where a dispatch-and-wait persistence would deadlock.
        val changed = withContext(runtimeDispatcher) { client.activate() }

        assertTrue(changed)
        assertTrue(client[flag])
        client.close()
        runtimeDispatcher.close()
        executor.shutdown()
    }

    /**
     * The gate is a strict-order turnstile: commit 2 reaching it first waits for
     * commit 1, so disk, log, and delivery order all equal activation order
     * (spec 0003 §4).
     */
    @Test
    fun `commits are processed in commit order even when they arrive inverted`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        val received = mutableListOf<LeverUpdate>()
        backgroundScope.launch { client.updates.collect { received.add(it) } }
        settle()

        val firstIsAtTheGate = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondReachedTheGate = CountDownLatch(1)

        client.afterStateLock = { ticket ->
            if (ticket == 1L) {
                firstIsAtTheGate.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            } else {
                secondReachedTheGate.countDown()
            }
        }

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        val first = Thread { client.activate() }.apply { start() }
        check(firstIsAtTheGate.await(10, TimeUnit.SECONDS))

        client.stage(representation(2, mapOf("flag" to wireBool(false), "extra" to wireNumber("1"))))
        val second = Thread { client.activate() }.apply { start() }
        check(secondReachedTheGate.await(10, TimeUnit.SECONDS))
        // Commit 2 is now blocked on the gate holding no ticket of its own turn.
        Thread.sleep(50)
        assertFalse(
            harness.sink.contains(LeverLogLevel.INFO, "activated version=2"),
            "commit 2 overtook commit 1",
        )

        releaseFirst.countDown()
        first.join(10_000)
        second.join(10_000)
        settle()

        val activations = harness.sink.messages(LeverLogLevel.INFO).filter { it.startsWith("activated") }
        assertContentEquals(
            listOf("activated version=1 changed=1", "activated version=2 changed=2"),
            activations,
        )
        assertContentEquals(listOf(1, 2), received.map { it.version })
        assertEquals(2, harness.cacheOnlyClient().also { it.close() }.activatedVersion)
        client.afterStateLock = null
        client.close()
    }

    @Test
    fun `a failed persist logs and still advances the ticket`() = runTest {
        val harness = harness()
        // A directory where the snapshot file's name is taken by a directory:
        // every write fails, every activation still stands.
        val store = harness.cacheStore()
        store.directory.mkdirs()
        File(store.snapshotFile.path).mkdirs()

        val client = harness.cacheOnlyClient()
        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        assertTrue(client.activate())
        assertTrue(client[flag])
        assertTrue(harness.sink.contains(LeverLogLevel.ERROR, "cache write failed"))

        client.stage(representation(2, mapOf("flag" to wireBool(false))))
        assertTrue(client.activate(), "the queue must not wedge behind a full disk")
        assertEquals(2, client.activatedVersion)
        client.close()
    }

    // MARK: updates

    @Test
    fun `every collector sees every update from subscription onward`() = runTest {
        val client = harness().cacheOnlyClient()
        val first = mutableListOf<LeverUpdate>()
        val second = mutableListOf<LeverUpdate>()

        backgroundScope.launch { client.updates.collect { first.add(it) } }
        settle()

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        client.activate()
        settle()

        backgroundScope.launch { client.updates.collect { second.add(it) } }
        settle()

        client.stage(representation(2, mapOf("flag" to wireBool(false))))
        client.activate()
        settle()

        assertContentEquals(listOf(1, 2), first.map { it.version })
        assertContentEquals(listOf(2), second.map { it.version }, "a late collector gets what follows")
        client.close()
    }

    @Test
    fun `a stalled collector absorbs a burst without losing anything or blocking activate`() =
        runTest {
            val client = harness().cacheOnlyClient()
            val received = mutableListOf<LeverUpdate>()
            backgroundScope.launch {
                client.updates.collect {
                    // Slow on purpose: unlimited capacity is what keeps the
                    // synchronous activate() from ever waiting on this.
                    delay(1_000)
                    received.add(it)
                }
            }
            settle()

            repeat(20) { index ->
                client.stage(representation(index + 1, mapOf("flag" to wireNumber(index.toString()))))
                assertTrue(client.activate())
            }
            assertTrue(received.isEmpty(), "the collector has not run yet")

            advance(60.seconds)
            assertContentEquals((1..20).toList(), received.map { it.version })
            client.close()
        }

    @Test
    fun `close completes current collectors and hands later ones a finished flow`() = runTest {
        val client = harness().cacheOnlyClient()
        val received = mutableListOf<LeverUpdate>()
        val completed = CountDownLatch(1)

        backgroundScope.launch {
            client.updates.collect { received.add(it) }
            completed.countDown()
        }
        settle()

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        client.activate()
        client.close()
        settle()

        assertContentEquals(listOf(1), received.map { it.version })
        assertTrue(completed.await(5, TimeUnit.SECONDS), "the collector never completed")

        val afterClose = mutableListOf<LeverUpdate>()
        backgroundScope.launch { client.updates.collect { afterClose.add(it) } }
        settle()
        assertTrue(afterClose.isEmpty())
    }

    // MARK: close

    @Test
    fun `close waits for an activation that is already past the state lock`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()

        val activationIsPastTheLock = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val closeReturned = AtomicInteger()

        client.afterStateLock = {
            activationIsPastTheLock.countDown()
            check(releaseActivation.await(10, TimeUnit.SECONDS))
        }

        client.stage(representation(5, mapOf("flag" to wireBool(true))))
        val activation = Thread { client.activate() }.apply { start() }
        check(activationIsPastTheLock.await(10, TimeUnit.SECONDS))

        val closer = Thread {
            client.close()
            closeReturned.set(1)
        }
        closer.start()
        Thread.sleep(100)
        assertEquals(0, closeReturned.get(), "close returned before the commit finished")
        assertNull(harness.cacheOnlyClient().also { it.close() }.activatedVersion)

        releaseActivation.countDown()
        activation.join(10_000)
        closer.join(10_000)

        assertEquals(1, closeReturned.get())
        // The durability and the callouts both landed before close returned.
        assertEquals(5, harness.cacheOnlyClient().also { it.close() }.activatedVersion)
        assertTrue(harness.sink.contains(LeverLogLevel.INFO, "activated version=5"))
        client.afterStateLock = null
    }

    @Test
    fun `an activation arriving after the closed mark changes nothing`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        val received = mutableListOf<LeverUpdate>()
        backgroundScope.launch { client.updates.collect { received.add(it) } }
        settle()

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        client.close()
        val logsAtClose = harness.sink.all.size

        assertFalse(client.activate(), "no write, no log, no delivery")
        settle()

        assertEquals(logsAtClose, harness.sink.all.size)
        assertTrue(received.isEmpty())
        assertNull(harness.cacheOnlyClient().also { it.close() }.activatedVersion)
    }

    @Test
    fun `nothing is enqueued or logged after close returns, and what was owed still drains`() =
        runTest {
            val harness = harness()
            val client = harness.cacheOnlyClient()
            val received = mutableListOf<LeverUpdate>()

            // The collector is registered but never advanced, so it is stalled
            // with an update sitting in its channel when close lands.
            backgroundScope.launch { client.updates.collect { received.add(it) } }
            settle()

            client.stage(representation(1, mapOf("flag" to wireBool(true))))
            assertTrue(client.activate())

            client.close()
            val logsAfterClose = harness.sink.all.size

            // Delivery of accepted work still happens; nothing new joins it.
            settle()
            assertContentEquals(listOf(1), received.map { it.version })
            assertEquals(logsAfterClose, harness.sink.all.size)
        }

    @Test
    fun `close never waits on a stalled collector`() = runTest {
        val client = harness().cacheOnlyClient()
        backgroundScope.launch { client.updates.collect { awaitCancellation() } }
        settle()

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        client.activate()
        settle()

        val done = CountDownLatch(1)
        Thread {
            client.close()
            done.countDown()
        }
            .start()
        assertTrue(done.await(5, TimeUnit.SECONDS), "close blocked on a stalled collector")
    }

    @Test
    fun `a channel closed between capture and delivery neither blocks nor throws`() = runTest {
        // The mechanism, pinned directly: an unlimited channel closed underneath
        // a delivery fails its trySend instead of blocking or throwing.
        val channel = Channel<LeverUpdate>(Channel.UNLIMITED)
        channel.close()
        val result = channel.trySend(LeverUpdate(1, emptySet()))
        assertTrue(result.isFailure)

        // …and a collector cancelled mid-commit is simply gone: the activation
        // still returns, logs, and persists.
        val harness = harness()
        val client = harness.cacheOnlyClient()
        val job = backgroundScope.launch { client.updates.collect { } }
        settle()
        client.afterStateLock = { job.cancel() }

        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        assertTrue(client.activate())
        assertEquals(1, client.activatedVersion)
        client.afterStateLock = null
        client.close()
    }

    /**
     * "Repeated or concurrent `close()` is a no-op after the first" cannot mean
     * "returns before the close is a fact": a second caller that raced past the
     * barrier would observe a closed client while the first caller's admitted
     * commit was still persisting, logging, and delivering
     * (review 0003 pass 3, P3-F3).
     */
    @Test
    fun `a concurrent close waits for the same boundary as the first`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()

        val pastTheLock = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        client.afterStateLock = {
            pastTheLock.countDown()
            check(releaseCommit.await(10, TimeUnit.SECONDS))
        }

        client.stage(representation(3, mapOf("flag" to wireBool(true))))
        val activation = Thread { client.activate() }.apply { start() }
        check(pastTheLock.await(10, TimeUnit.SECONDS))

        val returned = AtomicInteger()
        val closers =
            (1..2).map {
                Thread {
                    client.close()
                    returned.incrementAndGet()
                }
            }
        closers.forEach { it.start() }
        Thread.sleep(150)
        assertEquals(0, returned.get(), "a closer returned while the commit was mid-flight")

        releaseCommit.countDown()
        activation.join(10_000)
        closers.forEach { it.join(10_000) }

        assertEquals(2, returned.get())
        // The teardown itself happened exactly once, before either return.
        assertEquals(1, harness.runtimeShutdowns)
        assertTrue(harness.sink.contains(LeverLogLevel.INFO, "activated version=3"))
        assertEquals(3, harness.cacheOnlyClient().also { it.close() }.activatedVersion)
        client.afterStateLock = null
    }

    @Test
    fun `reads after close serve values but start no new sink callback`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        client.stage(representation(1, mapOf("flag" to wireString("nope"))))
        client.activate()
        client.close()

        val logsAfterClose = harness.sink.all.size
        // A first-ever absent read and a first-ever mismatch read: both would
        // log on an open client.
        assertEquals(1, client[retries])
        assertFalse(client[flag])
        assertEquals(0, client[LeverKey.int("never-read", default = 0)])

        assertEquals(logsAfterClose, harness.sink.all.size, "a read logged past the boundary")
    }

    @Test
    fun `close is idempotent under repetition and concurrency`() = runTest {
        val client = harness().cacheOnlyClient()
        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        client.activate()

        val threads = (1..8).map { Thread { client.close() } }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        client.close()
        client.close()

        // Reads survive forever: a closed client degrades to a static one.
        assertTrue(client[flag])
        assertEquals(1, client.activatedVersion)
    }

    @Test
    fun `close releases the lifecycle observer, the transport, and the thread once`() = runTest {
        val harness = harness()
        val client = harness.client(harness.configuration())
        settle()
        assertEquals(1, harness.lifecycle.subscriptions)

        val closers = (1..4).map { Thread { client.close() } }
        closers.forEach { it.start() }
        closers.forEach { it.join(10_000) }

        assertEquals(1, harness.lifecycle.detaches, "the observer was not removed exactly once")
        assertEquals(1, harness.runtimeShutdowns)
        assertTrue(harness.transport.isClosed)
    }

    @Test
    fun `control operations after close fail loud, activate returns false`() = runTest {
        val client = harness().cacheOnlyClient()
        client.close()

        assertFailsWith<IllegalStateException> { client.fetch() }
        assertFailsWith<IllegalStateException> { client.fetchAndActivate() }
        assertFalse(client.activate())
    }

    // MARK: the shared instance

    @Test
    fun `only one caller can reserve the singleton`() {
        Lever.resetForTesting()
        val winners = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val start = CountDownLatch(1)
        val threads =
            (1..16).map {
                Thread {
                    start.await()
                    val won = Lever.reserveInstallation()
                    results.add(won)
                    if (won) winners.incrementAndGet()
                }
            }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(10_000) }

        assertEquals(1, winners.get(), "two callers both built a live runtime")
        assertEquals(16, results.size)
        Lever.resetForTesting()
    }

    @Test
    fun `reading shared before configure throws`() {
        Lever.resetForTesting()
        val failure = assertFailsWith<IllegalStateException> { Lever.shared }
        assertTrue(failure.message!!.contains("Lever.configure"))
    }

    @Test
    fun `a closed dispatcher is released exactly once`() = runTest {
        val harness = harness()
        val client = harness.cacheOnlyClient()
        assertEquals(0, harness.runtimeShutdowns)
        client.close()
        client.close()
        assertEquals(1, harness.runtimeShutdowns)
        assertTrue(harness.transport.isClosed)
    }

    @Test
    fun `an unused real dispatcher still runs the client`() = runTest {
        // Sanity check that the harness's default dispatcher choice is not what
        // makes the synchronous paths work.
        val harness = harness(dispatcher = Dispatchers.Default)
        val client = harness.cacheOnlyClient()
        client.stage(representation(1, mapOf("flag" to wireBool(true))))
        assertTrue(client.activate())
        assertTrue(client[flag])
        client.close()
    }
}
