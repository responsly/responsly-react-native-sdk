package com.responsly.sdk.internal.config

import com.responsly.sdk.PresentationStyle

internal enum class TriggerType { EVENT, SCREEN, APP_LAUNCH, UNKNOWN }

/** `name` is the trait (`attribute`) or event property (`property`); `value` is a String, Double, Boolean or null. */
internal class Condition(val name: String?, val op: String, val value: Any?)

internal class TriggerConfig(
    val type: TriggerType,
    val name: String?,
    val minCount: Double,
    val conditions: List<Condition>,
)

internal class Audience(
    val platforms: List<String>,
    val appIds: List<String>,
    val locales: List<String>,
    val identified: Boolean?,
    val matchAny: Boolean,
    val conditions: List<Condition>,
)

internal enum class FrequencyMode { ONCE, UNTIL_COMPLETED, RECURRING, ALWAYS }

internal class Frequency(val mode: FrequencyMode, val recontactMillis: Long, val maxDisplays: Double?)

internal class Schedule(val startsAt: Long?, val endsAt: Long?)

internal enum class PresentationSize { SMALL, MEDIUM, LARGE }

internal class Presentation(
    val style: PresentationStyle,
    val size: PresentationSize,
    val dismissible: Boolean,
    val autoCloseMillis: Long,
)

internal class SurveyConfig(
    val id: String,
    val name: String,
    val url: String,
    val triggers: List<TriggerConfig>,
    val delayMillis: Long,
    val audience: Audience,
    val samplingPercent: Double,
    val frequency: Frequency,
    val schedule: Schedule,
    val presentation: Presentation,
    val anonymous: Boolean,
)

internal class SdkConfig(
    val version: Int,
    val ttlMillis: Long,
    val globalCooldownMillis: Long,
    val surveys: List<SurveyConfig>,
) {
    fun find(surveyId: String): SurveyConfig? = surveys.firstOrNull { it.id == surveyId }

    companion object {
        const val MIN_TTL_MILLIS = 60_000L
    }
}
