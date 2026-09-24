package com.localvoicetv

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/** One cancellation token per user request, including all its HTTP calls. */
class NetworkCancellation {
    @Volatile private var cancelled = false
    private var connection: HttpURLConnection? = null

    fun check() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    @Synchronized fun attach(value: HttpURLConnection) {
        check()
        connection = value
    }

    @Synchronized fun detach() { connection = null }

    fun cancel() {
        val active = synchronized(this) {
            cancelled = true
            connection.also { connection = null }
        }
        active?.disconnect()
    }
}

class HttpStatusException(val status: Int) : IOException("HTTP $status")

fun interface JsonTransport {
    fun get(url: URL, headers: Map<String, String>, cancellation: NetworkCancellation): JSONObject
}

/** Shared HTTPS transport. No credentials in URLs/logs, redirects or automatic retries. */
class JsonHttpClient(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val maxResponseBytes: Int = 1_048_576,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : JsonTransport {
    override fun get(url: URL, headers: Map<String, String>, cancellation: NetworkCancellation): JSONObject {
        require(url.protocol == "https" && url.userInfo == null) { "联网接口必须使用 HTTPS" }
        cancellation.check()
        val connection = openConnection(url)
        val started = System.nanoTime()
        val stage = when {
            url.path.startsWith("/geo/") -> "city"
            url.path.startsWith("/weather/") -> "weather"
            else -> "location"
        }
        var outcome = "cancelled"
        Log.i("NetworkRequest", "start stage=$stage")
        try {
            cancellation.attach(connection)
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val status = connection.responseCode
            outcome = "http_$status"
            cancellation.check()
            if (status != HttpURLConnection.HTTP_OK) throw HttpStatusException(status)
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            val bytes = stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    cancellation.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) throw IOException("接口返回数据过大")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            cancellation.check()
            return try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: JSONException) {
                throw IOException("接口返回的不是有效 JSON")
            }
        } catch (error: Exception) {
            if (error !is HttpStatusException) outcome = error.javaClass.simpleName
            throw error
        } finally {
            Log.i("NetworkRequest", "finish stage=$stage result=$outcome elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
            cancellation.detach()
            connection.disconnect()
        }
    }
}
