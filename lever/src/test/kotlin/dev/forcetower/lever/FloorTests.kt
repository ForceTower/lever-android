package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import dev.forcetower.lever.runtime.LifecyclePhase
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

@Serializable internal data class Paywall2(val headline: String, val cta: String)

/**
 * The three-layer floor, end to end (plan 0003 M8, spec 0002 §10.1).
 *
 * Live values → disk-cached last-activated values → code defaults. An
 * unreachable server means stale config, never a broken app — and this is the
 * suite that makes that a fact rather than an intent (research 0001 §7).
 */
internal class FloorTests {
    private val harnesses = mutableListOf<TestHarness>()

    @AfterTest
    fun tearDown() {
        harnesses.forEach { it.cleanup() }
    }

    private fun TestScope.harness(phase: LifecyclePhase = LifecyclePhase.FOREGROUND): TestHarness =
        TestHarness(testScheduler, phase = phase).also { harnesses.add(it) }

    private val enrollment = LeverKey.boolean("enable_enrollment", default = false)
    private val greeting = LeverKey.string("greeting", default = "hello")
    private val retries = LeverKey.int("max_retries", default = 3)
    private val ratio = LeverKey.double("ratio", default = 1.5)
    private val paywall = LeverKey.json("paywall", default = Paywall2("fallback", "none"))

    @Test
    fun `first run with a dead transport serves code defaults and never throws`() = runTest {
        val harness = harness()
        val client = harness.client()
        settle()
        advance(harness, 24.hours)

        assertFalse(client[enrollment])
        assertEquals("hello", client[greeting])
        assertEquals(3, client[retries])
        assertEquals(1.5, client[ratio])
        assertEquals(Paywall2("fallback", "none"), client[paywall])
        assertNull(client.activatedVersion)

        // …and an explicit fetch surfaces the failure instead of hiding it.
        assertFailsWith<LeverException.Network> { client.fetch() }
        client.close()
    }

    @Test
    fun `a warm cache serves through a dead transport, from the first statement`() = runTest {
        val harness = harness()
        harness.seedCache(
            12,
            mapOf(
                "enable_enrollment" to wireBool(true),
                "greeting" to wireString("olá"),
                "max_retries" to wireNumber("7"),
            ),
            fetchedAt = harness.now - 24 * 3600,
        )

        val client = harness.client()
        // No settling: the floor is loaded before anything asynchronous exists.
        assertTrue(client[enrollment])
        assertEquals("olá", client[greeting])
        assertEquals(7, client[retries])
        assertEquals(12, client.activatedVersion)

        settle()
        advance(harness, 24.hours)
        assertTrue(client[enrollment], "a failing refresh must change nothing")
        assertEquals(12, client.activatedVersion)
        client.close()
    }

    @Test
    fun `a 401 on either endpoint keeps serving and never clears the cache`() = runTest {
        val harness = harness()
        val store = harness.seedCache(3, mapOf("enable_enrollment" to wireBool(true)))
        harness.transport.enqueue(statusResponse(401))
        harness.transport.enqueueStream(StreamScript(status = 401).stream)

        val client = harness.client(harness.configuration(minimumFetchInterval = 12.hours))
        settle()
        advance(harness, 12.hours)

        assertTrue(client[enrollment])
        assertEquals(3, client.activatedVersion)
        assertTrue(store.snapshotFile.isFile, "a 401 deleted the cache file")
        assertTrue(harness.sink.contains(LeverLogLevel.WARN, "stream stopped, invalid key"))

        harness.transport.enqueue(statusResponse(401))
        assertFailsWith<LeverException.InvalidKey> { client.fetch() }
        assertTrue(client[enrollment], "reads survive an explicit 401 too")
        client.close()
    }

    @Test
    fun `a corrupt cache file is a first run, and the next activation rewrites it`() = runTest {
        val harness = harness()
        val store = harness.cacheStore()
        store.directory.mkdirs()
        store.snapshotFile.writeText("{ this is not a cache file")

        harness.transport.enqueue(
            jsonResponse(resolveBody(4, mapOf("enable_enrollment" to boolValue(true))))
        )
        val client = harness.client()
        assertFalse(client[enrollment], "defaults while the file is unreadable")
        assertTrue(harness.sink.contains(LeverLogLevel.WARN, "cache file is corrupt"))

        settle()
        assertTrue(client[enrollment])
        assertEquals(4, harness.client(harness.configuration(automaticUpdates = false)).activatedVersion)
        client.close()
    }

