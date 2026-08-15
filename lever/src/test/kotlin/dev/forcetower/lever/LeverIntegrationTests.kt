package dev.forcetower.lever

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import dev.forcetower.lever.runtime.LifecyclePhase
import dev.forcetower.lever.runtime.ProcessLifecycleSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The public API on a real Android stack: the singleton's misuse rules, the
 * live `ProcessLifecycleOwner` binding, and the floor through the real
 * transport (plan 0003 M4, M6, M8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class LeverIntegrationTests {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun configuration(
        baseUrl: String = UNREACHABLE,
        automaticUpdates: Boolean = false,
    ) = LeverConfiguration(
        baseUrl = baseUrl,
        clientKey = "pk_test",
        minimumFetchInterval = Duration.ZERO,
        automaticUpdates = automaticUpdates,
        logSink = { _, _ -> },
    )

    @BeforeTest
    fun reset() {
        Lever.resetForTesting()
        context.noBackupFilesDir.resolve("lever").deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        Lever.resetForTesting()
    }

    private val flag = LeverKey.boolean("enable_enrollment", default = false)

    @Test
    fun `reading shared before configure names the fix`() {
        val failure = assertFailsWith<IllegalStateException> { Lever.shared }
        assertTrue(failure.message!!.contains("Application.onCreate"))
    }

    @Test
    fun `configure installs a client and refuses a second call`() {
        Lever.configure(context, configuration())
        assertFalse(Lever.shared[flag], "the code default is the floor")

        val failure = assertFailsWith<IllegalStateException> { Lever.configure(context, configuration()) }
        assertTrue(failure.message!!.contains("configure was called twice"))
    }

    @Test
    fun `the shared instance cannot be closed`() {
        Lever.configure(context, configuration())
        val failure = assertFailsWith<IllegalStateException> { Lever.shared.close() }
        assertTrue(failure.message!!.contains("process-lived"))
    }

    @Test
    fun `a failed construction releases the reservation`() {
        assertFailsWith<IllegalArgumentException> {
            Lever.configure(context, baseUrl = "not a url", clientKey = "pk_test")
        }
        // The reservation was released, so a correct call still works.
        Lever.configure(context, configuration())
        assertFalse(Lever.shared[flag])
    }

    @Test
    fun `the cache lands in noBackupFilesDir by default`() {
        Lever.configure(context, configuration())
        val identity = context.noBackupFilesDir.resolve("lever/identity.json")
        assertTrue(identity.isFile, "the installation identity was not written where it belongs")
    }

    /**
     * The floor over the real OkHttp transport: an unreachable server means
     * defaults, never a crash, and an explicit fetch surfaces the failure.
     */
    @Test
    fun `an unreachable server serves defaults and surfaces the failure`() = runTest {
        val client = LeverClient(context, configuration())
        assertFalse(client[flag])
        assertNull(client.activatedVersion)
        assertFailsWith<LeverException.Network> { client.fetch() }
        assertFalse(client[flag])
        client.close()
    }

    @Test
    fun `the live lifecycle source reports the current phase and removes its observer`() = runTest {
        val registry = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val before = registry.observerCount

        val phases = mutableListOf<LifecyclePhase>()
        val job = backgroundScope.launch { ProcessLifecycleSource().phases().collect { phases.add(it) } }
        // The collector registers first, then the main thread runs the install,
        // then the phase reaches the collector.
        testScheduler.runCurrent()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        testScheduler.runCurrent()

        assertEquals(
            listOf(
                if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    LifecyclePhase.FOREGROUND
                } else {
                    LifecyclePhase.BACKGROUND
                }
            ),
            phases,
            "the source must report the current phase at subscription",
        )
        assertEquals(before + 1, registry.observerCount)

        job.cancel()
        testScheduler.runCurrent()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before, registry.observerCount, "the observer stayed installed after teardown")
    }

    @Test
    fun `the first phase drives the automatic path on a real lifecycle`() = runTest {
        val client = LeverClient(context, configuration(automaticUpdates = true))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // Nothing to assert about the network here — the point is that binding
        // the real ProcessLifecycleOwner neither throws nor hangs, and reads
        // keep working while it runs.
        assertFalse(client[flag])
        client.close()
    }

    /**
     * `close()` promises release, not a queued intention: the observer is gone
     * when it returns, with no looper idling afterwards to help it along
     * (review 0003 pass 3, P3-F3).
     */
    @Test
    fun `close removes the live lifecycle observer before it returns`() = runTest {
        val registry = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val before = registry.observerCount

        val client = LeverClient(context, configuration(automaticUpdates = true))
        // The live runtime subscribes from its own thread and installs the
        // observer through the main looper, so wait for that to land.
        val deadline = System.nanoTime() + 5_000_000_000
        while (registry.observerCount == before && System.nanoTime() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(before + 1, registry.observerCount, "the observer never installed")

        client.close()
        assertEquals(before, registry.observerCount, "the observer outlived close()")
    }

    private companion object {
        /** Port 1 is reserved and closed: connections are refused immediately. */
        const val UNREACHABLE = "http://127.0.0.1:1"
    }
}
