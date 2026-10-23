package com.vtubephone.core.llm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object PcmWav {
    /** 16 kHz, mono, signed little-endian PCM16, exactly the recorder/Gemini contract. */
    fun encode(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}

/** RMS in absolute 1/30-second bins, so decoder buffer boundaries never affect mouth timing. */
class PcmEnvelope {
    private val sums = DoubleArray(3600)
    private val counts = IntArray(3600)
    private var size = 0

    fun add(pcm: ByteBuffer, presentationUs: Long, sampleRate: Int, channels: Int, floatPcm: Boolean = false) {
        require(sampleRate > 0 && channels > 0)
        val width = if (floatPcm) 4 else 2
        val frames = pcm.remaining() / (width * channels)
        for (frame in 0 until frames) {
            val bin = ((presentationUs + frame * 1_000_000L / sampleRate) * 30 / 1_000_000).toInt()
            repeat(channels) {
                val sample = if (floatPcm) pcm.float.toDouble() else pcm.short / 32768.0
                if (bin in sums.indices && sample.isFinite()) {
                    sums[bin] += sample * sample; counts[bin]++; size = maxOf(size, bin + 1)
                }
            }
        }
    }

    fun levels(): FloatArray = FloatArray(size) { i ->
        // Fixed gain keeps quiet speech visible without opening the mouth on digital silence.
        if (counts[i] == 0) 0f else (sqrt(sums[i] / counts[i]) * 4).coerceIn(0.0, 1.0).toFloat()
    }
}
