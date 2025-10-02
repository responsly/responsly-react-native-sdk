package com.responsly.sdk.internal.targeting

import com.responsly.sdk.NotShownReason
import com.responsly.sdk.internal.Fnv1a
import com.responsly.sdk.internal.config.FrequencyMode
import com.responsly.sdk.internal.config.SdkConfig
import com.responsly.sdk.internal.config.SurveyConfig
import com.responsly.sdk.internal.config.TriggerConfig
import com.responsly.sdk.internal.config.TriggerType
import com.responsly.sdk.internal.state.SdkState
import java.util.Locale

/** What the engine knows about the app, apart from the persisted state. */
internal class TargetingContext(
    val platform: String,
    val appId: String,
    /** The device's preferred language as a BCP 47 tag; overridden by [SdkState.locale]. */
    val deviceLocale: String,
    /** Targeting is paused (in-memory only). */
    val paused: Boolean,
    /** A survey is on screen or waiting to be shown. */
    val surveyActive: Boolean,
)

/** A call that can match triggers. */
internal sealed class TriggerCall {
    /** [count] is the event's count after this call was counted. */
    class Event(val name: String, val properties: Map<String, Any?>, val count: Int) : TriggerCall()

    class Screen(val name: String) : TriggerCall()

    object AppLaunch : TriggerCall()
}

/**
 * @property selected the survey to show, or `null`.
 * @property notShown every other matched survey with its reason, in configuration order.
 */
internal class TargetingDecision(
    val selected: SurveyConfig?,
    val notShown: Map<String, NotShownReason>,
)

/**
 * Decides which survey to show. A pure function of the configuration, the state, the context and
 * the current time: it keeps nothing and changes nothing.
 */
internal object TargetingEngine {
    /** Section 4.1: the first eligible survey with a matching trigger wins. */
    fun onTrigger(
        config: SdkConfig,
        state: SdkState,
        context: TargetingContext,
        call: TriggerCall,
        now: Long,
    ): TargetingDecision {
        var selected: SurveyConfig? = null
        val notShown = LinkedHashMap<String, NotShownReason>()
        for (survey in config.surveys) {
            if (survey.triggers.none { matches(it, call) }) continue
            val reason = targetingReason(config, survey, state, context, now)
            when {
                reason != null -> notShown[survey.id] = reason
                selected == null -> selected = survey
                else -> notShown[survey.id] = NotShownReason.ANOTHER_SURVEY_SELECTED
            }
        }
        return TargetingDecision(selected, notShown)
    }

    /** `showSurvey`: checks 1-4, 6-12 of section 4.2. */
    fun onShow(
        config: SdkConfig,
        state: SdkState,
        context: TargetingContext,
        surveyId: String,
        force: Boolean,
        now: Long,
    ): TargetingDecision {
        val survey = config.find(surveyId)
        val reason = when {
            !state.trackingEnabled -> NotShownReason.DISABLED
            survey == null -> NotShownReason.SURVEY_NOT_FOUND
            context.surveyActive -> NotShownReason.ANOTHER_SURVEY_VISIBLE
            force -> null
            else -> commonReason(survey, state, context, now)
        }
        return if (reason == null) {
            TargetingDecision(survey, emptyMap())
        } else {
            TargetingDecision(null, mapOf(surveyId to reason))
        }
    }

    /**
     * Eligibility of a survey for targeting (checks 1, 3, 5-14). With [ignoreActive] check 3 is
     * skipped: used to re-check the waiting survey itself when its delay ends.
     */
    fun targetingReason(
        config: SdkConfig,
        survey: SurveyConfig,
        state: SdkState,
        context: TargetingContext,
        now: Long,
        ignoreActive: Boolean = false,
    ): NotShownReason? {
        if (!state.trackingEnabled) return NotShownReason.DISABLED
        if (context.surveyActive && !ignoreActive) return NotShownReason.ANOTHER_SURVEY_VISIBLE
        if (context.paused) return NotShownReason.PAUSED
        commonReason(survey, state, context, now)?.let { return it }
        val lastAuto = state.lastAutoDisplayAt
        if (config.globalCooldownMillis > 0 && lastAuto != null && now - lastAuto < config.globalCooldownMillis) {
            return NotShownReason.GLOBAL_COOLDOWN
        }
        if (Fnv1a.samplingBucket(state.installId, survey.id) >= survey.samplingPercent) {
            return NotShownReason.SAMPLED_OUT
        }
        return null
    }

