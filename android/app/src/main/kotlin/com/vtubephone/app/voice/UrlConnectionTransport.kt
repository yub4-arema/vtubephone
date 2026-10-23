package com.vtubephone.app.voice

import android.os.Looper
import com.vtubephone.core.llm.HttpResult
import com.vtubephone.core.llm.HttpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UrlConnectionTransport(private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 10_000) : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: ByteArray) = request(url, headers, body)
    override fun get(url: String, headers: Map<String, String>) = request(url, headers, null)

    private fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "HTTP must run off the main thread" }
        check(!Thread.currentThread().isInterrupted) { "Request cancelled" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = if (body == null) "GET" else "POST"
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Request cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 16 * 1024 * 1024) throw IOException("Response too large")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            return HttpResult(status, bytes, connection.headerFields.entries.mapNotNull { (k, v) ->
                k?.let { it to v.joinToString(",") }
            }.toMap())
        } finally { connection.disconnect() }
    }
}
