package dev.forcetower.lever

import dev.forcetower.lever.transport.Header
import dev.forcetower.lever.transport.HttpRequest
import dev.forcetower.lever.transport.OkHttpTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/**
 * The live OkHttp transport against a real server (review 0003 pass 3, P3-F2).
 *
 * The scripted double can prove the runtime *calls* close; only a real socket
 * proves the transport's close actually releases one. A response body that is
 * never closed keeps its connection checked out, so a leak shows up as a server
 * that never sees a reused connection.
 */
internal class TransportIntegrationTests {
    private val server = MockWebServer().also { it.start() }
    private val transport = OkHttpTransport()

    @AfterTest
    fun tearDown() {
        transport.close()
        server.close()
    }

    private fun streamRequest() =
        HttpRequest(
            url = server.url("/v1/stream").toString(),
            headers = listOf(Header("Accept", "text/event-stream")),
        )

    @Test
    fun `rejected rounds release their connection`() = runBlocking {
        repeat(4) {
            // `addHeader`, not `headers`: replacing the header set would drop
            // MockWebServer's Content-Length and make every response unpoolable,
            // which would look exactly like the leak this test hunts.
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "1").build())
        }

        repeat(4) {
            val stream = transport.openStream(streamRequest())
            assertEquals(503, stream.status)
            // The runtime's `finally`, by hand: a rejected round never collects
            // the chunks, so this is the only thing that frees the socket.
            stream.close()
        }

        val sequences = (1..4).map { server.takeRequest().connectionIndex }
        assertTrue(
            sequences.distinct().size < sequences.size,
            "every rejected round opened a new connection, so none was released: $sequences",
        )
    }

    @Test
    fun `a collected stream releases its connection at eof`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(": hb\n\n")
                    .build()
            )
        }

        repeat(2) {
            val stream = transport.openStream(streamRequest())
            assertEquals(200, stream.status)
            assertEquals(": hb\n\n", stream.chunks.toList().joinToString("") { chunk -> chunk.decodeToString() })
            stream.close()
        }

        val sequences = (1..2).map { server.takeRequest().connectionIndex }
        assertTrue(
            sequences.distinct().size < sequences.size,
            "the connection was not reused after eof: $sequences",
        )
    }

    @Test
    fun `closing a stream twice is harmless`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        val stream = transport.openStream(streamRequest())
        stream.close()
        stream.close()
        assertEquals(401, stream.status)
    }
}
