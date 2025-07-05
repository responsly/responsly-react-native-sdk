import Foundation

/// How much the SDK writes to the system log. Every line is prefixed `[Responsly]`.
public enum ResponslyLogLevel: Int, Comparable, CaseIterable, Sendable {
    case none = 0
    case error = 1
    case info = 2
    case debug = 3

    public static func < (lhs: ResponslyLogLevel, rhs: ResponslyLogLevel) -> Bool {
        lhs.rawValue < rhs.rawValue
    }

    /// The protocol name of the level: `none`, `error`, `info` or `debug`.
    public var name: String {
        switch self {
        case .none: return "none"
        case .error: return "error"
        case .info: return "info"
        case .debug: return "debug"
        }
    }

    /// Creates a level from its protocol name (`none`, `error`, `info`, `debug`).
    public init?(name: String) {
        guard let level = ResponslyLogLevel.allCases.first(where: { $0.name == name.lowercased() }) else {
            return nil
        }
        self = level
    }
}

/// The container a survey is presented in.
public enum ResponslyPresentationStyle: String, CaseIterable, Sendable {
    case bottomSheet = "bottom_sheet"
    case dialog = "dialog"
    case fullscreen = "fullscreen"
}

/// Options for `Responsly.initialize(sdkKey:options:)`.
public struct ResponslyOptions: Equatable, Sendable {
    /// `https://api.responsly.com`
    public static let defaultAPIHost = URL(string: "https://api.responsly.com")!

    /// Base URL of the Responsly API.
    public var apiHost: URL
    public var logLevel: ResponslyLogLevel

    public init(apiHost: URL = ResponslyOptions.defaultAPIHost, logLevel: ResponslyLogLevel = .error) {
        self.apiHost = apiHost
        self.logLevel = logLevel
    }
}

/// Options for `Responsly.showSurvey(_:options:)`.
public struct ResponslyShowOptions: Equatable, Sendable {
    /// Skips targeting checks (audience, schedule, frequency) and the display guard.
    /// A survey is still not shown while tracking is disabled, when the id is unknown,
    /// or while another survey is visible.
    public var force: Bool
    /// Hidden-field values for this survey only; merged over `setHiddenFields`.
    public var hiddenFields: [String: String]
    /// Language tag for this survey only, e.g. `pl` or `pt-BR`.
    public var locale: String?
    /// Overrides the presentation style from the survey's configuration.
    public var presentation: ResponslyPresentationStyle?

    public init(
        force: Bool = false,
        hiddenFields: [String: String] = [:],
        locale: String? = nil,
        presentation: ResponslyPresentationStyle? = nil
    ) {
        self.force = force
        self.hiddenFields = hiddenFields
        self.locale = locale
        self.presentation = presentation
    }
}
