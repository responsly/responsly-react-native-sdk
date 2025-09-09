package com.responsly.sdk

/**
 * Asked on the main thread right before a survey becomes visible. Call [Decision.allow] or [Decision.veto];
 * no answer within 2 seconds counts as allow. Not asked for `showSurvey(force = true)`.
 */
public fun interface DisplayGuard {
    public fun shouldShow(survey: Survey, decision: Decision)
}

/** The answer to a [DisplayGuard] question. Safe to call from any thread; the first call wins. */
public interface Decision {
    /** Show the survey. */
    public fun allow()

    /** Do not show the survey; listeners get [NotShownReason.VETOED]. */
    public fun veto()
}
