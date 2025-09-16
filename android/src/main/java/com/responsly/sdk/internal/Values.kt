package com.responsly.sdk.internal

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trait and event-property values. Inside the SDK a value is always a `String`, a `Double` or a
 * `Boolean`; dates are ISO 8601 UTC strings.
 */
internal object Values {
    /** Converts a value supplied by the host app. `null` stays `null` (it removes a trait). */
    fun normalize(value: Any?): Any? = when (value) {
        null -> null
        is String -> value
        is Boolean -> value
        is Number -> {
            val number = value.toDouble()
            // JSON cannot hold NaN or infinities, so they are kept as text.
            if (number.isNaN() || number.isInfinite()) value.toString() else number
        }
        is Date -> IsoTime.format(value.time)
        else -> value.toString()
    }

    /** Copies a host-supplied map; `null` values are kept so callers can remove traits. */
    fun normalize(values: Map<String, Any?>): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(values.size)
        for ((key, value) in values) result[key] = normalize(value)
        return result
    }

    /** Converts a JSON scalar; `null` for JSON null or a missing value. */
    fun fromJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is Boolean -> value
        is Number -> value.toDouble()
        else -> value.toString()
    }

    /** Reads a JSON object of scalars; JSON nulls are kept as `null`. */
    fun mapFromJson(json: JSONObject?): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        if (json == null) return result
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = fromJson(json.opt(key))
        }
        return result
    }

    fun toJson(values: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in values) {
            if (value != null) json.put(key, value)
        }
        return json
    }

    /**
     * Text of a value: a string as is, a boolean as `true` / `false`, a number without a fractional
     * part as an integer, otherwise its shortest decimal form without an exponent.
     */
    fun text(value: Any): String = when (value) {
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Number -> numberText(value.toDouble())
        else -> value.toString()
    }

    private fun numberText(number: Double): String {
        if (number.isNaN() || number.isInfinite()) return number.toString()
        if (number == Math.rint(number)) {
            return if (Math.abs(number) < 9.0e18) number.toLong().toString() else BigDecimal(number).toPlainString()
        }
        return BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
    }
}

/** Typed JSON accessors that return `null` (never a coerced value) for missing or wrong-typed fields. */
internal fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String

internal fun JSONObject.numberOrNull(key: String): Double? =
    (opt(key) as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }

internal fun JSONObject.booleanOrNull(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONObject.objectOrNull(key: String): JSONObject? = opt(key) as? JSONObject

internal fun JSONObject.arrayOrNull(key: String): JSONArray? = opt(key) as? JSONArray

internal fun JSONArray.objects(): List<JSONObject> {
    val result = ArrayList<JSONObject>(length())
    for (index in 0 until length()) (opt(index) as? JSONObject)?.let(result::add)
    return result
}

internal fun JSONArray.strings(): List<String> {
    val result = ArrayList<String>(length())
    for (index in 0 until length()) (opt(index) as? String)?.let(result::add)
    return result
}

/** Converts parsed JSON into plain Kotlin values for the public API. */
internal object JsonValues {
    fun toKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = toKotlin(value.opt(key))
            }
            map
        }
        is JSONArray -> {
            val list = ArrayList<Any?>(value.length())
            for (index in 0 until value.length()) list.add(toKotlin(value.opt(index)))
            list
        }
        is String, is Boolean -> value
        is Int, is Long, is Short, is Byte -> (value as Number).toLong()
        // Implementations of org.json box numbers differently; keep whole numbers as Long and
        // everything else as Double so callers see the same types everywhere.
        is BigInteger -> if (value.bitLength() < 64) value.toLong() else value.toDouble()
        is Number -> value.toDouble()
        else -> value.toString()
    }
}
