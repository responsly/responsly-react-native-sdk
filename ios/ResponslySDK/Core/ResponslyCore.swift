import Foundation

/// The SDK's behaviour, free of UIKit: state, configuration, targeting, the survey session and
/// telemetry. Everything it touches from the outside world comes in through `Environment`.
///
/// All mutable state is confined to one serial queue. Public calls hop onto it, so they are safe
/// from any thread and keep their order; listeners and the display guard are called through
/// `Environment.onMain`.
final class ResponslyCore: @unchecked Sendable {
    typealias DisplayGuard = (ResponslySurvey, @escaping (Bool) -> Void) -> Void

    struct Environment {
        var platform: String
        var sdkVersion: String
        /// Bundle identifier.
        var appId: String
        var appVersion: String
        var osVersion: String
        /// The device's preferred language as a BCP 47 tag.
        var deviceLocale: () -> String
        var now: () -> Date
        var storage: KeyValueStorage
        var transport: HTTPTransport
        var presenter: SurveyPresenting
        /// `nil`: timers on the core queue.
        var scheduler: Scheduler?
        /// Runs a block on the main thread.
        var onMain: (@escaping () -> Void) -> Void
        /// Receives log lines instead of the unified log (tests).
        var logSink: ((String) -> Void)?
    }

    /// `ready` must arrive within this many seconds of the load starting.
    static let loadTimeout: TimeInterval = 15
    /// No answer from the display guard within this many seconds counts as "allow".
    static let displayGuardTimeout: TimeInterval = 2
    /// Trigger calls kept while the configuration is not available yet; the oldest are dropped.
    static let maxPendingCalls = 50
    /// Calls remembered before `initialize`; the oldest are dropped.
    static let maxPreInitCalls = 1000

    private enum PendingCall {
        case trigger(TriggerCall)
        case manual(surveyId: String, options: ResponslyShowOptions)
    }

    private let env: Environment
    private let queue: DispatchQueue
    private let logger: SDKLogger
    private let store: StateStore
    private let scheduler: Scheduler
    private let listeners = ListenerRegistry()

    // Everything below is confined to `queue`.
    private var sdkKey: String?
    private var client: APIClient?
    private var telemetry: TelemetryQueue?
    /// `nil` until `initialize`.
    private var state: SDKState?
    private var config: SDKConfig?
    private var configETag: String?
    private var configFetchedAt: Date?
    /// The server answered 401: nothing is shown and nothing is fetched until the next launch.
    private var configBlocked = false
    private var fetchInFlight = false
    private var fetchGeneration = 0
    private var appLaunchEvaluated = false
    private var replayingPreInitCalls = false
    private var preInitCalls: [() -> Void] = []
    private var pendingCalls: [PendingCall] = []
    private var paused = false
    private var currentScreen: String?
    private var displayGuard: DisplayGuard?
    private var session: SurveySession?

    init(environment: Environment) {
        let queue = DispatchQueue(label: "com.responsly.sdk.core")
        self.env = environment
        self.queue = queue
        self.logger = SDKLogger(sink: environment.logSink)
        self.store = StateStore(storage: environment.storage)
        self.scheduler = QueueScheduler(base: environment.scheduler ?? DispatchScheduler(queue: queue), queue: queue)
    }

    // MARK: - Public surface (any thread)

    func initialize(sdkKey: String, options: ResponslyOptions) {
        queue.async { self.onInitialize(sdkKey: sdkKey, options: options) }
    }

    func identify(userId: String, traits: [String: ResponslyValue?]) {
        whenInitialized { $0.onIdentify(userId: userId, traits: traits) }
    }

    func setAttributes(_ traits: [String: ResponslyValue?]) {
        whenInitialized { $0.onSetAttributes(traits) }
    }

    func track(_ event: String, properties: [String: ResponslyValue]) {
        whenInitialized { $0.onTrack(event, properties: properties) }
    }

    func screen(_ name: String) {
        whenInitialized { $0.onScreen(name) }
    }

    func showSurvey(_ surveyId: String, options: ResponslyShowOptions) {
        queue.async { self.onShowSurvey(surveyId, options: options) }
    }

