package dev.forcetower.lever

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The one-line entry point, meant for `Application.onCreate`.
 *
 * ```kotlin
 * Lever.configure(this, LeverConfiguration(baseUrl = "https://config.example.dev",
 *                                          clientKey = BuildConfig.LEVER_KEY))
 * ```
 *
 * There is deliberately no lazy placeholder client: a half-configured singleton
 * silently serving defaults is the failure mode Firebase users know and hate,
 * so misuse throws instead (spec 0003 §2.1). Multiple explicit [LeverClient]
 * instances are always allowed — [shared] is sugar, not a registry.
 */
public object Lever {
    private val lock = ReentrantLock()
    private var installation: Installation = Installation.Empty

    /**
     * `Reserved` is the state between winning the right to configure and having
     * a client to install. It exists so the configure-once rule is a guarantee
     * rather than a check: without it, two callers can both read "not
     * configured", both build a client — each with a live runtime, timer, and
     * stream — and install one over the other (spec 0002 §12.1).
     */
    private sealed interface Installation {
        data object Empty : Installation

        data object Reserved : Installation

        class Installed(val client: LeverClient) : Installation
    }

    /**
     * @throws IllegalStateException if called twice.
     * @throws IllegalArgumentException if `baseUrl` is not an absolute http(s)
     *   origin.
     */
    public fun configure(context: Context, configuration: LeverConfiguration) {
        check(reserveInstallation()) {
            "Lever.configure was called twice. Configure once at launch; for a " +
                "second environment, construct a LeverClient directly."
        }

        // Deliberately outside the lock: construction touches the filesystem and
        // logs through a host-provided sink.
        val client =
            try {
                LeverClient(context, configuration)
            } catch (cause: Throwable) {
                lock.withLock { installation = Installation.Empty }
                throw cause
            }

        client.isSharedInstance = true
        lock.withLock { installation = Installation.Installed(client) }
    }

    public fun configure(
        context: Context,
        baseUrl: String,
        clientKey: String,
        leverContext: LeverContext = LeverContext(),
    ) {
        configure(
            context,
            LeverConfiguration(baseUrl = baseUrl, clientKey = clientKey, context = leverContext),
        )
    }

    /** @throws IllegalStateException if [configure] has not run yet. */
    public val shared: LeverClient
        get() {
            val current = lock.withLock { installation }
            check(current is Installation.Installed) {
                "Lever.shared was read before Lever.configure. Call " +
                    "Lever.configure(…) in Application.onCreate, before the first read."
            }
            return current.client
        }

    /**
     * The atomic half of [configure]: exactly one caller can move the singleton
     * out of `Empty`. Factored out so the race has a test that does not have to
     * crash the test process to observe the loser.
     */
    internal fun reserveInstallation(): Boolean =
        lock.withLock {
            if (installation !== Installation.Empty) return@withLock false
            installation = Installation.Reserved
            true
        }

    /**
     * Test-only: the singleton is process-global, so its suite needs a way back
     * to the unconfigured state. The production [IllegalStateException]s stay
     * intact.
     */
    internal fun resetForTesting() {
        lock.withLock { installation = Installation.Empty }
    }
}
