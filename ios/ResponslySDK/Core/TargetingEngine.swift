import Foundation

/// What the engine needs to know about the app and the install.
struct TargetingContext: Equatable {
    /// `ios`
    var platform: String
    /// Bundle identifier.
    var appId: String
    /// The device's preferred language as a BCP 47 tag; used when there is no `setLocale` override.
    var deviceLocale: String
    var installId: String
}

/// In-memory conditions that are not part of the persisted state.
struct TargetingFlags: Equatable {
    var paused = false
    /// A survey is visible or waiting for its delay.
    var surveyActive = false
}

/// A call that can match triggers (protocol section 4.1).
enum TriggerCall: Equatable {
    /// `count` is `event_counts[name]` after this call was counted.
    case event(name: String, properties: [String: StoredValue], count: Int)
    case screen(name: String)
    case appLaunch

    var publicTrigger: ResponslyTrigger {
        switch self {
        case .event(let name, _, _): return ResponslyTrigger(type: .event, name: name)
        case .screen(let name): return ResponslyTrigger(type: .screen, name: name)
        case .appLaunch: return ResponslyTrigger(type: .appLaunch)
        }
    }
}

struct TargetingDecision: Equatable {
    struct NotShown: Equatable {
        let surveyId: String
        let reason: ResponslyNotShownReason
    }

    /// The survey to show, if any.
    var selected: SurveyConfig?
    /// Every matched survey that was not selected, in configuration order.
    var notShown: [NotShown] = []
}

/// Targeting (protocol section 4). A pure function of configuration, state, context and time:
/// it keeps no state and reads no clock.
enum TargetingEngine {
    enum Mode: Equatable {
        /// Selected by a trigger.
        case targeting
        /// Requested with `showSurvey`.
        case manual(force: Bool)
    }

    // MARK: - Decisions

    /// Walks the surveys in configuration order, keeps those with a matching trigger and selects
    /// the first eligible one.
    static func evaluate(
        trigger: TriggerCall,
        config: SDKConfig,
        state: SDKState,
        flags: TargetingFlags,
        context: TargetingContext,
        now: Date
    ) -> TargetingDecision {
        var decision = TargetingDecision()
        for survey in config.surveys where matches(survey: survey, trigger: trigger) {
            let reason = ineligibilityReason(
                for: survey, mode: .targeting, config: config, state: state,
                flags: flags, context: context, now: now
            )
            if let reason = reason {
                decision.notShown.append(.init(surveyId: survey.id, reason: reason))
            } else if decision.selected == nil {
                decision.selected = survey
            } else {
                decision.notShown.append(.init(surveyId: survey.id, reason: .anotherSurveySelected))
            }
        }
        return decision
    }

    /// `showSurvey(surveyId, force:)`.
    static func evaluateManual(
        surveyId: String,
        force: Bool,
        config: SDKConfig,
        state: SDKState,
        flags: TargetingFlags,
        context: TargetingContext,
        now: Date
    ) -> TargetingDecision {
        var decision = TargetingDecision()
        // Check 1 comes before the lookup (check 2).
        guard state.trackingEnabled else {
            decision.notShown.append(.init(surveyId: surveyId, reason: .disabled))
            return decision
        }
        guard let survey = config.survey(id: surveyId) else {
            decision.notShown.append(.init(surveyId: surveyId, reason: .surveyNotFound))
            return decision
        }
        let reason = ineligibilityReason(
            for: survey, mode: .manual(force: force), config: config, state: state,
            flags: flags, context: context, now: now
        )
        if let reason = reason {
            decision.notShown.append(.init(surveyId: surveyId, reason: reason))
        } else {
            decision.selected = survey
        }
        return decision
    }

    // MARK: - Triggers

    static func matches(survey: SurveyConfig, trigger call: TriggerCall) -> Bool {
        survey.triggers.contains { matches(trigger: $0, call: call) }
    }