    /** Checks 6-12: schedule, audience and frequency. */
    private fun commonReason(
        survey: SurveyConfig,
        state: SdkState,
        context: TargetingContext,
        now: Long,
    ): NotShownReason? {
        val schedule = survey.schedule
        if (schedule.startsAt != null && now < schedule.startsAt) return NotShownReason.OUTSIDE_SCHEDULE
        if (schedule.endsAt != null && now >= schedule.endsAt) return NotShownReason.OUTSIDE_SCHEDULE

        val audience = survey.audience
        if (audience.platforms.isNotEmpty() && context.platform !in audience.platforms) {
            return NotShownReason.PLATFORM_MISMATCH
        }
        if (audience.appIds.isNotEmpty() && context.appId !in audience.appIds) {
            return NotShownReason.APP_MISMATCH
        }
        if (audience.locales.isNotEmpty()) {
            val locale = state.locale ?: context.deviceLocale
            if (audience.locales.none { localeMatches(it, locale) }) return NotShownReason.LOCALE_MISMATCH
        }
        if (audience.identified != null && audience.identified != (state.userId != null)) {
            return NotShownReason.AUDIENCE_MISMATCH
        }
        if (audience.conditions.isNotEmpty()) {
            val matched = if (audience.matchAny) {
                Conditions.matchesAny(audience.conditions, state.traits)
            } else {
                Conditions.matchesAll(audience.conditions, state.traits)
            }
            if (!matched) return NotShownReason.AUDIENCE_MISMATCH
        }
        return frequencyReason(survey, state, now)
    }

    private fun frequencyReason(survey: SurveyConfig, state: SdkState, now: Long): NotShownReason? {
        val record = state.surveys[survey.id]
        val displays = record?.displayCount ?: 0
        val last = record?.lastDisplayedAt
        val done = record?.completedAt != null
        val frequency = survey.frequency
        val overLimit = frequency.maxDisplays != null && displays >= frequency.maxDisplays
        val tooSoon = last != null && now - last < frequency.recontactMillis
        return when (frequency.mode) {
            FrequencyMode.ONCE -> when {
                done -> NotShownReason.ALREADY_COMPLETED
                displays > 0 -> NotShownReason.FREQUENCY_LIMIT
                else -> null
            }
            FrequencyMode.UNTIL_COMPLETED -> when {
                done -> NotShownReason.ALREADY_COMPLETED
                overLimit -> NotShownReason.FREQUENCY_LIMIT
                tooSoon -> NotShownReason.RECONTACT_WAIT
                else -> null
            }
            FrequencyMode.RECURRING -> when {
                overLimit -> NotShownReason.FREQUENCY_LIMIT
                tooSoon -> NotShownReason.RECONTACT_WAIT
                else -> null
            }
            FrequencyMode.ALWAYS -> if (overLimit) NotShownReason.FREQUENCY_LIMIT else null
        }
    }

    fun matches(trigger: TriggerConfig, call: TriggerCall): Boolean = when (call) {
        is TriggerCall.Event ->
            trigger.type == TriggerType.EVENT &&
                trigger.name == call.name &&
                call.count >= trigger.minCount &&
                Conditions.matchesAll(trigger.conditions, call.properties)
        is TriggerCall.Screen -> trigger.type == TriggerType.SCREEN && trigger.name == call.name
        TriggerCall.AppLaunch -> trigger.type == TriggerType.APP_LAUNCH
    }

    /**
     * Case-insensitive, `_` equals `-`; an entry matches the tag itself and any tag that extends it
     * (`pt` matches `pt-BR`, `pt-BR` does not match `pt-PT`).
     */
    fun localeMatches(entry: String, tag: String): Boolean {
        val wanted = normalizeLocale(entry)
        val actual = normalizeLocale(tag)
        if (wanted.isEmpty()) return false
        return actual == wanted || actual.startsWith("$wanted-")
    }

    private fun normalizeLocale(tag: String): String = tag.trim().replace('_', '-').lowercase(Locale.ROOT)
}
