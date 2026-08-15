package dev.forcetower.lever

import dev.forcetower.lever.logging.LeverLogLevel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable

@Serializable internal data class Paywall(val headline: String, val cta: String)

/** The read-resolution and validation matrices (plan 0003 M2). */
internal class ModelTests {
    private val sink = RecordingLogSink()

    private fun <V> read(key: LeverKey<V>, vararg values: Pair<String, WireValue>): ReadOutcome<V> =
        resolveRead(key, values.toMap())

    private fun <V> resolved(key: LeverKey<V>, vararg values: Pair<String, WireValue>): V? =
        (read(key, *values) as? ReadOutcome.Resolved)?.value

    // MARK: the decode matrix

    @Test
    fun `boolean decodes and rejects everything else`() {
        val key = LeverKey.boolean("flag", default = false)
        assertEquals(true, resolved(key, "flag" to wireBool(true)))
        assertEquals(ReadOutcome.Absent, read(key))
        assertEquals(ReadOutcome.Mismatch, read(key, "flag" to wireString("true")))
        assertEquals(ReadOutcome.Mismatch, read(key, "flag" to wireNumber("1")))
        // The right wire type carrying the wrong JSON is still a mismatch.
        assertEquals(ReadOutcome.Mismatch, read(key, "flag" to wireValue("boolean", "\"true\"")))
    }

    @Test
    fun `string decodes and rejects everything else`() {
        val key = LeverKey.string("greeting", default = "none")
        assertEquals("olá", resolved(key, "greeting" to wireString("olá")))
        assertEquals(ReadOutcome.Absent, read(key))
        assertEquals(ReadOutcome.Mismatch, read(key, "greeting" to wireBool(true)))
        assertEquals(ReadOutcome.Mismatch, read(key, "greeting" to wireValue("string", "3")))
    }

    @Test
    fun `int takes exactly representable integers only`() {
        val key = LeverKey.int("retries", default = 1)
        assertEquals(3, resolved(key, "retries" to wireNumber("3")))
        // A fractional part is a mismatch; a zero fraction is not.
        assertEquals(3, resolved(key, "retries" to wireNumber("3.0")))
        assertEquals(ReadOutcome.Mismatch, read(key, "retries" to wireNumber("3.5")))
        assertEquals(Int.MAX_VALUE, resolved(key, "retries" to wireNumber("2147483647")))
        assertEquals(ReadOutcome.Mismatch, read(key, "retries" to wireNumber("2147483648")))
        assertEquals(Int.MIN_VALUE, resolved(key, "retries" to wireNumber("-2147483648")))
        assertEquals(ReadOutcome.Mismatch, read(key, "retries" to wireNumber("-2147483649")))
        assertEquals(ReadOutcome.Mismatch, read(key, "retries" to wireString("3")))
    }

    @Test
    fun `long widens the range and keeps the exactness rule`() {
        val key = LeverKey.long("big", default = 0L)
        assertEquals(3L, resolved(key, "big" to wireNumber("3")))
        assertEquals(Long.MAX_VALUE, resolved(key, "big" to wireNumber("9223372036854775807")))
        assertEquals(
            ReadOutcome.Mismatch,
            read(key, "big" to wireNumber("9223372036854775808")),
        )
        assertEquals(Long.MIN_VALUE, resolved(key, "big" to wireNumber("-9223372036854775808")))
        assertEquals(ReadOutcome.Mismatch, read(key, "big" to wireNumber("1.5")))
    }

    /**
     * The read rule is the shared one: any integral wire value exactly
     * representable in the requested type decodes. The JavaScript safe-integer
     * bound is authoring guidance, not decoder behavior, so there is no
     * Android-only safe-range mismatch (spec 0003 §2.2).
     */
    @Test
    fun `integer lexemes at the javascript safe-integer boundary decode exactly`() {
        val key = LeverKey.long("big", default = 0L)
        assertEquals(9_007_199_254_740_991L, resolved(key, "big" to wireNumber("9007199254740991")))
        assertEquals(9_007_199_254_740_992L, resolved(key, "big" to wireNumber("9007199254740992")))
        // The one a `Double` round trip would silently corrupt into …992.
        assertEquals(9_007_199_254_740_993L, resolved(key, "big" to wireNumber("9007199254740993")))
    }

    @Test
    fun `double takes any number`() {
        val key = LeverKey.double("ratio", default = 1.5)
        assertEquals(3.0, resolved(key, "ratio" to wireNumber("3")))
        assertEquals(0.25, resolved(key, "ratio" to wireNumber("0.25")))
        assertEquals(ReadOutcome.Mismatch, read(key, "ratio" to wireString("0.25")))
        assertEquals(ReadOutcome.Absent, read(key))
    }

