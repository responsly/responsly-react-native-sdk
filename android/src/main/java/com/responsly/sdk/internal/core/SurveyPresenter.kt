package com.responsly.sdk.internal.core

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.bridge.BridgeMessage
import com.responsly.sdk.internal.config.PresentationSize

/** Everything the UI needs to load one survey. [token] identifies this attempt in every callback. */
internal data class PresentationRequest(
    val token: Long,
    val surveyId: String,
    /** The URL to load: the survey URL with `?sdk=1` (and `&lang=`). */
    val url: String,
    /** The survey URL from the configuration; navigation anywhere else leaves the survey. */
    val surveyUrl: String,
    /** The only origin that receives the context and may post bridge messages. */
    val origin: String,
    val contextScript: String,
    val style: PresentationStyle,
    val size: PresentationSize,
    val dismissible: Boolean,
    val userAgentSuffix: String,
)

/** Reported by the presenter on the main thread. */
internal interface PresenterEvents {
    /** A bridge message from the main frame of the survey origin. `ready` arrives only once the host activity is in front. */
    fun onBridgeMessage(token: Long, message: BridgeMessage)

    /** The page cannot be shown or is broken: no WebView, a main-frame load error, a WebView process that died. */
    fun onFailure(token: Long, code: String, detail: String)

    /** The activity hosting the still hidden container went away; the survey can be loaded again elsewhere. */
    fun onHostLost(token: Long)

    /** The respondent used the close button, the backdrop or system back. */
    fun onUserClosed(token: Long)

    /** The activity hosting the visible survey was destroyed; the container is already gone. */
    fun onHostDestroyed(token: Long)

    /** The visible page navigated to another address, which was opened in the system browser. */
    fun onNavigatedAway(token: Long)
}

/**
 * The native container and its WebView. Main thread only. The presenter shows at most one survey;
 * calls with a token other than the current one are ignored.
 */
internal interface SurveyPresenter {
    /** Creates the container hidden and starts loading, as soon as an activity is in front. */
    fun present(request: PresentationRequest, events: PresenterEvents)

    /** Makes the loaded survey visible. */
    fun reveal(token: Long)

    /** Shows or hides the close affordances (close button, backdrop tap, system back). */
    fun setDismissible(token: Long, dismissible: Boolean)

    /** Closes the container, or cancels the load. */
    fun dismiss(token: Long, animated: Boolean)

    /** Removes cookies and site storage of the given origins. */
    fun clearWebsiteData(origins: List<String>)
}
