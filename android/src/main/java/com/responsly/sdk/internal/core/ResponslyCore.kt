package com.responsly.sdk.internal.core

import com.responsly.sdk.Answer
import com.responsly.sdk.Decision
import com.responsly.sdk.DisplayGuard
import com.responsly.sdk.NotShownReason
import com.responsly.sdk.QuestionAnsweredEvent
import com.responsly.sdk.ResponslyOptions
import com.responsly.sdk.ShowOptions
import com.responsly.sdk.Survey
import com.responsly.sdk.SurveyCompletedEvent
import com.responsly.sdk.SurveyDismissedEvent
import com.responsly.sdk.SurveyErrorEvent
import com.responsly.sdk.SurveyNotShownEvent
import com.responsly.sdk.SurveyShownEvent
import com.responsly.sdk.SurveyTrigger
import com.responsly.sdk.internal.Cancellable
import com.responsly.sdk.internal.Clock
import com.responsly.sdk.internal.JsonValues
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.Scheduler
import com.responsly.sdk.internal.arrayOrNull
import com.responsly.sdk.internal.bridge.BridgeMessage
import com.responsly.sdk.internal.bridge.SurveyContext
import com.responsly.sdk.internal.bridge.SurveyUrl
import com.responsly.sdk.internal.config.ConfigParser
import com.responsly.sdk.internal.config.SdkConfig
import com.responsly.sdk.internal.config.SurveyConfig
import com.responsly.sdk.internal.net.ConfigResponse
import com.responsly.sdk.internal.net.SdkApi
import com.responsly.sdk.internal.objects
import com.responsly.sdk.internal.state.KeyValueStorage
import com.responsly.sdk.internal.state.StateStore
import com.responsly.sdk.internal.targeting.TargetingContext
import com.responsly.sdk.internal.targeting.TargetingEngine
import com.responsly.sdk.internal.targeting.TriggerCall
import com.responsly.sdk.internal.telemetry.TelemetryQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Facts about the host app. [deviceLocale] is read whenever it is needed, so language changes are picked up. */
internal class Environment(
    val appId: String,
    val appVersion: String,
    val osVersion: String,
    val deviceLocale: () -> String,
)

/** What the core needs from the device; faked in tests. */
internal interface Platform {
    val environment: Environment
    val storage: KeyValueStorage
    val presenter: SurveyPresenter

    fun createApi(apiHost: String, sdkKey: String): SdkApi
}

/**
 * The SDK's behaviour: state, configuration, targeting, and the life of the one survey that can be
 * active at a time. Every public method may be called from any thread; all work happens on the
 * scheduler's core thread, so the fields below need no locking.
 */
