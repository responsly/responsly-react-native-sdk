package com.responsly.sdk.internal.ui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.responsly.sdk.internal.Logger

/**
 * Cookies and site storage of survey pages.
 *
 * Where the WebView supports profiles, surveys get their own, so their data never mixes with the
 * app's own web content and `reset()` can clear exactly it. Older WebViews share the default
 * profile; there `reset()` removes what can be removed per origin. Main thread only.
 */
internal class WebsiteData(private val context: Context, private val logger: Logger) {
    private var profileFailed = false

    /**
     * Moves a new WebView to the survey profile. Must be the first thing done with it. Returns the
     * profile's cookie manager, or `null` when the WebView stays on the default profile.
     */
    fun useSurveyProfile(webView: WebView): CookieManager? {
        markUsed()
        if (profileFailed || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return null
        return try {
            WebViewCompat.setProfile(webView, PROFILE)
            WebViewCompat.getProfile(webView).cookieManager
        } catch (e: Exception) {
            profileFailed = true
            logger.error("Could not use a separate WebView profile; surveys share the default one", e)
            null
        }
    }

    /** `reset()`: removes the stored website data of surveys. */
    fun clear(origins: List<String>) {
        // Nothing was ever stored: do not load the WebView just to clear it.
        if (!wasUsed()) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getProfile(PROFILE)?.let { profile ->
                    profile.cookieManager.removeAllCookies(null)
                    profile.cookieManager.flush()
                    profile.webStorage.deleteAllData()
                    logger.debug { "website data of the survey profile cleared" }
                }
            }
            if (profileFailed || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                clearDefaultProfile(origins)
            }
        } catch (e: Exception) {
            logger.error("Could not clear the website data of surveys", e)
        }
    }

    /**
     * Without profiles the data is shared with the app, so only what belongs to the survey origins
     * goes: their quota-managed storage and the cookies they set for their own host.
     */
    private fun clearDefaultProfile(origins: List<String>) {
        val storage = WebStorage.getInstance()
        val cookies = CookieManager.getInstance()
        for (origin in origins) {
            storage.deleteOrigin(origin)
            val header = cookies.getCookie(origin) ?: continue
            for (pair in header.split(';')) {
                val name = pair.substringBefore('=').trim()
                // No Domain attribute: this replaces only a host-only cookie of that name.
                if (name.isNotEmpty()) cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
            }
        }
        cookies.flush()
        logger.debug { "website data of ${origins.size} survey origin(s) cleared" }
    }

    private fun wasUsed(): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_USED, false)

    private fun markUsed() {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_USED, false)) preferences.edit().putBoolean(KEY_USED, true).apply()
    }

    private companion object {
        const val PROFILE = "responsly"
        const val PREFERENCES = "com.responsly.sdk.internal"
        const val KEY_USED = "web_data"
    }
}
