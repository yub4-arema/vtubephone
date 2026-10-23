package com.vtubephone.core.llm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmAudioTests {
    @Test fun wavHeaderMatchesActualPcmAndRecordingFormat() {
        val pcm = byteArrayOf(0, 1, -1, 127)
        val bytes = PcmWav.encode(pcm)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)); assertEquals(40, b.getInt(4))
        assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
        assertEquals(16, b.getInt(16)); assertEquals(1, b.getShort(20).toInt()); assertEquals(1, b.getShort(22).toInt())
        assertEquals(16000, b.getInt(24)); assertEquals(32000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt()); assertEquals(16, b.getShort(34).toInt())
        assertEquals(pcm.size, b.getInt(40)); assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }

    @Test fun rmsUsesAbsoluteTimeAndCombinesBuffersWithinBins() {
        val e = PcmEnvelope()
        fun pcm(value: Short, n: Int) = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(n) { putShort(value) }; flip()
        }
        e.add(pcm(4096, 400), 0, 24000, 1)
        e.add(pcm(-4096, 400), 16667, 24000, 1)
        e.add(pcm(0, 800), 33334, 24000, 1)
        val levels = e.levels()
        assertEquals(0.5f, levels[0], .001f); assertEquals(0f, levels[1], .001f)
    }

    @Test fun floatStereoRmsHandlesSilenceAndClipsToOne() {
        val e = PcmEnvelope()
        val b = ByteBuffer.allocate(8 * 800).order(ByteOrder.nativeOrder()).apply {
            repeat(800) { putFloat(1f); putFloat(-1f) }; flip()
        }
        e.add(b, 33334, 24000, 2, true)
        val levels = e.levels(); assertEquals(0f, levels[0], 0f); assertEquals(1f, levels[1], 0f)
    }
}
