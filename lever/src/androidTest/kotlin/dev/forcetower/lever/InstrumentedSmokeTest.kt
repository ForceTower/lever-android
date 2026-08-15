package dev.forcetower.lever

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forcetower.lever.storage.CacheStore
import dev.forcetower.lever.storage.CachedSnapshot
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The AAR on a real stack (plan 0003 M1, M3).
 *
 * Two things only a device can prove: that the library loads and serves reads
 * through the public API with the real `ProcessLifecycleOwner` binding, and that
 * the identity file's publication primitives behave on the actual
 * `noBackupFilesDir` filesystem rather than the host JVM's.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = context.noBackupFilesDir.resolve("lever-instrumented")

    private val flag = LeverKey.boolean("enable_enrollment", default = false)

    @Before
    fun setUp() {
        directory.deleteRecursively()
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun readsServeDefaultsWithNoServer() {
        val client =
            LeverClient(
                context,
                LeverConfiguration(
                    baseUrl = "http://127.0.0.1:1",
                    clientKey = "pk_test",
                    minimumFetchInterval = Duration.ZERO,
                    cacheDirectory = directory,
                    logSink = { _, _ -> },
                ),
            )

        assertFalse(client[flag], "the code default is the floor")
        assertEquals(null, client.activatedVersion)
        client.close()
    }

    @Test
    fun theCacheRoundTripsOnTheDeviceFilesystem() {
        val store = CacheStore(directory, "0123456789abcdef", { _, _ -> })
        val snapshot =
            CachedSnapshot(
                version = 3,
                etag = "\"abc\"",
                values = mapOf("enable_enrollment" to WireValue("boolean", JsonPrimitive(true))),
                fetchedAt = 1_755_100_000,
                activatedAt = 1_755_100_000,
            )
        store.save(snapshot)
        assertEquals(snapshot, store.loadSnapshot())
    }

    /**
     * The publication primitive spec 0002 §12 requires: the name never exists
     * before its complete bytes, so a racing initializer either wins or reads a
     * complete file. `Files.createLink` is the same `link(2)` on API 26+, but
     * only a device proves it on the real filesystem.
     */
    @Test
    fun racingInitializersConvergeOnOneCompleteIdentity() {
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val observed = java.util.Collections.synchronizedList(mutableListOf<String>())

        repeat(threads) {
            pool.submit {
                start.await()
                observed.add(CacheStore(directory, "hash", { _, _ -> }).loadOrCreateClientId())
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(threads, observed.size)
        assertEquals(1, observed.toSet().size, "identities diverged: ${observed.toSet()}")
        val identity = assertNotNull(observed.firstOrNull())
        assertEquals(identity, UUID.fromString(identity).toString())
    }

    @Test
    fun theSharedInstanceConfiguresOnceAndServesReads() {
        Lever.resetForTesting()
        try {
            Lever.configure(
                context,
                LeverConfiguration(
                    baseUrl = "http://127.0.0.1:1",
                    clientKey = "pk_test",
                    automaticUpdates = true,
                    cacheDirectory = directory,
                    logSink = { _, _ -> },
                ),
            )
            assertFalse(Lever.shared[flag])
        } finally {
            Lever.resetForTesting()
        }
    }
}