    func closeSurvey() {
        queue.async {
            guard let session = self.session else { return }
            self.logger.debug("closeSurvey: closing survey \(session.survey.id)")
            self.endSession(session)
        }
    }

    func pause() {
        queue.async {
            self.paused = true
            self.logger.debug("targeting paused")
        }
    }

    func resume() {
        queue.async {
            self.paused = false
            self.logger.debug("targeting resumed")
        }
    }

    func setLocale(_ languageTag: String?) {
        whenInitialized { $0.onSetLocale(languageTag) }
    }

    func setHiddenFields(_ fields: [String: String]) {
        whenInitialized { $0.onSetHiddenFields(fields) }
    }

    func setTrackingEnabled(_ enabled: Bool) {
        whenInitialized { $0.onSetTrackingEnabled(enabled) }
    }

    func reset() {
        whenInitialized { $0.onReset() }
    }

    func setDisplayGuard(_ displayGuard: DisplayGuard?) {
        queue.async { self.displayGuard = displayGuard }
    }

    func addListener(_ listener: ResponslyListener) -> ResponslyListenerToken {
        listeners.add(listener)
    }

    func removeListener(_ token: ResponslyListenerToken) {
        listeners.remove(token)
    }

    // MARK: - App lifecycle

    /// The app returned to the foreground: refresh a stale configuration.
    func applicationWillEnterForeground() {
        queue.async {
            guard self.state != nil else { return }
            self.refreshConfigIfNeeded()
            self.telemetry?.scheduleFlushIfNeeded()
        }
    }

    /// The app went to the background: flush telemetry. `completion` may be called on any thread.
    func applicationDidEnterBackground(completion: @escaping () -> Void) {
        queue.async {
            guard let telemetry = self.telemetry else {
                completion()
                return
            }
            telemetry.flush(completion: completion)
        }
    }

    /// Blocks until everything queued so far has run (tests).
    func waitUntilIdle() {
        queue.sync {}
    }

    // MARK: - Initialisation

    /// Runs the call now when the SDK is initialised, otherwise remembers it for `initialize`.
    private func whenInitialized(_ call: @escaping (ResponslyCore) -> Void) {
        queue.async {
            if self.state != nil {
                call(self)
                return
            }
            self.preInitCalls.append { [unowned self] in call(self) }
            if self.preInitCalls.count > ResponslyCore.maxPreInitCalls {
                self.preInitCalls.removeFirst(self.preInitCalls.count - ResponslyCore.maxPreInitCalls)
            }
        }
    }

    private func onInitialize(sdkKey rawKey: String, options: ResponslyOptions) {
        logger.level = options.logLevel
        let key = rawKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else {
            logger.error("initialize: the SDK key is empty")
            return
        }

        if let currentKey = sdkKey {
            if currentKey == key {
                guard let client = client, client.apiHost != options.apiHost else {
                    logger.debug("initialize: already initialised with this SDK key")
                    return
                }
                logger.info("initialize: API host changed to \(options.apiHost.absoluteString), fetching the configuration again")
                if let session = session, session.phase != .visible {
                    endSession(session)
                }
                makeClient(key: key, apiHost: options.apiHost)
                dropConfig()
                configBlocked = false
                appLaunchEvaluated = false
                refreshConfigIfNeeded()
                return
            }
            // A different key resets state first.
            logger.info("initialize: a different SDK key, resetting state")
            if let session = session {
                endSession(session)
            }
            failPendingManualCalls(.configUnavailable)
            pendingCalls.removeAll()
            telemetry?.suspend()
            telemetry = nil
            dropConfig(keepStored: true)
            configBlocked = false
            appLaunchEvaluated = false
        }

        var loaded = store.loadState()
        if let storedKey = loaded.sdkKey, storedKey != key {
            // The state on disk belongs to another SDK key.
            let hosts = store.loadCachedConfig()?.config.surveyHosts ?? []
            env.presenter.clearWebsiteData(forHosts: hosts)
            loaded.reset()
            store.clearCachedConfig()
            store.saveTelemetry([])
        }
        loaded.sdkKey = key
        store.saveState(loaded)
        state = loaded
        sdkKey = key
        makeClient(key: key, apiHost: options.apiHost)
        telemetry = makeTelemetryQueue()
        logger.info("initialized, SDK \(env.sdkVersion), API host \(options.apiHost.absoluteString)")

        // Apply what was called before `initialize`, in order. Trigger calls wait for the
        // configuration so that they are evaluated after `app_launch`.
        replayingPreInitCalls = true
        let calls = preInitCalls
        preInitCalls = []
        calls.forEach { $0() }
        replayingPreInitCalls = false

        if let cached = store.loadCachedConfig(), cached.apiHost == options.apiHost.absoluteString {
            config = cached.config
            configETag = cached.etag
            configFetchedAt = cached.fetchedAt
            logger.debug("config: using the cached copy (\(cached.config.surveys.count) surveys)")
        }
        if config != nil {
            configBecameAvailable()
        } else if state?.trackingEnabled == false {
            // No network while tracking is disabled, so the configuration will not arrive.
            pendingCalls.removeAll()
        }
        refreshConfigIfNeeded()
        telemetry?.scheduleFlushIfNeeded()
    }

