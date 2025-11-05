package com.responsly.sdk.internal.bridge

import org.json.JSONException
import org.json.JSONObject

/** A message from the survey page: `{"responsly":1,"type":"<type>","payload":{...}}`. */
internal class BridgeMessage(val type: String, val payload: JSONObject) {
    companion object {
        const val READY = "ready"
        const val RESIZE = "resize"
        const val PAGE_CHANGED = "page_changed"
        const val QUESTION_ANSWERED = "question_answered"
        const val COMPLETED = "completed"
        const val CLOSE_REQUESTED = "close_requested"
        const val ERROR = "error"

        /** Returns `null` for anything that is not a version-1 bridge message. */
        fun parse(raw: String?): BridgeMessage? {
            if (raw.isNullOrEmpty()) return null
            val json = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                return null
            }
            if ((json.opt("responsly") as? Number)?.toDouble() != 1.0) return null
            val type = (json.opt("type") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            return BridgeMessage(type, json.opt("payload") as? JSONObject ?: JSONObject())
        }
    }
}
