import Foundation

/// The configuration from `GET /sdk/v1/config` (protocol section 2).
///
/// Decoding is deliberately lenient: every field except a survey's `id` and `url` may be missing
/// or of the wrong type, in which case the default applies; unknown fields are ignored.
struct SDKConfig: Equatable {
    static let minimumTTL: TimeInterval = 60

    var version: Int = 1
    var ttl: TimeInterval = 600
    var globalCooldownSeconds: TimeInterval = 0
    var surveys: [SurveyConfig] = []

    var effectiveTTL: TimeInterval {
        max(ttl, SDKConfig.minimumTTL)
    }

    func survey(id: String) -> SurveyConfig? {
        surveys.first { $0.id == id }
    }

    /// Hosts of every survey URL, lower-cased.
    var surveyHosts: [String] {
        var seen = Set<String>()
        var hosts: [String] = []
        for survey in surveys {
            guard let host = survey.url.host?.lowercased(), !host.isEmpty else { continue }
            if seen.insert(host).inserted {
                hosts.append(host)
            }
        }
        return hosts
    }

    /// `nil` when the body is not a JSON object.
    static func parse(_ data: Data) -> SDKConfig? {
        guard let json = try? JSONSerialization.jsonObject(with: data, options: []) else { return nil }
        return parse(json: json)
    }

    static func parse(json: Any) -> SDKConfig? {
        guard let object = json as? [String: Any] else { return nil }
        var config = SDKConfig()
        if let version = JSONRead.int(object["version"]) {
            config.version = version
        }
        if let ttl = JSONRead.double(object["ttl"]) {
            config.ttl = ttl
        }
        if let settings = object["settings"] as? [String: Any],
           let cooldown = JSONRead.double(settings["global_cooldown_seconds"]) {
            config.globalCooldownSeconds = max(0, cooldown)
        }
        var seen = Set<String>()
        for entry in (object["surveys"] as? [Any]) ?? [] {
            guard let survey = SurveyConfig(json: entry), seen.insert(survey.id).inserted else { continue }
            config.surveys.append(survey)
        }
        return config
    }
}

struct SurveyConfig: Equatable {
    var id: String
    var name: String = ""
    var url: URL
    var updatedAt: String?
    var triggers: [TriggerConfig] = []
    var delaySeconds: TimeInterval = 0
    var audience = AudienceConfig()
    var samplingPercent: Double = 100
    var frequency = FrequencyConfig()
    var schedule = ScheduleConfig()
    var presentation = PresentationConfig()
    var anonymous = false

    init(id: String, url: URL) {
        self.id = id
        self.url = url
    }

    /// `nil` when `id` or `url` is missing or unusable: such a survey is skipped.
    init?(json: Any) {
        guard let object = json as? [String: Any],
              let id = object["id"] as? String, !id.isEmpty,
              let urlString = object["url"] as? String,
              let url = URL(string: urlString),
              TransportSecurity.isAllowed(url)
        else { return nil }

        self.init(id: id, url: url)
        if let name = object["name"] as? String {
            self.name = name
        }
        updatedAt = object["updated_at"] as? String
        if let triggers = object["triggers"] as? [Any] {
            self.triggers = triggers.map(TriggerConfig.init(json:))
        }
        if let delay = JSONRead.double(object["delay_seconds"]) {
            delaySeconds = max(0, delay)
        }
        if let audience = object["audience"] as? [String: Any] {
            self.audience = AudienceConfig(json: audience)
        }
        if let sampling = JSONRead.double(object["sampling_percent"]) {
            samplingPercent = sampling
        }
        if let frequency = object["frequency"] as? [String: Any] {
            self.frequency = FrequencyConfig(json: frequency)
        }
        if let schedule = object["schedule"] as? [String: Any] {
            self.schedule = ScheduleConfig(json: schedule)
        }
        if let presentation = object["presentation"] as? [String: Any] {
            self.presentation = PresentationConfig(json: presentation)
        }
        if let anonymous = JSONRead.bool(object["anonymous"]) {
            self.anonymous = anonymous
        }
    }
}

enum TriggerConfig: Equatable {
    case event(name: String, minCount: Int, conditions: [Condition])
    case screen(name: String)
    case appLaunch
    /// An unknown type, or a known type without its name. Never matches.
    case unknown

    init(json: Any) {
        guard let object = json as? [String: Any], let type = object["type"] as? String else {
            self = .unknown
            return
        }
        switch type {
        case "event":
            guard let name = object["name"] as? String else {
                self = .unknown
                return
            }
            let minCount = JSONRead.int(object["min_count"]) ?? 1
            let conditions = ((object["conditions"] as? [Any]) ?? []).map {
                Condition(json: $0, nameKeys: ["property", "attribute"])
            }
            self = .event(name: name, minCount: minCount, conditions: conditions)
        case "screen":
            guard let name = object["name"] as? String else {
                self = .unknown
                return
            }
            self = .screen(name: name)
        case "app_launch":
            self = .appLaunch
        default:
            self = .unknown
        }
    }
}

