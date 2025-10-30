package com.responsly.sdk.internal.platform

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.responsly.sdk.LogLevel
import com.responsly.sdk.internal.Cancellable
import com.responsly.sdk.internal.LogSink
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.Scheduler
import com.responsly.sdk.internal.core.Environment
import com.responsly.sdk.internal.core.Platform
import com.responsly.sdk.internal.core.SurveyPresenter
import com.responsly.sdk.internal.net.HttpSdkApi
import com.responsly.sdk.internal.net.SdkApi
import com.responsly.sdk.internal.state.KeyValueStorage
import com.responsly.sdk.internal.ui.AndroidSurveyPresenter
import com.responsly.sdk.internal.ui.UploadGrants
import com.responsly.sdk.internal.ui.WebsiteData
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Logcat, tag `Responsly`. */
internal object AndroidLogSink : LogSink {
    private const val TAG = "Responsly"

    override fun write(level: LogLevel, message: String, error: Throwable?) {
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, message, error)
            LogLevel.INFO -> Log.i(TAG, message, error)
            LogLevel.DEBUG -> Log.d(TAG, message, error)
            LogLevel.NONE -> Unit
        }
    }
}

/** A single core thread, a single network thread and the main looper. */
internal class AndroidScheduler(private val logger: Logger) : Scheduler {
    private val core = Executors.newSingleThreadScheduledExecutor(daemon("responsly-core"))
    private val network = Executors.newSingleThreadExecutor(daemon("responsly-net"))
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun execute(task: () -> Unit) {
        core.execute(guarded(task))
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val future = core.schedule(guarded(task), delayMillis, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }

    override fun io(task: () -> Unit) {
        network.execute(guarded(task))
    }

    override fun main(task: () -> Unit) {
        // Always posted, so main-thread work keeps the order in which the core thread asked for it.
        mainHandler.post(guarded(task))
    }

    /** A bug in the SDK must never take the host app down with it. */
    private fun guarded(task: () -> Unit): Runnable = Runnable {
        try {
            task()
        } catch (e: Exception) {
            logger.error("Unexpected error inside the SDK", e)
        }
    }

    private fun daemon(name: String) = ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
}

/** `SharedPreferences` named `com.responsly.sdk`; writes are asynchronous. */
internal class SharedPreferencesStorage(context: Context) : KeyValueStorage {
    private val preferences: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = try {
        preferences.getString(key, null)
    } catch (e: ClassCastException) {
        null
    }

    override fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }

    private companion object {
        const val NAME = "com.responsly.sdk"
    }
}

/** Everything the core needs from Android, created once per process at the first `initialize`. */
internal class AndroidPlatform(
    private val context: Context,
    tracker: ActivityTracker,
    scheduler: Scheduler,
    logger: Logger,
    private val sdkVersion: String,
) : Platform {
    override val environment = Environment(
        appId = context.packageName,
        appVersion = appVersion(context),
        osVersion = Build.VERSION.RELEASE ?: "",
        deviceLocale = { deviceLocale(context) },
    )

    /** Opened on first use, which is on the core thread. */
    override val storage: KeyValueStorage by lazy { SharedPreferencesStorage(context) }

    override val presenter: SurveyPresenter = AndroidSurveyPresenter(tracker, WebsiteData(context, logger), logger)

    init {
        scheduler.io { UploadGrants.releaseStale(context) }
    }

    override fun createApi(apiHost: String, sdkKey: String): SdkApi =
        HttpSdkApi(apiHost, sdkKey, context.packageName, sdkVersion)

    private companion object {
        fun appVersion(context: Context): String = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }

        /** The app's preferred language as a BCP 47 tag; follows per-app language settings. */
        fun deviceLocale(context: Context): String {
            val configuration = context.resources.configuration
            val locale = if (Build.VERSION.SDK_INT >= 24) {
                configuration.locales.takeIf { !it.isEmpty }?.get(0)
            } else {
                @Suppress("DEPRECATION")
                configuration.locale
            }
            return (locale ?: Locale.getDefault()).toLanguageTag()
        }
    }
}
