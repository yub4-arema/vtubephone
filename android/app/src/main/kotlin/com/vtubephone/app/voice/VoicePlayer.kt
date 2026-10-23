package com.vtubephone.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.vtubephone.core.llm.TtsClient
import com.vtubephone.core.llm.TtsResult
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** speak/stop may be called from any thread. All player operations and mouth callbacks run on main. */
class VoicePlayer(context: Context, private val tts: TtsClient,
    onMouth: (Float) -> Unit = {}) : AutoCloseable {
    private val cacheDir = context.applicationContext.cacheDir
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-play").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var request: Future<*>? = null
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var tick: Runnable? = null
    @Volatile var onMouth: (Float) -> Unit = onMouth

    @Synchronized fun speak(text: String, speaker: Int) {
        val id = generation.incrementAndGet()
        request?.cancel(true)
        main.post { if (generation.get() == id) releasePlayer() }
        if (text.isBlank() || worker.isShutdown) return
        request = worker.submit {
            var file: File? = null
            try {
                val result = tts.synthesize(text, speaker) as? TtsResult.Audio
                    ?: return@submit android.util.Log.w("VtubePhoneVoice", "TTS failed: ${tts.lastFailure}").let { }
                if (generation.get() != id || Thread.currentThread().isInterrupted) return@submit
                val localFile = File.createTempFile("speech-", if (result.mimeType == "audio/wav") ".wav" else ".mp3", cacheDir)
                file = localFile
                localFile.writeBytes(result.bytes)
                val levels = AudioEnvelopeDecoder.decode(localFile)
                if (generation.get() != id || Thread.currentThread().isInterrupted) return@submit
                // Transfer file ownership to main, including the stale-generation cleanup path.
                if (main.post {
                    if (generation.get() != id) localFile.delete()
                    else play(localFile, levels, id)
                }) file = null
            } catch (_: Exception) {
                // Caption-only fallback. No synthetic mouth motion on a decoding/playback failure.
            } finally { file?.delete() }
        }
    }

    private fun play(file: File, levels: FloatArray, id: Long) {
        releasePlayer()
        audioFile = file
        try {
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.setOnPreparedListener {
                if (generation.get() != id || player !== p) return@setOnPreparedListener
                try {
                    p.start()
                    val update = object : Runnable {
                        override fun run() {
                            if (generation.get() != id || player !== p) return
                            try {
                                val index = p.currentPosition.toLong() * 30 / 1000
                                onMouth(levels.getOrElse(index.toInt()) { 0f })
                                main.postDelayed(this, 33)
                            } catch (_: Exception) { releasePlayer() }
                        }
                    }
                    tick = update
                    main.post(update)
                } catch (_: Exception) { releasePlayer() }
            }
            p.setOnCompletionListener { if (player === p) releasePlayer() }
            p.setOnErrorListener { _, _, _ -> if (player === p) releasePlayer(); true }
            p.prepareAsync()
        } catch (_: Exception) { releasePlayer() }
    }

    @Synchronized fun stop() {
        val id = generation.incrementAndGet()
        request?.cancel(true); request = null
        main.post { if (generation.get() == id) releasePlayer() }
    }

    private fun releasePlayer() {
        tick?.let(main::removeCallbacks); tick = null
        player?.release(); player = null
        audioFile?.delete(); audioFile = null
        runCatching { onMouth(0f) }
    }

    override fun close() { stop(); worker.shutdownNow(); tts.close() }
}
