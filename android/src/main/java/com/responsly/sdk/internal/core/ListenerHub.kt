package com.responsly.sdk.internal.core

import com.responsly.sdk.ResponslyListener
import com.responsly.sdk.internal.Logger
import com.responsly.sdk.internal.Scheduler
import java.util.concurrent.CopyOnWriteArrayList

/** The registered listeners; callbacks are delivered on the main thread, in the order they were raised. */
internal class ListenerHub(private val scheduler: Scheduler, private val logger: Logger) {
    private val listeners = CopyOnWriteArrayList<ResponslyListener>()

    fun add(listener: ResponslyListener) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: ResponslyListener) {
        listeners.remove(listener)
    }

    fun dispatch(call: (ResponslyListener) -> Unit) {
        scheduler.main {
            for (listener in listeners) {
                try {
                    call(listener)
                } catch (e: Exception) {
                    logger.error("A ResponslyListener threw an exception", e)
                }
            }
        }
    }
}
