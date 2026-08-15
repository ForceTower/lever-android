package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import dev.forcetower.lever.transport.ResolveEndpoint
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** Resolve request construction, status mapping, and coalescing (plan 0003 M5). */
internal class TransportTests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    private fun TestScope.harness(): TestHarness =
        TestHarness(testScheduler).also { harnesses.add(it) }

    private fun TestHarness.explicitClient(
        clientKey: String = "pk_test",
        namespace: String? = null,
    ) = client(
        configuration(clientKey = clientKey, automaticUpdates = false, cacheNamespace = namespace)
    )

    private val flag = LeverKey.boolean("flag", default = false)

    // MARK: request construction

    @Test
    fun `the request carries the auth header, the context query, and no validator`() = runTest {
        val harness = harness()
        val client =
            harness.client(
                harness.configuration(
                    automaticUpdates = false,
                    context =
                        LeverContext(
                            LeverPlatform("android"),
                            "5.2",
                            mapOf("tier" to "free trial", "locale" to "pt-BR"),
                        ),
                )
            )
        harness.transport.enqueue(jsonResponse(resolveBody(1), etag = "\"abc\""))
        client.fetch()

        val request = harness.transport.requests.single()
        assertEquals(
            "https://lever.example/v1/resolve?platform=android&appVersion=5.2.0" +
                "&clientId=${client.clientId}&attr.locale=pt-BR&attr.tier=free%20trial",
            request.url,
        )
        assertEquals("Bearer pk_test", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("If-None-Match"))
        client.close()
    }

    @Test
    fun `percent encoding follows the unreserved set, so a space is never a plus`() {
        assertEquals("free%20trial", ResolveEndpoint.percentEncoded("free trial"))
        assertEquals("a-b_c.d~e", ResolveEndpoint.percentEncoded("a-b_c.d~e"))
        assertEquals("caf%C3%A9", ResolveEndpoint.percentEncoded("café"))
        assertEquals("%2B%2F%3D%26", ResolveEndpoint.percentEncoded("+/=&"))
    }

    @Test
    fun `a base path is joined byte-exactly`() = runTest {
        val harness = harness()
        val client =
            harness.client(
                LeverConfiguration(
                    baseUrl = "https://lever.example/config/",
                    clientKey = "pk_test",
                    context = LeverContext(LeverPlatform("android")),
                    automaticUpdates = false,
                    cacheDirectory = harness.directory,
                    logSink = harness.sink,
                )
            )
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        client.fetch()

        assertTrue(
            harness.transport.requests.single().url.startsWith(
                "https://lever.example/config/v1/resolve?platform=android&clientId="
            ),
            harness.transport.requests.single().url,
        )
        client.close()
    }

    @Test
    fun `the second request sends the stored validator`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(jsonResponse(resolveBody(1), etag = "\"abc\""))
        harness.transport.enqueue(statusResponse(304, etag = "\"abc\""))

        client.fetchAndActivate()
        client.fetch()

        assertEquals("\"abc\"", harness.transport.requests[1].header("If-None-Match"))
        client.close()
    }

    // MARK: the status matrix

    @Test
    fun `a 200 stages a representation with its etag and clock`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(
            jsonResponse(resolveBody(3, mapOf("flag" to boolValue(true))), etag = "\"abc\"")
        )

        client.fetch()
        val staged = assertNotNull(client.newestRepresentation())
        assertTrue(staged.isStaged)
        assertEquals(3, staged.representation.version)
        assertEquals("\"abc\"", staged.representation.etag)
        assertEquals(harness.now, staged.representation.fetchedAt)
        assertFalse(client[flag], "staging is not activation")
        client.close()
    }

    @Test
    fun `a 200 without an etag is accepted and sends no validator next time`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(jsonResponse(resolveBody(1)))
        harness.transport.enqueue(jsonResponse(resolveBody(2)))

        client.fetchAndActivate()
        client.fetch()

        assertNull(harness.transport.requests[1].header("If-None-Match"))
        client.close()
    }

    @Test
    fun `every other status maps to its exception`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()

        harness.transport.enqueue(statusResponse(401))
        assertIs<LeverException.InvalidKey>(assertFailsWith<LeverException> { client.fetch() })

        harness.transport.enqueue(statusResponse(503))
        assertEquals(503, assertIs<LeverException.Server>(assertFailsWith<LeverException> { client.fetch() }).status)

        // 204 and the rest of the 2xx range are not the contract either.
        harness.transport.enqueue(statusResponse(204))
        assertEquals(204, assertIs<LeverException.Server>(assertFailsWith<LeverException> { client.fetch() }).status)

        // A refused redirect reaches the SDK as its 3xx status.
        harness.transport.enqueue(statusResponse(302))
        assertEquals(302, assertIs<LeverException.Server>(assertFailsWith<LeverException> { client.fetch() }).status)

        harness.transport.enqueue(nonHttpResponse)
        assertIs<LeverException.InvalidResponse>(assertFailsWith<LeverException> { client.fetch() })

        harness.transport.enqueue(IOException("offline"))
        assertIs<LeverException.Network>(assertFailsWith<LeverException> { client.fetch() })

        assertNull(client.activatedVersion, "no failure may touch the snapshot")
        client.close()
    }

    // MARK: the envelope (spec 0001 §5.1)

    @Test
    fun `an ok-false envelope is a fetch failure, never an empty values map`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.seedCache(4, mapOf("flag" to wireBool(true)))
        val warm = harness.explicitClient()
        assertTrue(warm[flag])

        harness.transport.enqueue(
            jsonResponse(envelope(data = null, ok = false, errorCode = "internal_error"), status = 200)
        )
        assertIs<LeverException.InvalidResponse>(assertFailsWith<LeverException> { warm.fetch() })
        assertTrue(warm[flag], "the previous snapshot must keep serving")
        assertEquals(4, warm.activatedVersion)

        // …and an envelope that says ok but carries no data is the same failure.
        harness.transport.enqueue(jsonResponse(envelope(data = null, ok = true)))
        assertIs<LeverException.InvalidResponse>(assertFailsWith<LeverException> { warm.fetch() })

        client.close()
        warm.close()
    }

    @Test
    fun `an invalid body changes nothing at all`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(
            jsonResponse(resolveBody(2, mapOf("flag" to boolValue(true))), etag = "\"good\"")
        )
        client.fetchAndActivate()
        val before = assertNotNull(client.newestRepresentation()).representation

        for (body in
            listOf(
                "not json",
                envelope("""{"version":-1,"values":{}}"""),
                envelope("""{"values":{}}"""),
                envelope("""{"version":"three","values":{}}"""),
                """{"version":1,"values":{}}""", // the pre-envelope shape
            )
        ) {
            harness.transport.enqueue(jsonResponse(body, etag = "\"bad\""))
            assertIs<LeverException.InvalidResponse>(assertFailsWith<LeverException> { client.fetch() })
            assertEquals(before, assertNotNull(client.newestRepresentation()).representation)
        }
        client.close()
    }

    @Test
    fun `the envelope's message is ignored and its error code only reaches the log`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(
            jsonResponse(
                """{"ok":true,"message":"anything at all","data":{"version":8,"values":{}},"error":null}"""
            )
        )
        client.fetchAndActivate()
        assertEquals(8, client.activatedVersion)

        harness.transport.enqueue(
            jsonResponse(envelope(null, ok = false, errorCode = "invalid_key"), status = 401)
        )
        assertFailsWith<LeverException.InvalidKey> { client.fetch() }
        assertTrue(harness.sink.contains(LeverLogLevel.DEBUG, "code=invalid_key"))
        client.close()
    }

    // MARK: 304 ownership

    @Test
    fun `a 304 confirming the activated representation persists freshness`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(
            jsonResponse(resolveBody(1, mapOf("flag" to boolValue(true))), etag = "\"abc\"")
        )
        client.fetchAndActivate()

        harness.advanceWallClock(3_600)
        harness.transport.enqueue(statusResponse(304, etag = "\"abc\""))
        client.fetch()

        assertEquals(harness.now, assertNotNull(client.newestRepresentation()).representation.fetchedAt)
        assertEquals(1, client.activatedVersion)
        assertTrue(client[flag])

        // The refreshed clock survives a relaunch, so the next launch does not
        // refetch inside the interval.
        val restarted = harness.explicitClient()
        assertEquals(harness.now, assertNotNull(restarted.newestRepresentation()).representation.fetchedAt)
        client.close()
        restarted.close()
    }

    @Test
    fun `a 304 confirming staged state never touches activated values`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(
            jsonResponse(resolveBody(1, mapOf("flag" to boolValue(true))), etag = "\"v1\"")
        )
        client.fetchAndActivate()

        // Stage a newer representation, then have the server confirm *it*.
        harness.transport.enqueue(
            jsonResponse(resolveBody(2, mapOf("flag" to boolValue(false))), etag = "\"v2\"")
        )
        client.fetch()
        harness.advanceWallClock(60)
        harness.transport.enqueue(statusResponse(304, etag = "\"v2\""))
        client.fetch()

        assertEquals("\"v2\"", harness.transport.requests[2].header("If-None-Match"))
        assertTrue(client[flag], "activated values must not move")
        assertEquals(1, client.activatedVersion)

        // fetch-200-stage → fetch-304 → activate lands the staged payload.
        assertTrue(client.activate())
        assertEquals(2, client.activatedVersion)
        assertFalse(client[flag])
        client.close()
    }

    @Test
    fun `an unsolicited 304 is an invalid response`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(statusResponse(304))
        assertIs<LeverException.InvalidResponse>(assertFailsWith<LeverException> { client.fetch() })
        assertNull(client.activatedVersion)
        client.close()
    }

    @Test
    fun `a freshness write racing an activation regresses neither values nor freshness`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.enqueue(
            jsonResponse(resolveBody(1, mapOf("flag" to boolValue(true))), etag = "\"v1\"")
        )
        client.fetchAndActivate()

        // A 304 refreshing the activated representation, ordered through the same
        // commit gate as the activation that follows it.
        harness.advanceWallClock(120)
        harness.transport.enqueue(statusResponse(304, etag = "\"v1\""))
        client.fetch()
        val refreshedAt = harness.now

        harness.advanceWallClock(60)
        harness.transport.enqueue(
            jsonResponse(resolveBody(2, mapOf("flag" to boolValue(false))), etag = "\"v2\"")
        )
        assertTrue(client.fetchAndActivate())

        val restarted = harness.explicitClient()
        assertEquals(2, restarted.activatedVersion)
        assertFalse(restarted[flag])
        assertTrue(
            assertNotNull(restarted.newestRepresentation()).representation.fetchedAt >= refreshedAt,
            "freshness went backwards",
        )
        client.close()
        restarted.close()
    }

    // MARK: coalescing

    @Test
    fun `concurrent fetches collapse to one request`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.pause()
        harness.transport.enqueue(jsonResponse(resolveBody(5, mapOf("flag" to boolValue(true)))))

        val waiters = (1..4).map { backgroundScope.launch { client.fetch() } }
        settle()
        assertEquals(1, harness.transport.requestCount)

        harness.transport.resume()
        settle()
        assertTrue(waiters.all { it.isCompleted })
        assertEquals(1, harness.transport.requestCount)
        assertTrue(client.activate())
        client.close()
    }

    @Test
    fun `one waiter's cancellation leaves the shared fetch running`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.pause()
        harness.transport.enqueue(jsonResponse(resolveBody(5, mapOf("flag" to boolValue(true)))))

        var thrown: Throwable? = null
        val leaving =
            backgroundScope.launch {
                try {
                    client.fetch()
                } catch (cause: Throwable) {
                    thrown = cause
                    throw cause
                }
            }
        val staying = backgroundScope.launch { client.fetch() }
        settle()
        assertEquals(1, harness.transport.requestCount)

        leaving.cancel()
        settle()
        harness.transport.resume()
        settle()

        assertIs<CancellationException>(thrown, "cancellation must never be wrapped")
        assertTrue(staying.isCompleted && !staying.isCancelled, "the staying waiter was taken down too")
        assertEquals(1, harness.transport.requestCount)
        assertTrue(client.activate(), "the shared fetch ran to completion")
        client.close()
    }

    @Test
    fun `close racing a coalesced fetch surfaces as the closed error`() = runTest {
        val harness = harness()
        val client = harness.explicitClient()
        harness.transport.pause()

        var thrown: Throwable? = null
        val waiter: Job =
            backgroundScope.launch {
                try {
                    client.fetch()
                } catch (cause: Throwable) {
                    thrown = cause
                }
            }
        settle()
        assertEquals(1, harness.transport.requestCount)

        client.close()
        harness.transport.resume()
        settle()

        assertTrue(waiter.isCompleted)
        assertIs<IllegalStateException>(thrown)
        assertTrue(harness.transport.isClosed)
    }
}
