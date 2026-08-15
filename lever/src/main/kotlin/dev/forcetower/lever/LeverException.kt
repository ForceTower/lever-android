package dev.forcetower.lever

import java.io.IOException

/**
 * Everything an explicit [LeverClient.fetch] can fail with. Automatic paths log
 * these and change nothing — the current snapshot keeps serving
 * (spec 0002 §5.4).
 *
 * Every case is a class instantiated per failure rather than a singleton: a
 * `Throwable` carries mutable stack-trace and suppressed-exception state.
 */
public sealed class LeverException(message: String) : Exception(message) {
    /** 401 — the key is unknown or has been rotated. Never clears the cache. */
    public class InvalidKey : LeverException("invalid key")

    /** Any HTTP status other than 200/304/401, unexpected 2xx like 204 included. */
    public class Server(public val status: Int) : LeverException("server status=$status")

    public class Network(override val cause: IOException) :
        LeverException("network error=${cause.javaClass.simpleName}")

    /** Undecodable body, a non-HTTP transport event, or a 304 nobody asked for. */
    public class InvalidResponse : LeverException("invalid response")
}
