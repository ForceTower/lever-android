package dev.forcetower.lever.transport

import dev.forcetower.lever.LeverException
import dev.forcetower.lever.ValidatedConfiguration
import dev.forcetower.lever.WireValue
import dev.forcetower.lever.leverJson
import kotlinx.serialization.Serializable

/**
 * Request construction and status mapping for `GET /v1/resolve`
 * (spec 0002 §6.1), as pure functions over the validated configuration — no
 * network involved, so the contract fixtures can assert both halves directly.
 */
internal object ResolveEndpoint {
    /** RFC 3986's unreserved set — the one the fixtures pin. */
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun percentEncoded(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val character = byte.toInt().toChar()
            if (byte >= 0 && UNRESERVED.indexOf(character) >= 0) {
                append(character)
            } else {
                append('%')
                append("%02X".format(byte))
            }
        }
    }

    /**
     * Reserved names first, in a fixed order, then `attr.*` sorted by name —
     * the ordering the contract fixtures pin for every SDK (spec 0002 §12).
     */
    fun query(configuration: ValidatedConfiguration, clientId: String): String {
        val items = mutableListOf<String>()
        configuration.platform?.let { items.add("platform=${percentEncoded(it)}") }
        configuration.appVersion?.let { items.add("appVersion=${percentEncoded(it)}") }
        items.add("clientId=${percentEncoded(clientId)}")
        // `configuration.attributes` is already in ascending UTF-8 byte order.
        for (attribute in configuration.attributes) {
            items.add("attr.${percentEncoded(attribute.name)}=${percentEncoded(attribute.value)}")
        }
        return items.joinToString("&")
    }

    fun request(
        configuration: ValidatedConfiguration,
        clientId: String,
        ifNoneMatch: String?,
    ): HttpRequest {
        val headers = mutableListOf(
            Header("Authorization", "Bearer ${configuration.clientKey}"),
            Header("Accept", "application/json"),
        )
        if (ifNoneMatch != null) headers.add(Header("If-None-Match", ifNoneMatch))
        return HttpRequest(
            url = "${configuration.baseUrl}/v1/resolve?${query(configuration, clientId)}",
            headers = headers,
        )
    }

    fun streamRequest(configuration: ValidatedConfiguration): HttpRequest =
        HttpRequest(
            url = "${configuration.baseUrl}/v1/stream",
            headers = listOf(
                Header("Authorization", "Bearer ${configuration.clientKey}"),
                Header("Accept", "text/event-stream"),
            ),
        )

    sealed interface Outcome {
        data class Fresh(
            val version: Int,
            val values: Map<String, WireValue>,
            val etag: String?,
        ) : Outcome

        /** The representation whose validator we sent is still current. */
        data object NotModified : Outcome
    }

    /**
     * Maps one HTTP response to what the runtime should do with it.
     * [sentValidator] decides whether a 304 is legitimate at all.
     */
    fun outcome(response: HttpResponse, sentValidator: Boolean): Outcome {
        val status = response.status ?: throw LeverException.InvalidResponse()

        return when (status) {
            200 -> {
                val envelope =
                    try {
                        leverJson.decodeFromString<Envelope>(response.body.decodeToString())
                    } catch (_: RuntimeException) {
                        throw LeverException.InvalidResponse()
                    }
                // `ok: false`, or a null/absent `data`, is a fetch failure —
                // **never** an empty `values` map. Treating it as empty would
                // resolve every key to its code default while the previous
                // snapshot was still serviceable, collapsing the three-layer
                // floor on a server that was reachable (spec 0001 §6.3).
                val payload =
                    envelope.data?.takeIf { envelope.ok } ?: throw LeverException.InvalidResponse()
                if (payload.version < 0) throw LeverException.InvalidResponse()
                // A 200 with no ETag is accepted; later requests simply send no
                // validator for that representation.
                Outcome.Fresh(payload.version, payload.values, response.headers["ETag"])
            }

            // Nothing to confirm — the server answered a question we did not ask.
            304 -> if (sentValidator) Outcome.NotModified else throw LeverException.InvalidResponse()

            401 -> throw LeverException.InvalidKey()

            // 204 and the rest of the 2xx range included: the contract is
            // 200/304/401, and anything else is a server the SDK cannot read.
            else -> throw LeverException.Server(status)
        }
    }

    /**
     * The `error.code` a failure envelope carried, for the log line only —
     * never for control flow; the status code already carries the branch
     * (spec 0001 §5.1).
     */
    fun errorCode(response: HttpResponse): String? =
        try {
            leverJson.decodeFromString<Envelope>(response.body.decodeToString()).error?.code
        } catch (_: RuntimeException) {
            null
        }

    /**
     * The spec 0001 §5.1 envelope. `message` is decoded and ignored: it is
     * explicitly non-contractual, so nothing may branch on it.
     */
    @Serializable
    private data class Envelope(
        val ok: Boolean = false,
        val message: String? = null,
        val data: Payload? = null,
        val error: ErrorBody? = null,
    )

    @Serializable private data class ErrorBody(val code: String? = null)

    /**
     * The resolve payload, one level down from the envelope: `version` must be
     * a non-negative integer. Any shape violation is `InvalidResponse`, and the
     * caller changes nothing — decode failure is atomic (spec 0002 §6.1).
     */
    @Serializable
    private data class Payload(val version: Int, val values: Map<String, WireValue>)
}
