package com.vtubephone.core.llm

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TtsTests {
    private val wav = PcmWav.encode(ByteArray(320))
    private val job = """{"success":true,"audioStatusUrl":"https://audio1.tts.quest/status.json","mp3DownloadUrl":"https://audio1.tts.quest/audio.mp3"}"""

    @Test fun keyedV2PostsFormAndReturnsBinaryAudio() {
        val transport = MockHttp { url, headers, body ->
            assertEquals("deprecatedapis.tts.quest", URI(url).host)
            assertEquals("speaker=2", URI(url).query)
            assertEquals("application/x-www-form-urlencoded", headers["Content-Type"])
            val form = body!!.toString(Charsets.UTF_8)
            assertTrue(form.contains("text=%E3%81%82+%26")); assertTrue(form.contains("key=k%2B%26"))
            HttpResult(200, wav)
        }
        TtsClient(transport, "k+&").use {
            val result = it.synthesize("あ &", 2) as TtsResult.Audio
            assertArrayEquals(wav, result.bytes); assertEquals("audio/wav", result.mimeType)
        }
    }

    @Test fun rejectedKeyFallsBackToKeylessV3AndStaysThere() {
        var now = 0L
        val urls = ArrayList<String>()
        val transport = MockHttp { url, _, _ ->
            urls += url
            when {
                url.contains("deprecatedapis") -> jsonResult("{\n\t\"errorMessage\": \"invalidApiKey\"\n}", 403)
                url.contains("/synthesis?") -> jsonResult(job)
                url.endsWith("status.json") -> jsonResult("""{"success":true,"isAudioReady":true}""")
                else -> HttpResult(200, "ID3fake-mp3".toByteArray())
            }
        }
        TtsClient(transport, "bad", clock = { now }, sleep = { now += it }).use {
            assertTrue(it.synthesize("あ", 42) is TtsResult.Audio)
            assertTrue(it.synthesize("い", 42) is TtsResult.Audio)
        }
        assertEquals("the bad key is tried once, then v3 only", 1, urls.count { it.contains("deprecatedapis") })
    }

    @Test fun keylessV3PollsStatusUntilReadyThenDownloadsMp3() {
        var now = 0L
        val urls = ArrayList<String>()
        val transport = MockHttp { url, _, body ->
            assertNull(body); urls += url
            when (urls.size) {
                1 -> { assertTrue(url.contains("/v3/voicevox/synthesis?text=")); jsonResult(job) }
                2 -> jsonResult("""{"success":true,"isAudioReady":false}""")
                3 -> jsonResult("""{"success":true,"isAudioReady":true}""")
                else -> HttpResult(200, "ID3fake-mp3".toByteArray())
            }
        }
        TtsClient(transport, clock = { now }, sleep = { now += it }).use {
            assertEquals("audio/mpeg", (it.synthesize("こんにちは", 2) as TtsResult.Audio).mimeType)
            assertEquals(4, urls.size); assertEquals(urls[1], urls[2])
            assertEquals("https://audio1.tts.quest/audio.mp3", urls.last()); assertEquals(1000L, now)
        }
    }

    @Test fun retryAfterIsHonoredOnSynthesisAndPolling() {
        var now = 0L
        val times = ArrayList<Long>()
        val transport = MockHttp { _, _, _ ->
            times += now
            when (times.size) {
                1 -> jsonResult("""{"retryAfter":2}""", 429)
                2 -> jsonResult(job)
                3 -> jsonResult("""{"retryAfter":3}""")
                4 -> jsonResult("""{"isAudioReady":true}""")
                else -> HttpResult(200, wav)
            }
        }
        TtsClient(transport, clock = { now }, sleep = { now += it }).use {
            assertTrue(it.synthesize("a", 2) is TtsResult.Audio)
            assertEquals(listOf(0L, 2000L, 2500L, 5500L, 5500L), times)
        }
    }

    @Test fun longRetryIsRetainedAcrossUtterancesWithoutAnotherRequest() {
        var now = 0L
        var calls = 0
        val transport = MockHttp { _, _, _ -> calls++; jsonResult("""{"retryAfter":60}""") }
        TtsClient(transport, clock = { now }, sleep = { now += it }).use {
            assertEquals(TtsResult.Failure("retryAfter"), it.synthesize("a", 2))
            now = 5000
            assertEquals(TtsResult.Failure("retryAfter"), it.synthesize("b", 2))
            assertEquals(1, calls)
        }
    }

    @Test fun headerRetryAfterIsAlsoRespected() {
        var now = 0L
        var calls = 0
        val transport = MockHttp { _, _, _ ->
            calls++
            if (calls == 1) HttpResult(429, byteArrayOf(), mapOf("retry-after" to "2")) else HttpResult(200, wav)
        }
        TtsClient(transport, "key", clock = { now }, sleep = { now += it }).use {
            assertTrue(it.synthesize("a", 2) is TtsResult.Audio); assertEquals(2000L, now)
        }
    }

    @Test fun v2ErrorsPreserveFailureReason() {
        listOf("invalidApiKey", "notEnoughPoints", "failed").forEach { reason ->
            TtsClient(MockHttp { _, _, _ -> jsonResult(reason) }, "key").use {
                assertEquals(TtsResult.Failure(reason), it.synthesize("a", 2))
            }
        }
        TtsClient(MockHttp { _, _, _ -> jsonResult("""{"success":false,"error":"invalidApiKey"}""") }, "key").use {
            assertEquals(TtsResult.Failure("invalidApiKey"), it.synthesize("a", 2))
        }
    }

    @Test fun v3AudioErrorAndHttpAndMalformedResponseFailQuietly() {
        listOf(jsonResult("""{"success":false}"""), jsonResult("""{"isAudioError":true}"""),
            jsonResult("broken"), jsonResult("down", 503), jsonResult("{}")).forEach { response ->
            TtsClient(MockHttp { _, _, _ -> response }).use {
                assertTrue(it.synthesize("a", 2) is TtsResult.Failure)
            }
        }
    }

    @Test fun missingStatusUrlDoesNotCreateDuplicateSynthesisJobs() {
        var calls = 0
        TtsClient(MockHttp { _, _, _ ->
            calls++; jsonResult("""{"success":true,"mp3DownloadUrl":"https://audio1.tts.quest/audio.mp3"}""")
        }).use {
            assertEquals(TtsResult.Failure("missingStatusUrl"), it.synthesize("a", 2))
            assertEquals(1, calls)
        }
    }

    @Test fun pendingSynthesisStopsAtFifteenSeconds() {
        var now = 0L
        var calls = 0
        val transport = MockHttp { _, _, _ -> calls++; jsonResult(if (calls == 1) job else """{"isAudioReady":false}""") }
        TtsClient(transport, clock = { now }, sleep = { now += it }).use {
            assertTrue(it.synthesize("a", 2) is TtsResult.Failure)
            assertTrue(now <= 15_000); assertTrue(calls <= 30)
        }
    }

    @Test fun noDownloadBeforeReadinessAndOnlyTtsQuestHosts() {
        var calls = 0
        var now = 0L
        val malicious = job.replace("https://audio1.tts.quest/status.json", "https://audio1.tts.quest.evil.test/status")
        TtsClient(MockHttp { _, _, _ -> calls++; jsonResult(malicious) }, clock = { now }, sleep = { now += it }).use {
            assertEquals(TtsResult.Failure("invalidUrl"), it.synthesize("a", 2))
            assertEquals(1, calls)
        }
    }

    @Test fun cancelledCallerInterruptsPolling() {
        val entered = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val transport = MockHttp { _, _, _ -> jsonResult(job) }
        TtsClient(transport, sleep = {
            entered.countDown()
            try { CountDownLatch(1).await() } finally { stopped.countDown() }
        }).use { client ->
            val caller = Thread { assertTrue(client.synthesize("a", 2) is TtsResult.Failure) }
            caller.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); caller.interrupt()
            caller.join(1000); assertFalse(caller.isAlive); assertTrue(stopped.await(1, TimeUnit.SECONDS))
        }
    }
}