    private func makeClient(key: String, apiHost: URL) {
        client = APIClient(
            apiHost: apiHost, sdkKey: key, platform: env.platform,
            sdkVersion: env.sdkVersion, appId: env.appId, transport: env.transport
        )
    }

    private func makeTelemetryQueue() -> TelemetryQueue {
        TelemetryQueue(
            store: store,
            scheduler: scheduler,
            isEnabled: { [weak self] in self?.state?.trackingEnabled ?? false },
            send: { [weak self] events, completion in
                guard let self = self, let client = self.client else {
                    completion(.retry)
                    return
                }
                self.logger.debug("telemetry: sending \(events.count) event(s)")
                client.sendEvents(events) { result in
                    self.queue.async {
                        self.logger.debug("telemetry: \(result)")
                        completion(result)
                    }
                }
            }
        )
    }

    // MARK: - Configuration

    private func refreshConfigIfNeeded() {
        guard let state = state, let client = client else { return }
        guard state.trackingEnabled, !configBlocked, !fetchInFlight else { return }
        let now = env.now()
        if let config = config, let fetchedAt = configFetchedAt {
            let age = now.timeIntervalSince(fetchedAt)
            if age >= 0 && age < config.effectiveTTL {
                return
            }
        }
        fetchInFlight = true
        let generation = fetchGeneration
        logger.debug("config: fetching from \(client.apiHost.absoluteString)")
        client.fetchConfig(etag: config != nil ? configETag : nil) { [weak self] result in
            guard let self = self else { return }
            self.queue.async {
                self.onConfigFetched(result, generation: generation, apiHost: client.apiHost)
            }
        }
    }

    private func onConfigFetched(_ result: ConfigFetchResult, generation: Int, apiHost: URL) {
        guard generation == fetchGeneration else { return }
        fetchInFlight = false
        let now = env.now()

        switch result {
        case .fetched(let newConfig, let body, let etag):
            logger.debug("config: 200, \(newConfig.surveys.count) survey(s), ttl \(Int(newConfig.effectiveTTL)) s")
            config = newConfig
            configETag = etag
            configFetchedAt = now
            store.saveCachedConfig(CachedConfig(
                body: body, etag: etag, fetchedAt: now, apiHost: apiHost.absoluteString, config: newConfig
            ))
            configBecameAvailable()

        case .notModified:
            guard config != nil else {
                logger.info("config: 304 without a cached copy")
                failPendingManualCalls(.configUnavailable)
                return
            }
            logger.debug("config: 304, keeping the cached copy")
            configFetchedAt = now
            store.touchCachedConfig(fetchedAt: now)
            configBecameAvailable()

        case .invalidKey:
            logger.error("config: 401 invalid SDK key; nothing will be shown until the next launch")
            configBlocked = true
            dropConfig()
            failPendingManualCalls(.configUnavailable)
            pendingCalls.removeAll()
            if let session = session, session.phase != .visible {
                cancelBeforeVisible(session, reason: .configUnavailable)
            }

        case .failed(let message):
            logger.info("config: fetch failed (\(message)); will retry on the next foreground")
            if config == nil {
                // Trigger calls stay queued for the retry; an explicit request gets its answer now.
                failPendingManualCalls(.configUnavailable)
            } else {
                // The cached copy is all there is: answer what was waiting for the refresh.
                configBecameAvailable()
            }
        }
    }

