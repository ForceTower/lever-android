package dev.forcetower.lever.runtime

import android.os.Handler
import android.os.Looper
import dev.forcetower.lever.logging.LeverLogSink
import dev.forcetower.lever.logging.warn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class LifecyclePhase { FOREGROUND, BACKGROUND }

/**
 * Reports the **current** phase at subscription, then every transition.
 *
 * Initial state, not just events, is the requirement: a client constructed
 * while the app is already foregrounded must connect the stream immediately —
 * waiting for a transition that already happened would leave SSE closed for the
 * whole session (spec 0002 §5.2). It is also the one init trigger: there is no
 * separate construction-time fetch to race it (spec 0002 §12).
 */
internal interface LifecycleSource {
    fun phases(): Flow<LifecyclePhase>

    /**
     * Detaches from the platform's lifecycle, in two parts with different
     * guarantees (spec 0003 §4):
     *
     * - **Callbacks stop immediately**, synchronously, before this returns.
     *   Nothing the platform reports afterwards reaches the SDK.
     * - **The observer registration is removed before this returns**, unless
     *   the main thread is unavailable — removal is a main-thread-only API, so
     *   a blocked main thread would otherwise turn one client's teardown into a
     *   hang. In that case the removal stays queued and runs when the main
     *   thread frees up, against an observer that is already inert.
     *
     * Cancelling the collecting coroutine only *queues* both, which is why this
     * exists. Idempotent, and safe to call before the observer has finished
     * installing.
     */
    fun detach() {}
}

/**
 * `ProcessLifecycleOwner` behind the seam. Lifecycle APIs are main-thread only,
 * so the observer is installed and the current state read together there — one
 * post, so no transition can slip between the two (spec 0003 §5).
 */
internal class ProcessLifecycleSource(private val sink: LeverLogSink) : LifecycleSource {
    private val installed = AtomicReference<DefaultLifecycleObserver?>(null)

    /**
     * Set by [detach] and read by the install itself: teardown can win the race
     * against a posted installation, and an observer added *after* its detach
     * would never be removed at all.
     */
    private val detached = AtomicBoolean(false)

    override fun phases(): Flow<LifecyclePhase> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val observer =
                object : DefaultLifecycleObserver {
                    // Detach suppresses callbacks *here*, so suppression does not
                    // depend on the removal having run — the removal can only be
                    // as prompt as the main thread allows, and this cannot.
                    override fun onStart(owner: LifecycleOwner) {
                        if (!detached.get()) trySend(LifecyclePhase.FOREGROUND)
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        if (!detached.get()) trySend(LifecyclePhase.BACKGROUND)
                    }
                }

            handler.post {
                // Teardown may already have happened; installing now would leave
                // an observer nobody is left to remove.
                if (detached.get()) return@post
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                trySend(
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        LifecyclePhase.FOREGROUND
                    } else {
                        LifecyclePhase.BACKGROUND
                    }
                )
                // Registering re-delivers the current state to the new observer,
                // which `distinctUntilChanged` collapses into the read above.
                lifecycle.addObserver(observer)
                installed.set(observer)
            }

            awaitClose { detach() }
        }
            .distinctUntilChanged()

    override fun detach() {
        // Marking first is what closes the race: an installation still sitting in
        // the main thread's queue sees this and skips.
        if (!detached.compareAndSet(false, true)) return
        val remove =
            Runnable {
                installed.getAndSet(null)?.let {
                    ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
                }
            }

        // Lifecycle APIs are main-thread only. A caller already on the main
        // thread removes inline — posting there and waiting would deadlock on
        // itself — and anyone else waits for the post to run. Either way the
        // removal is ordered behind the installation it has to undo.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run()
            return
        }
        val removed = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            remove.run()
            removed.countDown()
        }
        if (!removed.await(DETACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            // Only reachable when the main thread is blocked by something else.
            // The observer is already inert — it stopped emitting the moment
            // `detached` was set — so what is left queued is the registration
            // itself. Waiting longer would turn someone else's stall into this
            // client's hang, which is the worse of the two failures.
            sink.warn("lifecycle observer removal is waiting on a blocked main thread")
        }
    }

    private companion object {
        const val DETACH_TIMEOUT_SECONDS = 2L
    }
}
