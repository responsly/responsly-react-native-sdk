package com.responsly.sdk.internal

/**
 * ISO 8601 timestamps without `java.time` (not available on API 23) and without
 * `SimpleDateFormat` (locale- and thread-sensitive).
 */
internal object IsoTime {
    private val PATTERN = Regex(
        "^([0-9]{4})-([0-9]{2})-([0-9]{2})" +
            "(?:[Tt ]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:[.,]([0-9]{1,9}))?)?" +
            "(Z|z|[+-][0-9]{2}(?::?[0-9]{2})?)?)?$",
    )

    /** Parses a timestamp to epoch milliseconds; a missing offset means UTC. Returns `null` when malformed. */
    fun parse(text: String?): Long? {
        val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
        val groups = match.groupValues
        val year = groups[1].toInt()
        val month = groups[2].toInt()
        val day = groups[3].toInt()
        val hour = groups[4].ifEmpty { "0" }.toInt()
        val minute = groups[5].ifEmpty { "0" }.toInt()
        val second = groups[6].ifEmpty { "0" }.toInt()
        if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        if (hour > 23 || minute > 59 || second > 60) return null
        val millis = groups[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toInt()
        val offsetMinutes = parseOffset(groups[8]) ?: return null
        val days = daysFromCivil(year, month, day)
        val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1_000L + millis
    }

    /** Formats epoch milliseconds as `yyyy-MM-ddTHH:mm:ssZ` (UTC, seconds precision). */
    fun format(epochMillis: Long): String {
        val totalSeconds = floorDiv(epochMillis, 1_000L)
        val days = floorDiv(totalSeconds, 86_400L)
        val secondOfDay = (totalSeconds - days * 86_400L).toInt()
        val (year, month, day) = civilFromDays(days)
        return buildString(20) {
            append(year.toString().padStart(4, '0'))
            append('-').append(two(month)).append('-').append(two(day))
            append('T').append(two(secondOfDay / 3_600))
            append(':').append(two(secondOfDay / 60 % 60))
            append(':').append(two(secondOfDay % 60))
            append('Z')
        }
    }

    // Math.floorDiv needs API 24.
    private fun floorDiv(a: Long, b: Long): Long {
        val quotient = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) quotient - 1 else quotient
    }

    private fun two(value: Int): String = if (value < 10) "0$value" else value.toString()

    private fun parseOffset(text: String): Int? {
        if (text.isEmpty() || text == "Z" || text == "z") return 0
        val sign = if (text[0] == '-') -1 else 1
        val digits = text.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toInt()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
        if (hours > 23 || minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }

    private fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    // Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = floorDiv(y, 400L)
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yearOfEra + era * 400 + (if (month <= 2) 1 else 0)).toInt()
        return Triple(year, month, day)
    }
}