    static func matches(trigger: TriggerConfig, call: TriggerCall) -> Bool {
        switch (trigger, call) {
        case (.event(let name, let minCount, let conditions), .event(let calledName, let properties, let count)):
            return name == calledName
                && count >= minCount
                && conditions.allSatisfy { ConditionEvaluator.evaluate($0, against: properties) }
        case (.screen(let name), .screen(let calledName)):
            return name == calledName
        case (.appLaunch, .appLaunch):
            return true
        default:
            return false
        }
    }

    // MARK: - Eligibility

    /// The checks of protocol section 4.2, in order. `nil` means eligible.
    /// (Check 2, "the survey id exists", is done by `evaluateManual`.)
    static func ineligibilityReason(
        for survey: SurveyConfig,
        mode: Mode,
        config: SDKConfig,
        state: SDKState,
        flags: TargetingFlags,
        context: TargetingContext,
        now: Date
    ) -> ResponslyNotShownReason? {
        // 1
        guard state.trackingEnabled else { return .disabled }
        // 3
        if flags.surveyActive { return .anotherSurveyVisible }
        // 4
        if mode == .manual(force: true) { return nil }
        // 5
        if mode == .targeting, flags.paused { return .paused }
        // 6
        if let startsAt = survey.schedule.startsAt, now < startsAt { return .outsideSchedule }
        if let endsAt = survey.schedule.endsAt, now >= endsAt { return .outsideSchedule }
        // 7
        let audience = survey.audience
        if !audience.platforms.isEmpty, !audience.platforms.contains(context.platform) {
            return .platformMismatch
        }
        // 8
        if !audience.appIds.isEmpty, !audience.appIds.contains(context.appId) {
            return .appMismatch
        }
        // 9
        if !audience.locales.isEmpty {
            let locale = effectiveLocale(state: state, context: context)
            if !LocaleMatcher.matches(entries: audience.locales, tag: locale) {
                return .localeMismatch
            }
        }
        // 10
        if let identified = audience.identified, identified != (state.userId != nil) {
            return .audienceMismatch
        }
        // 11
        if !audience.conditions.isEmpty {
            let matched: Bool
            if audience.matchAny {
                matched = audience.conditions.contains { ConditionEvaluator.evaluate($0, against: state.traits) }
            } else {
                matched = audience.conditions.allSatisfy { ConditionEvaluator.evaluate($0, against: state.traits) }
            }
            if !matched { return .audienceMismatch }
        }
        // 12
        if let reason = frequencyReason(survey.frequency, state: state.surveys[survey.id] ?? SurveyState(), now: now) {
            return reason
        }
        guard mode == .targeting else { return nil }
        // 13
        if config.globalCooldownSeconds > 0, let last = state.lastAutoDisplayAt,
           now.timeIntervalSince(last) < config.globalCooldownSeconds {
            return .globalCooldown
        }
        // 14
        let bucket = samplingBucket(installId: context.installId, surveyId: survey.id)
        if !(Double(bucket) < survey.samplingPercent) {
            return .sampledOut
        }
        return nil
    }

    static func effectiveLocale(state: SDKState, context: TargetingContext) -> String {
        state.locale ?? context.deviceLocale
    }

    static func frequencyReason(_ frequency: FrequencyConfig, state: SurveyState, now: Date) -> ResponslyNotShownReason? {
        let done = state.completedAt != nil
        let underLimit = frequency.maxDisplays.map { state.displayCount < $0 } ?? true
        let recontactPassed = state.lastDisplayedAt.map { now.timeIntervalSince($0) >= frequency.recontactSeconds } ?? true

        switch frequency.mode {
        case .once:
            if done { return .alreadyCompleted }
            if state.displayCount != 0 { return .frequencyLimit }
        case .untilCompleted:
            if done { return .alreadyCompleted }
            if !underLimit { return .frequencyLimit }
            if !recontactPassed { return .recontactWait }
        case .recurring:
            if !underLimit { return .frequencyLimit }
            if !recontactPassed { return .recontactWait }
        case .always:
            if !underLimit { return .frequencyLimit }
        }
        return nil
    }

    /// `fnv1a32(utf8(install_id + ":" + survey_id)) mod 100`: stable for an install.
    static func samplingBucket(installId: String, surveyId: String) -> Int {
        Int(FNV1a.hash32(installId + ":" + surveyId) % 100)
    }
}