    /// Forgets the configuration and whatever fetch is in flight.
    private func dropConfig(keepStored: Bool = false) {
        config = nil
        configETag = nil
        configFetchedAt = nil
        fetchGeneration += 1
        fetchInFlight = false
        if !keepStored {
            store.clearCachedConfig()
        }
    }

    /// The configuration is there (from the cache or the network): evaluate `app_launch` once per
    /// `initialize`, then the calls that were waiting, in order.
    private func configBecameAvailable() {
        if !appLaunchEvaluated {
            appLaunchEvaluated = true
            evaluate(.appLaunch)
        }
        let calls = pendingCalls
        pendingCalls = []
        for call in calls {
            switch call {
            case .trigger(let trigger):
                evaluate(trigger)
            case .manual(let surveyId, let options):
                evaluateManual(surveyId, options: options)
            }
        }
    }

    private func enqueuePending(_ call: PendingCall) {
        pendingCalls.append(call)
        while pendingCalls.count > ResponslyCore.maxPendingCalls {
            let dropped = pendingCalls.removeFirst()
            if case .manual(let surveyId, _) = dropped {
                reportNotShown(surveyId, .configUnavailable)
            }
        }
    }

    private func failPendingManualCalls(_ reason: ResponslyNotShownReason) {
        let calls = pendingCalls
        pendingCalls = []
        for call in calls {
            switch call {
            case .manual(let surveyId, _):
                reportNotShown(surveyId, reason)
            case .trigger:
                pendingCalls.append(call)
            }
        }
    }

    // MARK: - Identity and settings

    private func onIdentify(userId: String, traits: [String: ResponslyValue?]) {
        guard var current = state else { return }
        guard !userId.isEmpty else {
            logger.error("identify: the user id is empty")
            return
        }
        current.identify(userId: userId, traits: storedTraits(traits))
        commit(current)
        logger.debug("identify: user id set, \(traits.count) trait(s) merged")
    }

    private func onSetAttributes(_ traits: [String: ResponslyValue?]) {
        guard var current = state else { return }
        current.mergeTraits(storedTraits(traits))
        commit(current)
        logger.debug("setAttributes: \(traits.count) trait(s) merged")
    }

    private func storedTraits(_ traits: [String: ResponslyValue?]) -> [String: StoredValue?] {
        var stored: [String: StoredValue?] = [:]
        for (name, value) in traits {
            guard let value = value else {
                stored.updateValue(nil, forKey: name)
                continue
            }
            if let converted = StoredValue(value) {
                stored.updateValue(converted, forKey: name)
            } else {
                logger.error("trait \(name) is not a finite number and was ignored")
            }
        }
        return stored
    }

    private func onSetLocale(_ languageTag: String?) {
        guard var current = state else { return }
        current.locale = ResponslyCore.normalizedLocale(languageTag)
        commit(current)
        logger.debug("setLocale: \(current.locale ?? "device locale")")
    }

    private func onSetHiddenFields(_ fields: [String: String]) {
        guard var current = state else { return }
        current.hiddenFields = fields
        commit(current)
    }

    private func onSetTrackingEnabled(_ enabled: Bool) {
        guard var current = state, current.trackingEnabled != enabled else { return }
        current.trackingEnabled = enabled
        commit(current)
        logger.info("tracking \(enabled ? "enabled" : "disabled")")
        if enabled {
            refreshConfigIfNeeded()
            telemetry?.scheduleFlushIfNeeded()
        } else {
            // No network, no targeting, nothing shown.
            if let session = session {
                endSession(session)
            }
            failPendingManualCalls(.disabled)
            pendingCalls.removeAll()
            fetchGeneration += 1
            fetchInFlight = false
            telemetry?.suspend()
        }
    }

    private func onReset() {
        guard state != nil else { return }
        if let session = session {
            endSession(session)
        }
        env.presenter.clearWebsiteData(forHosts: config?.surveyHosts ?? [])
        guard var current = state else { return }
        current.reset()
        commit(current)
        telemetry?.clear()
        failPendingManualCalls(.configUnavailable)
        pendingCalls.removeAll()
        dropConfig()
        logger.info("reset: local state cleared")
        // The SDK stays initialised: fetch the configuration again.
        refreshConfigIfNeeded()
    }

