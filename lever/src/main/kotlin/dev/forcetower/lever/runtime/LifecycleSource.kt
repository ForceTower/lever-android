package dev.forcetower.lever.runtime

import android.os.Handler
import android.os.Looper
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
}

/**
 * `ProcessLifecycleOwner` behind the seam. Lifecycle APIs are main-thread only,
 * so the observer is installed and the current state read together there — one
 * post, so no transition can slip between the two (spec 0003 §5).
 */
internal class ProcessLifecycleSource : LifecycleSource {
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

            awaitClose {
                // Not through the runtime: teardown must remove the observer even
                // though the scope that installed it is already cancelled.
                handler.post { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
            }
        }
            .distinctUntilChanged()
}
