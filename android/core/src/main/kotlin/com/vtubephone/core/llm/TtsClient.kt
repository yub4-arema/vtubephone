package com.vtubephone.core.llm

import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.*
import kotlinx.serialization.json.*

sealed interface TtsResult {
    data class Audio(val bytes: ByteArray, val mimeType: String) : TtsResult
    data class Failure(val reason: String) : TtsResult
}

/** su-shiki v2/v3 protocol: https://voicevox.su-shiki.com/su-shikiapis/ (and ttsquest/). */
class TtsClient(private val transport: HttpTransport, private val apiKey: String? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }) : AutoCloseable {
    private val executor = requestExecutor("voicevox-request")
    // Retain server cooldown across utterances, including a retry longer than this call's deadline.
    private var notBeforeMs = Long.MIN_VALUE / 2
    // A rejected or exhausted key switches to the keyless low-speed API for the rest of the session.
    @Volatile private var keyRejected = false
    /** Last failure reason, for logs (never contains the key). */
    @Volatile var lastFailure: String? = null; private set

    fun synthesize(text: String, speaker: Int): TtsResult {
        if (text.isBlank() || speaker < 0) return TtsResult.Failure("invalidInput")
        val request = try { executor.submit<TtsResult> {
            val fast = !apiKey.isNullOrBlank() && !keyRejected
            val first = fetch(text, speaker, fast)
            if (fast && first is TtsResult.Failure && first.reason != "cancelled") {
                if (first.reason in listOf("invalidApiKey", "notEnoughPoints")) keyRejected = true
                fetch(text, speaker, fast = false)
            } else first
        } }
        catch (_: RejectedExecutionException) { return TtsResult.Failure("busy") }
        val result = try { request.get(30_000, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { TtsResult.Failure("timeout") }
        catch (e: ExecutionException) { TtsResult.Failure("transport: ${e.cause?.let { it::class.simpleName + " " + it.message }}") }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); TtsResult.Failure("cancelled") }
        finally { request.cancel(true); executor.remove(request as Runnable) }
        lastFailure = (result as? TtsResult.Failure)?.reason
        return result
    }

    private fun fetch(text: String, speaker: Int, fast: Boolean): TtsResult {
        val deadline = clock() + 15_000
        var url = if (fast) "https://deprecatedapis.tts.quest/v2/voicevox/audio/?speaker=$speaker"
            else "https://api.tts.quest/v3/voicevox/synthesis?text=${encode(text)}&speaker=$speaker"
        var post = fast
        var downloadUrl: String? = null
        var polling = false
        var downloading = false
        var networkErrors = 0
        while (clock() < deadline && !Thread.currentThread().isInterrupted) {
            val wait = notBeforeMs - clock()
            if (wait > 0) {
                if (wait >= deadline - clock()) return TtsResult.Failure("retryAfter")
                sleep(wait)
                continue
            }
            if (!allowedUrl(url)) return TtsResult.Failure("invalidUrl")
            val response = try {
                if (post) transport.post(url,
                    mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    "text=${encode(text)}&key=${encode(apiKey.orEmpty())}&speaker=$speaker".toByteArray())
                else transport.get(url)
            } catch (e: java.io.IOException) {
                // Mobile DNS/network hiccups (seen on device: UnknownHost for audio2.tts.quest) are retried in budget.
                if (Thread.currentThread().isInterrupted || ++networkErrors > 3) throw e
                notBeforeMs = clock() + 400L * networkErrors
                continue
            }
            if (clock() >= deadline) return TtsResult.Failure("timeout")
            if (response.successful) audioType(response.body)?.let { return TtsResult.Audio(response.body, it) }
            val plain = response.text().trim()
            if (plain.trim('"') in listOf("invalidApiKey", "notEnoughPoints", "failed"))
                return TtsResult.Failure(plain.trim('"'))
            val obj = try { Json.parseToJsonElement(plain) as? JsonObject } catch (_: IllegalArgumentException) { null }
            // v2 answers {"errorMessage": "invalidApiKey"} with HTTP 403; v3 uses "error"/"errorMessage".
            val error = obj?.string("error") ?: obj?.string("errorMessage")
            if (error in listOf("invalidApiKey", "notEnoughPoints")) return TtsResult.Failure(error!!)
            val retrySeconds = maxOf(obj?.number("retryAfter") ?: 0.0,
                response.header("Retry-After")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0)
            if (retrySeconds > 0) {
                val delay = kotlin.math.ceil(retrySeconds * 1000).coerceAtMost((Long.MAX_VALUE / 4).toDouble()).toLong()
                notBeforeMs = clock() + delay
                // Reissue the same operation only after the full specified interval.
                continue
            }
            if (!response.successful) return TtsResult.Failure("http${response.status}")
            if (obj == null) return TtsResult.Failure("invalidResponse")
            if (obj["success"] == JsonPrimitive(false) || obj["isAudioError"] == JsonPrimitive(true))
                return TtsResult.Failure(error ?: "synthesisFailed")
            if (downloading) return TtsResult.Failure("invalidAudio")
            obj.string("mp3DownloadUrl")?.let { downloadUrl = it }
            if (downloadUrl == null) obj.string("wavDownloadUrl")?.let { downloadUrl = it }
            if (obj["isAudioReady"] == JsonPrimitive(true)) {
                url = downloadUrl ?: return TtsResult.Failure("missingAudioUrl")
                post = false
                downloading = true
            } else {
                val status = obj.string("audioStatusUrl")
                if (status != null) { url = status; post = false; polling = true }
                if (!polling) return TtsResult.Failure("missingStatusUrl")
                // Poll status, never create another synthesis job while this job is pending.
                notBeforeMs = clock() + 500
            }
        }
        return TtsResult.Failure("timeout")
    }

    private fun allowedUrl(url: String) = runCatching {
        val u = URI(url)
        u.scheme == "https" && u.host?.lowercase()?.endsWith(".tts.quest") == true &&
            u.rawUserInfo == null && (u.port == -1 || u.port == 443)
    }.getOrDefault(false)

    private fun audioType(b: ByteArray): String? {
        if (b.size >= 12 && b.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            b.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") return "audio/wav"
        if (b.size >= 3 && (b.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3" ||
                (b[0].toInt() and 255 == 255 && b[1].toInt() and 224 == 224))) return "audio/mpeg"
        return null
    }

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
    override fun close() { executor.shutdownNow() }
}
