package dev.forcetower.lever

import java.math.BigInteger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

/**
 * A typed config key: the wire name plus the default served whenever the live
 * value is absent or does not fit (spec 0003 §2.3).
 *
 * Keys are declared once, as constants, so a default cannot drift between call
 * sites:
 *
 * ```kotlin
 * object Flags {
 *     val enableEnrollment = LeverKey.boolean("enable_enrollment", default = false)
 *     val paywall = LeverKey.json("paywall", default = PaywallConfig.Standard)
 * }
 *
 * if (lever[Flags.enableEnrollment]) { … }
 * ```
 *
 * `V` is unconstrained; the factories below install the decoder, so there is no
 * way to build a key the SDK cannot read.
 */
public class LeverKey<V> internal constructor(
    public val name: String,
    public val defaultValue: V,
    /**
     * The log-dedupe identity and the `as=` tail of a mismatch warning. Two keys
     * may share a wire name with different Kotlin types (spec 0002 §2.3).
     *
     * This is deliberately *not* the memo identity: a serializer's `serialName`
     * is not a type identity — `List<String>` and `List<Int>` are both
     * `kotlin.collections.ArrayList`, and two custom serializers may share a
     * descriptor name on purpose. Colliding here costs a merged log line;
     * colliding in the memo would serve one key's decoded object to another and
     * throw `ClassCastException` at the call site.
     */
    internal val typeId: String,
    /** Only `json` keys memoize — the others are a branch, not a deserializer. */
    internal val memoizes: Boolean,
    internal val decode: (WireValue) -> V?,
) {
    public companion object {
        /** A `boolean` parameter. */
        public fun boolean(name: String, default: Boolean): LeverKey<Boolean> =
            LeverKey(name, default, typeId = "boolean", memoizes = false) { raw ->
                if (raw.type != WireType.BOOLEAN) return@LeverKey null
                val primitive = raw.value as? JsonPrimitive ?: return@LeverKey null
                if (primitive.isString || primitive is JsonNull) return@LeverKey null
                primitive.content.toBooleanStrictOrNull()
            }

        /** A `string` parameter. */
        public fun string(name: String, default: String): LeverKey<String> =
            LeverKey(name, default, typeId = "string", memoizes = false) { raw ->
                if (raw.type != WireType.STRING) return@LeverKey null
                val primitive = raw.value as? JsonPrimitive ?: return@LeverKey null
                if (!primitive.isString) return@LeverKey null
                primitive.content
            }

        /**
         * A `number` reaches an `Int` key only when it is exactly representable:
         * a fractional part or a magnitude outside `Int` is a mismatch
         * (spec 0002 §2.3).
         */
        public fun int(name: String, default: Int): LeverKey<Int> =
            LeverKey(name, default, typeId = "int", memoizes = false) { raw ->
                integral(raw)?.takeIf { it >= INT_MIN && it <= INT_MAX }?.toInt()
            }

        /**
         * The same rule as [int], over the wider Kotlin range. The service
         * cannot preserve integers beyond the JavaScript safe range
         * (|n| ≤ 2⁵³ − 1), so a larger value may arrive already rounded — the
         * decoder is faithful to the lexeme it is given either way
         * (spec 0003 §2.2).
         */
        public fun long(name: String, default: Long): LeverKey<Long> =
            LeverKey(name, default, typeId = "long", memoizes = false) { raw ->
                integral(raw)?.takeIf { it >= LONG_MIN && it <= LONG_MAX }?.toLong()
            }

        /** A `number` parameter, read as a `Double`; any JSON number fits. */
        public fun double(name: String, default: Double): LeverKey<Double> =
            LeverKey(name, default, typeId = "double", memoizes = false) { raw ->
                numberLexeme(raw)?.toDoubleOrNull()
            }

        /**
         * A `json` parameter decoded with an explicit serializer — the overload
         * for types whose serializer cannot be reified (generic types,
         * hand-written serializers).
         *
         * The SDK's `Json` instance is fixed: a passed serializer carries its
         * own decoding logic, but there is no way to install a
         * `SerializersModule`, so contextual and polymorphic setups must be
         * self-contained in the serializer.
         *
         * Prefer immutable model types: "stable between activations" is a
         * promise about the SDK's storage, not about aliasing a shared instance.
         */
        public fun <T> json(name: String, default: T, serializer: KSerializer<T>): LeverKey<T> =
            LeverKey(
                name,
                default,
                typeId = "json:" + serializer.descriptor.serialName,
                memoizes = true,
            ) { raw ->
                if (raw.type != WireType.JSON) return@LeverKey null
                try {
                    leverJson.decodeFromJsonElement(serializer, raw.value)
                } catch (_: RuntimeException) {
                    null
                }
            }

        /**
         * A `json` parameter whose serializer is resolved at the declaration
         * site. The declaring module needs kotlinx.serialization's compiler
         * plugin, and `T` must be `@Serializable`.
         */
        public inline fun <reified T> json(name: String, default: T): LeverKey<T> =
            json(name, default, serializer())

        private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

        private fun numberLexeme(raw: WireValue): String? {
            if (raw.type != WireType.NUMBER) return null
            val primitive = raw.value as? JsonPrimitive ?: return null
            if (primitive.isString || primitive is JsonNull) return null
            return primitive.content
        }

        /**
         * The wire lexeme, not a `Double` round trip: `9007199254740993` is an
         * exact `Long` and must decode as one, which routing through binary
         * floating point would silently corrupt.
         */
        private fun integral(raw: WireValue): BigInteger? {
            val lexeme = numberLexeme(raw) ?: return null
            lexeme.toBigIntegerOrNull()?.let { return it }
            val decimal = lexeme.toBigDecimalOrNull() ?: return null
            return try {
                decimal.toBigIntegerExact()
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}

/**
 * Why a read resolved the way it did — the caller turns this into the right
 * deduped log line and, for `json`, into a memo entry (spec 0002 §2.3).
 */
internal sealed interface ReadOutcome<out V> {
    data class Resolved<V>(val value: V) : ReadOutcome<V>

    /** Not published, or a first run with no cache. Normal mid-rollout. */
    data object Absent : ReadOutcome<Nothing>

    /**
     * Present but unreadable as `V`: wrong wire type, a `number` that is not
     * exactly representable, or a `json` payload that failed to decode.
     */
    data object Mismatch : ReadOutcome<Nothing>
}

/** Spec 0002 §2.3, as a pure function over a raw payload. Never throws. */
internal fun <V> resolveRead(key: LeverKey<V>, values: Map<String, WireValue>): ReadOutcome<V> {
    val raw = values[key.name] ?: return ReadOutcome.Absent
    val decoded = key.decode(raw) ?: return ReadOutcome.Mismatch
    return ReadOutcome.Resolved(decoded)
}
