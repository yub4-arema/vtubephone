package com.vtubephone.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.vtubephone.core.llm.PcmWav
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*

/** Permission is checked/requested by the caller. AudioRecord is released on every exit path. */
class VoiceRecorder : AutoCloseable {
    private class Session(val audio: AudioRecord) {
        val result = CompletableFuture<ByteArray?>()
        @Volatile var stopping = false
        var timer: ScheduledFuture<*>? = null
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-limit").apply { isDaemon = true } }
    private var current: Session? = null

    @SuppressLint("MissingPermission")
    @Synchronized fun start(): Boolean {
        if (current?.result?.isDone == false) return false
        var audio: AudioRecord? = null
        return try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, 3200)).build()
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val session = Session(audio)
            current = session
            session.timer = timer.schedule({ finish(session) }, 10, TimeUnit.SECONDS)
            Thread({ capture(session) }, "voice-record").apply { isDaemon = true; start() }
            true
        } catch (_: Exception) { audio?.release(); current = null; false }
    }

    /** Returns the auto-stopped recording too. At most one caller consumes each recording. */
    fun stop(): ByteArray? {
        val session = synchronized(this) { current } ?: return null
        finish(session)
        val result = try { session.result.get(1500, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
        catch (_: Exception) { null }
        synchronized(this) { if (current === session) current = null }
        return result
    }

    private fun finish(s: Session) = synchronized(s) {
        if (!s.stopping) {
            s.stopping = true
            runCatching { s.audio.stop() }
        }
    }

    private fun capture(s: Session) {
        val pcm = ByteBuffer.allocate(16000 * 2 * 10).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(800)
        var valid = true
        try {
            while (!s.stopping && pcm.remaining() >= 2) {
                val n = s.audio.read(samples, 0, minOf(samples.size, pcm.remaining() / 2), AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (!s.stopping) valid = false; break }
                for (i in 0 until n) pcm.putShort(samples[i])
            }
        } catch (_: Exception) { valid = false }
        finally {
            s.timer?.cancel(false)
            finish(s)
            s.audio.release()
            s.result.complete(if (valid && pcm.position() > 0) PcmWav.encode(pcm.array().copyOf(pcm.position())) else null)
        }
    }

    override fun close() { stop(); timer.shutdownNow() }
}