internal class ResponslyCore(
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val logger: Logger,
    private val listeners: ListenerHub,
    private val sdkVersion: String,
) {
    private enum class Phase {
        /** Selected; waiting for `delay_seconds`. */
        WAITING,

        /** The page is loading in the hidden container. */
        LOADING,

        /** The page is ready; the display guard is deciding. */
        GUARD,

        VISIBLE,
    }

    private class Session(
        val survey: SurveyConfig,
        val trigger: SurveyTrigger,
        /** Selected by targeting rather than `showSurvey`. */
        val auto: Boolean,
        val force: Boolean,
        val options: ShowOptions?,
    ) {
        var phase = Phase.WAITING
        var request: PresentationRequest? = null

        /** The presenter attempt whose callbacks are accepted. */
        var token = 0L
        var completed = false
        var decision: GuardDecision? = null
        var delayTimer: Cancellable? = null
        var loadTimer: Cancellable? = null
        var guardTimer: Cancellable? = null
        var autoCloseTimer: Cancellable? = null

        fun cancelTimers() {
            delayTimer?.cancel()
            loadTimer?.cancel()
            guardTimer?.cancel()
            autoCloseTimer?.cancel()
            delayTimer = null
            loadTimer = null
            guardTimer = null
            autoCloseTimer = null
            decision = null
        }
    }

    private class GuardDecision(private val onDecided: (GuardDecision, Boolean) -> Unit) : Decision {
        private val decided = AtomicBoolean(false)

        override fun allow() = decide(true)

        override fun veto() = decide(false)

        private fun decide(allowed: Boolean) {
            if (decided.compareAndSet(false, true)) onDecided(this, allowed)
        }
    }

    /** A call made before the configuration was available. */
    private sealed class PendingCall {
        class Trigger(val call: TriggerCall) : PendingCall()

        class Show(val surveyId: String, val options: ShowOptions) : PendingCall()
    }

    private var platform: Platform? = null
    private var store: StateStore? = null
    private var api: SdkApi? = null
    private var telemetry: TelemetryQueue? = null
    private var sdkKey: String? = null

    /** The API host in use, without a trailing slash. */
    private var apiHost: String? = null

    private var config: SdkConfig? = null

    /** The configuration is available and the calls queued before it have been evaluated. */
    private var configReady = false
    private var fetchInFlight = false

    /** The server answered 401: nothing is fetched or shown until the next launch. */
    private var keyInvalid = false

    /** Bumped whenever the state is reset, so that a response to an older request is ignored. */
    private var generation = 0
    private var appLaunchEvaluated = false

    private var paused = false
    private var guard: DisplayGuard? = null
    private var currentScreen: String? = null

    private val remembered = ArrayDeque<() -> Unit>()
    private val pendingCalls = ArrayDeque<PendingCall>()
    private var session: Session? = null
    private var lastToken = 0L

    // region Public API (any thread)

    fun initialize(sdkKey: String, options: ResponslyOptions, platform: Platform) {
        scheduler.execute { doInitialize(sdkKey, options, platform) }
    }

    fun identify(userId: String, traits: Map<String, Any?>) = whenInitialized { store ->
        store.identify(userId, traits)
        logger.debug { "identify: user id set, ${traits.size} trait(s) merged" }
    }

    fun setAttributes(traits: Map<String, Any?>) = whenInitialized { store ->
        store.setAttributes(traits)
        logger.debug { "setAttributes: ${traits.size} trait(s) merged" }
    }

    fun track(event: String, properties: Map<String, Any?>) = whenInitialized { store ->
        val count = store.countEvent(event)
        logger.debug { "track: \"$event\" (count $count)" }
        onTriggerCall(TriggerCall.Event(event, properties, count))
    }

    fun screen(name: String) = whenInitialized {
        currentScreen = name
        logger.debug { "screen: \"$name\"" }
        onTriggerCall(TriggerCall.Screen(name))
    }

    fun showSurvey(surveyId: String, options: ShowOptions) {
        scheduler.execute {
            val store = store
            when {
                store == null -> notShown(surveyId, NotShownReason.NOT_INITIALIZED)
                configReady -> evaluateShow(surveyId, options)
                !store.state.trackingEnabled -> notShown(surveyId, NotShownReason.DISABLED)
                fetchInFlight -> enqueuePending(PendingCall.Show(surveyId, options))
                else -> notShown(surveyId, NotShownReason.CONFIG_UNAVAILABLE)
            }
        }
    }

    fun closeSurvey() {
        scheduler.execute {
            val active = session ?: return@execute
            if (active.phase == Phase.VISIBLE) {
                closeVisible(active, animated = true)
            } else {
                cancelBeforeVisible(active, "closeSurvey()")
            }
        }
    }

    fun pause() {
        scheduler.execute {
            paused = true
            logger.debug { "targeting paused" }
        }
    }

    fun resume() {
        scheduler.execute {
            paused = false
            logger.debug { "targeting resumed" }
        }
    }

    fun setLocale(languageTag: String?) = whenInitialized { store ->
        store.setLocale(languageTag?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun setHiddenFields(fields: Map<String, String>) = whenInitialized { store ->
        store.setHiddenFields(fields)
    }

    fun setTrackingEnabled(enabled: Boolean) = whenInitialized { store ->
        if (store.state.trackingEnabled == enabled) return@whenInitialized
        store.setTrackingEnabled(enabled)
        logger.info { if (enabled) "tracking enabled" else "tracking disabled" }
        if (enabled) {
            refreshConfigIfNeeded("tracking enabled")
        } else {
            session?.let { active ->
                if (active.phase == Phase.VISIBLE) {
                    closeVisible(active, animated = true)
                } else {
                    // Never shown: cancelled without a state change, telemetry or a callback.
                    cancelBeforeVisible(active, "tracking disabled")
                }
            }
            pendingCalls.filterIsInstance<PendingCall.Show>().forEach { notShown(it.surveyId, NotShownReason.DISABLED) }
            pendingCalls.clear()
            telemetry?.clear()
        }
    }

    fun reset() = whenInitialized { store ->
        logger.info { "reset" }
        resetState(store)
        refreshConfigIfNeeded("reset")
    }

    fun setDisplayGuard(guard: DisplayGuard?) {
        scheduler.execute { this.guard = guard }
    }

    fun onAppForeground() {
        scheduler.execute {
            if (store == null) return@execute
            refreshConfigIfNeeded("foreground")
            telemetry?.start()
        }
    }

    fun onAppBackground() {
        scheduler.execute { telemetry?.flush() }
    }

    /** Presenter callbacks arrive on the main thread and are handled on the core thread. */
    val presenterEvents: PresenterEvents = object : PresenterEvents {
        override fun onBridgeMessage(token: Long, message: BridgeMessage) =
            forSession(token) { handleBridgeMessage(it, message) }

        override fun onFailure(token: Long, code: String, detail: String) =
            forSession(token) { handleFailure(it, code, detail) }

        override fun onHostLost(token: Long) = forSession(token) { handleHostLost(it) }

        override fun onUserClosed(token: Long) = forSession(token) {
            if (it.phase == Phase.VISIBLE) closeVisible(it, animated = true)
        }

        override fun onHostDestroyed(token: Long) = forSession(token) {
            if (it.phase == Phase.VISIBLE) closeVisible(it, animated = false)
        }

        override fun onNavigatedAway(token: Long) = forSession(token) {
            if (it.phase == Phase.VISIBLE && it.completed) closeVisible(it, animated = true)
        }
    }

    // endregion

    // region Initialisation and reset (core thread from here on)

    private fun doInitialize(rawKey: String, options: ResponslyOptions, platform: Platform) {
        logger.level = options.logLevel
        val key = rawKey.trim()
        if (key.isEmpty()) {
            logger.error("initialize: the SDK key is empty; the call is ignored")
            return
        }
        val host = options.apiHost.trim().trimEnd('/')
        var store = this.store
        if (store != null && sdkKey == key) {
            if (apiHost == host) {
                logger.debug { "initialize: already initialised with this key and API host" }
            } else {
                switchApiHost(store, key, host)
            }
            return
        }
        if (store != null) {
            logger.info { "initialize: the SDK key changed; resetting state" }
            resetState(store)
            telemetry?.close()
            keyInvalid = false
            appLaunchEvaluated = false
        } else {
            store = StateStore(platform.storage)
            store.load()
            this.store = store
            this.platform = platform
            if (store.sdkKey != null && store.sdkKey != key) {
                logger.info { "initialize: the stored state belongs to another SDK key; resetting it" }
                config = store.configBody?.let(ConfigParser::parse)
                resetState(store)
            }
        }
        store.bindSdkKey(key)
        sdkKey = key
        apiHost = host
        val api = platform.createApi(host, key)
        this.api = api
        val telemetry = TelemetryQueue(store, api, scheduler, logger)
        this.telemetry = telemetry

        // A cached configuration is only ever used with the host it was downloaded from.
        if (store.configBody != null && store.configHost != host) {
            logger.debug { "config: the cached copy is from another API host; dropped" }
            store.clearConfig()
        }
        config = store.configBody?.let(ConfigParser::parse)
        if (config == null && store.configBody != null) store.clearConfig()
        logger.info {
            val cached = config?.let { "${it.surveys.size} survey(s) in the cached configuration" } ?: "no cached configuration"
            "initialised, SDK $sdkVersion, $cached"
        }

        // Calls made before initialize, in their original order. Triggers among them are queued
        // behind app_launch because the configuration is not marked available yet.
        val calls = remembered.toList()
        remembered.clear()
        calls.forEach { it() }

        if (config != null) onConfigAvailable()
        refreshConfigIfNeeded("initialize")
        telemetry.start()
    }

    /**
     * `initialize` again with the same key and another host: everything the user did stays, but the
     * configuration has to come from the new host.
     */
    private fun switchApiHost(store: StateStore, key: String, host: String) {
        val platform = platform ?: return
        logger.info { "initialize: the API host changed; downloading the configuration again" }
        // A survey of the old configuration that is not on screen yet is not shown any more.
        session?.let { active ->
            if (active.phase != Phase.VISIBLE) cancelBeforeVisible(active, "API host changed")
        }
        store.clearConfig()
        config = null
        configReady = false
        // An answer still on its way from the old host must not be taken for the new one's.
        fetchInFlight = false
        generation++
        keyInvalid = false
        appLaunchEvaluated = false

        apiHost = host
        val api = platform.createApi(host, key)
        this.api = api
        telemetry?.close()
        val telemetry = TelemetryQueue(store, api, scheduler, logger)
        this.telemetry = telemetry

        refreshConfigIfNeeded("API host changed")
        telemetry.start()
    }

    /** Section 3: forget everything but the install id and the tracking switch. */
    private fun resetState(store: StateStore) {
        session?.let { active ->
            if (active.phase == Phase.VISIBLE) {
                closeVisible(active, animated = false)
            } else {
                cancelBeforeVisible(active, "reset")
            }
        }
        val origins = config?.surveys.orEmpty().mapNotNull { SurveyUrl.origin(it.url) }.distinct()
        telemetry?.clear()
        store.reset()
        config = null
        configReady = false
        fetchInFlight = false
        generation++
        failPendingShows()
        pendingCalls.clear()
        val presenter = platform?.presenter
        scheduler.main { presenter?.clearWebsiteData(origins) }
    }

    private fun whenInitialized(action: (StateStore) -> Unit) {
        scheduler.execute {
            val store = store
            if (store != null) {
                action(store)
            } else {
                if (remembered.size >= MAX_REMEMBERED_CALLS) remembered.removeFirst()
                remembered.addLast { this.store?.let(action) }
            }
        }
    }

    // endregion

    // region Configuration

    private fun refreshConfigIfNeeded(why: String) {
        val store = store ?: return
        val api = api ?: return
        if (!store.state.trackingEnabled || keyInvalid || fetchInFlight) return
        val cached = config
        val fetchedAt = store.configFetchedAt
        val now = clock.now()
        if (cached != null && fetchedAt != null && now >= fetchedAt && now - fetchedAt < cached.ttlMillis) return

        fetchInFlight = true
        val etag = if (cached != null) store.configEtag else null
        val requestGeneration = generation
        logger.debug { "config: fetching ($why)" }
        scheduler.io {
            val response = api.fetchConfig(etag)
            scheduler.execute {
                if (requestGeneration == generation) onConfigResponse(response)
            }
        }
    }

    private fun onConfigResponse(response: ConfigResponse) {
        fetchInFlight = false
        val store = store ?: return
        when (response) {
            is ConfigResponse.Ok -> {
                val parsed = ConfigParser.parse(response.body)
                if (parsed == null) {
                    logger.error("config: the response is not a JSON object; keeping the cached copy")
                } else {
                    store.saveConfig(response.body, response.etag, clock.now(), apiHost.orEmpty())
                    config = parsed
                    logger.debug { "config: fetched, ${parsed.surveys.size} survey(s), ttl ${parsed.ttlMillis / 1000} s" }
                }
            }
            ConfigResponse.NotModified -> {
                if (config != null) store.touchConfig(clock.now())
                logger.debug { "config: not modified" }
            }
            ConfigResponse.InvalidKey -> {
                logger.error("config: the SDK key was rejected (401); nothing is shown until the next launch")
                keyInvalid = true
                store.clearConfig()
                config = null
                configReady = false
                session?.let { active ->
                    if (active.phase != Phase.VISIBLE) endNotShown(active, NotShownReason.CONFIG_UNAVAILABLE)
                }
                failPendingShows()
                pendingCalls.clear()
            }
            is ConfigResponse.Failed -> logger.info {
                val fallback = if (config != null) "keeping the cached copy" else "no cached copy"
                "config: fetch failed (${response.reason}); $fallback"
            }
        }
        if (config == null) {
            failPendingShows()
        } else if (!configReady) {
            onConfigAvailable()
        }
    }

    /** The configuration is available for the first time since initialize (or since a reset). */
    private fun onConfigAvailable() {
        configReady = true
        if (!appLaunchEvaluated) {
            appLaunchEvaluated = true
            evaluateTrigger(TriggerCall.AppLaunch)
        }
        while (configReady) {
            when (val call = pendingCalls.removeFirstOrNull() ?: break) {
                is PendingCall.Trigger -> evaluateTrigger(call.call)
                is PendingCall.Show -> evaluateShow(call.surveyId, call.options)
            }
        }
    }

    private fun enqueuePending(call: PendingCall) {
        if (pendingCalls.size >= MAX_PENDING_CALLS) {
            val dropped = pendingCalls.removeFirst()
            if (dropped is PendingCall.Show) notShown(dropped.surveyId, NotShownReason.CONFIG_UNAVAILABLE)
        }
        pendingCalls.addLast(call)
    }

    private fun failPendingShows() {
        val shows = pendingCalls.filterIsInstance<PendingCall.Show>()
        if (shows.isEmpty()) return
        pendingCalls.removeAll { it is PendingCall.Show }
        shows.forEach { notShown(it.surveyId, NotShownReason.CONFIG_UNAVAILABLE) }
    }

    // endregion

    // region Targeting

    private fun onTriggerCall(call: TriggerCall) {
        when {
            configReady -> evaluateTrigger(call)
            // No targeting while tracking is disabled, also not later for what happened meanwhile.
            store?.state?.trackingEnabled == false -> logger.debug { "trigger ignored: tracking is disabled" }
            else -> enqueuePending(PendingCall.Trigger(call))
        }
    }

    private fun evaluateTrigger(call: TriggerCall) {
        val config = config ?: return
        val store = store ?: return
        val decision = TargetingEngine.onTrigger(config, store.state, targetingContext(), call, clock.now())
        decision.notShown.forEach { (surveyId, reason) -> notShown(surveyId, reason) }
        val survey = decision.selected ?: return
        val trigger = when (call) {
            is TriggerCall.Event -> SurveyTrigger(SurveyTrigger.TYPE_EVENT, call.name)
            is TriggerCall.Screen -> SurveyTrigger(SurveyTrigger.TYPE_SCREEN, call.name)
            TriggerCall.AppLaunch -> SurveyTrigger(SurveyTrigger.TYPE_APP_LAUNCH, null)
        }
        logger.debug { "targeting: survey ${survey.id} selected by ${describe(trigger)}" }
        startSession(Session(survey, trigger, auto = true, force = false, options = null))
    }

    private fun evaluateShow(surveyId: String, options: ShowOptions) {
        val config = config ?: return notShown(surveyId, NotShownReason.CONFIG_UNAVAILABLE)
        val store = store ?: return
        val decision =
            TargetingEngine.onShow(config, store.state, targetingContext(), surveyId, options.force, clock.now())
        decision.notShown.forEach { (id, reason) -> notShown(id, reason) }
        val survey = decision.selected ?: return
        logger.debug { "targeting: survey ${survey.id} selected by showSurvey" + if (options.force) " (force)" else "" }
        val trigger = SurveyTrigger(SurveyTrigger.TYPE_MANUAL, null)
        startSession(Session(survey, trigger, auto = false, force = options.force, options = options))
    }

    private fun targetingContext(): TargetingContext {
        val environment = platform?.environment
        return TargetingContext(
            platform = PLATFORM,
            appId = environment?.appId.orEmpty(),
            deviceLocale = environment?.deviceLocale?.invoke().orEmpty(),
            paused = paused,
            surveyActive = session != null,
        )
    }

    private fun notShown(surveyId: String, reason: NotShownReason) {
        logger.debug { "targeting: survey $surveyId not shown: ${reason.value}" }
        listeners.dispatch { it.onSurveyNotShown(SurveyNotShownEvent(surveyId, reason)) }
    }

    // endregion

    // region The active survey

    private fun startSession(active: Session) {
        session = active
        val delay = if (active.auto) active.survey.delayMillis else 0L
        if (delay <= 0L) {
            beginLoading(active)
            return
        }
        logger.debug { "survey ${active.survey.id}: waiting ${delay} ms before showing" }
        active.delayTimer = scheduler.schedule(delay) {
            if (session === active && active.phase == Phase.WAITING) {
                active.delayTimer = null
                onDelayElapsed(active)
            }
        }
    }

    private fun onDelayElapsed(active: Session) {
        val config = config
        val store = store
        if (config == null || store == null) {
            endNotShown(active, NotShownReason.CONFIG_UNAVAILABLE)
            return
        }
        val reason = TargetingEngine.targetingReason(
            config,
            active.survey,
            store.state,
            targetingContext(),
            clock.now(),
            ignoreActive = true,
        )
        if (reason != null) endNotShown(active, reason) else beginLoading(active)
    }

    private fun beginLoading(active: Session) {
        val trigger = active.trigger
        if (trigger.type == SurveyTrigger.TYPE_SCREEN && trigger.name != currentScreen) {
            endNotShown(active, NotShownReason.SCREEN_CHANGED)
            return
        }
        val request = buildRequest(active)
        if (request == null) {
            logger.error("survey ${active.survey.id}: \"${active.survey.url}\" is not an http(s) URL")
            endNotShown(active, NotShownReason.LOAD_FAILED)
            return
        }
        active.request = request
        active.phase = Phase.LOADING
        startLoadTimer(active)
        present(active)
    }

    private fun startLoadTimer(active: Session) {
        active.loadTimer?.cancel()
        active.loadTimer = scheduler.schedule(LOAD_TIMEOUT_MILLIS) {
            if (session === active && active.phase == Phase.LOADING) {
                active.loadTimer = null
                logger.info { "survey ${active.survey.id}: not ready within ${LOAD_TIMEOUT_MILLIS / 1000} s" }
                endNotShown(active, NotShownReason.LOAD_FAILED)
            }
        }
    }

    private fun present(active: Session) {
        val presenter = platform?.presenter ?: return
        val request = active.request?.copy(token = ++lastToken) ?: return
        active.token = request.token
        logger.debug { "survey ${active.survey.id}: loading ${request.url}" }
        scheduler.main { presenter.present(request, presenterEvents) }
    }

    private fun buildRequest(active: Session): PresentationRequest? {
        val environment = platform?.environment ?: return null
        val state = store?.state ?: return null
        val survey = active.survey
        val origin = SurveyUrl.origin(survey.url) ?: return null
        // Only a locale that was set explicitly goes into the URL.
        val explicitLocale = active.options?.locale?.trim()?.takeIf { it.isNotEmpty() } ?: state.locale
        val style = active.options?.presentation ?: survey.presentation.style
        val hidden = LinkedHashMap(state.hiddenFields)
        active.options?.hiddenFields?.let(hidden::putAll)
        val context = SurveyContext.build(
            platform = PLATFORM,
            sdkVersion = sdkVersion,
            appId = environment.appId,
            appVersion = environment.appVersion,
            osVersion = environment.osVersion,
            locale = explicitLocale ?: environment.deviceLocale(),
            surveyId = survey.id,
            trigger = active.trigger,
            style = style,
            dismissible = survey.presentation.dismissible,
            anonymous = survey.anonymous,
            userId = state.userId,
            traits = LinkedHashMap(state.traits),
            hidden = hidden,
        )
        return PresentationRequest(
            token = 0L,
            surveyId = survey.id,
            url = SurveyUrl.build(survey.url, explicitLocale),
            surveyUrl = survey.url,
            origin = origin,
            contextScript = SurveyContext.script(context),
            style = style,
            size = survey.presentation.size,
            dismissible = survey.presentation.dismissible,
            userAgentSuffix = " ResponslySDK/$sdkVersion ($PLATFORM)",
        )
    }

    private fun forSession(token: Long, action: (Session) -> Unit) {
        scheduler.execute {
            val active = session
            if (active != null && active.token == token) action(active)
        }
    }

    private fun handleBridgeMessage(active: Session, message: BridgeMessage) {
        val payload = message.payload
        logger.debug { "bridge: ${message.type} ${payload.toString().take(MAX_LOGGED_PAYLOAD)}" }
        val surveyId = active.survey.id
        when (message.type) {
            BridgeMessage.READY -> if (active.phase == Phase.LOADING) onReady(active)
            BridgeMessage.ERROR -> {
                val code = text(payload, "code").ifEmpty { "unknown" }
                val detail = text(payload, "message")
                if (active.phase == Phase.VISIBLE) {
                    listeners.dispatch { it.onSurveyError(SurveyErrorEvent(surveyId, code, detail)) }
                } else {
                    logger.info { "survey $surveyId: the page reported \"$code\" before it was ready" }
                    endNotShown(active, NotShownReason.SURVEY_UNAVAILABLE)
                }
            }
            BridgeMessage.QUESTION_ANSWERED -> if (active.phase == Phase.VISIBLE) {
                val answer = answer(payload)
                listeners.dispatch {
                    it.onQuestionAnswered(
                        QuestionAnsweredEvent(surveyId, answer.questionId, answer.type, answer.title, answer.value),
                    )
                }
            }
            BridgeMessage.COMPLETED -> if (active.phase == Phase.VISIBLE && !active.completed) {
                onCompleted(active, payload)
            }
            BridgeMessage.CLOSE_REQUESTED -> if (active.phase == Phase.VISIBLE) {
                closeVisible(active, animated = true)
            } else {
                endNotShown(active, NotShownReason.LOAD_FAILED)
            }
            // resize is handled by the container, page_changed needs nothing, unknown types are ignored.
            else -> Unit
        }
    }

    private fun onReady(active: Session) {
        active.loadTimer?.cancel()
        active.loadTimer = null
        val guard = guard
        if (guard == null || active.force) {
            reveal(active)
            return
        }
        active.phase = Phase.GUARD
        val decision = GuardDecision { decision, allowed ->
            scheduler.execute { onGuardDecision(active, decision, allowed) }
        }
        active.decision = decision
        active.guardTimer = scheduler.schedule(GUARD_TIMEOUT_MILLIS) {
            if (session === active && active.decision === decision) {
                logger.debug { "survey ${active.survey.id}: the display guard did not answer; showing" }
                decision.allow()
            }
        }
        val survey = Survey(active.survey.id, active.survey.name)
        scheduler.main {
            try {
                guard.shouldShow(survey, decision)
            } catch (e: Exception) {
                logger.error("The DisplayGuard threw an exception; the survey is shown", e)
                decision.allow()
            }
        }
    }

    private fun onGuardDecision(active: Session, decision: GuardDecision, allowed: Boolean) {
        if (session !== active || active.phase != Phase.GUARD || active.decision !== decision) return
        active.guardTimer?.cancel()
        active.guardTimer = null
        active.decision = null
        if (allowed) reveal(active) else endNotShown(active, NotShownReason.VETOED)
    }

    /** Section 4.4: the survey becomes visible. */
    private fun reveal(active: Session) {
        val surveyId = active.survey.id
        active.phase = Phase.VISIBLE
        store?.markDisplayed(surveyId, clock.now(), active.auto)
        telemetry?.enqueue(TELEMETRY_DISPLAYED, surveyId)
        val token = active.token
        val presenter = platform?.presenter
        scheduler.main { presenter?.reveal(token) }
        logger.info { "survey $surveyId shown (${describe(active.trigger)})" }
        val event = SurveyShownEvent(Survey(surveyId, active.survey.name), active.trigger)
        listeners.dispatch { it.onSurveyShown(event) }
    }

    private fun onCompleted(active: Session, payload: JSONObject) {
        val surveyId = active.survey.id
        active.completed = true
        store?.markCompleted(surveyId, clock.now())
        telemetry?.enqueue(TELEMETRY_COMPLETED, surveyId)
        val answers = payload.arrayOrNull("answers")?.objects().orEmpty().map(::answer)
        val event = SurveyCompletedEvent(surveyId, text(payload, "response_id"), answers)
        logger.info { "survey $surveyId completed" }
        listeners.dispatch { it.onSurveyCompleted(event) }

        // A completed survey can always be closed, and closes by itself after auto_close_seconds.
        val token = active.token
        val presenter = platform?.presenter
        scheduler.main { presenter?.setDismissible(token, true) }
        val autoClose = active.survey.presentation.autoCloseMillis
        if (autoClose > 0L) {
            active.autoCloseTimer = scheduler.schedule(autoClose) {
                if (session === active) closeVisible(active, animated = true)
            }
        }
    }

    /** Closes the visible survey; counts as dismissed unless it was completed. */
    private fun closeVisible(active: Session, animated: Boolean) {
        val surveyId = active.survey.id
        active.cancelTimers()
        session = null
        dismissContainer(active.token, animated)
        if (active.completed) {
            logger.debug { "survey $surveyId closed" }
            return
        }
        store?.markDismissed(surveyId)
        telemetry?.enqueue(TELEMETRY_DISMISSED, surveyId)
        logger.info { "survey $surveyId dismissed" }
        listeners.dispatch { it.onSurveyDismissed(SurveyDismissedEvent(surveyId)) }
    }

    /** Ends a survey that never became visible. Nothing is recorded. */
    private fun endNotShown(active: Session, reason: NotShownReason) {
        cancelBeforeVisible(active, null)
        notShown(active.survey.id, reason)
    }

    private fun cancelBeforeVisible(active: Session, why: String?) {
        active.cancelTimers()
        if (active.phase != Phase.WAITING) dismissContainer(active.token, animated = false)
        if (session === active) session = null
        if (why != null) logger.debug { "survey ${active.survey.id}: cancelled before it was shown ($why)" }
    }

    private fun dismissContainer(token: Long, animated: Boolean) {
        val presenter = platform?.presenter ?: return
        scheduler.main { presenter.dismiss(token, animated) }
    }

    private fun handleFailure(active: Session, code: String, detail: String) {
        val surveyId = active.survey.id
        when (active.phase) {
            Phase.LOADING, Phase.GUARD -> {
                logger.info { "survey $surveyId: failed to load ($code: $detail)" }
                endNotShown(active, NotShownReason.LOAD_FAILED)
            }
            Phase.VISIBLE -> {
                logger.info { "survey $surveyId: the page broke ($code: $detail); closing" }
                listeners.dispatch { it.onSurveyError(SurveyErrorEvent(surveyId, code, detail)) }
                closeVisible(active, animated = true)
            }
            Phase.WAITING -> Unit
        }
    }

    /** The hidden container lost its activity (a splash screen finished, another screen opened): load again. */
    private fun handleHostLost(active: Session) {
        if (active.phase != Phase.LOADING && active.phase != Phase.GUARD) return
        logger.debug { "survey ${active.survey.id}: the host activity changed before the survey was shown; loading again" }
        if (active.phase == Phase.GUARD) {
            active.guardTimer?.cancel()
            active.guardTimer = null
            active.decision = null
            active.phase = Phase.LOADING
            startLoadTimer(active)
        }
        present(active)
    }

    private fun answer(json: JSONObject): Answer = Answer(
        questionId = text(json, "question_id"),
        type = text(json, "type"),
        title = text(json, "title"),
        value = JsonValues.toKotlin(json.opt("value")),
    )

    /** A string field; numbers are accepted too (ids), anything else is empty. */
    private fun text(json: JSONObject, key: String): String = when (val value = json.opt(key)) {
        is String -> value
        is Number -> JsonValues.toKotlin(value).toString()
        else -> ""
    }

    private fun describe(trigger: SurveyTrigger): String =
        if (trigger.name != null) "${trigger.type} \"${trigger.name}\"" else trigger.type

    // endregion

    companion object {
        const val PLATFORM = "android"
        const val LOAD_TIMEOUT_MILLIS = 15_000L
        const val GUARD_TIMEOUT_MILLIS = 2_000L
        const val MAX_PENDING_CALLS = 50
        const val MAX_REMEMBERED_CALLS = 200
        private const val MAX_LOGGED_PAYLOAD = 500
        private const val TELEMETRY_DISPLAYED = "displayed"
        private const val TELEMETRY_COMPLETED = "completed"
        private const val TELEMETRY_DISMISSED = "dismissed"
    }
}
