package com.responsly.sdk.internal.targeting

import com.responsly.sdk.internal.Values
import com.responsly.sdk.internal.config.Condition
import java.util.Locale

/** Condition semantics of the protocol (section 4.3). Values are String, Double, Boolean or null (absent). */
internal object Conditions {
    private val DECIMAL = Regex("^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$")

    fun matchesAll(conditions: List<Condition>, values: Map<String, Any?>): Boolean =
        conditions.all { evaluate(it, values) }

    fun matchesAny(conditions: List<Condition>, values: Map<String, Any?>): Boolean =
        conditions.any { evaluate(it, values) }

    fun evaluate(condition: Condition, values: Map<String, Any?>): Boolean =
        evaluate(condition.op, condition.name?.let(values::get), condition.value)

    fun evaluate(op: String, actual: Any?, expected: Any?): Boolean = when (op) {
        "exists" -> actual != null
        "not_exists" -> actual == null
        "eq" -> actual != null && equal(actual, expected)
        "neq" -> actual == null || !equal(actual, expected)
        "contains" -> actual != null && contains(actual, expected)
        "not_contains" -> actual == null || !contains(actual, expected)
        "gt" -> compare(actual, expected)?.let { it > 0 } ?: false
        "gte" -> compare(actual, expected)?.let { it >= 0 } ?: false
        "lt" -> compare(actual, expected)?.let { it < 0 } ?: false
        "lte" -> compare(actual, expected)?.let { it <= 0 } ?: false
        else -> false
    }

    private fun equal(actual: Any, expected: Any?): Boolean {
        if (expected == null) return false
        if (actual is Number && expected is Number) return actual.toDouble() == expected.toDouble()
        if (actual is Boolean && expected is Boolean) return actual == expected
        return lower(Values.text(actual)) == lower(Values.text(expected))
    }

    private fun contains(actual: Any, expected: Any?): Boolean {
        if (expected == null) return false
        return lower(Values.text(actual)).contains(lower(Values.text(expected)))
    }

    /** Sign of `actual - expected`, or `null` when the two sides cannot be ordered. */
    private fun compare(actual: Any?, expected: Any?): Int? {
        if (actual == null || expected == null) return null
        val left = numeric(actual)
        val right = numeric(expected)
        if (left != null && right != null) return left.compareTo(right)
        if (actual is String && expected is String) return compareOrdinal(actual, expected)
        return null
    }

    private fun numeric(value: Any): Double? = when (value) {
        is Number -> value.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> parseDecimal(value)
        else -> null
    }

    /** A finite decimal in plain or exponent notation; nothing the strict pattern rejects. */
    fun parseDecimal(text: String): Double? {
        if (!DECIMAL.matches(text)) return null
        val number = text.toDoubleOrNull() ?: return null
        return number.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    /** Ordinal comparison by Unicode code point (the order of the UTF-8 encodings). */
    private fun compareOrdinal(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left.codePointAt(i)
            val b = right.codePointAt(j)
            if (a != b) return if (a < b) -1 else 1
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        return (left.length - i).compareTo(right.length - j)
    }

    private fun lower(text: String): String = text.lowercase(Locale.ROOT)
}
