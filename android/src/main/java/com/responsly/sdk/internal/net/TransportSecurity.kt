package com.responsly.sdk.internal.net

import java.net.URI
import java.util.Locale

/**
 * Which addresses the SDK talks to and shows. Everything goes over https. Plain http is accepted
 * only for hosts on a developer's machine or local network, so that the SDK can be tried against a
 * local Responsly (10.0.2.2, the host machine as the emulator sees it, among them); such an
 * address never comes from a production configuration.
 */
internal object TransportSecurity {
    fun isAllowed(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return false
        }
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return false
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> true
            "http" -> isLocalDevelopmentHost(host)
            else -> false
        }
    }

    /**
     * `localhost`, names under `.localhost`, `.local` and `.test`, `::1`, and the loopback and
     * private IPv4 ranges (127/8, 10/8, 172.16/12, 192.168/16).
     */
    fun isLocalDevelopmentHost(rawHost: String): Boolean {
        val host = rawHost.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.length !in 1..3 || part.any { it !in '0'..'9' }) return false
            part.toInt().takeIf { it <= 255 } ?: return false
        }
        val (first, second) = octets
        return first == 127 || first == 10 || (first == 192 && second == 168) || (first == 172 && second in 16..31)
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".test")
}
