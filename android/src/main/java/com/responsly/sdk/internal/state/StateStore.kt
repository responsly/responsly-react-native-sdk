package com.responsly.sdk.internal.state

import com.responsly.sdk.internal.Values
import com.responsly.sdk.internal.numberOrNull
import com.responsly.sdk.internal.objectOrNull
import com.responsly.sdk.internal.stringOrNull
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The small storage surface the SDK needs; backed by `SharedPreferences` on a device. */
internal interface KeyValueStorage {
    fun getString(key: String): String?

    /** Stores [value]; `null` removes the key. */
    fun putString(key: String, value: String?)
}

/** One telemetry event waiting to be sent. Nothing but the type and the survey id is recorded. */
internal class TelemetryEvent(val type: String, val surveyId: String)

/**
 * Loads and persists the local state of section 3. Not thread-safe: confined to the core thread.
 * Every mutation is written through immediately, key by key.
 */
internal class StateStore(
    private val storage: KeyValueStorage,
    private val newInstallId: () -> String = { UUID.randomUUID().toString().lowercase(Locale.ROOT) },
) {
    val state = SdkState()

    /** The key the stored state belongs to. */
    var sdkKey: String? = null
        private set

    var configBody: String? = null
        private set
    var configEtag: String? = null
        private set
    var configFetchedAt: Long? = null
        private set

    val telemetry: MutableList<TelemetryEvent> = ArrayList()

    fun load() {
        sdkKey = storage.getString(KEY_SDK_KEY)
        val installId = storage.getString(KEY_INSTALL_ID)
        if (installId.isNullOrEmpty()) {
            state.installId = newInstallId()
            storage.putString(KEY_INSTALL_ID, state.installId)
        } else {
            state.installId = installId
        }
        state.trackingEnabled = storage.getString(KEY_TRACKING_ENABLED) != "false"
        state.userId = storage.getString(KEY_USER_ID)
        state.locale = storage.getString(KEY_LOCALE)
        state.lastAutoDisplayAt = storage.getString(KEY_LAST_AUTO_DISPLAY_AT)?.toLongOrNull()

        state.traits.clear()
        Values.mapFromJson(readObject(KEY_TRAITS)).forEach { (name, value) ->
            if (value != null) state.traits[name] = value
        }
        state.hiddenFields = stringMap(readObject(KEY_HIDDEN_FIELDS))

        state.eventCounts.clear()
        readObject(KEY_EVENT_COUNTS)?.let { json ->
            for (name in json.keys()) json.numberOrNull(name)?.let { state.eventCounts[name] = it.toInt() }
        }

        state.surveys.clear()
        readObject(KEY_SURVEYS)?.let { json ->
            for (id in json.keys()) {
                val item = json.objectOrNull(id) ?: continue
                state.surveys[id] = SurveyRecord(
                    displayCount = item.numberOrNull("display_count")?.toInt() ?: 0,
                    lastDisplayedAt = item.numberOrNull("last_displayed_at")?.toLong(),
                    completedAt = item.numberOrNull("completed_at")?.toLong(),
                    dismissedCount = item.numberOrNull("dismissed_count")?.toInt() ?: 0,
                )
            }
        }

        configBody = storage.getString(KEY_CONFIG)
        configEtag = storage.getString(KEY_CONFIG_ETAG)
        configFetchedAt = storage.getString(KEY_CONFIG_FETCHED_AT)?.toLongOrNull()

        telemetry.clear()
        storage.getString(KEY_TELEMETRY)?.let { raw ->
            try {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val type = item.stringOrNull("type") ?: continue
                    val surveyId = item.stringOrNull("survey_id") ?: continue
                    telemetry.add(TelemetryEvent(type, surveyId))
                }
            } catch (e: JSONException) {
                // A corrupt queue is dropped.
            }
        }
    }

    fun bindSdkKey(key: String) {
        sdkKey = key
        storage.putString(KEY_SDK_KEY, key)
    }

    fun identify(userId: String, traits: Map<String, Any?>) {
        state.userId = userId
        storage.putString(KEY_USER_ID, userId)
        setAttributes(traits)
    }

    fun setAttributes(traits: Map<String, Any?>) {
        if (traits.isEmpty()) return
        state.mergeTraits(traits)
        storage.putString(KEY_TRAITS, Values.toJson(state.traits).toString())
    }

    fun setLocale(locale: String?) {
        state.locale = locale
        storage.putString(KEY_LOCALE, locale)
    }

    fun setHiddenFields(fields: Map<String, String>) {
        state.hiddenFields = LinkedHashMap(fields)
        storage.putString(KEY_HIDDEN_FIELDS, JSONObject(fields as Map<*, *>).toString())
    }

    fun setTrackingEnabled(enabled: Boolean) {
        state.trackingEnabled = enabled
        storage.putString(KEY_TRACKING_ENABLED, enabled.toString())
    }

    fun countEvent(name: String): Int {
        val count = state.countEvent(name)
        storage.putString(KEY_EVENT_COUNTS, JSONObject(state.eventCounts as Map<*, *>).toString())
        return count
    }

    fun markDisplayed(surveyId: String, now: Long, auto: Boolean) {
        state.markDisplayed(surveyId, now, auto)
        saveSurveys()
        if (auto) storage.putString(KEY_LAST_AUTO_DISPLAY_AT, now.toString())
    }

    fun markCompleted(surveyId: String, now: Long) {
        state.markCompleted(surveyId, now)
        saveSurveys()
    }

    fun markDismissed(surveyId: String) {
        state.markDismissed(surveyId)
        saveSurveys()
    }

    fun saveConfig(body: String, etag: String?, fetchedAt: Long) {
        configBody = body
        configEtag = etag
        configFetchedAt = fetchedAt
        storage.putString(KEY_CONFIG, body)
        storage.putString(KEY_CONFIG_ETAG, etag)
        storage.putString(KEY_CONFIG_FETCHED_AT, fetchedAt.toString())
    }

    /** `304 Not Modified`: the cached copy is fresh again. */
    fun touchConfig(fetchedAt: Long) {
        configFetchedAt = fetchedAt
        storage.putString(KEY_CONFIG_FETCHED_AT, fetchedAt.toString())
    }

    fun clearConfig() {
        configBody = null
        configEtag = null
        configFetchedAt = null
        storage.putString(KEY_CONFIG, null)
        storage.putString(KEY_CONFIG_ETAG, null)
        storage.putString(KEY_CONFIG_FETCHED_AT, null)
    }

    fun saveTelemetry() {
        if (telemetry.isEmpty()) {
            storage.putString(KEY_TELEMETRY, null)
            return
        }
        val array = JSONArray()
        for (event in telemetry) {
            array.put(JSONObject().put("type", event.type).put("survey_id", event.surveyId))
        }
        storage.putString(KEY_TELEMETRY, array.toString())
    }

    /** Clears everything except the install id, the tracking switch and the bound SDK key. */
    fun reset() {
        state.clear()
        telemetry.clear()
        for (key in RESET_KEYS) storage.putString(key, null)
        clearConfig()
    }

    private fun saveSurveys() {
        val json = JSONObject()
        for ((id, record) in state.surveys) {
            val item = JSONObject()
            item.put("display_count", record.displayCount)
            item.put("dismissed_count", record.dismissedCount)
            record.lastDisplayedAt?.let { item.put("last_displayed_at", it) }
            record.completedAt?.let { item.put("completed_at", it) }
            json.put(id, item)
        }
        storage.putString(KEY_SURVEYS, json.toString())
    }

    private fun readObject(key: String): JSONObject? {
        val raw = storage.getString(key) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: JSONException) {
            null
        }
    }

    private fun stringMap(json: JSONObject?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (json == null) return result
        for (key in json.keys()) json.stringOrNull(key)?.let { result[key] = it }
        return result
    }

    companion object {
        const val KEY_SDK_KEY = "sdk_key"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_TRAITS = "traits"
        const val KEY_LOCALE = "locale"
        const val KEY_HIDDEN_FIELDS = "hidden_fields"
        const val KEY_TRACKING_ENABLED = "tracking_enabled"
        const val KEY_EVENT_COUNTS = "event_counts"
        const val KEY_SURVEYS = "surveys"
        const val KEY_LAST_AUTO_DISPLAY_AT = "last_auto_display_at"
        const val KEY_CONFIG = "config"
        const val KEY_CONFIG_ETAG = "config_etag"
        const val KEY_CONFIG_FETCHED_AT = "config_fetched_at"
        const val KEY_TELEMETRY = "telemetry_queue"

        private val RESET_KEYS = listOf(
            KEY_USER_ID,
            KEY_TRAITS,
            KEY_LOCALE,
            KEY_HIDDEN_FIELDS,
            KEY_EVENT_COUNTS,
            KEY_SURVEYS,
            KEY_LAST_AUTO_DISPLAY_AT,
            KEY_TELEMETRY,
        )
    }
}
