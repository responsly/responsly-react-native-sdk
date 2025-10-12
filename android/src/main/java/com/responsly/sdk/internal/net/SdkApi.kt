package com.responsly.sdk.internal.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal sealed class ConfigResponse {
    class Ok(val body: String, val etag: String?) : ConfigResponse()

    object NotModified : ConfigResponse()

    /** `401`: the SDK key is not valid. */
    object InvalidKey : ConfigResponse()

    class Failed(val reason: String) : ConfigResponse()
}

internal enum class PostResult {
    SENT,

    /** The server refused the batch (4xx); sending it again would not help. */
    REJECTED,

    /** Network error or server error: keep the events and try again later. */
    RETRY,
}

/** The two HTTP calls of section 2. Blocking; called on the I/O thread. */
internal interface SdkApi {
    fun fetchConfig(etag: String?): ConfigResponse

    /** Posts `{"events":[...]}`. */
    fun postEvents(body: String): PostResult
}

internal class HttpSdkApi(
    apiHost: String,
    private val sdkKey: String,
    private val appId: String,
    private val sdkVersion: String,
    private val platform: String = "android",
) : SdkApi {
    private val base = apiHost.trim().trimEnd('/')

    override fun fetchConfig(etag: String?): ConfigResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("/sdk/v1/config")
            connection.requestMethod = "GET"
            if (!etag.isNullOrEmpty()) connection.setRequestProperty("If-None-Match", etag)
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    ConfigResponse.Ok(readText(connection.inputStream), connection.getHeaderField("ETag"))
                HttpURLConnection.HTTP_NOT_MODIFIED -> ConfigResponse.NotModified
                HttpURLConnection.HTTP_UNAUTHORIZED -> ConfigResponse.InvalidKey
                else -> ConfigResponse.Failed("HTTP $status")
            }
        } catch (e: Exception) {
            ConfigResponse.Failed(e.toString())
        } finally {
            connection?.disconnect()
        }
    }

    override fun postEvents(body: String): PostResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = open("/sdk/v1/events")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            when {
                status in 200..299 -> PostResult.SENT
                status == 408 || status == 429 -> PostResult.RETRY
                status in 400..499 -> PostResult.REJECTED
                else -> PostResult.RETRY
            }
        } catch (e: Exception) {
            PostResult.RETRY
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val connection = URL(base + path).openConnection() as? HttpURLConnection
            ?: throw IOException("apiHost must be an http(s) URL")
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.useCaches = false
        connection.setRequestProperty("X-Responsly-Sdk-Key", sdkKey)
        connection.setRequestProperty("X-Responsly-Platform", platform)
        connection.setRequestProperty("X-Responsly-Sdk-Version", sdkVersion)
        connection.setRequestProperty("X-Responsly-App-Id", appId)
        connection.setRequestProperty("Accept", "application/json")
        // Replaces the platform default, which carries the device model and OS build.
        connection.setRequestProperty("User-Agent", "ResponslySDK/$sdkVersion ($platform)")
        return connection
    }

    private fun readText(stream: InputStream): String = stream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_BODY_BYTES) throw IOException("configuration larger than $MAX_BODY_BYTES bytes")
        }
        String(output.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
