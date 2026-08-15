package dev.forcetower.lever.transport

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The live transport: one **internal, dedicated** `OkHttpClient` whose settings
 * are pinned rather than inherited (spec 0003 §6.1).
 *
 * Every setting is a decision: redirects are refused because the
 * `Authorization` header must never travel to an origin the developer did not
 * configure; there is no HTTP cache because the SDK's ETag plus its disk cache
 * *is* the cache, and a second one underneath produces confusing
 * double-freshness; and there is no cookie jar on a public read endpoint.
 */
internal class OkHttpTransport : LeverTransport {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    override suspend fun send(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.toOkHttp())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val mapped =
                            try {
                                response.use {
                                    HttpResponse(
                                        status = it.code,
                                        headers = it.leverHeaders(),
                                        body = it.body.bytes(),
                                    )
                                }
                            } catch (cause: IOException) {
                                continuation.resumeWithException(cause)
                                return
                            }
                        continuation.resume(mapped)
                    }
                }
            )
        }

    override suspend fun openStream(request: HttpRequest): HttpStream {
        // Liveness on the stream belongs to the client-owned 60 s watchdog, not
        // to a second read timeout underneath it (spec 0003 §6.2).
        val streamClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val call = streamClient.newCall(request.toOkHttp())

        val response =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (!continuation.isCancelled) continuation.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            continuation.resume(response)
                        }
                    }
                )
            }

        val reading = AtomicBoolean(false)
        return HttpStream(
            status = response.code,
            headers = response.leverHeaders(),
            chunks = response.chunks(call, reading),
        ) {
            // A round that was never read — a 401, a 503, a wrong media type —
            // is released by closing its body, which hands the connection back
            // to the pool. Cancelling instead would tear the socket down and
            // make every reconnect pay for a new one.
            if (reading.get()) call.cancel()
            try {
                response.close()
            } catch (_: IllegalStateException) {
                // Already consumed by the chunk flow's `use`.
            }
        }
    }

    override fun close() {
        // Cancellation alone leaks the dispatcher's threads and the sockets.
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    /**
     * Chunked at line boundaries **or** at [MAX_CHUNK_BYTES], whichever comes
     * first. Flushing only on newlines would let a peer that never sends one
     * grow this buffer without limit — below the parser, where the 1 MiB frame
     * bound cannot see it (spec 0002 §12.1).
     */
    private fun Response.chunks(call: Call, reading: AtomicBoolean): Flow<ByteArray> = callbackFlow {
        reading.set(true)
        val reader =
            launch(Dispatchers.IO) {
                try {
                    body.source().use { source ->
                        val buffer = ByteArray(MAX_CHUNK_BYTES)
                        var length = 0
                        while (!source.exhausted()) {
                            buffer[length++] = source.readByte()
                            if (buffer[length - 1] == LINE_FEED || length == MAX_CHUNK_BYTES) {
                                if (trySend(buffer.copyOf(length)).isFailure) return@launch
                                length = 0
                            }
                        }
                        if (length > 0) trySend(buffer.copyOf(length))
                    }
                    this@callbackFlow.close()
                } catch (cause: IOException) {
                    // EOF and read errors are both "reconnect through backoff".
                    this@callbackFlow.close(cause)
                }
            }

        // The blocking read is not interruptible by cancellation, so teardown
        // cancels the call and lets the socket close underneath it
        // (spec 0003 §6.2).
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    private fun HttpRequest.toOkHttp(): Request {
        val builder = Request.Builder().url(url)
        for (header in headers) builder.addHeader(header.name, header.value)
        return builder.get().build()
    }

    private fun Response.leverHeaders(): HttpHeaders =
        HttpHeaders(headers.map { (name, value) -> name to value })

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_CHUNK_BYTES = 16 * 1024
        const val LINE_FEED: Byte = 0x0A
    }
}
