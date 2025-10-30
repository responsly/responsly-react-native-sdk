package com.responsly.sdk.internal.platform

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.responsly.sdk.internal.ui.FileChooserActivity
import java.lang.ref.WeakReference

/**
 * Knows which activity is in front and whether the app is in the foreground, so the host never
 * has to pass an activity. Callbacks arrive on the main thread; the SDK's own activity is ignored.
 */
internal class ActivityTracker(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    interface Observer {
        fun onActivityResumed(activity: Activity)

        fun onActivityDestroyed(activity: Activity)
    }

    @Volatile
    var observer: Observer? = null

    private var resumed: WeakReference<Activity>? = null
    private val started = HashSet<Int>()

    /** The activity in front, if it can host a dialog. */
    fun resumedActivity(): Activity? = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /**
     * `initialize` may be called from an activity that is already running, whose lifecycle
     * callbacks were missed. Main thread only.
     */
    fun seed(context: Context) {
        val activity = findActivity(context) ?: return
        if (isInternal(activity) || activity.isFinishing || activity.isDestroyed) return
        started.add(System.identityHashCode(activity))
        if (resumed?.get() == null) resumed = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (isInternal(activity)) return
        val wasBackground = started.isEmpty()
        started.add(System.identityHashCode(activity))
        if (wasBackground) onForeground()
    }

    override fun onActivityResumed(activity: Activity) {
        if (isInternal(activity)) return
        started.add(System.identityHashCode(activity))
        resumed = WeakReference(activity)
        observer?.onActivityResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityStopped(activity: Activity) {
        if (isInternal(activity)) return
        val wasForeground = started.remove(System.identityHashCode(activity))
        // A configuration change stops and restarts the activity; the app stays in the foreground.
        if (wasForeground && started.isEmpty() && !activity.isChangingConfigurations) onBackground()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (isInternal(activity)) return
        started.remove(System.identityHashCode(activity))
        if (resumed?.get() === activity) resumed = null
        observer?.onActivityDestroyed(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun isInternal(activity: Activity): Boolean = activity is FileChooserActivity

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
