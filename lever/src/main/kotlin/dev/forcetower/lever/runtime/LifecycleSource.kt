package dev.forcetower.lever.runtime

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
     * Removes the platform observer, and does not return until it is gone.
     *
     * Cancelling the collecting coroutine only *queues* the removal, and
     * `close()` promises release rather than a scheduled intention
     * (spec 0003 §4). Idempotent.
     */
    fun detach() {}
}

/**
 * `ProcessLifecycleOwner` behind the seam. Lifecycle APIs are main-thread only,
 * so the observer is installed and the current state read together there — one
 * post, so no transition can slip between the two (spec 0003 §5).
 */
internal class ProcessLifecycleSource : LifecycleSource {
    private val installed = AtomicReference<DefaultLifecycleObserver?>(null)

    override fun phases(): Flow<LifecyclePhase> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val observer =
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        trySend(LifecyclePhase.FOREGROUND)
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        trySend(LifecyclePhase.BACKGROUND)
                    }
                }

            installed.set(observer)
            handler.post {
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
            }

            awaitClose { detach() }
        }
            .distinctUntilChanged()

    override fun detach() {
        val observer = installed.getAndSet(null) ?: return
        val remove = Runnable { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }

        // Lifecycle APIs are main-thread only. A caller already on the main
        // thread removes inline — posting there and waiting would deadlock on
        // itself — and anyone else waits for the post to run.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run()
            return
        }
        val removed = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            remove.run()
            removed.countDown()
        }
        removed.await(DETACH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        /**
         * A blocked main thread must not turn teardown into a hang; the observer
         * is then removed by the post whenever the main thread frees up, and it
         * can only feed an already-cancelled flow in the meantime.
         */
        const val DETACH_TIMEOUT_SECONDS = 2L
    }
}
