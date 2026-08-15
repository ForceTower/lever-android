package dev.forcetower.lever

import dev.forcetower.lever.runtime.LeverEnvironment
import dev.forcetower.lever.runtime.LifecyclePhase
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The runtime: the init trigger, the in-session timer, and teardown (plan 0003 M6). */
internal class SchedulingTests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    private fun TestScope.harness(phase: LifecyclePhase = LifecyclePhase.FOREGROUND): TestHarness =
        TestHarness(testScheduler, phase = phase).also { harnesses.add(it) }

    private val flag = LeverKey.boolean("flag", default = false)

    // MARK: the init trigger

    @Test
    fun `the first reported phase is the one init trigger`() = runTest {
        val harness = harness()
        harness.transport.enqueue(
            jsonResponse(resolveBody(1, mapOf("flag" to boolValue(true))), etag = "\"a\"")
        )

        val client = harness.client()
        settle()

        assertEquals(1, harness.transport.requestCount)
        assertTrue(client[flag], "the automatic path activates what it fetches")
        client.close()
    }

    @Test
    fun `a failing first run still issues exactly one request`() = runTest {
        val harness = harness()
        // Nothing scripted: the transport answers like an unreachable network.
        val client = harness.client()
        settle()

        assertEquals(1, harness.transport.requestCount, "no duplicate init trigger")
        assertFalse(client[flag])
        assertNull(client.activatedVersion)
        client.close()
    }

    @Test
    fun `a client born backgrounded runs the init trigger once and opens no stream`() = runTest {
        val harness = harness(phase = LifecyclePhase.BACKGROUND)
        harness.transport.enqueue(jsonResponse(resolveBody(1)))

        val client = harness.client()
        settle()

        assertEquals(1, harness.transport.requestCount)
        assertEquals(0, harness.transport.streamRequests.size)
        client.close()
    }

    // MARK: the interval

    @Test
    fun `construction inside the interval issues no request`() = runTest {
        val harness = harness()
        harness.seedCache(3, mapOf("flag" to wireBool(true)), fetchedAt = harness.now - 60)

        val client = harness.client()
        settle()

        assertEquals(0, harness.transport.requestCount)
        assertTrue(client[flag], "the cache still serves")
        client.close()
    }

    @Test
    fun `construction outside the interval fetches`() = runTest {
        val harness = harness()
        harness.seedCache(3, mapOf("flag" to wireBool(true)), fetchedAt = harness.now - 13 * 3600)
        harness.transport.enqueue(jsonResponse(resolveBody(4, mapOf("flag" to boolValue(false)))))

        val client = harness.client()
        settle()

        assertEquals(1, harness.transport.requestCount)
        assertFalse(client[flag])
        client.close()
    }

    @Test
    fun `an explicit fetch ignores the interval`() = runTest {
        val harness = harness()
        harness.seedCache(3, mapOf("flag" to wireBool(true)), fetchedAt = harness.now)
        val client = harness.client()
        settle()
        assertEquals(0, harness.transport.requestCount)

        harness.transport.enqueue(jsonResponse(resolveBody(4, mapOf("flag" to boolValue(false)))))
        assertTrue(client.fetchAndActivate())
        assertEquals(1, harness.transport.requestCount)
        client.close()
    }

    // MARK: the in-session timer

    @Test
    fun `the timer fires at lastFetchAt plus the interval and re-arms`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = 1.hours))
        settle()
        assertEquals(1, harness.transport.requestCount)

        advance(harness, 59.minutes)
        assertEquals(1, harness.transport.requestCount, "the timer fired early")

        harness.transport.enqueue(jsonResponse(resolveBody(2)))
        advance(harness, 1.minutes)
        assertEquals(2, harness.transport.requestCount)

        harness.transport.enqueue(jsonResponse(resolveBody(3)))
        advance(harness, 1.hours)
        assertEquals(3, harness.transport.requestCount, "the timer did not re-arm")
        client.close()
    }

    @Test
    fun `a zero interval arms no timer at all`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = Duration.ZERO))
        settle()
        assertEquals(1, harness.transport.requestCount)

        // "Always eligible" is not "continuously": lifecycle edges only.
        advance(harness, 24.hours)
        assertEquals(1, harness.transport.requestCount, "zero interval hot-looped")
        client.close()
    }

    @Test
    fun `a sub-60s interval polls at the floor while lifecycle edges keep it`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = 5.seconds))
        settle()
        assertEquals(1, harness.transport.requestCount)

        advance(harness, 59.seconds)
        assertEquals(1, harness.transport.requestCount, "the timer ran under the 60s floor")

        harness.transport.enqueue(jsonResponse(resolveBody(2)))
        advance(harness, 1.seconds)
        assertEquals(2, harness.transport.requestCount)

        // A lifecycle edge keeps the configured interval: 6 s after the last
        // fetch, the client is eligible again.
        harness.transport.enqueue(jsonResponse(resolveBody(3)))
        advance(harness, 6.seconds)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()
        assertEquals(3, harness.transport.requestCount)
        client.close()
    }

    @Test
    fun `exactly sixty seconds arms as configured`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = 60.seconds))
        settle()

        harness.transport.enqueue(jsonResponse(resolveBody(2)))
        advance(harness, 60.seconds)
        assertEquals(2, harness.transport.requestCount)
        client.close()
    }

    @Test
    fun `a failed automatic fetch re-arms from the attempt`() = runTest {
        val harness = harness()
        harness.transport.enqueue(IOException("offline"))
        val client = harness.client(harness.configuration(minimumFetchInterval = 1.hours))
        settle()
        assertEquals(1, harness.transport.requestCount)

        // First-run offline has no `lastFetchAt`; the failed attempt anchors the
        // timer, so this must not hot-loop.
        advance(harness, 59.minutes)
        assertEquals(1, harness.transport.requestCount)

        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        advance(harness, 1.minutes)
        assertEquals(2, harness.transport.requestCount)
        client.close()
    }

    @Test
    fun `a wall-clock jump does not disturb the timer`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = 1.hours))
        settle()

        // The wall clock leaps a day; the monotonic timer keeps its own counsel.
        harness.advanceWallClock(24 * 3600)
        settle()
        assertEquals(1, harness.transport.requestCount)

        harness.transport.enqueue(jsonResponse(resolveBody(2)))
        advance(1.hours)
        assertEquals(2, harness.transport.requestCount)
        client.close()
    }

    @Test
    fun `backgrounding cancels the timer and foregrounding restarts the path`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = 1.hours))
        settle()

        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        advance(harness, 6.hours)
        assertEquals(1, harness.transport.requestCount, "a backgrounded client kept polling")

        harness.transport.enqueue(jsonResponse(resolveBody(2)))
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()
        assertEquals(2, harness.transport.requestCount)
        client.close()
    }

    // MARK: cache-only mode

    @Test
    fun `a cache-only client starts nothing and still reads and fetches on demand`() = runTest {
        val harness = harness()
        harness.seedCache(3, mapOf("flag" to wireBool(true)))

        val client = harness.client(harness.configuration(automaticUpdates = false))
        settle()
        advance(harness, 24.hours)

        assertEquals(0, harness.transport.requestCount, "a reader must never fetch on its own")
        assertEquals(0, harness.transport.streamRequests.size)
        assertEquals(0, harness.lifecycle.subscriptions, "no lifecycle observation")
        assertTrue(client[flag], "reads serve the cache")

        // The explicit override still works.
        harness.transport.enqueue(jsonResponse(resolveBody(4, mapOf("flag" to boolValue(false)))))
        assertTrue(client.fetchAndActivate())
        assertFalse(client[flag])
        client.close()
    }

    // MARK: teardown

    @Test
    fun `close releases the lifecycle observer, the transport, and the dispatcher`() = runTest {
        val harness = harness()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        val client = harness.client(harness.configuration(minimumFetchInterval = 1.hours))
        settle()
        assertEquals(1, harness.lifecycle.subscriptions)

        client.close()
        settle()

        assertEquals(1, harness.lifecycle.unsubscriptions, "the lifecycle observer stayed installed")
        assertTrue(harness.transport.isClosed)
        assertEquals(1, harness.runtimeShutdowns)

        // No callback of any kind past the boundary.
        val requests = harness.transport.requestCount
        val logs = harness.sink.all.size
        advance(harness, 24.hours)
        assertEquals(requests, harness.transport.requestCount)
        assertEquals(logs, harness.sink.all.size)
        assertFailsWith<IllegalStateException> { client.fetch() }
    }

    /**
     * Resource release, not just job cancellation: the runtime owns a real
     * thread, and cancelling its scope would leak it (spec 0003 §4).
     */
    @Test
    fun `close shuts the runtime thread down instead of leaking it`() = runTest {
        val runtime = LeverEnvironment.singleThreadRuntime()
        val harness =
            TestHarness(testScheduler, dispatcher = runtime.dispatcher, onShutdown = runtime.shutdown)
                .also { harnesses.add(it) }

        // Other suites hold live clients of their own, so this watches the
        // thread *this* runtime started rather than every thread sharing a name.
        val before = runtimeThreads()
        val client = harness.client(harness.configuration(automaticUpdates = false))
        client.stage(Representation(1, mapOf("flag" to wireBool(true)), null, harness.now, null))
        assertTrue(client.activate())

        val started = runtimeThreads() - before
        assertTrue(started.isNotEmpty(), "the runtime thread never started")

        client.close()
        val deadline = System.nanoTime() + 10_000_000_000
        while (started.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(started.none { it.isAlive }, "the runtime thread leaked")
    }

    private fun runtimeThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) {
            it.name == "lever-runtime" && it.isAlive
        }
}
