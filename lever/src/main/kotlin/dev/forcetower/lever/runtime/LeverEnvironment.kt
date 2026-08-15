package dev.forcetower.lever.runtime

import dev.forcetower.lever.ValidatedConfiguration
import dev.forcetower.lever.transport.LeverTransport
import dev.forcetower.lever.transport.OkHttpTransport
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * The runtime's thread of execution and how to release it. Cancelling jobs
 * alone leaks the thread (spec 0003 §4).
 */
internal class RuntimeThread(val dispatcher: CoroutineDispatcher, val shutdown: () -> Unit)

/**
 * Everything non-deterministic, in one injectable bundle (spec 0003 §10).
 *
 * The two time seams are separate on purpose: persisted timestamps must survive
 * a relaunch (so they are wall-clock Unix seconds), while timers, backoff, and
 * the watchdog must not care what the wall clock does — those run on the
 * dispatcher's delay scheduling, which `kotlinx-coroutines-test` virtualizes
 * wholesale.
 */
internal class LeverEnvironment(
    val makeTransport: (ValidatedConfiguration) -> LeverTransport,
    /** Wall clock, Unix seconds. */
    val now: () -> Long,
    val lifecycle: () -> LifecycleSource,
    /** Full jitter: a value in `0..ceiling` (spec 0002 §6.2). */
    val jitter: (Double) -> Double,
    val runtimeThread: () -> RuntimeThread,
) {
    companion object {
        fun live(): LeverEnvironment =
            LeverEnvironment(
                makeTransport = { OkHttpTransport() },
                now = { System.currentTimeMillis() / 1000 },
                lifecycle = { ProcessLifecycleSource() },
                jitter = { ceiling -> Random.nextDouble(0.0, ceiling) },
                runtimeThread = { singleThreadRuntime() },
            )

        /**
         * The coroutine analogue of the Swift actor: one thread, so scheduling,
         * lifecycle reaction, fetch execution, and SSE all have one home and
         * cancellation has one owner (spec 0003 §4).
         */
        fun singleThreadRuntime(): RuntimeThread {
            val dispatcher: ExecutorCoroutineDispatcher =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "lever-runtime").apply { isDaemon = true }
                }
                    .asCoroutineDispatcher()
            return RuntimeThread(dispatcher) { dispatcher.close() }
        }
    }
}
