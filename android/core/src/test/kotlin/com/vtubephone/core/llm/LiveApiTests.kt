package com.vtubephone.core.llm

import com.vtubephone.core.brain.Trigger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: VTUBEPHONE_LIVE_SMOKE=1. Never log keys, request bodies or API responses. */
class LiveApiTests {
    private class LiveHttp : HttpTransport {
        var lastStatus = 0
        override fun post(url: String, headers: Map<String, String>, body: ByteArray) = request(url, headers, body)
        override fun get(url: String, headers: Map<String, String>) = request(url, headers, null)
        private fun request(url: String, headers: Map<String, String>, body: ByteArray?): HttpResult {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 3000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
                c.requestMethod = if (body == null) "GET" else "POST"
                headers.forEach(c::setRequestProperty)
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
                lastStatus = c.responseCode
                val stream = if (lastStatus in 200..299) c.inputStream else c.errorStream
                return HttpResult(lastStatus, stream?.use { it.readBytes() } ?: byteArrayOf(),
                    c.headerFields.entries.mapNotNull { (k, v) -> k?.let { it to v.joinToString(",") } }.toMap())
            } finally { c.disconnect() }
        }
    }

    @Test fun geminiStructuredTurnAgainstRealApi() {
        assumeTrue(System.getenv("VTUBEPHONE_LIVE_SMOKE") == "1")
        val properties = Properties().apply { File("../local.properties").inputStream().use { load(it) } }
        val key = properties.getProperty("GEMINI_API_KEY", "")
        assumeTrue(key.isNotBlank())
        val http = LiveHttp()
        val start = System.nanoTime()
        val turn = GeminiClient(http, key).generate(context(Trigger.Voice(null, "こんにちは")))
        assertNotNull("Gemini returned no valid turn (HTTP ${http.lastStatus})", turn)
        assertEquals("llm", turn!!.tag)
        assertTrue(turn.say.isNotBlank()); assertTrue(turn.options.size <= 3)
        println("Gemini smoke: HTTP ${http.lastStatus}, valid AgentTurn, elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
    }

    @Test fun voicevoxWithConfiguredKeyAgainstRealApi() {
        assumeTrue(System.getenv("VTUBEPHONE_LIVE_SMOKE") == "1")
        val properties = Properties().apply { File("../local.properties").inputStream().use { load(it) } }
        val http = LiveHttp()
        TtsClient(http, properties.getProperty("VOICEVOX_API_KEY", "")).use { client ->
            val start = System.nanoTime()
            val result = client.synthesize("こんにちは、きこえる？", 42)
            println("VOICEVOX keyed smoke: ${(result as? TtsResult.Failure)?.reason ?: "audio"}, HTTP ${http.lastStatus}, elapsedMs=${(System.nanoTime() - start) / 1_000_000}")
            assertTrue(result is TtsResult.Audio)
        }
    }

    @Test fun voicevoxKeylessAgainstRealApi() {
        assumeTrue(System.getenv("VTUBEPHONE_LIVE_SMOKE") == "1")
        val http = LiveHttp()
        TtsClient(http).use { client ->
            val result = client.synthesize("こんにちは", 2)
            assertTrue("VOICEVOX smoke: ${(result as? TtsResult.Failure)?.reason}, HTTP ${http.lastStatus}", result is TtsResult.Audio)
            val audio = result as TtsResult.Audio
            println("VOICEVOX smoke: HTTP ${http.lastStatus}, ${audio.mimeType}, ${audio.bytes.size} bytes")
        }
    }
}
