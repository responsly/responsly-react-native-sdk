package com.responsly.sdk.internal

/** FNV-1a, 32 bit, over UTF-8 bytes. */
internal object Fnv1a {
    private const val OFFSET_BASIS = 0x811c9dc5.toInt()
    private const val PRIME = 0x01000193

    /** The unsigned 32-bit hash, as a `Long`. */
    fun hash32(text: String): Long {
        var hash = OFFSET_BASIS
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xff)
            hash *= PRIME
        }
        return hash.toLong() and 0xffffffffL
    }

    /** Sampling bucket (0..99) of an install for a survey; stable for the install. */
    fun samplingBucket(installId: String, surveyId: String): Int = (hash32("$installId:$surveyId") % 100).toInt()
}
