package com.responsly.sdk.internal.bridge

/** CSS hex colours (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`) as ARGB integers. */
internal object CssColor {
    fun parse(text: String?): Int? {
        val hex = text?.trim()?.takeIf { it.startsWith("#") }?.substring(1) ?: return null
        if (hex.any { Character.digit(it, 16) < 0 || it.code > 0x7f }) return null
        val expanded = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            6, 8 -> hex
            else -> return null
        }
        val rgb = expanded.substring(0, 6).toInt(16)
        val alpha = if (expanded.length == 8) expanded.substring(6, 8).toInt(16) else 0xff
        return (alpha shl 24) or rgb
    }

    /** Whether dark content reads better on this colour (relative luminance, WCAG). */
    fun isLight(argb: Int): Boolean {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel(argb shr 16 and 0xff) +
            0.7152 * channel(argb shr 8 and 0xff) +
            0.0722 * channel(argb and 0xff)
        return luminance > 0.4
    }
}