    private func commit(_ newState: SDKState) {
        state = newState
        store.saveState(newState)
    }

    static func normalizedLocale(_ tag: String?) -> String? {
        guard let tag = tag?.trimmingCharacters(in: .whitespacesAndNewlines), !tag.isEmpty else { return nil }
        return tag
    }

    // MARK: - Triggers

    private func onTrack(_ event: String, properties: [String: ResponslyValue]) {
        guard var current = state else { return }
        guard !event.isEmpty else {
            logger.error("track: the event name is empty")
            return
        }
        // Counted at call time, whether or not the configuration is available.
        let count = current.incrementEventCount(event)
        commit(current)
        var stored: [String: StoredValue] = [:]
        for (name, value) in properties {
            if let converted = StoredValue(value) {
                stored[name] = converted
            }
        }
        handleTrigger(.event(name: event, properties: stored, count: count))
    }

    private func onScreen(_ name: String) {
        guard state != nil else { return }
        currentScreen = name
        handleTrigger(.screen(name: name))
    }

    private func handleTrigger(_ call: TriggerCall) {
        guard let state = state else { return }
        if config != nil && !replayingPreInitCalls {
            evaluate(call)
            return
        }
        // Not available yet: keep the call for when it is, unless it never will be.
        guard !configBlocked else { return }
        guard state.trackingEnabled || replayingPreInitCalls else { return }
        enqueuePending(.trigger(call))
    }

    private func evaluate(_ call: TriggerCall) {
        guard let state = state, let config = config else { return }
        let decision = TargetingEngine.evaluate(
            trigger: call, config: config, state: state,
            flags: TargetingFlags(paused: paused, surveyActive: session != nil),
            context: targetingContext(state), now: env.now()
        )
        for item in decision.notShown {
            reportNotShown(item.surveyId, item.reason)
        }
        guard let survey = decision.selected else { return }
        logger.debug("targeting: survey \(survey.id) selected by \(ResponslyCore.describe(call.publicTrigger))")
        start(SurveySession(survey: survey, source: .targeting(call)))
    }

    private func onShowSurvey(_ surveyId: String, options: ResponslyShowOptions) {
        guard let state = state else {
            reportNotShown(surveyId, .notInitialized)
            return
        }
        guard state.trackingEnabled else {
            reportNotShown(surveyId, .disabled)
            return
        }
        guard let config = config else {
            if fetchInFlight {
                // The first configuration is on its way: answer when it arrives.
                enqueuePending(.manual(surveyId: surveyId, options: options))
            } else {
                reportNotShown(surveyId, .configUnavailable)
            }
            return
        }
        if config.survey(id: surveyId) == nil, fetchInFlight {
            // Not in the cached copy, but a refresh is under way: the survey may be new.
            enqueuePending(.manual(surveyId: surveyId, options: options))
            return
        }
        evaluateManual(surveyId, options: options)
    }

    private func evaluateManual(_ surveyId: String, options: ResponslyShowOptions) {
        guard let state = state, let config = config else { return }
        let decision = TargetingEngine.evaluateManual(
            surveyId: surveyId, force: options.force, config: config, state: state,
            flags: TargetingFlags(paused: paused, surveyActive: session != nil),
            context: targetingContext(state), now: env.now()
        )
        for item in decision.notShown {
            reportNotShown(item.surveyId, item.reason)
        }
        guard let survey = decision.selected else { return }
        logger.debug("showSurvey: survey \(survey.id) is eligible\(options.force ? " (forced)" : "")")
        start(SurveySession(survey: survey, source: .manual(options)))
    }

    private func targetingContext(_ state: SDKState) -> TargetingContext {
        TargetingContext(
            platform: env.platform, appId: env.appId,
            deviceLocale: env.deviceLocale(), installId: state.installId
        )
    }

    // MARK: - Survey session

    private func start(_ session: SurveySession) {
        self.session = session
        let delay = session.isAuto ? session.survey.delaySeconds : 0
        guard delay > 0 else {
            load(session)
            return
        }
        session.phase = .delay
        let sessionId = session.id
        logger.debug("survey \(session.survey.id): waiting \(delay) s before showing")
        session.timer = scheduler.schedule(after: delay) { [weak self] in
            self?.delayEnded(sessionId: sessionId)
        }
    }

