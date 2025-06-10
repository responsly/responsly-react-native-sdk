package com.responsly.reactnative

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap

/**
 * Old architecture: the same methods as the generated TurboModule spec, written by hand.
 * Keep it in step with `src/NativeResponslySdk.ts`.
 */
abstract class ResponslySdkSpec(context: ReactApplicationContext) : ReactContextBaseJavaModule(context) {
    abstract fun initialize(sdkKey: String, options: ReadableMap)

    abstract fun identify(userId: String, traits: ReadableMap, removed: ReadableArray)

    abstract fun setAttributes(traits: ReadableMap, removed: ReadableArray)

    abstract fun track(event: String, properties: ReadableMap)

    abstract fun screen(name: String)

    abstract fun showSurvey(surveyId: String, options: ReadableMap)

    abstract fun closeSurvey()

    abstract fun pause()

    abstract fun resume()

    abstract fun setLocale(locale: String?)

    abstract fun setHiddenFields(fields: ReadableMap)

    abstract fun setTrackingEnabled(enabled: Boolean)

    abstract fun reset()

    abstract fun setDisplayGuardEnabled(enabled: Boolean)

    abstract fun resolveDisplayGuard(requestId: String, allow: Boolean)

    abstract fun addListener(eventName: String)

    abstract fun removeListeners(count: Double)
}
