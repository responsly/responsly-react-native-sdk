package com.responsly.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.responsly.sdk.Decision
import com.responsly.sdk.DisplayGuard
import com.responsly.sdk.LogLevel
import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.QuestionAnsweredEvent
import com.responsly.sdk.Responsly
import com.responsly.sdk.ResponslyListener
import com.responsly.sdk.ResponslyOptions
import com.responsly.sdk.ShowOptions
import com.responsly.sdk.SurveyCompletedEvent
import com.responsly.sdk.SurveyDismissedEvent
import com.responsly.sdk.SurveyErrorEvent
import com.responsly.sdk.SurveyNotShownEvent
import com.responsly.sdk.SurveyShownEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The React Native module `ResponslySdk`: forwards the calls of the JavaScript layer to the
 * Responsly Android SDK (the `com.responsly.sdk` sources compiled into this library) and its
 * callbacks to JavaScript as device events.
 *
 * One instance belongs to one React instance. [invalidate] detaches it from the SDK, which
 * lives as long as the process: a JavaScript reload must not leave a listener or a display
 * guard behind that talks to a runtime that is gone.
 */
class ResponslySdkModule(reactContext: ReactApplicationContext) : ResponslySdkSpec(reactContext) {
    /** Subscriptions of the JavaScript NativeEventEmitter. */
    private val jsListeners = AtomicInteger(0)

    /** Questions of the display guard that JavaScript has not answered yet, by request id. */
    private val pendingDecisions = ConcurrentHashMap<String, Decision>()

    @Volatile
    private var displayGuardInstalled = false

    private val listener = object : ResponslyListener {
        override fun onSurveyShown(event: SurveyShownEvent) = sendEvent("surveyShown", event.toMap())

        override fun onSurveyNotShown(event: SurveyNotShownEvent) = sendEvent("surveyNotShown", event.toMap())

        override fun onQuestionAnswered(event: QuestionAnsweredEvent) = sendEvent("questionAnswered", event.toMap())

        override fun onSurveyCompleted(event: SurveyCompletedEvent) = sendEvent("surveyCompleted", event.toMap())

        override fun onSurveyDismissed(event: SurveyDismissedEvent) = sendEvent("surveyDismissed", event.toMap())

        override fun onSurveyError(event: SurveyErrorEvent) = sendEvent("surveyError", event.toMap())
    }

    private val displayGuard = DisplayGuard { survey, decision ->
        if (!displayGuardInstalled || jsListeners.get() <= 0) {
            // Nobody is listening on the JavaScript side.
            decision.allow()
        } else {
            val requestId = UUID.randomUUID().toString()
            // One survey is decided at a time; whatever is still here was never answered.
            pendingDecisions.clear()
            pendingDecisions[requestId] = decision
            val body = Arguments.createMap()
            body.putString("requestId", requestId)
            body.putMap("survey", toWritableMap(survey.toMap()))
            if (!emit(DISPLAY_GUARD_EVENT, body)) {
                pendingDecisions.remove(requestId)
                decision.allow()
            }
        }
    }

    init {
        Responsly.addListener(listener)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        Responsly.removeListener(listener)
        if (displayGuardInstalled) {
            displayGuardInstalled = false
            Responsly.setDisplayGuard(null)
        }
        allowPendingDecisions()
        jsListeners.set(0)
        super.invalidate()
    }

    // --- Calls --------------------------------------------------------------------------------

    @ReactMethod
    override fun initialize(sdkKey: String, options: ReadableMap) {
        val defaults = ResponslyOptions()
        val logLevel = when (options.stringOrNull("logLevel")) {
            "none" -> LogLevel.NONE
            "error" -> LogLevel.ERROR
            "info" -> LogLevel.INFO
            "debug" -> LogLevel.DEBUG
            else -> defaults.logLevel
        }
        val apiHost = options.stringOrNull("apiHost") ?: defaults.apiHost
        Responsly.initialize(reactApplicationContext, sdkKey, ResponslyOptions(apiHost, logLevel))
    }

    @ReactMethod
    override fun identify(userId: String, traits: ReadableMap, removed: ReadableArray) {
        Responsly.identify(userId, traitsOf(traits, removed))
    }

    @ReactMethod
    override fun setAttributes(traits: ReadableMap, removed: ReadableArray) {
        Responsly.setAttributes(traitsOf(traits, removed))
    }

    @ReactMethod
    override fun track(event: String, properties: ReadableMap) {
        Responsly.track(event, scalarsOf(properties))
    }

    @ReactMethod
    override fun screen(name: String) {
        Responsly.screen(name)
    }

    @ReactMethod
    override fun showSurvey(surveyId: String, options: ReadableMap) {
        val force = options.hasKey("force") && options.getType("force") == ReadableType.Boolean && options.getBoolean("force")
        val hiddenFields =
            if (options.hasKey("hiddenFields") && options.getType("hiddenFields") == ReadableType.Map) {
                options.getMap("hiddenFields")?.let(::stringsOf) ?: emptyMap()
            } else {
                emptyMap()
            }
        val locale = options.stringOrNull("locale")?.takeIf { it.isNotEmpty() }
        val presentation = options.stringOrNull("presentation")?.let(PresentationStyle::fromValue)
        Responsly.showSurvey(surveyId, ShowOptions(force, hiddenFields, locale, presentation))
    }

    @ReactMethod
    override fun closeSurvey() {
        Responsly.closeSurvey()
    }

    @ReactMethod
    override fun pause() {
        Responsly.pause()
    }

