package com.responsly.sdk.internal.ui

import android.app.Activity
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.core.PresentationRequest
import com.responsly.sdk.internal.core.PresenterEvents
import com.responsly.sdk.internal.core.SurveyPresenter
import com.responsly.sdk.internal.platform.ActivityTracker

/** Shows surveys on whichever activity of the app is in front. Main thread only. */
internal class AndroidSurveyPresenter(
    private val tracker: ActivityTracker,
    private val webData: WebsiteData,
    private val logger: Logger,
) : SurveyPresenter, ActivityTracker.Observer {
    private class Waiting(val request: PresentationRequest, val events: PresenterEvents)

    /** A survey that waits for an activity to come to the front. */
    private var waiting: Waiting? = null
    private var window: SurveyWindow? = null

    init {
        tracker.observer = this
    }

    override fun present(request: PresentationRequest, events: PresenterEvents) {
        window?.destroy()
        window = null
        waiting = null
        val activity = tracker.resumedActivity()
        if (activity == null) {
            logger.debug { "survey ${request.surveyId}: waiting for an activity to come to the front" }
            waiting = Waiting(request, events)
        } else {
            start(activity, request, events)
        }
    }

    override fun reveal(token: Long) {
        current(token)?.reveal()
    }

    override fun setDismissible(token: Long, dismissible: Boolean) {
        current(token)?.setDismissible(dismissible)
    }

    override fun dismiss(token: Long, animated: Boolean) {
        if (waiting?.request?.token == token) waiting = null
        current(token)?.dismiss(animated)
    }

    override fun clearWebsiteData(origins: List<String>) {
        webData.clear(origins)
    }

    override fun onActivityResumed(activity: Activity) {
        val waiting = waiting
        if (waiting != null) {
            this.waiting = null
            start(activity, waiting.request, waiting.events)
        } else {
            window?.onActivityResumed(activity)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        window?.onActivityDestroyed(activity)
    }

    private fun start(activity: Activity, request: PresentationRequest, events: PresenterEvents) {
        var created: SurveyWindow? = null
        try {
            if (!SurveyWindow.isBridgeSupported()) {
                logger.error(
                    "The installed Android System WebView is too old for surveys: it supports neither " +
                        "document-start scripts nor web message listeners. Survey ${request.surveyId} is not shown.",
                )
                events.onFailure(request.token, "webview_unsupported", "The WebView lacks the features the bridge needs")
                return
            }
            created = SurveyWindow(
                activity = activity,
                request = request,
                events = events,
                logger = logger,
                webData = webData,
                isHostInFront = { tracker.resumedActivity() === activity },
                onGone = { gone -> if (window === gone) window = null },
            )
            window = created
            created.start()
        } catch (e: Exception) {
            // No WebView package, a WebView that is being updated, a window that cannot be added.
            abandon(created, request, events, e)
        } catch (e: LinkageError) {
            abandon(created, request, events, e)
        }
    }

    private fun abandon(created: SurveyWindow?, request: PresentationRequest, events: PresenterEvents, cause: Throwable) {
        logger.error("Could not create the survey container", cause)
        created?.destroy()
        events.onFailure(request.token, "webview_error", cause.toString())
    }

    private fun current(token: Long): SurveyWindow? = window?.takeIf { it.token == token }
}
