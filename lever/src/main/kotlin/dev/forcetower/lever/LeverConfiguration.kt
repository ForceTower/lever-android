package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogSink
import dev.forcetower.lever.logging.LogcatSink
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The targeting platform sent with every resolve. A string, not an enum, so the
 * server-side platform vocabulary can grow without an SDK release.
 */
@JvmInline
public value class LeverPlatform(public val rawValue: String) {
    public companion object {
        public val CURRENT: LeverPlatform = LeverPlatform("android")
    }
}

/**
 * Everything the server evaluates targeting rules against. Fixed at
 * construction in v1 — mutable, login-scoped attributes are spec 0003 §11's
 * open question.
 */
public class LeverContext(
    public val platform: LeverPlatform = LeverPlatform.CURRENT,
    public val appVersion: String? = null,
    attributes: Map<String, String> = emptyMap(),
) {
    /**
     * Snapshotted at construction: mutating the map handed in can never change
     * request context or bypass validation (spec 0003 §3).
     */
    public val attributes: Map<String, String> = attributes.toMap()
}

/**
 * @param baseUrl the origin lever is deployed at. Anything that cannot serve as
 *   an absolute `http(s)` origin — unparseable input, a non-`http(s)` scheme, a
 *   missing host, a relative URL, or embedded userinfo — is a programmer error
 *   and throws [IllegalArgumentException]. Every other validation repairs or
 *   omits and logs (spec 0003 §3).
 * @param clientKey `pk_…`. An identifier, not a secret — resolved values are
 *   readable by every end user, so config must never carry secrets.
 * @param minimumFetchInterval throttles the SDK's automatic paths, never an
 *   explicit [LeverClient.fetch]. Under DEBUG, set it to [Duration.ZERO] rather
 *   than reaching for a bypass.
 * @param automaticUpdates `false` makes the client a cache-only reader: no
 *   automatic fetch, timer, lifecycle observation, or stream. Explicit
 *   `fetch()` still works as a deliberate override.
 * @param autoActivateOnNudge whether a push nudge activates what it fetched.
 * @param cacheDirectory `null` resolves to `context.noBackupFilesDir`, which
 *   keeps Auto Backup from cloning the installation identity onto new devices.
 * @param cacheNamespace pins the snapshot file's identity to a name you control
 *   (e.g. `"prod"`), so a shipped client-key rotation still lands on the warm
 *   cache. `null` derives it from [clientKey], which a rotation orphans.
 */
public class LeverConfiguration(
    public val baseUrl: String,
    public val clientKey: String,
    public val context: LeverContext = LeverContext(),
    public val minimumFetchInterval: Duration = 12.hours,
    public val automaticUpdates: Boolean = true,
    public val autoActivateOnNudge: Boolean = true,
    public val cacheDirectory: File? = null,
    public val cacheNamespace: String? = null,
    public val logSink: LeverLogSink = LogcatSink(),
) {
    init {
        // The one validation that throws, and it throws here rather than at
        // client construction: an unusable origin is a compile-time-shaped
        // mistake, not a degraded runtime condition (spec 0003 §3).
        parseOrigin(baseUrl)
    }
}

/**
 * What [LeverClient.activate] publishes when the serving values actually
 * changed. Metadata-only commits are silent (spec 0002 §4).
 */
public data class LeverUpdate(
    public val version: Int,
    public val changedKeys: Set<String>,
)
