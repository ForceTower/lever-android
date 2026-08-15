package dev.forcetower.lever.transport

import kotlinx.coroutines.flow.Flow

internal data class Header(val name: String, val value: String)

/**
 * Header names are case-insensitive; storing them lowercased means a lookup can
 * never depend on what casing the proxy in front of lever happened to use.
 */
internal class HttpHeaders(pairs: List<Pair<String, String>> = emptyList()) {
    private val storage = pairs.associate { (name, value) -> name.lowercase() to value }

    operator fun get(name: String): String? = storage[name.lowercase()]

    companion object {
        fun of(vararg pairs: Pair<String, String>) = HttpHeaders(pairs.toList())
    }
}

/**
 * Header order is part of the request contract the fixtures pin, so it is a
 * list rather than a map.
 */
internal class HttpRequest(val url: String, val headers: List<Header>) {
    fun header(name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value
}

internal class HttpResponse(
    /**
     * `null` when the transport handed back something that is not an HTTP
     * response — the `InvalidResponse` case, kept separate from every real
     * status (spec 0002 §6.1).
     */
    val status: Int?,
    val headers: HttpHeaders = HttpHeaders(),
    val body: ByteArray = ByteArray(0),
)

/**
 * A validated, still-open stream. [chunks] yields whatever arrives; the SSE
 * parser is written to be indifferent to chunk boundaries (spec 0002 §6.2).
 */
internal class HttpStream(
    val status: Int?,
    val headers: HttpHeaders = HttpHeaders(),
    val chunks: Flow<ByteArray>,
)

/**
 * Everything the runtime needs from the network, and the seam tests replace
 * with a scripted double (spec 0003 §10).
 *
 * Implementations surface transport failures as `IOException` and leave every
 * HTTP status alone — status interpretation is [ResolveEndpoint]'s job, so it
 * can be tested without a network.
 */
internal interface LeverTransport {
    suspend fun send(request: HttpRequest): HttpResponse

    suspend fun openStream(request: HttpRequest): HttpStream

    /**
     * Releases the connection pool and the dispatcher's threads. Cancelling the
     * runtime's jobs alone leaks both (spec 0003 §4).
     */
    fun close()
}
