package com.responsly.sdk.internal.bridge

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.SurveyTrigger
import com.responsly.sdk.internal.Values
import org.json.JSONObject

/** The context the SDK hands to the survey page before any page script runs (section 5.2). */
internal object SurveyContext {
    @Suppress("LongParameterList")
    fun build(
        platform: String,
        sdkVersion: String,
        appId: String,
        appVersion: String,
        osVersion: String,
        locale: String,
        surveyId: String,
        trigger: SurveyTrigger,
        style: PresentationStyle,
        dismissible: Boolean,
        anonymous: Boolean,
        userId: String?,
        traits: Map<String, Any?>,
        hidden: Map<String, String>,
    ): JSONObject {
        val json = JSONObject()
        json.put("v", 1)
        json.put("platform", platform)
        json.put("sdk_version", sdkVersion)
        json.put("app_id", appId)
        json.put("app_version", appVersion)
        json.put("os_version", osVersion)
        json.put("locale", locale)
        json.put("survey_id", surveyId)
        val triggerJson = JSONObject().put("type", trigger.type)
        trigger.name?.let { triggerJson.put("name", it) }
        json.put("trigger", triggerJson)
        json.put("presentation", JSONObject().put("style", style.value).put("dismissible", dismissible))
        if (anonymous || userId == null) {
            json.put("user", JSONObject.NULL)
        } else {
            json.put("user", JSONObject().put("id", userId).put("traits", Values.toJson(traits)))
        }
        val hiddenJson = JSONObject()
        if (!anonymous) hidden.forEach { (name, value) -> hiddenJson.put(name, value) }
        json.put("hidden", hiddenJson)
        return json
    }

    /**
     * The document-start script. It runs in every frame of the survey origin, so it defines the
     * context in the main frame only.
     */
    fun script(context: JSONObject): String {
        // JSON is valid JavaScript once the two line separators JSON allows unescaped are escaped.
        val literal = context.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return "(function(){if(window.top!==window.self){return;}window.__RESPONSLY_SDK__=$literal;})();"
    }
}
