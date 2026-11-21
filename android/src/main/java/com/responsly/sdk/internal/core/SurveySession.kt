package com.responsly.sdk.internal.core

import com.responsly.sdk.Decision
import com.responsly.sdk.ShowOptions
import com.responsly.sdk.SurveyTrigger
import com.responsly.sdk.internal.Cancellable
import com.responsly.sdk.internal.config.SurveyConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Stages from selecting a survey to revealing its native container. */
internal enum class SurveyPhase {
    /** Selected; waiting for `delay_seconds`. */
    WAITING,

    /** The page is loading in the hidden container. */
    LOADING,

    /** The page is ready; the display guard is deciding. */
    GUARD,

    VISIBLE,
}

/** Mutable state and timers of a single survey attempt. Confined to the core thread. */
internal class SurveySession(
    val survey: SurveyConfig,
    val trigger: SurveyTrigger,
    /** Selected by targeting rather than `showSurvey`. */
    val auto: Boolean,
    val force: Boolean,
    val options: ShowOptions?,
) {
    var phase = SurveyPhase.WAITING
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

internal class GuardDecision(private val onDecided: (GuardDecision, Boolean) -> Unit) : Decision {
    private val decided = AtomicBoolean(false)

    override fun allow() = decide(true)

    override fun veto() = decide(false)

    private fun decide(allowed: Boolean) {
        if (decided.compareAndSet(false, true)) onDecided(this, allowed)
    }
}

