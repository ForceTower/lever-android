package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogSink
import dev.forcetower.lever.logging.error
import dev.forcetower.lever.logging.info
import dev.forcetower.lever.logging.warn
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json

/** The SDK's fixed codec: one instance, no injectable `SerializersModule`. */
internal val leverJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The configuration the rest of the SDK runs on: repaired, canonical, and
 * already reduced to what goes on the wire.
 *
 * Validation happens once, at client construction, and never throws (the one
 * throwing case is [LeverConfiguration]'s base-URL check). The policy is
 * uniform (spec 0002 §3): anything the server would answer with a 400 is
 * repaired or omitted here, because a 400 costs *every* key its freshness —
 * omission is the floor-preserving choice.
 */
internal class ValidatedConfiguration(
    val baseUrl: String,
    val clientKey: String,
    val platform: String?,
    val appVersion: String?,
    /** Already ordered the way the query string must be built. */
    val attributes: List<Attribute>,
    val minimumFetchInterval: Duration,
    val automaticUpdates: Boolean,
    val autoActivateOnNudge: Boolean,
    val cacheDirectory: File,
    val cacheKeyHash: String,
    val logSink: LeverLogSink,
)

internal data class Attribute(val name: String, val value: String)

/**
 * All length checks count **UTF-16 code units** — the server measures
 * JavaScript string length, and Kotlin's `String.length` is already that unit,
 * so no translation layer is needed (research 0003 §4.3).
 */
private const val MAX_RESERVED_LENGTH = 64
private const val MAX_ATTRIBUTE_NAME_LENGTH = 64
private const val MAX_ATTRIBUTE_VALUE_LENGTH = 256
private const val MAX_ATTRIBUTES = 20
private val MAXIMUM_FETCH_INTERVAL = 365.days

/** The official semver.org grammar, the same one the server validates with. */
private val STRICT_SEMVER =
    Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)" +
            "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
    )

/** An absolute `http(s)` origin, split into the parts canonicalization needs. */
internal class Origin(
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val hasQueryOrFragment: Boolean,
) {
    /**
     * Scheme and host lowercased, a default port dropped, trailing slashes
     * stripped. This canonical form is what requests are built from *and* what
     * the cache identity hashes, so the two can never disagree
     * (spec 0002 §3, §7).
     */
    val canonical: String
        get() = buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != null) {
                append(':')
                append(port)
            }
            append(path)
        }
}

/**
 * The throwing boundary: "not an absolute http(s) origin" is a programmer
 * error, the Kotlin analogue of Swift's compile-time `URL` type
 * (spec 0003 §3).
 */
internal fun parseOrigin(baseUrl: String): Origin {
    val uri =
        try {
            URI(baseUrl)
        } catch (cause: URISyntaxException) {
            throw IllegalArgumentException("lever baseUrl is not a valid url: $baseUrl", cause)
        }

    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") {
        "lever baseUrl must be an absolute http(s) url: $baseUrl"
    }

    val authority =
        requireNotNull(uri.rawAuthority) { "lever baseUrl must include a host: $baseUrl" }
    // Credentials beside an Authorization header are rejected, not stripped.
    require(!authority.contains('@')) {
        "lever baseUrl must not carry userinfo: $baseUrl"
    }

    val hostPart: String
    val portPart: String?
    if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        require(end > 0) { "lever baseUrl has a malformed host: $baseUrl" }
        hostPart = authority.substring(0, end + 1)
        portPart = authority.substring(end + 1).removePrefix(":").ifEmpty { null }
    } else {
        val colon = authority.lastIndexOf(':')
        hostPart = if (colon >= 0) authority.substring(0, colon) else authority
        portPart = if (colon >= 0) authority.substring(colon + 1).ifEmpty { null } else null
    }
    require(hostPart.isNotEmpty()) { "lever baseUrl must include a host: $baseUrl" }

    val port = portPart?.toIntOrNull()
    require(portPart == null || (port != null && port in 1..65535)) {
        "lever baseUrl has an invalid port: $baseUrl"
    }

    val defaultPort = if (scheme == "http") 80 else 443
    return Origin(
        scheme = scheme,
        host = hostPart.lowercase(),
        port = port?.takeIf { it != defaultPort },
        path = (uri.rawPath ?: "").trimEnd('/'),
        hasQueryOrFragment = uri.rawQuery != null || uri.rawFragment != null,
    )
}

internal fun validate(
    configuration: LeverConfiguration,
    defaultCacheDirectory: () -> File,
): ValidatedConfiguration {
    val sink = configuration.logSink
    val origin = parseOrigin(configuration.baseUrl)
    if (origin.hasQueryOrFragment) {
        // The SDK owns the path and query space under the base.
        sink.warn("base url query and fragment are ignored url=${configuration.baseUrl}")
    }

    if (!configuration.clientKey.startsWith("pk_")) {
        sink.warn("client key does not look like a pk_ key — the server is the authority")
    }

    val namespace = configuration.cacheNamespace ?: configuration.clientKey
    return ValidatedConfiguration(
        baseUrl = origin.canonical,
        clientKey = configuration.clientKey,
        platform = validatePlatform(configuration.context.platform, sink),
        appVersion = validateAppVersion(configuration.context.appVersion, sink),
        attributes = validateAttributes(configuration.context.attributes, sink),
        minimumFetchInterval = validateInterval(configuration.minimumFetchInterval, sink),
        automaticUpdates = configuration.automaticUpdates,
        autoActivateOnNudge = configuration.autoActivateOnNudge,
        cacheDirectory = File(configuration.cacheDirectory ?: defaultCacheDirectory(), "lever"),
        cacheKeyHash = cacheKeyHash(origin.canonical, namespace),
        logSink = sink,
    )
}

