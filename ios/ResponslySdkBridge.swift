import Foundation

/// The Swift half of the React Native module: translates the loosely typed calls of
/// `ResponslySdk.mm` into the API of the Responsly iOS SDK (the sources in `ResponslySDK/`,
/// compiled into this same module) and turns its callbacks into events for JavaScript.
///
/// One instance belongs to one module instance. `invalidate()` detaches it from the SDK, which
/// itself lives as long as the process: a JavaScript reload must not leave a listener or a
/// display guard behind that talks to a runtime that is gone.
@objc(ResponslySdkBridge)
public final class ResponslySdkBridge: NSObject {
    /// Listener callbacks: `{ type, payload }`, where `payload` is the event's `toDictionary()`.
    @objc public static let eventName = "ResponslyEvent"
    /// A question from the display guard: `{ requestId, survey }`.
    @objc public static let displayGuardEventName = "ResponslyDisplayGuard"

    private let emit: (String, [String: Any]) -> Void
    private let lock = NSLock()
    private var listenerToken: ResponslyListenerToken?
    private var observing = false
    private var displayGuardInstalled = false
    private var pendingDecisions: [String: (Bool) -> Void] = [:]

    /// - Parameter emitter: sends an event to JavaScript; called on the main thread.
    @objc public init(emitter: @escaping (String, [String: Any]) -> Void) {
        self.emit = emitter
        super.init()
        listenerToken = Responsly.shared.addListener(ResponslyListenerBlock(
            surveyShown: { [weak self] event in self?.send("surveyShown", event.toDictionary()) },
            surveyNotShown: { [weak self] event in self?.send("surveyNotShown", event.toDictionary()) },
            questionAnswered: { [weak self] event in self?.send("questionAnswered", event.toDictionary()) },
            surveyCompleted: { [weak self] event in self?.send("surveyCompleted", event.toDictionary()) },
            surveyDismissed: { [weak self] event in self?.send("surveyDismissed", event.toDictionary()) },
            surveyError: { [weak self] event in self?.send("surveyError", event.toDictionary()) }
        ))
    }

    deinit {
        invalidate()
    }

    /// Whether JavaScript is subscribed to the module's events.
    @objc public func setObserving(_ observing: Bool) {
        lock.lock()
        self.observing = observing
        lock.unlock()
    }

    /// Detaches from the SDK. Safe to call more than once.
    @objc public func invalidate() {
        lock.lock()
        let token = listenerToken
        listenerToken = nil
        let hadDisplayGuard = displayGuardInstalled
        displayGuardInstalled = false
        observing = false
        let decisions = pendingDecisions
        pendingDecisions = [:]
        lock.unlock()

        if let token = token {
            Responsly.shared.removeListener(token)
        }
        if hadDisplayGuard {
            Responsly.shared.setDisplayGuard(nil)
        }
        decisions.values.forEach { $0(true) }
    }

    // MARK: - Calls

    @objc public func initialize(_ sdkKey: String, options: [String: Any]) {
        var sdkOptions = ResponslyOptions()
        if let apiHost = options["apiHost"] as? String, let url = URL(string: apiHost), url.scheme != nil {
            sdkOptions.apiHost = url
        }
        if let name = options["logLevel"] as? String, let logLevel = ResponslyLogLevel(name: name) {
            sdkOptions.logLevel = logLevel
        }
        Responsly.shared.initialize(sdkKey: sdkKey, options: sdkOptions)
    }

    @objc public func identify(_ userId: String, traits: [String: Any], removed: [String]) {
        Responsly.shared.identify(userId: userId, traits: Self.traits(traits, removed: removed))
    }

    @objc public func setAttributes(_ traits: [String: Any], removed: [String]) {
        Responsly.shared.setAttributes(Self.traits(traits, removed: removed))
    }

    @objc public func track(_ event: String, properties: [String: Any]) {
        Responsly.shared.track(event, properties: ResponslyValue.properties(from: properties))
    }

