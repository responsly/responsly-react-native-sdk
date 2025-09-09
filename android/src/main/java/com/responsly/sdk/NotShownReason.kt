package com.responsly.sdk

/**
 * Why a survey was not shown. [value] is the string used by the protocol and by [SurveyNotShownEvent.toMap].
 */
public enum class NotShownReason(public val value: String) {
    /** Tracking is disabled with [Responsly.setTrackingEnabled]. */
    DISABLED("disabled"),

    /** [Responsly.showSurvey] was called before [Responsly.initialize]. */
    NOT_INITIALIZED("not_initialized"),

    /** The configuration could not be downloaded and no cached copy exists. */
    CONFIG_UNAVAILABLE("config_unavailable"),

    /** The survey id is not part of the configuration. */
    SURVEY_NOT_FOUND("survey_not_found"),

    /** Another survey is on screen or waiting to be shown. */
    ANOTHER_SURVEY_VISIBLE("another_survey_visible"),

    /** The survey was eligible, but an earlier survey in the configuration was selected. */
    ANOTHER_SURVEY_SELECTED("another_survey_selected"),

    /** Targeting is paused with [Responsly.pause]. */
    PAUSED("paused"),

    /** The current time is outside the survey's schedule. */
    OUTSIDE_SCHEDULE("outside_schedule"),

    /** The survey does not target this platform. */
    PLATFORM_MISMATCH("platform_mismatch"),

    /** The survey does not target this application id. */
    APP_MISMATCH("app_mismatch"),

    /** The survey does not target the current locale. */
    LOCALE_MISMATCH("locale_mismatch"),

    /** The user does not match the survey's audience. */
    AUDIENCE_MISMATCH("audience_mismatch"),

    /** The user already completed the survey. */
    ALREADY_COMPLETED("already_completed"),

    /** The survey reached its display limit. */
    FREQUENCY_LIMIT("frequency_limit"),

    /** The survey was shown too recently. */
    RECONTACT_WAIT("recontact_wait"),

    /** Another survey was shown by targeting too recently. */
    GLOBAL_COOLDOWN("global_cooldown"),

    /** This install is outside the survey's sample. */
    SAMPLED_OUT("sampled_out"),

    /** The screen that triggered the survey is no longer the current one. */
    SCREEN_CHANGED("screen_changed"),

    /** The [DisplayGuard] vetoed the survey. */
    VETOED("vetoed"),

    /** The survey page did not load in time or failed to load. */
    LOAD_FAILED("load_failed"),

    /** The survey page reported that the survey cannot be answered. */
    SURVEY_UNAVAILABLE("survey_unavailable");

    public companion object {
        /** Returns the reason for a protocol string, or `null` when it is unknown. */
        @JvmStatic
        public fun fromValue(value: String): NotShownReason? = values().firstOrNull { it.value == value }
    }
}