    /// The delay ended: eligibility is checked again, and a screen trigger needs its screen.
    private func delayEnded(sessionId: UUID) {
        guard let session = session, session.id == sessionId, session.phase == .delay else { return }
        session.timer = nil
        guard let state = state, let config = config else {
            fail(session, .configUnavailable)
            return
        }
        guard let survey = config.survey(id: session.survey.id) else {
            fail(session, .surveyNotFound)
            return
        }
        session.survey = survey
        let reason = TargetingEngine.ineligibilityReason(
            for: survey, mode: .targeting, config: config, state: state,
            flags: TargetingFlags(paused: paused, surveyActive: false),
            context: targetingContext(state), now: env.now()
        )
        if let reason = reason {
            fail(session, reason)
            return
        }
        if case .targeting(.screen(let name)) = session.source, currentScreen != name {
            fail(session, .screenChanged)
            return
        }
        load(session)
    }

    /// The page reported `ready`. Unless the survey was forced, the app's display guard has the
    /// last say right before the survey becomes visible.
    private func pageBecameReady(_ session: SurveySession) {
        // The load timeout ends here: time spent waiting for the guard is not loading time.
        session.timer?.cancel()
        session.timer = nil
        let forced = session.showOptions?.force ?? false
        guard !forced, let displayGuard = displayGuard else {
            makeVisible(session)
            return
        }
        session.phase = .displayGuard
        let sessionId = session.id
        let survey = session.publicSurvey
        session.timer = scheduler.schedule(after: ResponslyCore.displayGuardTimeout) { [weak self] in
            self?.displayGuardAnswered(sessionId: sessionId, allow: true, timedOut: true)
        }
        env.onMain { [weak self] in
            displayGuard(survey) { allow in
                guard let self = self else { return }
                self.queue.async {
                    self.displayGuardAnswered(sessionId: sessionId, allow: allow, timedOut: false)
                }
            }
        }
    }

    private func displayGuardAnswered(sessionId: UUID, allow: Bool, timedOut: Bool) {
        guard let session = session, session.id == sessionId, session.phase == .displayGuard else { return }
        session.timer?.cancel()
        session.timer = nil
        if timedOut {
            logger.debug("survey \(session.survey.id): the display guard did not answer in time, showing")
        }
        if allow {
            makeVisible(session)
        } else {
            // The loaded page is discarded.
            cancelBeforeVisible(session, reason: .vetoed)
        }
    }

    /// The survey becomes visible: this is the moment a display is counted (protocol section 4.4).
    private func makeVisible(_ session: SurveySession) {
        guard var current = state else { return }
        let surveyId = session.survey.id
        session.phase = .visible
        current.recordDisplayed(surveyId: surveyId, auto: session.isAuto, at: env.now())
        commit(current)
        telemetry?.enqueue(type: .displayed, surveyId: surveyId)
        env.presenter.reveal(sessionId: session.id)
        logger.debug("survey \(surveyId): shown")
        let event = ResponslySurveyShownEvent(survey: session.publicSurvey, trigger: session.trigger)
        notify { $0.surveyShown(event) }
    }