/**
 * An absent platform means platform clauses never match — degraded targeting,
 * which beats a 400 that costs every key its freshness (spec 0002 §3).
 */
private fun validatePlatform(platform: LeverPlatform, sink: LeverLogSink): String? {
    if (platform.rawValue.length > MAX_RESERVED_LENGTH) {
        sink.warn(
            "platform omitted, over $MAX_RESERVED_LENGTH utf-16 units — " +
                "platform clauses will not match"
        )
        return null
    }
    return platform.rawValue
}

private fun validateAppVersion(appVersion: String?, sink: LeverLogSink): String? {
    if (appVersion == null) return null

    if (appVersion.length > MAX_RESERVED_LENGTH) {
        sink.error(
            "appVersion omitted, over $MAX_RESERVED_LENGTH utf-16 units — " +
                "version clauses will not match"
        )
        return null
    }
    if (STRICT_SEMVER.matches(appVersion)) return appVersion

    // Marketing versions are the common case and the intent is unambiguous.
    val padded = zeroPadded(appVersion)
    if (padded != null) {
        sink.info("appVersion normalized from=$appVersion to=$padded")
        return padded
    }

    sink.error(
        "appVersion is not semver and no version clause will ever match it " +
            "appVersion=$appVersion"
    )
    return appVersion
}

/** `"5"` → `"5.0.0"`, `"5.2"` → `"5.2.0"`; `null` for anything else. */
private fun zeroPadded(version: String): String? {
    val parts = version.split(".")
    if (parts.size !in 1..2) return null
    for (part in parts) {
        if (part.isEmpty() || !part.all { it in '0'..'9' }) return null
        if (part != "0" && part.startsWith("0")) return null
    }
    return if (parts.size == 1) "${parts[0]}.0.0" else "${parts[0]}.${parts[1]}.0"
}

/**
 * Wire-limit violations are dropped individually, and the survivors are capped
 * deterministically: map iteration order must never decide which targeting
 * inputs reach the server (spec 0002 §3).
 */
private fun validateAttributes(
    attributes: Map<String, String>,
    sink: LeverLogSink,
): List<Attribute> {
    val valid = mutableListOf<Attribute>()
    val dropped = mutableListOf<String>()

    for (name in attributes.keys.sortedWith(UTF8_ORDER)) {
        val value = attributes.getValue(name)
        if (name.length !in 1..MAX_ATTRIBUTE_NAME_LENGTH ||
            value.length > MAX_ATTRIBUTE_VALUE_LENGTH
        ) {
            dropped.add(name)
            continue
        }
        valid.add(Attribute(name, value))
    }
    if (dropped.isNotEmpty()) {
        sink.warn("attributes dropped, outside the wire limits names=${dropped.joinToString(",")}")
    }

    if (valid.size <= MAX_ATTRIBUTES) return valid
    val overflow = valid.drop(MAX_ATTRIBUTES).joinToString(",") { it.name }
    sink.warn("attributes dropped, over $MAX_ATTRIBUTES names=$overflow")
    return valid.take(MAX_ATTRIBUTES)
}

/**
 * Ascending UTF-8 byte order — the order the contract fixtures pin for query
 * items, and what the twenty-attribute selection sorts by. Kotlin's natural
 * `String` order is UTF-16 code-unit order, which disagrees above the BMP.
 */
internal val UTF8_ORDER: Comparator<String> = Comparator { left, right ->
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    var index = 0
    while (index < a.size && index < b.size) {
        val difference = (a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF)
        if (difference != 0) return@Comparator difference
        index++
    }
    a.size - b.size
}

private fun validateInterval(interval: Duration, sink: LeverLogSink): Duration {
    if (interval < Duration.ZERO) {
        // A negative interval would make every deadline permanently overdue,
        // which the hot-loop guard depends on not happening (spec 0002 §5.1).
        sink.warn("minimumFetchInterval clamped to zero from a negative value")
        return Duration.ZERO
    }
    // A year is already absurd for a config refresh, and past it the deadline
    // arithmetic stops fitting in the Unix seconds it is added to.
    if (interval > MAXIMUM_FETCH_INTERVAL) {
        sink.warn("minimumFetchInterval clamped to 365 days from a larger value")
        return MAXIMUM_FETCH_INTERVAL
    }
    if (interval > Duration.ZERO && interval < 60.seconds) {
        sink.info(
            "minimumFetchInterval is under the 60s polling floor — the in-session timer " +
                "runs at 60s, lifecycle edges keep the configured value"
        )
    }
    return interval
}

/**
 * The snapshot file's name: the first 16 hex chars of SHA-256 over the
 * canonical base URL and the cache namespace (spec 0002 §7).
 */
internal fun cacheKeyHash(canonicalBaseUrl: String, namespace: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$canonicalBaseUrl\n$namespace".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("", limit = 8, truncated = "") { "%02x".format(it) }
}