    @ReactMethod
    override fun resume() {
        Responsly.resume()
    }

    @ReactMethod
    override fun setLocale(locale: String?) {
        Responsly.setLocale(locale)
    }

    @ReactMethod
    override fun setHiddenFields(fields: ReadableMap) {
        Responsly.setHiddenFields(stringsOf(fields))
    }

    @ReactMethod
    override fun setTrackingEnabled(enabled: Boolean) {
        Responsly.setTrackingEnabled(enabled)
    }

    @ReactMethod
    override fun reset() {
        Responsly.reset()
    }

    // --- Display guard ------------------------------------------------------------------------

    /** Installs or removes the guard that forwards the question to JavaScript. */
    @ReactMethod
    override fun setDisplayGuardEnabled(enabled: Boolean) {
        displayGuardInstalled = enabled
        if (enabled) {
            Responsly.setDisplayGuard(displayGuard)
        } else {
            Responsly.setDisplayGuard(null)
            allowPendingDecisions()
        }
    }

    /**
     * The answer of the JavaScript guard. An answer that comes after the SDK's own 2-second limit
     * is ignored by the SDK.
     */
    @ReactMethod
    override fun resolveDisplayGuard(requestId: String, allow: Boolean) {
        val decision = pendingDecisions.remove(requestId) ?: return
        if (allow) decision.allow() else decision.veto()
    }

    private fun allowPendingDecisions() {
        val decisions = ArrayList(pendingDecisions.values)
        pendingDecisions.clear()
        decisions.forEach { it.allow() }
    }

    // --- Events -------------------------------------------------------------------------------

    @ReactMethod
    override fun addListener(eventName: String) {
        jsListeners.incrementAndGet()
    }

    @ReactMethod
    override fun removeListeners(count: Double) {
        val remaining = jsListeners.addAndGet(-count.toInt())
        if (remaining < 0) jsListeners.set(0)
    }

    private fun sendEvent(type: String, payload: Map<String, Any?>) {
        if (jsListeners.get() <= 0) return
        val body = Arguments.createMap()
        body.putString("type", type)
        body.putMap("payload", toWritableMap(payload))
        emit(EVENT, body)
    }

    /** Sends a device event to JavaScript; `false` when there is no live React instance. */
    private fun emit(eventName: String, body: WritableMap): Boolean {
        val context = reactApplicationContext
        if (!context.hasActiveReactInstance()) return false
        return try {
            context
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, body)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    companion object {
        const val NAME: String = "ResponslySdk"

        /** Listener callbacks: `{ type, payload }`, where `payload` is the event's `toMap()`. */
        private const val EVENT = "ResponslyEvent"

        /** A question from the display guard: `{ requestId, survey }`. */
        private const val DISPLAY_GUARD_EVENT = "ResponslyDisplayGuard"

        private fun ReadableMap.stringOrNull(key: String): String? =
            if (hasKey(key) && getType(key) == ReadableType.String) getString(key) else null

        /** The strings, numbers and booleans of a map; anything else is left out. */
        private fun scalarsOf(map: ReadableMap): MutableMap<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            val keys = map.keySetIterator()
            while (keys.hasNextKey()) {
                val key = keys.nextKey()
                when (map.getType(key)) {
                    ReadableType.String -> result[key] = map.getString(key)
                    ReadableType.Number -> result[key] = map.getDouble(key)
                    ReadableType.Boolean -> result[key] = map.getBoolean(key)
                    else -> Unit
                }
            }
            return result
        }

        /** `traits` holds the values to set; `removed` lists the traits to remove (a null value). */
        private fun traitsOf(traits: ReadableMap, removed: ReadableArray): Map<String, Any?> {
            val result = scalarsOf(traits)
            for (index in 0 until removed.size()) {
                if (removed.getType(index) == ReadableType.String) {
                    removed.getString(index)?.let { result[it] = null }
                }
            }
            return result
        }

        private fun stringsOf(map: ReadableMap): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val keys = map.keySetIterator()
            while (keys.hasNextKey()) {
                val key = keys.nextKey()
                if (map.getType(key) == ReadableType.String) {
                    map.getString(key)?.let { result[key] = it }
                }
            }
            return result
        }

        /** Converts the JSON-like maps of the SDK's events (`toMap()`), answer values included. */
        private fun toWritableMap(map: Map<*, *>): WritableMap {
            val result = Arguments.createMap()
            for ((rawKey, value) in map) {
                val key = rawKey?.toString() ?: continue
                when (value) {
                    null -> result.putNull(key)
                    is String -> result.putString(key, value)
                    is Boolean -> result.putBoolean(key, value)
                    is Number -> result.putDouble(key, value.toDouble())
                    is Map<*, *> -> result.putMap(key, toWritableMap(value))
                    is Iterable<*> -> result.putArray(key, toWritableArray(value))
                    else -> result.putString(key, value.toString())
                }
            }
            return result
        }

        private fun toWritableArray(values: Iterable<*>): WritableArray {
            val result = Arguments.createArray()
            for (value in values) {
                when (value) {
                    null -> result.pushNull()
                    is String -> result.pushString(value)
                    is Boolean -> result.pushBoolean(value)
                    is Number -> result.pushDouble(value.toDouble())
                    is Map<*, *> -> result.pushMap(toWritableMap(value))
                    is Iterable<*> -> result.pushArray(toWritableArray(value))
                    else -> result.pushString(value.toString())
                }
            }
            return result
        }
    }
}
