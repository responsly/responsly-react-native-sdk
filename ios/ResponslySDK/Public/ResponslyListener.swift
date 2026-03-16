import Foundation

/// Receives survey lifecycle callbacks. Every method is optional and is called on the main thread.
///
/// The SDK keeps a strong reference to a listener until `Responsly.removeListener(_:)` is called
/// with the token returned by `Responsly.addListener(_:)`.
public protocol ResponslyListener {
    /// The survey became visible.
    @MainActor func surveyShown(_ event: ResponslySurveyShownEvent)
    /// A survey matched a trigger (or was requested with `showSurvey`) but was not shown.
    @MainActor func surveyNotShown(_ event: ResponslySurveyNotShownEvent)
    @MainActor func questionAnswered(_ event: ResponslyQuestionAnsweredEvent)
    @MainActor func surveyCompleted(_ event: ResponslySurveyCompletedEvent)
    /// A survey that was shown was closed before it was completed.
    @MainActor func surveyDismissed(_ event: ResponslySurveyDismissedEvent)
    /// The survey page reported an error after it became visible.
    @MainActor func surveyError(_ event: ResponslySurveyErrorEvent)
}

public extension ResponslyListener {
    @MainActor func surveyShown(_ event: ResponslySurveyShownEvent) {}
    @MainActor func surveyNotShown(_ event: ResponslySurveyNotShownEvent) {}
    @MainActor func questionAnswered(_ event: ResponslyQuestionAnsweredEvent) {}
    @MainActor func surveyCompleted(_ event: ResponslySurveyCompletedEvent) {}
    @MainActor func surveyDismissed(_ event: ResponslySurveyDismissedEvent) {}
    @MainActor func surveyError(_ event: ResponslySurveyErrorEvent) {}
}

/// Identifies a registered listener; pass it to `Responsly.removeListener(_:)`.
public struct ResponslyListenerToken: Hashable, Sendable {
    let id: UUID

    init() {
        self.id = UUID()
    }
}

/// A closure-based listener.
///
/// ```swift
/// let token = Responsly.shared.addListener(ResponslyListenerBlock(
///     surveyCompleted: { event in print(event.responseId) }
/// ))
/// ```
public final class ResponslyListenerBlock: ResponslyListener {
    private let onSurveyShown: (@MainActor (ResponslySurveyShownEvent) -> Void)?
    private let onSurveyNotShown: (@MainActor (ResponslySurveyNotShownEvent) -> Void)?
    private let onQuestionAnswered: (@MainActor (ResponslyQuestionAnsweredEvent) -> Void)?
    private let onSurveyCompleted: (@MainActor (ResponslySurveyCompletedEvent) -> Void)?
    private let onSurveyDismissed: (@MainActor (ResponslySurveyDismissedEvent) -> Void)?
    private let onSurveyError: (@MainActor (ResponslySurveyErrorEvent) -> Void)?

    public init(
        surveyShown: (@MainActor (ResponslySurveyShownEvent) -> Void)? = nil,
        surveyNotShown: (@MainActor (ResponslySurveyNotShownEvent) -> Void)? = nil,
        questionAnswered: (@MainActor (ResponslyQuestionAnsweredEvent) -> Void)? = nil,
        surveyCompleted: (@MainActor (ResponslySurveyCompletedEvent) -> Void)? = nil,
        surveyDismissed: (@MainActor (ResponslySurveyDismissedEvent) -> Void)? = nil,
        surveyError: (@MainActor (ResponslySurveyErrorEvent) -> Void)? = nil
    ) {
        self.onSurveyShown = surveyShown
        self.onSurveyNotShown = surveyNotShown
        self.onQuestionAnswered = questionAnswered
        self.onSurveyCompleted = surveyCompleted
        self.onSurveyDismissed = surveyDismissed
        self.onSurveyError = surveyError
    }

    @MainActor public func surveyShown(_ event: ResponslySurveyShownEvent) {
        onSurveyShown?(event)
    }

    @MainActor public func surveyNotShown(_ event: ResponslySurveyNotShownEvent) {
        onSurveyNotShown?(event)
    }

    @MainActor public func questionAnswered(_ event: ResponslyQuestionAnsweredEvent) {
        onQuestionAnswered?(event)
    }

    @MainActor public func surveyCompleted(_ event: ResponslySurveyCompletedEvent) {
        onSurveyCompleted?(event)
    }

    @MainActor public func surveyDismissed(_ event: ResponslySurveyDismissedEvent) {
        onSurveyDismissed?(event)
    }

    @MainActor public func surveyError(_ event: ResponslySurveyErrorEvent) {
        onSurveyError?(event)
    }
}
