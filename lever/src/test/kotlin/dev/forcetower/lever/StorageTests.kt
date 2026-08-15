package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import dev.forcetower.lever.storage.CacheStore
import dev.forcetower.lever.storage.CachedSnapshot
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The cache codec and store (plan 0003 M3). */
internal class StorageTests {
    private val sink = RecordingLogSink()
    private val directory: File =
        File.createTempFile("lever-storage", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store(keyHash: String = "0123456789abcdef") =
        CacheStore(directory, keyHash, sink)

    private val snapshot =
        CachedSnapshot(
            version = 42,
            etag = "\"a1b2c3d4e5f60718\"",
            values =
                mapOf(
                    "enable_enrollment" to wireBool(true),
                    "greeting" to wireString("olá"),
                    "max_retries" to wireNumber("3"),
                    "paywall" to
                        wireValue("json", """{"cta":"Start trial","headline":"Go Pro","trialDays":7}"""),
                ),
            fetchedAt = 1_755_100_000,
            activatedAt = 1_755_100_005,
        )

    // MARK: round trip

    @Test
    fun `a saved snapshot loads back identically`() {
        val store = store()
        store.save(snapshot)
        assertEquals(snapshot, store.loadSnapshot())
        assertTrue(sink.all.isEmpty(), "unexpected logs: ${sink.all}")
    }

    @Test
    fun `the raw wire payload survives untouched`() {
        val store = store()
        store.save(snapshot)
        val paywall = assertNotNull(store.loadSnapshot()).values.getValue("paywall")
        assertEquals("json", paywall.type)
        assertEquals(
            "Go Pro",
            paywall.value.jsonObject.getValue("headline").jsonPrimitive.content,
        )
    }

    @Test
    fun `a null etag round-trips`() {
        val store = store()
        store.save(snapshot.copy(etag = null))
        assertNull(assertNotNull(store.loadSnapshot()).etag)
    }

    @Test
    fun `a first run has no snapshot and no log`() {
        assertNull(store().loadSnapshot())
        assertTrue(sink.all.isEmpty())
    }

    // MARK: corruption

    @Test
    fun `a corrupt file is a first run with a warning`() {
        val store = store()
        File(directory, "0123456789abcdef.json").writeText("{not json")
        assertNull(store.loadSnapshot())
        assertTrue(sink.contains(LeverLogLevel.WARN, "cache file is corrupt"))
    }

    @Test
    fun `an unknown schema is discarded, never migrated`() {
        val store = store()
        store.save(snapshot)
        val file = File(directory, "0123456789abcdef.json")
        file.writeText(file.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        assertNull(store.loadSnapshot())
        assertTrue(sink.contains(LeverLogLevel.WARN, "cache file schema is unknown"))
    }

    @Test
    fun `negative timestamps and versions are corrupt, extremes are kept`() {
        val store = store()

        store.save(snapshot.copy(fetchedAt = -1))
        assertNull(store.loadSnapshot())
        assertTrue(sink.contains(LeverLogLevel.WARN, "timestamps are out of range"))

        store.save(snapshot.copy(activatedAt = -1))
        assertNull(store.loadSnapshot())

        store.save(snapshot.copy(version = -1))
        assertNull(store.loadSnapshot())
        assertTrue(sink.contains(LeverLogLevel.WARN, "version is negative"))

        // An absurd but non-negative timestamp is *not* corrupt: it must reach
        // the scheduler's saturating arithmetic, not a discard (spec 0002 §12.1).
        store.save(snapshot.copy(fetchedAt = Long.MAX_VALUE, activatedAt = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, assertNotNull(store.loadSnapshot()).fetchedAt)
    }

    @Test
    fun `a write failure logs and leaves the caller alone`() {
        val blocked = File(directory, "blocked")
        blocked.writeText("not a directory")
        val store = CacheStore(blocked, "0123456789abcdef", sink)
        store.save(snapshot)
        assertTrue(
            sink.contains(LeverLogLevel.ERROR, "cache write failed") ||
                sink.contains(LeverLogLevel.ERROR, "cache directory could not be created")
        )
        assertNull(store.loadSnapshot())
    }

    // MARK: identity

    @Test
    fun `the client id is created once and reused`() {
        val first = store().loadOrCreateClientId()
        assertEquals(first, store().loadOrCreateClientId())
        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(first.lowercase(), first)
    }

    @Test
    fun `a non-canonical client id is rewritten in place, not regenerated`() {
        val id = UUID.randomUUID().toString().uppercase()
        File(directory, "identity.json").writeText("""{"schemaVersion":1,"clientId":"$id"}""")

        val loaded = store().loadOrCreateClientId()
        assertEquals(id.lowercase(), loaded)
        assertTrue(sink.contains(LeverLogLevel.WARN, "client id was not canonical"))
        // The rewrite stuck, so the next launch reads it clean.
        assertEquals(loaded, store().loadOrCreateClientId())
        assertEquals(1, sink.count(LeverLogLevel.WARN, "client id was not canonical"))
    }

    @Test
    fun `a garbage client id regenerates`() {
        File(directory, "identity.json").writeText("""{"schemaVersion":1,"clientId":"nope"}""")
        val loaded = store().loadOrCreateClientId()
        assertEquals(loaded, UUID.fromString(loaded).toString())
        assertTrue(sink.contains(LeverLogLevel.WARN, "client id is not a uuid"))
    }

    @Test
    fun `an unreadable identity file regenerates`() {
        File(directory, "identity.json").writeText("{oops")
        assertNotNull(store().loadOrCreateClientId())
        assertTrue(sink.contains(LeverLogLevel.WARN, "identity file is unreadable"))
    }

    /**
     * The publication primitive: the name never exists before its complete
     * bytes, so a racing initializer either wins or reads a **complete** file —
     * never an empty one it would judge corrupt and overwrite (spec 0002 §12).
     */
    @Test
    fun `racing first initializers converge on one complete client id`() {
        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val partial = java.util.Collections.synchronizedList(mutableListOf<String>())

        repeat(threads) {
            pool.submit {
                start.await()
                // A reader that finds the name must find complete content.
                val file = File(directory, "identity.json")
                if (file.exists()) {
                    val text = runCatching { file.readText() }.getOrDefault("")
                    if (text.isNotEmpty() && !text.contains("clientId")) partial.add(text)
                }
                observed.add(CacheStore(directory, "hash", sink).loadOrCreateClientId())
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(threads, observed.size)
        assertEquals(1, observed.toSet().size, "identities diverged: ${observed.toSet()}")
        assertTrue(partial.isEmpty(), "a reader saw an incomplete identity file: $partial")
        assertEquals(observed.first(), store().loadOrCreateClientId())
    }

    // MARK: rotation

    @Test
    fun `a key rotation keeps the identity and, with a namespace, the snapshot`() {
        val original = validate(configuration("pk_old", namespace = null)) { directory }
        val rotated = validate(configuration("pk_new", namespace = null)) { directory }
        assertTrue(original.cacheKeyHash != rotated.cacheKeyHash)

        val namespacedOld = validate(configuration("pk_old", namespace = "prod")) { directory }
        val namespacedNew = validate(configuration("pk_new", namespace = "prod")) { directory }
        assertEquals(namespacedOld.cacheKeyHash, namespacedNew.cacheKeyHash)

        val identity = CacheStore(original.cacheDirectory, original.cacheKeyHash, sink)
        val id = identity.loadOrCreateClientId()
        CacheStore(namespacedOld.cacheDirectory, namespacedOld.cacheKeyHash, sink).save(snapshot)

        // The rotated client: same installation identity, warm namespaced cache,
        // cold default-hash cache.
        assertEquals(
            id,
            CacheStore(rotated.cacheDirectory, rotated.cacheKeyHash, sink).loadOrCreateClientId(),
        )
        assertEquals(
            snapshot,
            CacheStore(namespacedNew.cacheDirectory, namespacedNew.cacheKeyHash, sink).loadSnapshot(),
        )
        assertNull(CacheStore(rotated.cacheDirectory, rotated.cacheKeyHash, sink).loadSnapshot())
    }

    @Test
    fun `the snapshot file name is a stable hash of the canonical url and namespace`() {
        val first = validate(configuration("pk_test", namespace = "prod")) { directory }
        val second = validate(configuration("pk_test", namespace = "prod")) { directory }
        assertEquals(first.cacheKeyHash, second.cacheKeyHash)
        assertEquals(cacheKeyHash("https://lever.example", "prod"), first.cacheKeyHash)
    }

    // MARK: the cross-SDK format promise

    /**
     * Spec 0003 §7: one cache format, not one per SDK — proven by decoding
     * lever-swift's own output unmodified. Bytes are explicitly not compared.
     */
    @Test
    fun `lever-swift's format fixtures decode unmodified`() {
        File(directory, "0123456789abcdef.json").writeText(resource("swift-snapshot.json"))
        val loaded = assertNotNull(store().loadSnapshot())

        assertEquals(42, loaded.version)
        assertEquals("\"a1b2c3d4e5f60718\"", loaded.etag)
        assertEquals(1_755_100_000, loaded.fetchedAt)
        assertEquals(1_755_100_005, loaded.activatedAt)
        assertEquals(
            setOf("enable_enrollment", "greeting", "max_retries", "ratio", "paywall"),
            loaded.values.keys,
        )
        assertEquals(wireBool(true), loaded.values.getValue("enable_enrollment"))
        assertEquals(wireString("olá"), loaded.values.getValue("greeting"))
        assertEquals(3, LeverKey.int("max_retries", 0).decode(loaded.values.getValue("max_retries")))
        assertEquals(0.25, LeverKey.double("ratio", 0.0).decode(loaded.values.getValue("ratio")))
        assertEquals(
            7,
            loaded.values
                .getValue("paywall")
                .value
                .jsonObject
                .getValue("trialDays")
                .jsonPrimitive
                .content
                .toInt(),
        )

        File(directory, "fedcba9876543210.json").writeText(resource("swift-snapshot-null-etag.json"))
        val empty = assertNotNull(store("fedcba9876543210").loadSnapshot())
        assertEquals(0, empty.version)
        assertNull(empty.etag)
        assertEquals(emptyMap(), empty.values)
    }

    @Test
    fun `lever-swift's identity file decodes unmodified`() {
        File(directory, "identity.json").writeText(resource("swift-identity.json"))
        assertEquals("48a96265-bd46-4aae-b30b-66e85ea1f3de", store().loadOrCreateClientId())
        assertTrue(sink.all.isEmpty(), "unexpected logs: ${sink.all}")
    }

    /**
     * The other half of the promise: what this SDK emits carries the same schema
     * and the same values as what it read. Member order and number spelling are
     * each serializer's business.
     */
    @Test
    fun `an emit-then-decode round trip keeps schema and values, not bytes`() {
        File(directory, "0123456789abcdef.json").writeText(resource("swift-snapshot.json"))
        val fromSwift = assertNotNull(store().loadSnapshot())

        store().save(fromSwift)
        val reEmitted = File(directory, "0123456789abcdef.json").readText()
        assertEquals(fromSwift, assertNotNull(store().loadSnapshot()))

        val schema = leverJson.decodeFromString<JsonObject>(reEmitted)
        assertEquals(
            setOf("schemaVersion", "version", "etag", "values", "fetchedAt", "activatedAt"),
            schema.keys,
        )
        assertEquals(JsonPrimitive(1), schema.getValue("schemaVersion"))
        // Values keep their raw wire shape rather than being re-encoded typed.
        assertEquals(
            setOf("type", "value"),
            schema.getValue("values").jsonObject.getValue("greeting").jsonObject.keys,
        )
    }

    private fun configuration(clientKey: String, namespace: String?) =
        LeverConfiguration(
            baseUrl = "https://lever.example",
            clientKey = clientKey,
            cacheDirectory = directory,
            cacheNamespace = namespace,
            logSink = sink,
        )

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/cache-format/$name")) { "missing $name" }
            .use { it.readBytes().decodeToString() }
}
