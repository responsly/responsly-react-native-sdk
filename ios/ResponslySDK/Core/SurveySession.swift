import Foundation

/// One survey attempt and its lifecycle, shared by targeting and manual presentation.
final class SurveySession {
    enum Source {
        case targeting(TriggerCall)
        case manual(ResponslyShowOptions)
    }

    enum Phase {
        /// Waiting for `delay_seconds`.
        case delay
        /// The WebView is loading, hidden.
        case loading
        /// The page is ready, still hidden; waiting for the display guard.
        case displayGuard
        case visible
    }

    let id = UUID()
    var survey: SurveyConfig
    let source: Source
    var phase: Phase = .delay
    var completed = false
    var timer: Cancellable?

    init(survey: SurveyConfig, source: Source) {
        self.survey = survey
        self.source = source
    }

    /// The UI layer holds a page for this session that is not on screen yet.
    var hasHiddenPage: Bool {
        phase == .loading || phase == .displayGuard
    }

    /// Selected by targeting rather than requested with `showSurvey`.
    var isAuto: Bool {
        if case .targeting = source { return true }
        return false
    }

    var showOptions: ResponslyShowOptions? {
        if case .manual(let options) = source { return options }
        return nil
    }

    var trigger: ResponslyTrigger {
        switch source {
        case .targeting(let call): return call.publicTrigger
        case .manual: return ResponslyTrigger(type: .manual)
        }
    }

    var publicSurvey: ResponslySurvey {
        ResponslySurvey(id: survey.id, name: survey.name)
    }
}
