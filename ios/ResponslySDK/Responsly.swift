import Foundation

/// The entry point of the Responsly SDK: shows in-app surveys, targeted on the device.
///
/// ```swift
/// Responsly.shared.initialize(sdkKey: "rsk_…")
/// Responsly.shared.identify(userId: "u_123", traits: ["plan": "pro"])
/// Responsly.shared.track("purchase_completed", properties: ["value": 129])
/// ```
///
/// Every method is safe to call from any thread and before `initialize`: such calls are
/// remembered and applied when the SDK is initialised. Listener callbacks and the display guard
/// are called on the main thread.
public final class Responsly: @unchecked Sendable {
    public static let shared = Responsly()

    /// The version of this SDK.
    public static let sdkVersion: String = "0.9.3"

    private let core: ResponslyCore
    private let lifecycleObserver: AnyObject?

    private init() {
        let core = ResponslyCore(environment: .live())
        self.core = core
        #if canImport(UIKit) && canImport(WebKit)
        self.lifecycleObserver = AppLifecycleObserver(core: core)
        #else
        self.lifecycleObserver = nil
        #endif
    }

    /// Starts the SDK: loads the cached configuration, fetches a fresh one when needed and applies
    /// every call made so far. Calling it again with the same key does nothing; a different key
    /// resets the local state first.
    ///
    /// - Parameter sdkKey: the team's public SDK key (`rsk_…`).
    public func initialize(sdkKey: String, options: ResponslyOptions = ResponslyOptions()) {
        core.initialize(sdkKey: sdkKey, options: options)
    }

    /// Sets the user id and merges traits. A `nil` value removes a trait.
    public func identify(userId: String, traits: [String: ResponslyValue?] = [:]) {
        core.identify(userId: userId, traits: traits)
    }

    /// Merges traits. A `nil` value removes a trait.
    public func setAttributes(_ traits: [String: ResponslyValue?]) {
        core.setAttributes(traits)
    }

    /// Counts the event and evaluates event triggers.
    public func track(_ event: String, properties: [String: ResponslyValue] = [:]) {
        core.track(event, properties: properties)
    }

    /// Sets the current screen and evaluates screen triggers.
    public func screen(_ name: String) {
        core.screen(name)
    }

    /// Shows a survey by id. The outcome is reported to listeners: `surveyShown`, or
    /// `surveyNotShown` with the reason.
    public func showSurvey(_ surveyId: String, options: ResponslyShowOptions = ResponslyShowOptions()) {
        core.showSurvey(surveyId, options: options)
    }

    /// Closes the visible survey; closing it before it is completed counts as dismissed.
    /// A survey that is not on screen yet (waiting for its delay or still loading) is cancelled
    /// silently: no callback, because it was never shown.
    public func closeSurvey() {
        core.closeSurvey()
    }

    /// Stops targeting. `showSurvey` still works.
    public func pause() {
        core.pause()
    }

    /// Resumes targeting after `pause()`.
    public func resume() {
        core.resume()
    }

    /// Overrides the locale used for targeting and for the survey's language. `nil` returns to
    /// the device's preferred language.
    public func setLocale(_ languageTag: String?) {
        core.setLocale(languageTag)
    }

    /// Hidden-field values sent with every survey. Replaces the previous map.
    public func setHiddenFields(_ fields: [String: String]) {
        core.setHiddenFields(fields)
    }

    /// `false`: no network, no targeting, nothing shown. The setting is persisted.
    public func setTrackingEnabled(_ enabled: Bool) {
        core.setTrackingEnabled(enabled)
    }

    /// Clears the user, traits, counters and cached configuration (for example on sign-out),
    /// clears the survey pages' stored website data and closes a visible survey. The SDK stays
    /// initialised; the install id and the tracking setting are kept.
    public func reset() {
        core.reset()
    }

    /// A last say right before a survey becomes visible. When the survey has loaded, the guard is
    /// called on the main thread with the survey and a `decide` closure: `decide(false)` vetoes
    /// it (`surveyNotShown` with `vetoed`). No answer within 2 seconds counts as "allow".
    /// Not asked for `showSurvey(…, force: true)`.
    public func setDisplayGuard(_ `guard`: ((ResponslySurvey, @escaping (Bool) -> Void) -> Void)?) {
        core.setDisplayGuard(`guard`)
    }

    /// Registers a listener. The SDK keeps it until `removeListener(_:)`.
    @discardableResult
    public func addListener(_ listener: ResponslyListener) -> ResponslyListenerToken {
        core.addListener(listener)
    }

    public func removeListener(_ token: ResponslyListenerToken) {
        core.removeListener(token)
    }
}

extension ResponslyCore.Environment {
    /// The real app, device and network.
    static func live() -> ResponslyCore.Environment {
        let bundle = Bundle.main
        let version = ProcessInfo.processInfo.operatingSystemVersion
        var osVersion = "\(version.majorVersion).\(version.minorVersion)"
        if version.patchVersion > 0 {
            osVersion += ".\(version.patchVersion)"
        }

        #if canImport(UIKit) && canImport(WebKit)
        let presenter: SurveyPresenting = UIKitSurveyPresenter()
        #else
        let presenter: SurveyPresenting = UnavailableSurveyPresenter()
        #endif

        return ResponslyCore.Environment(
            platform: "ios",
            sdkVersion: Responsly.sdkVersion,
            appId: bundle.bundleIdentifier ?? "",
            appVersion: (bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "",
            osVersion: osVersion,
            deviceLocale: { Locale.preferredLanguages.first ?? "en" },
            now: { Date() },
            storage: UserDefaultsStorage(),
            transport: URLSessionTransport(),
            presenter: presenter,
            scheduler: nil,
            onMain: { block in DispatchQueue.main.async(execute: block) },
            logSink: nil
        )
    }
}
