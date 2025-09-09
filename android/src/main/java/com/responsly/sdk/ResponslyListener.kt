package com.responsly.sdk

/**
 * Survey lifecycle callbacks, delivered on the main thread. Every method has an empty default
 * implementation, so implement only what you need.
 */
public interface ResponslyListener {
    /** A survey became visible. */
    public fun onSurveyShown(event: SurveyShownEvent) {}

    /** A survey matched a trigger or was requested with `showSurvey`, but was not shown. */
    public fun onSurveyNotShown(event: SurveyNotShownEvent) {}

    /** The respondent answered a question. */
    public fun onQuestionAnswered(event: QuestionAnsweredEvent) {}

    /** The respondent submitted the survey. */
    public fun onSurveyCompleted(event: SurveyCompletedEvent) {}

    /** The survey was closed before it was completed. */
    public fun onSurveyDismissed(event: SurveyDismissedEvent) {}

    /** The survey page reported an error while the survey was visible. */
    public fun onSurveyError(event: SurveyErrorEvent) {}
}
