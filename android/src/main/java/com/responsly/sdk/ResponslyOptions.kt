package com.responsly.sdk

/**
 * Options for [Responsly.initialize].
 *
 * @property apiHost base URL of the Responsly API.
 * @property logLevel how much the SDK writes to Logcat (tag `Responsly`).
 */
public class ResponslyOptions @JvmOverloads constructor(
    public val apiHost: String = DEFAULT_API_HOST,
    public val logLevel: LogLevel = LogLevel.ERROR,
) {
    override fun toString(): String = "ResponslyOptions(apiHost=$apiHost, logLevel=$logLevel)"

    public companion object {
        public const val DEFAULT_API_HOST: String = "https://api.responsly.com"
    }
}

/** Log verbosity, from silent to everything. */
public enum class LogLevel {
    NONE,
    ERROR,
    INFO,

    /** Logs every targeting decision, configuration fetch and bridge message. */
    DEBUG,
}
