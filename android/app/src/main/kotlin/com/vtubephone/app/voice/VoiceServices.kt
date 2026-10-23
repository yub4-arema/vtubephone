package com.vtubephone.app.voice

import android.content.Context
import com.vtubephone.app.BuildConfig
import com.vtubephone.core.brain.RuleBrain
import com.vtubephone.core.llm.*

class VoiceServices(context: Context) : AutoCloseable {
    private val transport = UrlConnectionTransport()
    val llm: LlmTurnSource? = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }?.let {
        LlmTurnSource(GeminiClient(transport, it), RuleBrain())
    }
    val recorder = VoiceRecorder()
    val player = VoicePlayer(context, TtsClient(transport, BuildConfig.VOICEVOX_API_KEY))

    override fun close() { llm?.close(); recorder.close(); player.close() }
}
