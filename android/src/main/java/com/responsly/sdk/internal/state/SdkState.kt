package com.responsly.sdk.internal.state

/** What the SDK remembers about one survey. Times are epoch milliseconds. */
internal class SurveyRecord(
    var displayCount: Int = 0,
    var lastDisplayedAt: Long? = null,
    var completedAt: Long? = null,
    var dismissedCount: Int = 0,
)

/** The local state of section 3 that targeting reads. Plain data; [StateStore] persists it. */
internal class SdkState {
    var installId: String = ""
    var userId: String? = null

    /** Trait values are String, Double or Boolean. */
    val traits: MutableMap<String, Any?> = LinkedHashMap()
    var locale: String? = null
    var hiddenFields: Map<String, String> = emptyMap()
    var trackingEnabled: Boolean = true
    val eventCounts: MutableMap<String, Int> = LinkedHashMap()
    val surveys: MutableMap<String, SurveyRecord> = LinkedHashMap()
    var lastAutoDisplayAt: Long? = null

    /** Merges traits; a `null` value removes the trait. */
    fun mergeTraits(values: Map<String, Any?>) {
        for ((name, value) in values) {
            if (value == null) traits.remove(name) else traits[name] = value
        }
    }

    /** Counts a `track` call and returns the new count. */
    fun countEvent(name: String): Int {
        val count = (eventCounts[name] ?: 0) + 1
        eventCounts[name] = count
        return count
    }

    /** A survey became visible; [auto] when it was selected by targeting. */
    fun markDisplayed(surveyId: String, now: Long, auto: Boolean) {
        val record = record(surveyId)
        record.displayCount += 1
        record.lastDisplayedAt = now
        if (auto) lastAutoDisplayAt = now
    }

    fun markCompleted(surveyId: String, now: Long) {
        record(surveyId).completedAt = now
    }

    fun markDismissed(surveyId: String) {
        record(surveyId).dismissedCount += 1
    }

    /** `reset()`: everything except the install id and the tracking switch. */
    fun clear() {
        userId = null
        traits.clear()
        locale = null
        hiddenFields = emptyMap()
        eventCounts.clear()
        surveys.clear()
        lastAutoDisplayAt = null
    }

    private fun record(surveyId: String): SurveyRecord = surveys.getOrPut(surveyId) { SurveyRecord() }
}
