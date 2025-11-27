package com.responsly.sdk

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.responsly.sdk.internal.Clock
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.Values
import com.responsly.sdk.internal.core.ListenerHub
import com.responsly.sdk.internal.core.ResponslyCore
import com.responsly.sdk.internal.platform.ActivityTracker
import com.responsly.sdk.internal.platform.AndroidLogSink
import com.responsly.sdk.internal.platform.AndroidPlatform
import com.responsly.sdk.internal.platform.AndroidScheduler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point of the Responsly SDK: in-app surveys, targeted on the device.
 *
 * Every method is safe to call from any thread and before [initialize]; calls made before
 * [initialize] are remembered and applied once it runs. Listener callbacks and the display guard
 * run on the main thread.
 *
 * ```
 * Responsly.initialize(applicationContext, "rsk_...")
 * Responsly.identify("user-123", mapOf("plan" to "pro"))
 * Responsly.track("purchase_completed", mapOf("value" to 49.9))
 * ```
 */
public object Responsly {
    /** Version of this SDK. */
    public const val SDK_VERSION: String = "0.9.4"

    private val logger = Logger(AndroidLogSink)
    private val scheduler = AndroidScheduler(logger)
    private val listeners = ListenerHub(scheduler, logger)
    private val core = ResponslyCore(scheduler, Clock { System.currentTimeMillis() }, logger, listeners, SDK_VERSION)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = ActivityTracker(core::onAppForeground, core::onAppBackground)
    private val trackerRegistered = AtomicBoolean(false)

    // Holds the application context only, never an activity.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var platform: AndroidPlatform? = null

    /**
     * Starts the SDK. Call it once, as early as possible, typically in `Application.onCreate`.
     * Calling it again with the same key and API host does nothing. A different key resets the
     * stored state first; the same key with another [ResponslyOptions.apiHost] switches to that
     * host, drops the cached configuration and downloads it again.
     *
     * @param context any context; the application context is used.
     * @param sdkKey the team's SDK key (`rsk_...`).
     */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, sdkKey: String, options: ResponslyOptions = ResponslyOptions()) {
        attach(context)
        // Normally the tracker has been watching since process start (see StartupProvider). If the
        // app removed that provider, the activity that calls initialize is the best hint there is.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tracker.seed(context)
        } else {
            mainHandler.post { tracker.seed(context) }
        }
        core.initialize(sdkKey, options, platformFor(context))
    }

    /**
     * Starts watching activities so a survey can be shown above the current one without the app
     * passing it in. Nothing else happens here: no disk access, no network.
     */
    internal fun attach(context: Context) {
        if (trackerRegistered.get()) return
        val application = context.applicationContext as? Application ?: context as? Application
        if (application == null) {
            logger.error("No Application is available yet; surveys can be shown once initialize is called")
            return
        }
        if (trackerRegistered.compareAndSet(false, true)) application.registerActivityLifecycleCallbacks(tracker)
    }

    /**
     * Sets the user id and merges [traits] into the stored traits. Values may be `String`,
     * `Number`, `Boolean` or `java.util.Date`; anything else is stored as its `toString()`.
     * A `null` value removes the trait.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) {
        if (userId.isBlank()) {
            logger.error("identify: the user id is blank; the call is ignored")
            return
        }
        core.identify(userId, Values.normalize(traits))
    }

    /** Merges [traits] into the stored traits; a `null` value removes the trait. */
    @JvmStatic
    public fun setAttributes(traits: Map<String, Any?>) {
        core.setAttributes(Values.normalize(traits))
    }

    /** Counts the event and shows a survey triggered by it, if one is eligible. */
    @JvmStatic
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        core.track(event, Values.normalize(properties).filterValues { it != null })
    }

    /** Sets the current screen and shows a survey triggered by it, if one is eligible. */
    @JvmStatic
    public fun screen(name: String) {
        core.screen(name)
    }

    /**
     * Shows a survey by id. Audience, schedule and frequency still apply unless
     * [ShowOptions.force] is set; the outcome is reported to listeners.
     */
    @JvmStatic
    @JvmOverloads
    public fun showSurvey(surveyId: String, options: ShowOptions = ShowOptions()) {
        core.showSurvey(surveyId, options)
    }

    /**
     * Closes the visible survey (counted as dismissed unless completed). A survey that is still
     * waiting or loading is cancelled silently: it was never shown, so nothing is recorded or reported.
     */
    @JvmStatic
    public fun closeSurvey() {
        core.closeSurvey()
    }

    /** Stops targeting until [resume]. [showSurvey] still works. Not persisted. */
    @JvmStatic
    public fun pause() {
        core.pause()
    }

    /** Resumes targeting after [pause]. */
    @JvmStatic
    public fun resume() {
        core.resume()
    }

    /** Overrides the locale used for targeting and for the survey language; `null` returns to the device language. */
    @JvmStatic
    public fun setLocale(languageTag: String?) {
        core.setLocale(languageTag)
    }

    /** Hidden-field values sent with every survey. Replaces the previous map. */
    @JvmStatic
    public fun setHiddenFields(fields: Map<String, String>) {
        core.setHiddenFields(LinkedHashMap(fields))
    }

    /** `false` turns the SDK off: no network, no targeting, nothing shown. Persisted. */
    @JvmStatic
    public fun setTrackingEnabled(enabled: Boolean) {
        core.setTrackingEnabled(enabled)
    }

    /**
     * Forgets the user: clears everything except the install id and the tracking switch, removes
     * the stored website data of surveys and closes a visible survey. Call it on logout.
     */
    @JvmStatic
    public fun reset() {
        core.reset()
    }

    /** Sets or removes the guard that is asked before a survey becomes visible. */
    @JvmStatic
    public fun setDisplayGuard(guard: DisplayGuard?) {
        core.setDisplayGuard(guard)
    }

    /** Adds a listener; adding the same instance twice has no effect. */
    @JvmStatic
    public fun addListener(listener: ResponslyListener) {
        listeners.add(listener)
    }

    @JvmStatic
    public fun removeListener(listener: ResponslyListener) {
        listeners.remove(listener)
    }

    private fun platformFor(context: Context): AndroidPlatform {
        platform?.let { return it }
        synchronized(this) {
            platform?.let { return it }
            val created = AndroidPlatform(context.applicationContext ?: context, tracker, scheduler, logger, SDK_VERSION)
            platform = created
            return created
        }
    }
}
