package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class JsonHttpClientTest {
    private val url = URL("https://test.qweatherapi.com/weather")
    private class Connection(private val body: ByteArray, private val status: Int = 200, private val encoding: String? = null) : HttpURLConnection(URL("https://example.org")) {
        var disconnected = false
        var bodyRead = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode() = status
        override fun getContentEncoding() = encoding
        override fun getInputStream() = ByteArrayInputStream(body).also { bodyRead = true }
    }

    @Test fun gzipResponseIsDecodedAndRequestIsBounded() {
        val bytes = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write("{\"text\":\"晴\"}".toByteArray()) } }.toByteArray()
        val connection = Connection(bytes, encoding = "gzip")
        val result = JsonHttpClient(openConnection = { connection }).get(url, mapOf("X-QW-Api-Key" to "test"), NetworkCancellation())
        assertEquals("晴", result.getString("text"))
        assertEquals(8000, connection.connectTimeout); assertEquals(12000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects); assertTrue(connection.disconnected)
        assertEquals("gzip", connection.getRequestProperty("Accept-Encoding"))
    }

    @Test fun httpErrorsAndRedirectsNeverReadBodiesOrForwardCredentials() {
        for (status in listOf(301, 302, 401, 403, 429, 500)) {
            val connection = Connection("secret-server-error".toByteArray(), status)
            val failure = assertThrows(HttpStatusException::class.java) {
                JsonHttpClient(openConnection = { connection }).get(url, mapOf("X-QW-Api-Key" to "secret"), NetworkCancellation())
            }
            assertEquals(status, failure.status); assertTrue(connection.disconnected); assertFalse(connection.bodyRead)
        }
    }

    @Test fun malformedJsonAndOversizedDecompressedDataAreRejected() {
        val broken = Connection("not json".toByteArray())
        assertThrows(IOException::class.java) { JsonHttpClient(openConnection = { broken }).get(url, emptyMap(), NetworkCancellation()) }
        val large = Connection("{\"large\":\"${"a".repeat(200)}\"}".toByteArray())
        assertThrows(IOException::class.java) { JsonHttpClient(maxResponseBytes = 100, openConnection = { large }).get(url, emptyMap(), NetworkCancellation()) }
        assertTrue(broken.disconnected); assertTrue(large.disconnected)
    }

    @Test fun cancellationAndCleartextFailBeforeConnectionCreation() {
        val client = JsonHttpClient(openConnection = { throw AssertionError("must not open") })
        assertThrows(CancellationException::class.java) { client.get(url, emptyMap(), NetworkCancellation().also { it.cancel() }) }
        assertThrows(IllegalArgumentException::class.java) { client.get(URL("http://example.org"), emptyMap(), NetworkCancellation()) }
    }
}
