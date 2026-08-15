package dev.forcetower.lever.logging

/**
 * Where SDK diagnostics go. Host apps implement this to route lever's logs into
 * their own pipeline; the interface stays this small on purpose (spec 0003 §8).
 *
 * The sink may be invoked from **any thread or coroutine context**, so
 * implementations must be thread-safe. It is never invoked while the SDK holds
 * its state lock, so a sink may safely perform synchronous reads (`get`/`value`)
 * while handling a message. It *is* invoked under the commit gate, so calling
 * `activate()`, `fetchAndActivate()`, or `close()` from inside a sink callback
 * is a programmer error that can deadlock on the gate invoking the sink.
 */
public fun interface LeverLogSink {
    public fun log(level: LeverLogLevel, message: String)
}

/**
 * `DEBUG` is routine detail (an absent key, a fetch starting), `INFO` a
 * milestone (an activation), `WARN` a repaired or degraded input, `ERROR` a
 * configuration mistake or a failed write.
 */
public enum class LeverLogLevel { DEBUG, INFO, WARN, ERROR }

internal fun LeverLogSink.debug(message: String) {
    log(LeverLogLevel.DEBUG, message)
}

internal fun LeverLogSink.info(message: String) {
    log(LeverLogLevel.INFO, message)
}

internal fun LeverLogSink.warn(message: String) {
    log(LeverLogLevel.WARN, message)
}

internal fun LeverLogSink.error(message: String) {
    log(LeverLogLevel.ERROR, message)
}