    /// Hands the survey to the UI layer, which loads it hidden. On `ready` the display guard is
    /// asked and the survey becomes visible.
    private func load(_ session: SurveySession) {
        guard let state = state, let origin = WebOrigin(url: session.survey.url) else {
            fail(session, .loadFailed)
            return
        }
        let survey = session.survey
        let options = session.showOptions
        // `lang` goes into the URL only when a locale was set explicitly.
        let explicitLocale = ResponslyCore.normalizedLocale(options?.locale) ?? state.locale
        let style = options?.presentation ?? survey.presentation.style
        var hiddenFields = state.hiddenFields
        if let extra = options?.hiddenFields {
            hiddenFields.merge(extra) { _, new in new }
        }
        let context = SurveyPage.context(SurveyPage.ContextInput(
            platform: env.platform,
            sdkVersion: env.sdkVersion,
            appId: env.appId,
            appVersion: env.appVersion,
            osVersion: env.osVersion,
            locale: explicitLocale ?? env.deviceLocale(),
            surveyId: survey.id,
            trigger: session.trigger,
            style: style,
            dismissible: survey.presentation.dismissible,
            anonymous: survey.anonymous,
            userId: state.userId,
            traits: state.traits,
            hiddenFields: hiddenFields
        ))
        let request = SurveyPresentationRequest(
            sessionId: session.id,
            surveyId: survey.id,
            surveyURL: survey.url,
            loadURL: SurveyPage.url(surveyURL: survey.url, lang: explicitLocale),
            contextScript: SurveyPage.contextScript(context: context, origin: origin),
            style: style,
            size: survey.presentation.size,
            dismissible: survey.presentation.dismissible,
            userAgentSuffix: " ResponslySDK/\(env.sdkVersion) (\(env.platform))",
            inspectable: logger.level == .debug
        )

        session.phase = .loading
        let sessionId = session.id
        session.timer = scheduler.schedule(after: ResponslyCore.loadTimeout) { [weak self] in
            self?.loadTimedOut(sessionId: sessionId)
        }
        logger.debug("survey \(survey.id): loading \(request.loadURL.absoluteString)")
        env.presenter.present(request) { [weak self] event in
            guard let self = self else { return }
            self.queue.async {
                self.onPresentationEvent(event, sessionId: sessionId)
            }
        }
    }

    /// Only the wait for `ready` is timed; the timer is cancelled when `ready` arrives.
    private func loadTimedOut(sessionId: UUID) {
        guard let session = session, session.id == sessionId, session.phase == .loading else { return }
        logger.info("survey \(session.survey.id): no `ready` within \(Int(ResponslyCore.loadTimeout)) s")
        cancelBeforeVisible(session, reason: .loadFailed)
    }

    private func onPresentationEvent(_ event: SurveyPresentationEvent, sessionId: UUID) {
        guard let session = session, session.id == sessionId else { return }
        switch event {
        case .message(let message, let raw):
            logger.debug("bridge: \(ResponslyCore.truncated(raw))")
            onBridgeMessage(message, session: session)

        case .loadFailed(let description):
            switch session.phase {
            case .loading, .displayGuard:
                logger.info("survey \(session.survey.id): load failed (\(description))")
                cancelBeforeVisible(session, reason: .loadFailed)
            case .visible:
                let errorEvent = ResponslySurveyErrorEvent(surveyId: session.survey.id, code: "load_failed", message: description)
                notify { $0.surveyError(errorEvent) }
                closeVisible(session)
            case .delay:
                break
            }

        case .navigatedAway(let url):
            logger.debug("survey \(session.survey.id): navigation away to \(url.absoluteString)")
            // After `completed`, leaving the survey closes it.
            if session.phase == .visible, session.completed {
                closeVisible(session)
            }

        case .closedByUser:
            guard session.phase == .visible else { return }
            finishVisible(session)
        }
    }

    private func onBridgeMessage(_ message: BridgeMessage, session: SurveySession) {
        let surveyId = session.survey.id
        switch message {
        case .ready:
            guard session.phase == .loading else { return }
            pageBecameReady(session)

        case .error(let code, let text):
            switch session.phase {
            case .loading, .displayGuard:
                logger.info("survey \(surveyId): the page reported \(code) before it was shown")
                cancelBeforeVisible(session, reason: .surveyUnavailable)
            case .visible:
                let event = ResponslySurveyErrorEvent(surveyId: surveyId, code: code, message: text)
                notify { $0.surveyError(event) }
            case .delay:
                break
            }

        case .questionAnswered(let answer):
            guard session.phase == .visible else { return }
            let event = ResponslyQuestionAnsweredEvent(
                surveyId: surveyId, questionId: answer.questionId,
                type: answer.type, title: answer.title, value: answer.value
            )
            notify { $0.questionAnswered(event) }

        case .completed(let responseId, let answers):
            guard session.phase == .visible, !session.completed, var current = state else { return }
            session.completed = true
            current.recordCompleted(surveyId: surveyId, at: env.now())
            commit(current)
            telemetry?.enqueue(type: .completed, surveyId: surveyId)
            // From now on the user can always close it, and it closes by itself after a moment.
            env.presenter.enableUserClose(sessionId: session.id)
            let autoClose = session.survey.presentation.autoCloseSeconds
            if autoClose > 0 {
                let sessionId = session.id
                session.timer = scheduler.schedule(after: autoClose) { [weak self] in
                    self?.autoCloseFired(sessionId: sessionId)
                }
            }
            logger.debug("survey \(surveyId): completed")
            let event = ResponslySurveyCompletedEvent(surveyId: surveyId, responseId: responseId, answers: answers)
            notify { $0.surveyCompleted(event) }

        case .closeRequested:
            switch session.phase {
            case .visible:
                closeVisible(session)
            case .loading, .displayGuard:
                cancelBeforeVisible(session, reason: .surveyUnavailable)
            case .delay:
                break
            }

        case .resize, .pageChanged, .unknown:
            // `resize` is handled by the container; the others need no reaction.
            break
        }
    }