    @Test
    fun `json decodes through both factories and fails to the default on a bad payload`() {
        val fallback = Paywall("fallback", "none")
        val reified = LeverKey.json("paywall", default = fallback)
        val explicit = LeverKey.json("paywall", fallback, Paywall.serializer())
        val good = wireValue("json", """{"headline":"Go Pro","cta":"Start trial"}""")
        val bad = wireValue("json", """{"headline":"Go Pro"}""")

        for (key in listOf(reified, explicit)) {
            assertEquals(Paywall("Go Pro", "Start trial"), resolved(key, "paywall" to good))
            assertEquals(ReadOutcome.Mismatch, read(key, "paywall" to bad))
            assertEquals(ReadOutcome.Mismatch, read(key, "paywall" to wireString("{}")))
            assertEquals(ReadOutcome.Absent, read(key))
        }
        // Two keys over one wire name must not share a memo identity.
        assertTrue(reified.typeId == explicit.typeId)
        assertTrue(reified.typeId != LeverKey.string("paywall", "x").typeId)
    }

    // MARK: base url

    @Test
    fun `base url rejects everything that is not an absolute http(s) origin`() {
        for (input in
            listOf(
                "ftp://lever.example",
                "lever.example/config",
                "//lever.example",
                "https://user:pass@lever.example",
                "https://",
                "not a url",
                "https://lever.example:notaport",
            )
        ) {
            assertFailsWith<IllegalArgumentException>(input) {
                LeverConfiguration(baseUrl = input, clientKey = "pk_test")
            }
        }
    }

    @Test
    fun `base url is canonicalized`() {
        assertEquals("https://lever.example", canonical("https://LEVER.example"))
        assertEquals("https://lever.example", canonical("https://lever.example:443"))
        assertEquals("http://lever.example", canonical("http://lever.example:80"))
        assertEquals("https://lever.example:8443", canonical("https://lever.example:8443"))
        assertEquals("https://lever.example", canonical("https://lever.example/"))
        assertEquals("https://lever.example", canonical("https://lever.example///"))
        assertEquals("https://lever.example/config", canonical("https://lever.example/config/"))
        assertEquals("https://lever.example/a/b", canonical("https://lever.example/a/b?x=1#f"))
    }

    @Test
    fun `base url query and fragment are stripped with a warning`() {
        canonical("https://lever.example?x=1")
        assertTrue(sink.contains(LeverLogLevel.WARN, "base url query and fragment are ignored"))
    }

    // MARK: reserved fields

    @Test
    fun `app version is padded, passed through, or omitted`() {
        assertEquals("5.2.1", validated(appVersion = "5.2.1").appVersion)
        assertEquals("5.2.1-beta.1+build", validated(appVersion = "5.2.1-beta.1+build").appVersion)

        assertEquals("5.0.0", validated(appVersion = "5").appVersion)
        assertEquals("5.2.0", validated(appVersion = "5.2").appVersion)
        assertTrue(sink.contains(LeverLogLevel.INFO, "appVersion normalized from=5.2 to=5.2.0"))

        // In-limit garbage travels verbatim with an error; no clause can match it.
        assertEquals("v5.2", validated(appVersion = "v5.2").appVersion)
        assertTrue(sink.contains(LeverLogLevel.ERROR, "appVersion is not semver"))

        // Leading zeros are not semver and are not a marketing version either.
        assertEquals("05.2", validated(appVersion = "05.2").appVersion)
    }

    @Test
    fun `overlong reserved fields are omitted, counted in utf-16 units`() {
        // 64 code units of astral characters is 32 characters — the server counts
        // the same way (spec 0003 §3).
        val astral = "😀".repeat(32)
        assertEquals(64, astral.length)
        assertEquals(astral, validated(appVersion = astral).appVersion)
        assertNull(validated(appVersion = astral + "x").appVersion)
        assertTrue(sink.contains(LeverLogLevel.ERROR, "appVersion omitted"))

        assertEquals(astral, validated(platform = LeverPlatform(astral)).platform)
        assertNull(validated(platform = LeverPlatform(astral + "x")).platform)
        assertTrue(sink.contains(LeverLogLevel.WARN, "platform omitted"))
    }

    // MARK: attributes

    @Test
    fun `attributes outside the wire limits are dropped`() {
        val attributes =
            mapOf(
                "ok" to "value",
                "" to "empty name",
                "n".repeat(65) to "long name",
                "long-value" to "v".repeat(257),
                "edge-name" to "v".repeat(256),
            )
        val result = validated(attributes = attributes)
        assertEquals(listOf("edge-name", "ok"), result.attributes.map { it.name })
        assertTrue(sink.contains(LeverLogLevel.WARN, "attributes dropped, outside the wire limits"))
    }

    @Test
    fun `twenty-one valid attributes select the same twenty under any insertion order`() {
        val names = (1..21).map { "attr-%02d".format(it) }
        val ascending = linkedMapOf<String, String>().apply { names.forEach { put(it, "v") } }
        val descending =
            linkedMapOf<String, String>().apply { names.reversed().forEach { put(it, "v") } }

        val first = validated(attributes = ascending).attributes.map { it.name }
        val second = validated(attributes = descending).attributes.map { it.name }
        assertEquals(first, second)
        assertEquals(names.take(20), first)
        assertTrue(sink.contains(LeverLogLevel.WARN, "attributes dropped, over 20 names=attr-21"))
    }

