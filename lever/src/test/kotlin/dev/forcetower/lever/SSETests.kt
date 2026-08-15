package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import dev.forcetower.lever.runtime.LeverRuntime
import dev.forcetower.lever.runtime.LifecyclePhase
import dev.forcetower.lever.transport.ServerSentEventParser
import dev.forcetower.lever.transport.VersionFrame
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** The SSE parser, the state machine, and nudge handling (plan 0003 M7). */
internal class SSETests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    private val flag = LeverKey.boolean("flag", default = false)

    // MARK: the parser

    private fun ServerSentEventParser.consume(text: String) = consume(text.toByteArray())

    @Test
    fun `frames parse whole, split, and across chunk boundaries`() {
        val parser = ServerSentEventParser()
        assertContentEquals(
            listOf(ServerSentEventParser.Event("version", "{\"version\":1}")),
            parser.consume("event: version\ndata: {\"version\":1}\n\n"),
        )

        // The same frame, one byte at a time.
        val split = ServerSentEventParser()
        val bytes = "event: version\ndata: {\"version\":2}\n\n".toByteArray()
        val events = bytes.flatMap { split.consume(byteArrayOf(it)) }
        assertContentEquals(listOf(ServerSentEventParser.Event("version", "{\"version\":2}")), events)
    }

    @Test
    fun `heartbeats, crlf, retry, and unknown fields are handled`() {
        val parser = ServerSentEventParser()
        assertTrue(parser.consume(": hb\n\n").isEmpty(), "a comment is not an event")
        assertTrue(parser.consume("retry: 5000\n\n").isEmpty(), "retry is parsed and discarded")
        assertTrue(parser.consume("id: 7\n\n").isEmpty(), "unknown fields are skipped")

        assertContentEquals(
            listOf(ServerSentEventParser.Event("version", "{\"version\":3}")),
            parser.consume("event: version\r\ndata: {\"version\":3}\r\n\r\n"),
        )

        // A lone CR at a chunk boundary may still be half a CRLF.
        val boundary = ServerSentEventParser()
        assertTrue(boundary.consume("data: a\r").isEmpty())
        assertContentEquals(
            listOf(ServerSentEventParser.Event(null, "a")),
            boundary.consume("\n\r\n"),
        )
    }

    @Test
    fun `multi-line data joins with newlines and a value keeps one leading space`() {
        val parser = ServerSentEventParser()
        assertContentEquals(
            listOf(ServerSentEventParser.Event(null, "one\ntwo")),
            parser.consume("data: one\ndata:two\n\n"),
        )
        assertContentEquals(
            listOf(ServerSentEventParser.Event(null, " padded")),
            parser.consume("data:  padded\n\n"),
        )
    }

    /**
     * The bound is accounted **before** appending, across every field kind, so a
     * broken peer cannot make the parser build a 100 MiB line first
     * (spec 0002 §12.1).
     */
    @Test
    fun `the frame bound counts every field kind and rejects before appending`() {
        val oversized = "x".repeat(ServerSentEventParser.MAX_FRAME_BYTES + 1)

        assertFailsWith<ServerSentEventParser.FrameTooLargeException> {
            ServerSentEventParser().consume("data: $oversized\n\n")
        }
        // A comment is a field too: a peer that never sends a `data:` line is
        // exactly the peer this bound exists for.
        assertFailsWith<ServerSentEventParser.FrameTooLargeException> {
            ServerSentEventParser().consume(": $oversized\n\n")
        }
        // Unterminated: nothing dispatched, still bounded.
        assertFailsWith<ServerSentEventParser.FrameTooLargeException> {
            ServerSentEventParser().consume(oversized)
        }
        // Accumulated across several terminated lines inside one frame.
        assertFailsWith<ServerSentEventParser.FrameTooLargeException> {
            val parser = ServerSentEventParser()
            val chunk = "data: ${"y".repeat(200_000)}\n"
            repeat(6) { parser.consume(chunk) }
        }
    }

    @Test
    fun `the frame budget resets when a blank line dispatches`() {
        val parser = ServerSentEventParser()
        val big = "z".repeat(600_000)
        repeat(6) {
            assertContentEquals(
                listOf(ServerSentEventParser.Event(null, big)),
                parser.consume("data: $big\n\n"),
            )
        }
    }

    @Test
    fun `version frames decode, and garbage is ignored`() {
        assertEquals(42, VersionFrame.version("""{"version":42}"""))
        assertNull(VersionFrame.version("""{"version":"42"}"""))
        assertNull(VersionFrame.version("not json"))
        assertNull(VersionFrame.version("""{}"""))
    }

    @Test
    fun `retry-after is integer seconds, capped, and otherwise ignored`() {
        assertEquals(120.0, LeverRuntime.retryAfterSeconds("120"))
        assertEquals(300.0, LeverRuntime.retryAfterSeconds("9000"))
        assertEquals(0.0, LeverRuntime.retryAfterSeconds("0"))
        assertNull(LeverRuntime.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(LeverRuntime.retryAfterSeconds("-5"))
        assertNull(LeverRuntime.retryAfterSeconds(null))
    }

    // MARK: the state machine

    /** A client whose init fetch is already satisfied, so the stream is alone. */
    private fun TestScope.streamHarness(
        autoActivate: Boolean = true,
        version: Int = 1,
    ): Pair<TestHarness, LeverClient> {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(version, mapOf("flag" to wireBool(false)), etag = "\"v$version\"")
        val client =
            harness.client(
                harness.configuration(minimumFetchInterval = 12.hours, autoActivateOnNudge = autoActivate)
            )
        settle()
        return harness to client
    }

    @Test
    fun `the stream connects with auth and the right accept header`() = runTest {
        val (harness, client) = streamHarness()
        val request = harness.transport.streamRequests.single()
        assertEquals("https://lever.example/v1/stream", request.url)
        assertEquals("Bearer pk_test", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        client.close()
    }

    @Test
    fun `a 200 with the wrong media type fails fast into backoff`() = runTest {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(1, mapOf("flag" to wireBool(false)))
        harness.transport.enqueueStream(StreamScript(contentType = "text/html").stream)

        val client = harness.client()
        settle()
        assertEquals(1, harness.transport.streamRequests.size)
        assertTrue(harness.sink.contains(LeverLogLevel.WARN, "stream refused, media type=text/html"))

        // …and it reconnects through backoff rather than sitting in the parser.
        harness.transport.enqueueStream(StreamScript().stream)
        advance(2.seconds)
        assertEquals(2, harness.transport.streamRequests.size)
        client.close()
    }

    @Test
    fun `backoff grows with full jitter and resets after a frame`() = runTest {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(1, mapOf("flag" to wireBool(false)))
        repeat(3) { harness.transport.enqueueStream(StreamScript().also { it.close() }.stream) }

        val client = harness.client()
        settle()
        assertEquals(1, harness.transport.streamRequests.size)

        // attempt 0 → ceiling 2^0 = 1 s (jitter fraction is 1.0 in tests)
        advance(999.milliseconds)
        assertEquals(1, harness.transport.streamRequests.size)
        advance(1.milliseconds)
        assertEquals(2, harness.transport.streamRequests.size)

        // attempt 1 → 2 s
        advance(2.seconds)
        assertEquals(3, harness.transport.streamRequests.size)

        // A round that received a frame resets the counter — and still takes its
        // delay, or a peer that closes after the connect frame becomes a hot loop.
        val withFrame = StreamScript()
        harness.transport.enqueueStream(withFrame.stream)
        advance(4.seconds)
        assertEquals(4, harness.transport.streamRequests.size)
        withFrame.version(1)
        settle()
        withFrame.close()
        settle()
        assertEquals(4, harness.transport.streamRequests.size, "no immediate reconnect")

        harness.transport.enqueueStream(StreamScript().stream)
        advance(1.seconds)
        assertEquals(5, harness.transport.streamRequests.size)
        client.close()
    }

    @Test
    fun `a 503 honors retry-after as the floor for that round`() = runTest {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(1, mapOf("flag" to wireBool(false)))
        harness.transport.enqueueStream(StreamScript(status = 503, retryAfter = "120").stream)
        harness.transport.enqueueStream(StreamScript().stream)

        val client = harness.client()
        settle()
        assertEquals(1, harness.transport.streamRequests.size)

        advance(119.seconds)
        assertEquals(1, harness.transport.streamRequests.size, "retry-after was ignored")
        advance(1.seconds)
        assertEquals(2, harness.transport.streamRequests.size)
        client.close()
    }

    @Test
    fun `a 401 stops the stream until the next foreground`() = runTest {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(1, mapOf("flag" to wireBool(true)))
        harness.transport.enqueueStream(StreamScript(status = 401).stream)

        val client = harness.client()
        settle()
        assertEquals(1, harness.transport.streamRequests.size)
        assertTrue(harness.sink.contains(LeverLogLevel.WARN, "stream stopped, invalid key"))

        advance(10.hours)
        assertEquals(1, harness.transport.streamRequests.size, "a 401 kept retrying")
        assertTrue(client[flag], "a 401 never clears the cache")

        harness.transport.enqueueStream(StreamScript().stream)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()
        assertEquals(2, harness.transport.streamRequests.size)
        client.close()
    }

    @Test
    fun `sixty seconds of silence reconnects, and any byte resets the watchdog`() = runTest {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(1, mapOf("flag" to wireBool(false)))
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        harness.transport.enqueueStream(StreamScript().stream)

        val client = harness.client()
        settle()

        advance(59.seconds)
        script.heartbeat()
        settle()
        advance(59.seconds)
        assertEquals(1, harness.transport.streamRequests.size, "the heartbeat did not reset the watchdog")

        advance(2.seconds)
        assertTrue(harness.sink.contains(LeverLogLevel.DEBUG, "stream idle for 60s"))
        advance(2.seconds)
        assertEquals(2, harness.transport.streamRequests.size)
        client.close()
    }

    @Test
    fun `backgrounding during backoff tears down cleanly`() = runTest {
        val harness = TestHarness(testScheduler).also { harnesses.add(it) }
        harness.seedCache(1, mapOf("flag" to wireBool(false)))
        harness.transport.enqueueStream(StreamScript().also { it.close() }.stream)

        val client = harness.client()
        settle()
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()

        advance(10.hours)
        assertEquals(1, harness.transport.streamRequests.size, "backoff survived backgrounding")
        client.close()
    }

    @Test
    fun `stream cancellation is never logged or retried as a transport failure`() = runTest {
        val (harness, client) = streamHarness()
        val logs = harness.sink.all.size
        client.close()
        settle()
        advance(10.hours)

        assertEquals(logs, harness.sink.all.size, "teardown logged")
        assertEquals(1, harness.transport.streamRequests.size, "teardown reconnected")
    }

    // MARK: nudges

    @Test
    fun `the replayed connect frame is deduped, and a differing version fetches once`() = runTest {
        val (harness, client) = streamHarness(version = 4)
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        // Reconnect so the scripted stream is the live one.
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()

        script.version(4)
        settle()
        assertEquals(0, harness.transport.requestCount, "the replayed connect frame refetched")

        harness.transport.enqueue(
            jsonResponse(resolveBody(5, mapOf("flag" to boolValue(true))), etag = "\"v5\"")
        )
        script.version(5)
        settle()
        assertEquals(1, harness.transport.requestCount)
        assertTrue(client[flag], "the nudge auto-activated")
        assertEquals(5, client.activatedVersion)
        client.close()
    }

    @Test
    fun `a lower announced version still refetches`() = runTest {
        val (harness, client) = streamHarness(version = 9)
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()

        harness.transport.enqueue(jsonResponse(resolveBody(2, mapOf("flag" to boolValue(true)))))
        script.version(2)
        settle()

        assertEquals(1, harness.transport.requestCount, "identity, not ordering")
        assertEquals(2, client.activatedVersion)
        client.close()
    }

    @Test
    fun `a nudge during an in-flight fetch yields exactly one follow-up`() = runTest {
        val (harness, client) = streamHarness(version = 1)
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()

        harness.transport.pause()
        harness.transport.enqueue(jsonResponse(resolveBody(2, mapOf("flag" to boolValue(false)))))
        val explicit = backgroundScope.launch { client.fetch() }
        settle()
        assertEquals(1, harness.transport.requestCount)

        // Several nudges during one fetch coalesce to one follow-up.
        script.version(3)
        script.version(4)
        settle()
        harness.transport.enqueue(
            jsonResponse(resolveBody(4, mapOf("flag" to boolValue(true))), etag = "\"v4\"")
        )
        harness.transport.resume()
        settle()

        assertTrue(explicit.isCompleted)
        assertEquals(2, harness.transport.requestCount, "one follow-up, not one per nudge")
        assertEquals(4, client.activatedVersion)
        assertTrue(client[flag])
        client.close()
    }

    @Test
    fun `a nudge already covered by the in-flight response yields no follow-up`() = runTest {
        val (harness, client) = streamHarness(version = 1)
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()

        harness.transport.pause()
        harness.transport.enqueue(
            jsonResponse(resolveBody(7, mapOf("flag" to boolValue(true))), etag = "\"v7\"")
        )
        val explicit = backgroundScope.launch { client.fetch() }
        settle()

        script.version(7)
        settle()
        harness.transport.resume()
        settle()

        assertTrue(explicit.isCompleted)
        assertEquals(1, harness.transport.requestCount, "the in-flight response already covered it")
        client.close()
    }

    /**
     * A nudge's activation policy is caller interest, not a token: it joins
     * whatever transport work answers it and applies its own policy when that
     * work completes (spec 0002 §12.1).
     */
    @Test
    fun `an auto-activating nudge joins a staging-only fetch already in flight`() = runTest {
        val (harness, client) = streamHarness(version = 1)
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()

        harness.transport.pause()
        harness.transport.enqueue(
            jsonResponse(resolveBody(2, mapOf("flag" to boolValue(true))), etag = "\"v2\"")
        )
        val staging = backgroundScope.launch { client.fetch() }
        settle()

        // The nudge announces exactly what the in-flight request will answer, so
        // no follow-up is needed — but somebody still has to activate it.
        script.version(2)
        settle()
        harness.transport.resume()
        settle()

        assertTrue(staging.isCompleted)
        assertEquals(1, harness.transport.requestCount)
        assertEquals(2, client.activatedVersion, "the nudge never activated the work it joined")
        assertTrue(client[flag])
        client.close()
    }

    @Test
    fun `with auto-activation off a nudge only stages`() = runTest {
        val (harness, client) = streamHarness(autoActivate = false, version = 1)
        val script = StreamScript()
        harness.transport.enqueueStream(script.stream)
        harness.lifecycle.send(LifecyclePhase.BACKGROUND)
        settle()
        harness.lifecycle.send(LifecyclePhase.FOREGROUND)
        settle()

        harness.transport.enqueue(
            jsonResponse(resolveBody(3, mapOf("flag" to boolValue(true))), etag = "\"v3\"")
        )
        script.version(3)
        settle()

        assertEquals(1, harness.transport.requestCount)
        assertFalse(client[flag], "the app activates on its own schedule")
        assertEquals(1, client.activatedVersion)
        assertTrue(client.activate())
        assertTrue(client[flag])
        client.close()
    }
}
