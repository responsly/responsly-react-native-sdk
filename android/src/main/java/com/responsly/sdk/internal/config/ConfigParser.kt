package com.responsly.sdk.internal.config

import com.responsly.sdk.PresentationStyle
import com.responsly.sdk.internal.IsoTime
import com.responsly.sdk.internal.Values
import com.responsly.sdk.internal.arrayOrNull
import com.responsly.sdk.internal.booleanOrNull
import com.responsly.sdk.internal.numberOrNull
import com.responsly.sdk.internal.objectOrNull
import com.responsly.sdk.internal.objects
import com.responsly.sdk.internal.stringOrNull
import com.responsly.sdk.internal.strings
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the configuration body. Every field except a survey's `id` and `url` may be missing; a
 * missing, wrong-typed or unknown value falls back to its default, and unknown fields are ignored.
 */
internal object ConfigParser {
    private const val DEFAULT_TTL_SECONDS = 600.0
    private const val DEFAULT_AUTO_CLOSE_SECONDS = 3.0

    /** Returns `null` when [body] is not a JSON object. */
    fun parse(body: String): SdkConfig? = try {
        parse(JSONObject(body))
    } catch (e: JSONException) {
        null
    }

    fun parse(json: JSONObject): SdkConfig {
        val surveys = ArrayList<SurveyConfig>()
        val seen = HashSet<String>()
        for (item in json.arrayOrNull("surveys")?.objects().orEmpty()) {
            val survey = parseSurvey(item) ?: continue
            if (seen.add(survey.id)) surveys.add(survey)
        }
        val ttlSeconds = json.numberOrNull("ttl") ?: DEFAULT_TTL_SECONDS
        val cooldownSeconds = json.objectOrNull("settings")?.numberOrNull("global_cooldown_seconds") ?: 0.0
        return SdkConfig(
            version = json.numberOrNull("version")?.toInt() ?: 1,
            ttlMillis = maxOf(millis(ttlSeconds), SdkConfig.MIN_TTL_MILLIS),
            globalCooldownMillis = millis(cooldownSeconds),
            surveys = surveys,
        )
    }

    private fun parseSurvey(json: JSONObject): SurveyConfig? {
        val id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
        val url = json.stringOrNull("url")?.takeIf { it.isNotEmpty() } ?: return null
        return SurveyConfig(
            id = id,
            name = json.stringOrNull("name") ?: "",
            url = url,
            triggers = json.arrayOrNull("triggers")?.objects().orEmpty().map(::parseTrigger),
            delayMillis = millis(json.numberOrNull("delay_seconds") ?: 0.0),
            audience = parseAudience(json.objectOrNull("audience")),
            samplingPercent = json.numberOrNull("sampling_percent") ?: 100.0,
            frequency = parseFrequency(json.objectOrNull("frequency")),
            schedule = parseSchedule(json.objectOrNull("schedule")),
            presentation = parsePresentation(json.objectOrNull("presentation")),
            anonymous = json.booleanOrNull("anonymous") ?: false,
        )
    }

    private fun parseTrigger(json: JSONObject): TriggerConfig {
        val type = when (json.stringOrNull("type")) {
            "event" -> TriggerType.EVENT
            "screen" -> TriggerType.SCREEN
            "app_launch" -> TriggerType.APP_LAUNCH
            else -> TriggerType.UNKNOWN
        }
        return TriggerConfig(
            type = type,
            name = json.stringOrNull("name"),
            minCount = json.numberOrNull("min_count") ?: 1.0,
            conditions = parseConditions(json.arrayOrNull("conditions")),
        )
    }

    private fun parseConditions(array: JSONArray?): List<Condition> =
        array?.objects().orEmpty().map { json ->
            Condition(
                name = json.stringOrNull("attribute") ?: json.stringOrNull("property"),
                op = json.stringOrNull("op") ?: "",
                value = Values.fromJson(json.opt("value")),
            )
        }

    private fun parseAudience(json: JSONObject?): Audience = Audience(
        platforms = json?.arrayOrNull("platforms")?.strings().orEmpty(),
        appIds = json?.arrayOrNull("app_ids")?.strings().orEmpty(),
        locales = json?.arrayOrNull("locales")?.strings().orEmpty(),
        identified = json?.booleanOrNull("identified"),
        matchAny = json?.stringOrNull("match") == "any",
        conditions = parseConditions(json?.arrayOrNull("conditions")),
    )

    private fun parseFrequency(json: JSONObject?): Frequency = Frequency(
        mode = when (json?.stringOrNull("mode")) {
            "until_completed" -> FrequencyMode.UNTIL_COMPLETED
            "recurring" -> FrequencyMode.RECURRING
            "always" -> FrequencyMode.ALWAYS
            else -> FrequencyMode.ONCE
        },
        recontactMillis = millis(json?.numberOrNull("recontact_seconds") ?: 0.0),
        maxDisplays = json?.numberOrNull("max_displays"),
    )

    private fun parseSchedule(json: JSONObject?): Schedule = Schedule(
        startsAt = IsoTime.parse(json?.stringOrNull("starts_at")),
        endsAt = IsoTime.parse(json?.stringOrNull("ends_at")),
    )

    private fun parsePresentation(json: JSONObject?): Presentation = Presentation(
        style = json?.stringOrNull("style")?.let(PresentationStyle::fromValue) ?: PresentationStyle.BOTTOM_SHEET,
        size = when (json?.stringOrNull("size")) {
            "small" -> PresentationSize.SMALL
            "large" -> PresentationSize.LARGE
            else -> PresentationSize.MEDIUM
        },
        dismissible = json?.booleanOrNull("dismissible") ?: true,
        autoCloseMillis = millis(json?.numberOrNull("auto_close_seconds") ?: DEFAULT_AUTO_CLOSE_SECONDS),
    )

    /** Seconds to milliseconds; negative durations count as zero. */
    private fun millis(seconds: Double): Long = if (seconds <= 0.0) 0L else (seconds * 1_000.0).toLong()
}
