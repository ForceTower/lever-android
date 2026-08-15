package dev.forcetower.lever

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One entry of the resolve payload's `values` map: `{"type": …, "value": …}`.
 *
 * `type` stays a `String` rather than an enum so a server that learns a new
 * parameter type degrades to a read-time mismatch — the floor — instead of
 * failing the whole decode and costing every other key its freshness.
 */
@Serializable
internal data class WireValue(val type: String, val value: JsonElement)

internal object WireType {
    const val BOOLEAN = "boolean"
    const val STRING = "string"
    const val NUMBER = "number"
    const val JSON = "json"
}

/**
 * `3` and `3.0` are the same JSON number, so they must compare equal:
 * `changedKeys` is a diff over wire values, not over serializer representations
 * (spec 0002 §4).
 */
internal fun jsonEquals(left: JsonElement, right: JsonElement): Boolean =
    when {
        left is JsonNull || right is JsonNull -> left is JsonNull && right is JsonNull
        left is JsonPrimitive && right is JsonPrimitive -> primitivesEqual(left, right)
        left is JsonArray && right is JsonArray ->
            left.size == right.size && left.indices.all { jsonEquals(left[it], right[it]) }
        left is JsonObject && right is JsonObject ->
            left.keys == right.keys &&
                left.all { (key, value) -> jsonEquals(value, right.getValue(key)) }
        else -> false
    }

private fun primitivesEqual(left: JsonPrimitive, right: JsonPrimitive): Boolean {
    if (left.isString != right.isString) return false
    if (left.isString) return left.content == right.content
    val leftNumber = left.content.toBigDecimalOrNull()
    val rightNumber = right.content.toBigDecimalOrNull()
    if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber) == 0
    return left.content == right.content
}

internal fun wireValuesEqual(left: WireValue, right: WireValue): Boolean =
    left.type == right.type && jsonEquals(left.value, right.value)

internal fun valuesEqual(
    left: Map<String, WireValue>,
    right: Map<String, WireValue>,
): Boolean =
    left.keys == right.keys && left.all { (key, value) -> wireValuesEqual(value, right.getValue(key)) }

/**
 * The raw diff [LeverUpdate.changedKeys] reports: added, removed, and changed,
 * compared over wire values rather than decoded ones (spec 0002 §4).
 */
internal fun changedKeys(
    before: Map<String, WireValue>,
    after: Map<String, WireValue>,
): Set<String> {
    val changed = mutableSetOf<String>()
    for ((key, value) in after) {
        val previous = before[key]
        if (previous == null || !wireValuesEqual(previous, value)) changed.add(key)
    }
    for (key in before.keys) if (key !in after) changed.add(key)
    return changed
}
