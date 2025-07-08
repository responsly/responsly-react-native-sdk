import Foundation

/// Per-survey counters (protocol section 3).
struct SurveyState: Codable, Equatable {
    var displayCount: Int = 0
    var lastDisplayedAt: Date?
    var completedAt: Date?
    var dismissedCount: Int = 0

    init() {}

    private enum CodingKeys: String, CodingKey {
        case displayCount = "display_count"
        case lastDisplayedAt = "last_displayed_at"
        case completedAt = "completed_at"
        case dismissedCount = "dismissed_count"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        displayCount = try container.decodeIfPresent(Int.self, forKey: .displayCount) ?? 0
        lastDisplayedAt = try container.decodeIfPresent(Date.self, forKey: .lastDisplayedAt)
        completedAt = try container.decodeIfPresent(Date.self, forKey: .completedAt)
        dismissedCount = try container.decodeIfPresent(Int.self, forKey: .dismissedCount) ?? 0
    }
}

/// Everything the SDK persists about this install, apart from the cached configuration and the
/// telemetry queue (protocol section 3). The state belongs to one SDK key.
struct SDKState: Codable, Equatable {
    /// The SDK key this state belongs to; a different key resets the state.
    var sdkKey: String?
    /// Random UUID v4, lower-case, created once. Never sent to the server.
    var installId: String
    var userId: String?
    var traits: [String: StoredValue] = [:]
    /// Override from `setLocale`.
    var locale: String?
    var hiddenFields: [String: String] = [:]
    var trackingEnabled = true
    var eventCounts: [String: Int] = [:]
    var surveys: [String: SurveyState] = [:]
    /// When a survey was last shown by targeting (not by `showSurvey`).
    var lastAutoDisplayAt: Date?

    init(installId: String = SDKState.makeInstallId()) {
        self.installId = installId
    }

    static func makeInstallId() -> String {
        UUID().uuidString.lowercased()
    }

    private enum CodingKeys: String, CodingKey {
        case sdkKey = "sdk_key"
        case installId = "install_id"
        case userId = "user_id"
        case traits
        case locale
        case hiddenFields = "hidden_fields"
        case trackingEnabled = "tracking_enabled"
        case eventCounts = "event_counts"
        case surveys
        case lastAutoDisplayAt = "last_auto_display_at"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sdkKey = try container.decodeIfPresent(String.self, forKey: .sdkKey)
        installId = try container.decode(String.self, forKey: .installId)
        userId = try container.decodeIfPresent(String.self, forKey: .userId)
        traits = try container.decodeIfPresent([String: StoredValue].self, forKey: .traits) ?? [:]
        locale = try container.decodeIfPresent(String.self, forKey: .locale)
        hiddenFields = try container.decodeIfPresent([String: String].self, forKey: .hiddenFields) ?? [:]
        trackingEnabled = try container.decodeIfPresent(Bool.self, forKey: .trackingEnabled) ?? true
        eventCounts = try container.decodeIfPresent([String: Int].self, forKey: .eventCounts) ?? [:]
        surveys = try container.decodeIfPresent([String: SurveyState].self, forKey: .surveys) ?? [:]
        lastAutoDisplayAt = try container.decodeIfPresent(Date.self, forKey: .lastAutoDisplayAt)
    }

    // MARK: - Mutations

    /// Clears everything except `install_id`, `tracking_enabled` and the SDK key.
    mutating func reset() {
        var fresh = SDKState(installId: installId)
        fresh.sdkKey = sdkKey
        fresh.trackingEnabled = trackingEnabled
        self = fresh
    }

    mutating func identify(userId: String, traits newTraits: [String: StoredValue?]) {
        self.userId = userId
        mergeTraits(newTraits)
    }

    /// Merges traits; a `nil` value removes the trait.
    mutating func mergeTraits(_ newTraits: [String: StoredValue?]) {
        for (name, value) in newTraits {
            if let value = value {
                traits[name] = value
            } else {
                traits.removeValue(forKey: name)
            }
        }
    }

    /// Counts one `track` call and returns the new count.
    @discardableResult
    mutating func incrementEventCount(_ name: String) -> Int {
        let count = (eventCounts[name] ?? 0) + 1
        eventCounts[name] = count
        return count
    }

    /// A survey became visible.
    mutating func recordDisplayed(surveyId: String, auto: Bool, at now: Date) {
        var survey = surveys[surveyId] ?? SurveyState()
        survey.displayCount += 1
        survey.lastDisplayedAt = now
        surveys[surveyId] = survey
        if auto {
            lastAutoDisplayAt = now
        }
    }

    /// The page reported `completed`.
    mutating func recordCompleted(surveyId: String, at now: Date) {
        var survey = surveys[surveyId] ?? SurveyState()
        survey.completedAt = now
        surveys[surveyId] = survey
    }

    /// The survey was closed before `completed`.
    mutating func recordDismissed(surveyId: String) {
        var survey = surveys[surveyId] ?? SurveyState()
        survey.dismissedCount += 1
        surveys[surveyId] = survey
    }
}
