package com.responsly.sdk

/** A survey from the configuration. */
public class Survey(public val id: String, public val name: String) {
    public fun toMap(): Map<String, Any?> = mapOf("id" to id, "name" to name)

    override fun toString(): String = "Survey(id=$id, name=$name)"
}

/**
 * What caused a survey to be shown.
 *
 * @property type `event`, `screen`, `app_launch` or `manual` (shown with `showSurvey`).
 * @property name the event or screen name; `null` for `app_launch` and `manual`.
 */
public class SurveyTrigger(public val type: String, public val name: String?) {
    public fun toMap(): Map<String, Any?> = mapOf("type" to type, "name" to name)

    override fun toString(): String = "SurveyTrigger(type=$type, name=$name)"

    public companion object {
        public const val TYPE_EVENT: String = "event"
        public const val TYPE_SCREEN: String = "screen"
        public const val TYPE_APP_LAUNCH: String = "app_launch"
        public const val TYPE_MANUAL: String = "manual"
    }
}

/**
 * One answer of a survey response.
 *
 * @property value the answer as the survey stores it: `String`, `Long` / `Double`, `Boolean`,
 * `List<Any?>`, `Map<String, Any?>` or `null`.
 */
public class Answer(
    public val questionId: String,
    public val type: String,
    public val title: String,
    public val value: Any?,
) {
    public fun toMap(): Map<String, Any?> =
        mapOf("question_id" to questionId, "type" to type, "title" to title, "value" to value)

    override fun toString(): String = "Answer(questionId=$questionId, type=$type, title=$title, value=$value)"
}

/** Payload of [ResponslyListener.onSurveyShown]. */
public class SurveyShownEvent(public val survey: Survey, public val trigger: SurveyTrigger) {
    public fun toMap(): Map<String, Any?> = mapOf("survey" to survey.toMap(), "trigger" to trigger.toMap())

    override fun toString(): String = "SurveyShownEvent(survey=$survey, trigger=$trigger)"
}

/** Payload of [ResponslyListener.onSurveyNotShown]. */
public class SurveyNotShownEvent(public val surveyId: String, public val reason: NotShownReason) {
    public fun toMap(): Map<String, Any?> = mapOf("survey_id" to surveyId, "reason" to reason.value)

    override fun toString(): String = "SurveyNotShownEvent(surveyId=$surveyId, reason=${reason.value})"
}

/**
 * Payload of [ResponslyListener.onQuestionAnswered].
 *
 * @property value see [Answer.value].
 */
public class QuestionAnsweredEvent(
    public val surveyId: String,
    public val questionId: String,
    public val type: String,
    public val title: String,
    public val value: Any?,
) {
    public fun toMap(): Map<String, Any?> = mapOf(
        "survey_id" to surveyId,
        "question_id" to questionId,
        "type" to type,
        "title" to title,
        "value" to value,
    )

    override fun toString(): String =
        "QuestionAnsweredEvent(surveyId=$surveyId, questionId=$questionId, type=$type, title=$title, value=$value)"
}

/** Payload of [ResponslyListener.onSurveyCompleted]. */
public class SurveyCompletedEvent(
    public val surveyId: String,
    public val responseId: String,
    public val answers: List<Answer>,
) {
    public fun toMap(): Map<String, Any?> = mapOf(
        "survey_id" to surveyId,
        "response_id" to responseId,
        "answers" to answers.map { it.toMap() },
    )

    override fun toString(): String =
        "SurveyCompletedEvent(surveyId=$surveyId, responseId=$responseId, answers=$answers)"
}

/** Payload of [ResponslyListener.onSurveyDismissed]. */
public class SurveyDismissedEvent(public val surveyId: String) {
    public fun toMap(): Map<String, Any?> = mapOf("survey_id" to surveyId)

    override fun toString(): String = "SurveyDismissedEvent(surveyId=$surveyId)"
}

/** Payload of [ResponslyListener.onSurveyError]. */
public class SurveyErrorEvent(public val surveyId: String, public val code: String, public val message: String) {
    public fun toMap(): Map<String, Any?> = mapOf("survey_id" to surveyId, "code" to code, "message" to message)

    override fun toString(): String = "SurveyErrorEvent(surveyId=$surveyId, code=$code, message=$message)"
}