    @objc public func screen(_ name: String) {
        Responsly.shared.screen(name)
    }

    @objc public func showSurvey(_ surveyId: String, options: [String: Any]) {
        var showOptions = ResponslyShowOptions()
        if let force = options["force"] as? Bool {
            showOptions.force = force
        }
        if let hiddenFields = options["hiddenFields"] as? [String: Any] {
            showOptions.hiddenFields = Self.strings(hiddenFields)
        }
        if let locale = options["locale"] as? String, !locale.isEmpty {
            showOptions.locale = locale
        }
        if let presentation = options["presentation"] as? String {
            showOptions.presentation = ResponslyPresentationStyle(rawValue: presentation)
        }
        Responsly.shared.showSurvey(surveyId, options: showOptions)
    }

    @objc public func closeSurvey() {
        Responsly.shared.closeSurvey()
    }

    @objc public func pause() {
        Responsly.shared.pause()
    }

    @objc public func resume() {
        Responsly.shared.resume()
    }

    @objc public func setLocale(_ locale: String?) {
        Responsly.shared.setLocale(locale)
    }

    @objc public func setHiddenFields(_ fields: [String: Any]) {
        Responsly.shared.setHiddenFields(Self.strings(fields))
    }

    @objc public func setTrackingEnabled(_ enabled: Bool) {
        Responsly.shared.setTrackingEnabled(enabled)
    }

    @objc public func reset() {
        Responsly.shared.reset()
    }

    // MARK: - Display guard

    /// Installs or removes the guard that forwards the question to JavaScript.
    @objc public func setDisplayGuardEnabled(_ enabled: Bool) {
        lock.lock()
        displayGuardInstalled = enabled
        let decisions = enabled ? [:] : pendingDecisions
        if !enabled {
            pendingDecisions = [:]
        }
        lock.unlock()

        if enabled {
            Responsly.shared.setDisplayGuard { [weak self] survey, decide in
                guard let self = self else {
                    decide(true)
                    return
                }
                self.askJavaScript(survey, decide: decide)
            }
        } else {
            Responsly.shared.setDisplayGuard(nil)
            decisions.values.forEach { $0(true) }
        }
    }

    /// The answer of the JavaScript guard. An answer that comes after the SDK's own 2-second
    /// limit finds nothing to decide and is ignored by the SDK.
    @objc public func resolveDisplayGuard(_ requestId: String, allow: Bool) {
        lock.lock()
        let decide = pendingDecisions.removeValue(forKey: requestId)
        lock.unlock()
        decide?(allow)
    }

    private func askJavaScript(_ survey: ResponslySurvey, decide: @escaping (Bool) -> Void) {
        let requestId = UUID().uuidString
        lock.lock()
        let canAsk = observing && displayGuardInstalled
        if canAsk {
            // One survey is decided at a time; whatever is still here was never answered.
            pendingDecisions = [requestId: decide]
        }
        lock.unlock()

        guard canAsk else {
            // Nobody is listening on the JavaScript side.
            decide(true)
            return
        }
        emit(Self.displayGuardEventName, ["requestId": requestId, "survey": survey.toDictionary()])
    }

    // MARK: - Helpers

    private func send(_ type: String, _ payload: [String: Any]) {
        lock.lock()
        let observing = self.observing
        lock.unlock()
        guard observing else { return }
        emit(Self.eventName, ["type": type, "payload": payload])
    }

    /// `values` holds strings, numbers and booleans; `removed` lists the traits to remove.
    private static func traits(_ values: [String: Any], removed: [String]) -> [String: ResponslyValue?] {
        var traits = ResponslyValue.traits(from: values)
        for key in removed {
            traits.updateValue(nil, forKey: key)
        }
        return traits
    }

    private static func strings(_ dictionary: [String: Any]) -> [String: String] {
        var result: [String: String] = [:]
        for (key, value) in dictionary {
            if let string = value as? String {
                result[key] = string
            }
        }
        return result
    }
}