    @Test
    fun `every mismatch shape falls back to the default with one warning`() = runTest {
        val harness = harness()
        harness.transport.enqueue(
            jsonResponse(
                resolveBody(
                    5,
                    mapOf(
                        "enable_enrollment" to stringValue("yes"),
                        "greeting" to boolValue(true),
                        "max_retries" to numberValue("3.5"),
                        "ratio" to stringValue("1.5"),
                        "paywall" to jsonWireValue("""{"headline":"only half"}"""),
                    ),
                )
            )
        )

        val client = harness.client()
        settle()

        assertFalse(client[enrollment])
        assertEquals("hello", client[greeting])
        assertEquals(3, client[retries])
        assertEquals(1.5, client[ratio])
        assertEquals(Paywall2("fallback", "none"), client[paywall])
        assertEquals(5, client.activatedVersion, "a mismatch is a read-time event, not a fetch failure")

        // An out-of-range integer is the same story.
        harness.transport.enqueue(
            jsonResponse(resolveBody(6, mapOf("max_retries" to numberValue("99999999999"))))
        )
        assertTrue(client.fetchAndActivate())
        assertEquals(3, client[retries])
        assertEquals(6, harness.sink.count(LeverLogLevel.WARN, "type mismatch"))
        client.close()
    }

    @Test
    fun `a key rotation keeps the identity, and a namespace keeps the warm floor`() = runTest {
        val harness = harness()
        val original = harness.client(harness.configuration(clientKey = "pk_old", automaticUpdates = false))
        val clientId = original.clientId
        harness.transport.enqueue(
            jsonResponse(resolveBody(2, mapOf("enable_enrollment" to boolValue(true))))
        )
        assertTrue(original.fetchAndActivate())
        original.close()

        // Rotating the key orphans the default (key-derived) snapshot…
        val rotated = harness.client(harness.configuration(clientKey = "pk_new"))
        settle()
        assertEquals(clientId, rotated.clientId, "the installation identity must survive")
        assertFalse(rotated[enrollment], "the default hash is key-derived, so this is cold")
        rotated.close()

        // …while a namespace pins it to a name the developer controls.
        val namespaced =
            harness.client(
                harness.configuration(clientKey = "pk_old", automaticUpdates = false, cacheNamespace = "prod")
            )
        harness.transport.enqueue(
            jsonResponse(resolveBody(3, mapOf("enable_enrollment" to boolValue(true))))
        )
        assertTrue(namespaced.fetchAndActivate())
        namespaced.close()

        val afterRotation =
            harness.client(harness.configuration(clientKey = "pk_new", cacheNamespace = "prod"))
        assertTrue(afterRotation[enrollment], "the namespaced floor did not survive the rotation")
        assertEquals(clientId, afterRotation.clientId)
        afterRotation.close()
    }

    @Test
    fun `a cache-write failure leaves the in-memory activation standing`() = runTest {
        val harness = harness()
        val store = harness.cacheStore()
        store.directory.mkdirs()
        // The snapshot's name is taken by a directory: every write fails.
        File(store.snapshotFile.path).mkdirs()

        harness.transport.enqueue(
            jsonResponse(resolveBody(8, mapOf("enable_enrollment" to boolValue(true))))
        )
        val client = harness.client()
        settle()

        assertTrue(client[enrollment], "reads serve the new snapshot")
        assertEquals(8, client.activatedVersion)
        assertTrue(harness.sink.contains(LeverLogLevel.ERROR, "cache write failed"))
        client.close()
    }

    /**
     * The supported topology: one authoritative writer, any number of cache-only
     * readers over the same directory (spec 0002 §7, spec 0003 §11).
     */
    @Test
    fun `a writer's activation is visible to the next reader, and readers never write`() = runTest {
        val harness = harness()

        val writer = harness.client(harness.configuration(automaticUpdates = false))
        harness.transport.enqueue(
            jsonResponse(resolveBody(9, mapOf("greeting" to stringValue("from the writer"))))
        )
        assertTrue(writer.fetchAndActivate())

        val reader = harness.client(harness.configuration(automaticUpdates = false))
        assertEquals("from the writer", reader[greeting])
        assertEquals(writer.clientId, reader.clientId, "one installation, one identity")

        val requestsBefore = harness.transport.requestCount
        settle()
        advance(harness, 24.hours)
        assertEquals(requestsBefore, harness.transport.requestCount, "a reader fetched on its own")

        // A second activation by the writer reaches the next reader's floor.
        harness.transport.enqueue(
            jsonResponse(resolveBody(10, mapOf("greeting" to stringValue("second publish"))))
        )
        assertTrue(writer.fetchAndActivate())
        assertEquals("second publish", harness.client(harness.configuration(automaticUpdates = false)).let {
            val value = it[greeting]
            it.close()
            value
        })

        writer.close()
        reader.close()
    }

    @Test
    fun `an offline first run recovers as soon as the server answers`() = runTest {
        val harness = harness()
        harness.transport.enqueue(IOException("offline"))

        val client = harness.client(harness.configuration(minimumFetchInterval = 1.hours))
        settle()
        assertFalse(client[enrollment])

        harness.transport.enqueue(
            jsonResponse(resolveBody(1, mapOf("enable_enrollment" to boolValue(true))))
        )
        advance(harness, 1.hours)

        assertTrue(client[enrollment], "the timer never retried after the failed first run")
        assertEquals(1, client.activatedVersion)
        client.close()
    }
}
