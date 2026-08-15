package dev.forcetower.lever.transport

import dev.forcetower.lever.leverJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A minimal SSE parser for exactly what lever emits (spec 0001 §7):
 * `event:`/`data:` frames, `:` comment heartbeats, and a `retry:` hint. A port
 * of the Swift parser, so frame semantics stay identical across SDKs
 * (spec 0003 §6.2).
 *
 * It is fed arbitrary byte chunks and holds whatever is incomplete, so frames
 * split across chunk boundaries — the normal case on a socket — parse the same
 * as frames that arrive whole.
 */
internal class ServerSentEventParser {
    data class Event(val name: String?, val data: String)

    /**
     * The server emits tiny frames, so an overrun means a broken peer. The
     * stream errors and reconnects through backoff rather than buffering
     * without limit (spec 0002 §6.2).
     */
    class FrameTooLargeException : Exception("sse frame exceeded ${MAX_FRAME_BYTES} bytes")

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /**
     * Bytes of the current frame already consumed out of [buffer]. Every line
     * counts — `data:`, `event:`, comments, `retry:`, unknown fields — because
     * the bound exists to stop a broken peer, and a broken peer picks the field
     * name (spec 0002 §12.1).
     */
    private var frameBytes = 0
    private var pendingName: String? = null
    private var pendingData: String? = null

    fun consume(chunk: ByteArray): List<Event> {
        // Checked before the bytes are appended, let alone turned into a
        // `String`: a check on what is left *over* after consuming a chunk
        // would happily build and discard a 100 MiB terminated line first.
        if (frameBytes.toLong() + size + chunk.size > MAX_FRAME_BYTES) {
            throw FrameTooLargeException()
        }
        append(chunk)

        val events = mutableListOf<Event>()
        var lineStart = 0
        var index = 0

        while (index < size) {
            val byte = buffer[index]
            if (byte != LF && byte != CR) {
                index++
                continue
            }
            // A lone CR at the very end may still be the first half of a CRLF.
            if (byte == CR && index + 1 == size) break

            val line = buffer.copyOfRange(lineStart, index)
            index += if (byte == CR && buffer[index + 1] == LF) 2 else 1
            frameBytes += index - lineStart
            lineStart = index
            handle(line)?.let(events::add)
        }

        consumePrefix(lineStart)
        return events
    }

    private fun handle(line: ByteArray): Event? {
        // The blank line dispatches whatever has accumulated, which is also
        // where the frame budget resets.
        if (line.isEmpty()) {
            val data = pendingData
            val name = pendingName
            pendingName = null
            pendingData = null
            frameBytes = 0
            return data?.let { Event(name, it) }
        }
        // `:` starts a comment — this is what a heartbeat is.
        if (line[0] == COLON) return null

        val colon = line.indexOf(COLON)
        val field: String
        val value: String
        if (colon < 0) {
            field = line.decodeToString()
            value = ""
        } else {
            var valueStart = colon + 1
            // A single leading space after the colon is part of the syntax.
            if (valueStart < line.size && line[valueStart] == SPACE) valueStart++
            field = line.copyOfRange(0, colon).decodeToString()
            value = line.copyOfRange(valueStart, line.size).decodeToString()
        }

        when (field) {
            "event" -> pendingName = value
            "data" -> pendingData = pendingData?.let { "$it\n$value" } ?: value
            // `retry:` is parsed and discarded: backoff is client-owned.
            else -> Unit
        }
        return null
    }

    private fun append(chunk: ByteArray) {
        if (size + chunk.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size))
        }
        chunk.copyInto(buffer, size)
        size += chunk.size
    }

    private fun consumePrefix(count: Int) {
        if (count == 0) return
        buffer.copyInto(buffer, 0, count, size)
        size -= count
    }

    private fun ByteArray.indexOf(byte: Byte): Int {
        for (index in indices) if (this[index] == byte) return index
        return -1
    }

    companion object {
        const val MAX_FRAME_BYTES = 1 shl 20

        private const val INITIAL_CAPACITY = 1024
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private const val COLON: Byte = 0x3A
        private const val SPACE: Byte = 0x20
    }
}

/**
 * The one frame lever sends: `{"version":42}`. Version numbers only, never
 * values — the stream is a nudge, not a source of truth (research 0001 §3.2).
 */
internal object VersionFrame {
    fun version(data: String): Int? {
        // Read as an element rather than a typed payload: a quoted number is a
        // broken peer, and every SDK must treat it the same way — ignore it.
        val element =
            try {
                leverJson.parseToJsonElement(data)
            } catch (_: RuntimeException) {
                return null
            }
        val version = (element as? JsonObject)?.get("version") as? JsonPrimitive ?: return null
        if (version.isString) return null
        return version.content.toIntOrNull()
    }
}