    private func autoCloseFired(sessionId: UUID) {
        guard let session = session, session.id == sessionId, session.phase == .visible else { return }
        logger.debug("survey \(session.survey.id): closing after completion")
        closeVisible(session)
    }

    /// `closeSurvey()`, `reset()`, tracking disabled: close whatever is going on.
    private func endSession(_ session: SurveySession) {
        switch session.phase {
        case .visible:
            closeVisible(session)
        case .delay, .loading, .displayGuard:
            // It was never shown, so it is cancelled silently: no state change, no telemetry,
            // no callback.
            session.timer?.cancel()
            session.timer = nil
            if session.hasHiddenPage {
                env.presenter.dismiss(sessionId: session.id, animated: false)
            }
            self.session = nil
            logger.debug("survey \(session.survey.id): cancelled before it became visible")
        }
    }

    private func closeVisible(_ session: SurveySession) {
        env.presenter.dismiss(sessionId: session.id, animated: true)
        finishVisible(session)
    }

    /// A visible survey is gone: closed before `completed` counts as dismissed.
    private func finishVisible(_ session: SurveySession) {
        session.timer?.cancel()
        session.timer = nil
        if self.session === session {
            self.session = nil
        }
        let surveyId = session.survey.id
        guard !session.completed else {
            logger.debug("survey \(surveyId): closed")
            return
        }
        if var current = state {
            current.recordDismissed(surveyId: surveyId)
            commit(current)
        }
        telemetry?.enqueue(type: .dismissed, surveyId: surveyId)
        logger.debug("survey \(surveyId): dismissed")
        let event = ResponslySurveyDismissedEvent(surveyId: surveyId)
        notify { $0.surveyDismissed(event) }
    }

    /// A survey that was not visible yet is abandoned and its page, if any, discarded.
    /// Changes no state.
    private func cancelBeforeVisible(_ session: SurveySession, reason: ResponslyNotShownReason) {
        if session.hasHiddenPage {
            env.presenter.dismiss(sessionId: session.id, animated: false)
        }
        fail(session, reason)
    }

    private func fail(_ session: SurveySession, _ reason: ResponslyNotShownReason) {
        session.timer?.cancel()
        session.timer = nil
        if self.session === session {
            self.session = nil
        }
        reportNotShown(session.survey.id, reason)
    }

    // MARK: - Reporting

    private func reportNotShown(_ surveyId: String, _ reason: ResponslyNotShownReason) {
        logger.debug("survey \(surveyId) not shown: \(reason.rawValue)")
        let event = ResponslySurveyNotShownEvent(surveyId: surveyId, reason: reason)
        notify { $0.surveyNotShown(event) }
    }

    private func notify(_ body: @escaping @MainActor (ResponslyListener) -> Void) {
        let listeners = self.listeners
        env.onMain {
            let snapshot = listeners.snapshot()
            guard !snapshot.isEmpty else { return }
            MainActor.assumeIsolated {
                for listener in snapshot {
                    body(listener)
                }
            }
        }
    }

    private static func describe(_ trigger: ResponslyTrigger) -> String {
        if let name = trigger.name {
            return "\(trigger.type.rawValue) \"\(name)\""
        }
        return trigger.type.rawValue
    }

    private static func truncated(_ raw: String, limit: Int = 400) -> String {
        raw.count > limit ? String(raw.prefix(limit)) + "…" : raw
    }
}
