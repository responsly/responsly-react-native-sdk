import Foundation

/// A survey, as passed to listeners and the display guard.
public struct ResponslySurvey: Hashable, Sendable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    public func toDictionary() -> [String: Any] {
        ["id": id, "name": name]
    }
}

/// What caused a survey to be shown.
public enum ResponslyTriggerType: String, CaseIterable, Sendable {
    case event = "event"
    case screen = "screen"
    case appLaunch = "app_launch"
    /// `showSurvey`
    case manual = "manual"
}

public struct ResponslyTrigger: Hashable, Sendable {
    public let type: ResponslyTriggerType
    /// Event or screen name; `nil` for `app_launch` and `manual`.
    public let name: String?

    public init(type: ResponslyTriggerType, name: String? = nil) {
        self.type = type
        self.name = name
    }

    public func toDictionary() -> [String: Any] {
        var dictionary: [String: Any] = ["type": type.rawValue]
        if let name = name {
            dictionary["name"] = name
        }
        return dictionary
    }
}

/// Why a survey was not shown.
public enum ResponslyNotShownReason: String, CaseIterable, Sendable {
    case disabled = "disabled"
    case notInitialized = "not_initialized"
    case configUnavailable = "config_unavailable"
    case surveyNotFound = "survey_not_found"
    case anotherSurveyVisible = "another_survey_visible"
    case anotherSurveySelected = "another_survey_selected"
    case paused = "paused"
    case outsideSchedule = "outside_schedule"
    case platformMismatch = "platform_mismatch"
    case appMismatch = "app_mismatch"
    case localeMismatch = "locale_mismatch"
    case audienceMismatch = "audience_mismatch"
    case alreadyCompleted = "already_completed"
    case frequencyLimit = "frequency_limit"
    case recontactWait = "recontact_wait"
    case globalCooldown = "global_cooldown"
    case sampledOut = "sampled_out"
    case screenChanged = "screen_changed"
    case vetoed = "vetoed"
    case loadFailed = "load_failed"
    case surveyUnavailable = "survey_unavailable"
}

/// One answer. `value` is JSON as the survey stores it: `String`, `NSNumber`,
/// `[Any]`, `[String: Any]` or `NSNull` (the types `JSONSerialization` produces).
public struct ResponslyAnswer: @unchecked Sendable {
    public let questionId: String
    public let type: String
    public let title: String
    public let value: Any

    public init(questionId: String, type: String, title: String, value: Any) {
        self.questionId = questionId
        self.type = type
        self.title = title
        self.value = value
    }

    public func toDictionary() -> [String: Any] {
        ["question_id": questionId, "type": type, "title": title, "value": value]
    }
}

public struct ResponslySurveyShownEvent: Hashable, Sendable {
    public let survey: ResponslySurvey
    public let trigger: ResponslyTrigger

    public init(survey: ResponslySurvey, trigger: ResponslyTrigger) {
        self.survey = survey
        self.trigger = trigger
    }

    public func toDictionary() -> [String: Any] {
        ["survey": survey.toDictionary(), "trigger": trigger.toDictionary()]
    }
}

public struct ResponslySurveyNotShownEvent: Hashable, Sendable {
    public let surveyId: String
    public let reason: ResponslyNotShownReason

    public init(surveyId: String, reason: ResponslyNotShownReason) {
        self.surveyId = surveyId
        self.reason = reason
    }

    public func toDictionary() -> [String: Any] {
        ["survey_id": surveyId, "reason": reason.rawValue]
    }
}

public struct ResponslyQuestionAnsweredEvent: @unchecked Sendable {
    public let surveyId: String
    public let questionId: String
    public let type: String
    public let title: String
    /// JSON value: `String`, `NSNumber`, `[Any]`, `[String: Any]` or `NSNull`.
    public let value: Any

    public init(surveyId: String, questionId: String, type: String, title: String, value: Any) {
        self.surveyId = surveyId
        self.questionId = questionId
        self.type = type
        self.title = title
        self.value = value
    }

    public func toDictionary() -> [String: Any] {
        [
            "survey_id": surveyId,
            "question_id": questionId,
            "type": type,
            "title": title,
            "value": value,
        ]
    }
}

public struct ResponslySurveyCompletedEvent: @unchecked Sendable {
    public let surveyId: String
    public let responseId: String
    public let answers: [ResponslyAnswer]

    public init(surveyId: String, responseId: String, answers: [ResponslyAnswer]) {
        self.surveyId = surveyId
        self.responseId = responseId
        self.answers = answers
    }

    public func toDictionary() -> [String: Any] {
        [
            "survey_id": surveyId,
            "response_id": responseId,
            "answers": answers.map { $0.toDictionary() },
        ]
    }
}

public struct ResponslySurveyDismissedEvent: Hashable, Sendable {
    public let surveyId: String

    public init(surveyId: String) {
        self.surveyId = surveyId
    }

    public func toDictionary() -> [String: Any] {
        ["survey_id": surveyId]
    }
}

public struct ResponslySurveyErrorEvent: Hashable, Sendable {
    public let surveyId: String
    public let code: String
    public let message: String

    public init(surveyId: String, code: String, message: String) {
        self.surveyId = surveyId
        self.code = code
        self.message = message
    }

    public func toDictionary() -> [String: Any] {
        ["survey_id": surveyId, "code": code, "message": message]
    }
}
