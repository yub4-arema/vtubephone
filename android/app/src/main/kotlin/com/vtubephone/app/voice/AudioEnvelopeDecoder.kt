package com.vtubephone.app.voice

import android.media.*
import android.os.SystemClock
import com.vtubephone.core.llm.PcmEnvelope
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decode locally; no Visualizer/audio-session capture or additional recording permission. */
internal object AudioEnvelopeDecoder {
    fun decode(file: File): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
                .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            var format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val envelope = PcmEnvelope()
            fun add(buffer: ByteBuffer, timeUs: Long) {
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                envelope.add(buffer.order(ByteOrder.nativeOrder()), timeUs,
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    encoding == AudioFormat.ENCODING_PCM_FLOAT)
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            fun checkRunning() {
                check(!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) { "Decode cancelled/timed out" }
            }
            if (mime == "audio/raw") {
                val buffer = ByteBuffer.allocate(1024 * 1024)
                while (true) {
                    checkRunning(); buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) break
                    buffer.position(0); buffer.limit(count)
                    add(buffer, extractor.sampleTime)
                    extractor.advance()
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                while (!outputEnded) {
                    checkRunning()
                    if (!inputEnded) {
                        val i = decoder.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(i)).apply { clear() }
                            val count = extractor.readSampleData(input, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(i, 0, count, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val i = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = decoder.outputFormat
                        else -> if (i >= 0) {
                            try {
                                if (info.size > 0) {
                                    val output = requireNotNull(decoder.getOutputBuffer(i))
                                    output.position(info.offset); output.limit(info.offset + info.size)
                                    add(output, info.presentationTimeUs)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { decoder.releaseOutputBuffer(i, false) }
                        }
                    }
                }
            }
            return envelope.levels()
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