struct AudienceConfig: Equatable {
    var platforms: [String] = []
    var appIds: [String] = []
    var locales: [String] = []
    /// `nil`: everybody; `true`: only identified users; `false`: only anonymous users.
    var identified: Bool?
    /// `match: "any"`. Every other value means "all".
    var matchAny = false
    var conditions: [Condition] = []

    init() {}

    init(json object: [String: Any]) {
        platforms = JSONRead.strings(object["platforms"])
        appIds = JSONRead.strings(object["app_ids"])
        locales = JSONRead.strings(object["locales"])
        identified = JSONRead.bool(object["identified"])
        matchAny = (object["match"] as? String) == "any"
        conditions = ((object["conditions"] as? [Any]) ?? []).map {
            Condition(json: $0, nameKeys: ["attribute", "property"])
        }
    }
}

enum FrequencyMode: String, Equatable {
    case once = "once"
    case untilCompleted = "until_completed"
    case recurring = "recurring"
    case always = "always"
}

struct FrequencyConfig: Equatable {
    var mode: FrequencyMode = .once
    var recontactSeconds: TimeInterval = 0
    var maxDisplays: Int?

    init() {}

    init(json object: [String: Any]) {
        // An unknown mode is treated as `once`.
        mode = (object["mode"] as? String).flatMap(FrequencyMode.init(rawValue:)) ?? .once
        recontactSeconds = max(0, JSONRead.double(object["recontact_seconds"]) ?? 0)
        maxDisplays = JSONRead.int(object["max_displays"])
    }
}

struct ScheduleConfig: Equatable {
    var startsAt: Date?
    var endsAt: Date?

    init() {}

    init(json object: [String: Any]) {
        startsAt = (object["starts_at"] as? String).flatMap(ISO8601.date(from:))
        endsAt = (object["ends_at"] as? String).flatMap(ISO8601.date(from:))
    }
}

enum PresentationSize: String, Equatable {
    case small = "small"
    case medium = "medium"
    case large = "large"
}

struct PresentationConfig: Equatable {
    static let defaultAutoCloseSeconds: TimeInterval = 3

    var style: ResponslyPresentationStyle = .bottomSheet
    var size: PresentationSize = .medium
    var dismissible = true
    /// Seconds between `completed` and the SDK closing the survey by itself; 0 = never.
    var autoCloseSeconds: TimeInterval = PresentationConfig.defaultAutoCloseSeconds

    init() {}

    init(json object: [String: Any]) {
        // An unknown style is treated as `bottom_sheet`, an unknown size as `medium`.
        style = (object["style"] as? String).flatMap(ResponslyPresentationStyle.init(rawValue:)) ?? .bottomSheet
        size = (object["size"] as? String).flatMap(PresentationSize.init(rawValue:)) ?? .medium
        dismissible = JSONRead.bool(object["dismissible"]) ?? true
        autoCloseSeconds = max(0, JSONRead.double(object["auto_close_seconds"]) ?? PresentationConfig.defaultAutoCloseSeconds)
    }
}

enum ConditionOperator: String, Equatable {
    case exists = "exists"
    case notExists = "not_exists"
    case eq = "eq"
    case neq = "neq"
    case contains = "contains"
    case notContains = "not_contains"
    case gt = "gt"
    case gte = "gte"
    case lt = "lt"
    case lte = "lte"
    /// Evaluates to false.
    case unknown = ""
}

struct Condition: Equatable {
    /// Trait or event-property name; `nil` when the entry is malformed (evaluates to false).
    var name: String?
    var op: ConditionOperator
    var value: StoredValue?

    init(name: String?, op: ConditionOperator, value: StoredValue? = nil) {
        self.name = name
        self.op = op
        self.value = value
    }

    init(json: Any, nameKeys: [String]) {
        guard let object = json as? [String: Any] else {
            self.init(name: nil, op: .unknown)
            return
        }
        let name = nameKeys.lazy.compactMap { object[$0] as? String }.first
        let op = (object["op"] as? String).flatMap(ConditionOperator.init(rawValue:)) ?? .unknown
        self.init(name: name, op: op, value: StoredValue(json: object["value"]))
    }
}

/// Type-strict readers for `JSONSerialization` values: a JSON boolean is never read as a number
/// and the other way round.
enum JSONRead {
    static func bool(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    static func double(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        return double.isFinite ? double : nil
    }

    static func int(_ value: Any?) -> Int? {
        guard let double = double(value), abs(double) < 9.0e15 else { return nil }
        return Int(double.rounded(.towardZero))
    }

    static func strings(_ value: Any?) -> [String] {
        ((value as? [Any]) ?? []).compactMap { $0 as? String }
    }

    /// A string, or the text of a number (ids may arrive either way).
    static func text(_ value: Any?) -> String? {
        if let string = value as? String {
            return string
        }
        if let stored = StoredValue(json: value), case .number = stored {
            return ConditionEvaluator.text(of: stored)
        }
        return nil
    }
}