    @Test
    fun `attributes are ordered by utf-8 bytes, not utf-16 code units`() {
        // U+FF5E sorts above U+1F600 in UTF-16 order (surrogates are 0xD83D…)
        // and below it in UTF-8 byte order, which is the order the wire pins.
        val names = listOf("～", "😀")
        assertTrue(names[1] < names[0], "utf-16 order disagrees, which is the point")
        assertEquals(names, names.sortedWith(UTF8_ORDER))
    }

    @Test
    fun `mutating the caller's map after construction changes nothing`() {
        val attributes = mutableMapOf("cohort" to "beta")
        val configuration =
            LeverConfiguration(
                baseUrl = "https://lever.example",
                clientKey = "pk_test",
                context = LeverContext(attributes = attributes),
                logSink = sink,
            )
        attributes["cohort"] = "control"
        attributes["injected"] = "yes"

        val result = validate(configuration) { File("/tmp") }
        assertEquals(listOf(Attribute("cohort", "beta")), result.attributes)
    }

    // MARK: interval

    @Test
    fun `the fetch interval clamps at both ends`() {
        assertEquals(Duration.ZERO, validated(interval = (-1).seconds).minimumFetchInterval)
        assertTrue(sink.contains(LeverLogLevel.WARN, "clamped to zero"))

        assertEquals(Duration.ZERO, validated(interval = Duration.ZERO).minimumFetchInterval)
        assertEquals(30.seconds, validated(interval = 30.seconds).minimumFetchInterval)
        assertTrue(sink.contains(LeverLogLevel.INFO, "under the 60s polling floor"))

        assertEquals(60.seconds, validated(interval = 60.seconds).minimumFetchInterval)
        assertEquals(365.days, validated(interval = 366.days).minimumFetchInterval)
        assertEquals(365.days, validated(interval = Duration.INFINITE).minimumFetchInterval)
        assertEquals(2, sink.count(LeverLogLevel.WARN, "clamped to 365 days"))

        // Saturating, never overflowing: the clamped value still fits the Unix
        // seconds it is added to (spec 0002 §12.1).
        assertEquals(31_536_000L, 365.days.inWholeSeconds)
    }

    @Test
    fun `a client key without the pk_ shape warns but is still sent`() {
        assertEquals("sk_oops", validated(clientKey = "sk_oops").clientKey)
        assertTrue(sink.contains(LeverLogLevel.WARN, "does not look like a pk_ key"))
    }

    @Test
    fun `the cache identity hashes the canonical url and the namespace`() {
        val key = validated(clientKey = "pk_a").cacheKeyHash
        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })

        // A rotation orphans the default hash and leaves a namespaced one alone.
        assertTrue(key != validated(clientKey = "pk_b").cacheKeyHash)
        assertEquals(
            validated(clientKey = "pk_a", namespace = "prod").cacheKeyHash,
            validated(clientKey = "pk_b", namespace = "prod").cacheKeyHash,
        )
        // Two spellings of one origin share the snapshot file.
        assertEquals(
            validated(baseUrl = "https://LEVER.example:443/", clientKey = "pk_a").cacheKeyHash,
            key,
        )
    }

    @Test
    fun `the cache directory is a lever subdirectory of the resolved root`() {
        val root = File("/tmp/lever-root")
        val result = validate(LeverConfiguration("https://lever.example", "pk_test", logSink = sink)) { root }
        assertEquals(File(root, "lever"), result.cacheDirectory)

        val explicit =
            validate(
                LeverConfiguration(
                    "https://lever.example",
                    "pk_test",
                    cacheDirectory = File("/tmp/explicit"),
                    logSink = sink,
                )
            ) { root }
        assertEquals(File("/tmp/explicit/lever"), explicit.cacheDirectory)
    }

    @Test
    fun `a valid configuration logs nothing`() {
        validated()
        assertTrue(sink.all.isEmpty(), "unexpected logs: ${sink.all}")
        assertFalse(sink.contains(LeverLogLevel.WARN, ""))
        assertNotNull(validated().platform)
    }

    private fun canonical(baseUrl: String): String = validated(baseUrl = baseUrl).baseUrl

    private fun validated(
        baseUrl: String = "https://lever.example",
        clientKey: String = "pk_test",
        platform: LeverPlatform = LeverPlatform("android"),
        appVersion: String? = null,
        attributes: Map<String, String> = emptyMap(),
        interval: Duration = 12.hours,
        namespace: String? = null,
    ): ValidatedConfiguration =
        validate(
            LeverConfiguration(
                baseUrl = baseUrl,
                clientKey = clientKey,
                context = LeverContext(platform, appVersion, attributes),
                minimumFetchInterval = interval,
                cacheNamespace = namespace,
                logSink = sink,
            )
        ) {
            File("/tmp/lever-tests")
        }
}
