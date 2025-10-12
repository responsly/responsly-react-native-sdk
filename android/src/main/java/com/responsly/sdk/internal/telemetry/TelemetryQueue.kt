package com.responsly.sdk.internal.telemetry

import com.responsly.sdk.internal.Cancellable
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.Scheduler
import com.responsly.sdk.internal.net.PostResult
import com.responsly.sdk.internal.net.SdkApi
import com.responsly.sdk.internal.state.StateStore
import com.responsly.sdk.internal.state.TelemetryEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Best-effort telemetry (section 2): batches of at most [BATCH_SIZE] events, sent when a batch is
 * full, when the app goes to the background, and at most every [FLUSH_INTERVAL_MILLIS] while events
 * are waiting. Unsent events are persisted (at most [MAX_QUEUE]) and retried. Core thread only.
 */
internal class TelemetryQueue(
    private val store: StateStore,
    private val api: SdkApi,
    private val scheduler: Scheduler,
    private val logger: Logger,
) {
    private var sending = false
    private var timer: Cancellable? = null

    /** The last attempt failed; wait for the timer instead of sending on every new event. */
    private var backingOff = false
    private var closed = false

    fun enqueue(type: String, surveyId: String) {
        if (closed || !store.state.trackingEnabled) return
        val queue = store.telemetry
        queue.add(TelemetryEvent(type, surveyId))
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
        store.saveTelemetry()
        if (queue.size >= BATCH_SIZE && !backingOff) flush() else ensureTimer()
    }

    /** Sends what is waiting, one batch after another. */
    fun flush() {
        if (closed || sending || store.telemetry.isEmpty() || !store.state.trackingEnabled) return
        timer?.cancel()
        timer = null
        sendNextBatch()
    }

    /** Starts the periodic flush when events are left over from an earlier run. */
    fun start() {
        ensureTimer()
    }

    /** Drops every waiting event (tracking was disabled, or the state was reset). */
    fun clear() {
        timer?.cancel()
        timer = null
        backingOff = false
        if (store.telemetry.isNotEmpty()) {
            store.telemetry.clear()
            store.saveTelemetry()
        }
    }

    /** Stops the queue for good; used when the SDK is re-initialised with another key. */
    fun close() {
        closed = true
        timer?.cancel()
        timer = null
    }

    private fun ensureTimer() {
        if (closed || timer != null || sending || store.telemetry.isEmpty() || !store.state.trackingEnabled) return
        timer = scheduler.schedule(FLUSH_INTERVAL_MILLIS) {
            timer = null
            flush()
        }
    }

    private fun sendNextBatch() {
        val batch = store.telemetry.take(BATCH_SIZE)
        if (batch.isEmpty()) return
        sending = true
        val body = encode(batch)
        scheduler.io {
            val result = api.postEvents(body)
            scheduler.execute { onResult(batch, result) }
        }
    }

    private fun onResult(batch: List<TelemetryEvent>, result: PostResult) {
        sending = false
        if (closed) return
        when (result) {
            PostResult.SENT, PostResult.REJECTED -> {
                backingOff = false
                // Remove by identity: the queue may have been trimmed or cleared meanwhile.
                store.telemetry.removeAll { queued -> batch.any { it === queued } }
                store.saveTelemetry()
                logger.debug {
                    val outcome = if (result == PostResult.SENT) "sent" else "rejected by the server, dropped"
                    "telemetry: ${batch.size} event(s) $outcome"
                }
                if (store.telemetry.isNotEmpty() && store.state.trackingEnabled) sendNextBatch()
            }
            PostResult.RETRY -> {
                backingOff = true
                logger.debug { "telemetry: could not send ${batch.size} event(s), will retry" }
                ensureTimer()
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 20
        const val MAX_QUEUE = 200
        const val FLUSH_INTERVAL_MILLIS = 30_000L

        fun encode(events: List<TelemetryEvent>): String {
            val array = JSONArray()
            for (event in events) {
                array.put(JSONObject().put("type", event.type).put("survey_id", event.surveyId))
            }
            return JSONObject().put("events", array).toString()
        }
    }
}
