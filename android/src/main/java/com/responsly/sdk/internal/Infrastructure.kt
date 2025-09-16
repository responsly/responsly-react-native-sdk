package com.responsly.sdk.internal

import com.responsly.sdk.LogLevel

/** Wall clock, injected so targeting stays a deterministic function of its inputs. */
internal fun interface Clock {
    /** Milliseconds since the Unix epoch. */
    fun now(): Long
}

internal fun interface Cancellable {
    fun cancel()
}

/**
 * The SDK's threads. All state lives on the core thread; network calls run on the I/O thread;
 * listeners, the display guard and the UI run on the main thread.
 */
internal interface Scheduler {
    /** Runs [task] on the core thread, after everything submitted before it. */
    fun execute(task: () -> Unit)

    /** Runs [task] on the core thread after [delayMillis]. */
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable

    /** Runs a blocking network call. */
    fun io(task: () -> Unit)

    /** Runs [task] on the main thread. */
    fun main(task: () -> Unit)
}

internal fun interface LogSink {
    fun write(level: LogLevel, message: String, error: Throwable?)
}

internal class Logger(private val sink: LogSink) {
    @Volatile
    var level: LogLevel = LogLevel.ERROR

    fun error(message: String, error: Throwable? = null) {
        if (level >= LogLevel.ERROR) sink.write(LogLevel.ERROR, PREFIX + message, error)
    }

    inline fun info(message: () -> String) {
        if (level >= LogLevel.INFO) write(LogLevel.INFO, message())
    }

    inline fun debug(message: () -> String) {
        if (level >= LogLevel.DEBUG) write(LogLevel.DEBUG, message())
    }

    fun write(level: LogLevel, message: String) {
        sink.write(level, PREFIX + message, null)
    }

    private companion object {
        const val PREFIX = "[Responsly] "
    }
}
