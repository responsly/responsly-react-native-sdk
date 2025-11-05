package com.responsly.sdk.internal.bridge

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale

/** Building the survey page URL (section 5.1) and the navigation policy (section 5.4). */
internal object SurveyUrl {
    /** `survey.url` + `?sdk=1`, plus `&lang=<code>` when a locale was set explicitly. */
    fun build(surveyUrl: String, lang: String?): String {
        val hash = surveyUrl.indexOf('#')
        val base = if (hash >= 0) surveyUrl.substring(0, hash) else surveyUrl
        val fragment = if (hash >= 0) surveyUrl.substring(hash) else ""
        val separator = when {
            !base.contains('?') -> "?"
            base.endsWith("?") || base.endsWith("&") -> ""
            else -> "&"
        }
        val query = StringBuilder("sdk=1")
        if (!lang.isNullOrBlank()) query.append("&lang=").append(URLEncoder.encode(lang.trim(), "UTF-8"))
        return base + separator + query + fragment
    }

    /**
     * The origin of an http(s) URL in the form WebView origin rules use, e.g.
     * `https://acme.responsly.com` or `http://10.0.2.2:8080`; `null` for anything else.
     */
    fun origin(url: String): String? {
        val uri = parse(url) ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == -1 || port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** True when [target] has the origin and path of the survey URL (query and fragment may differ). */
    fun isSurveyPage(surveyUrl: String, target: String): Boolean {
        val expectedOrigin = origin(surveyUrl) ?: return false
        if (origin(target) != expectedOrigin) return false
        return path(surveyUrl) == path(target)
    }

    private fun path(url: String): String? {
        val uri = parse(url) ?: return null
        return uri.rawPath?.ifEmpty { "/" } ?: "/"
    }

    private fun parse(url: String): URI? = try {
        URI(url.trim())
    } catch (e: URISyntaxException) {
        null
    }
}
