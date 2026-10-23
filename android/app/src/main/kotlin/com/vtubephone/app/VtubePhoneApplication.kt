package com.vtubephone.app

import android.app.Application
import com.vtubephone.app.brain.AppSettings
import com.vtubephone.app.brain.BrainService
import com.vtubephone.app.haptics.HapticsService
import com.vtubephone.app.sensing.SensingService
import com.vtubephone.app.voice.VoiceServices
import com.vtubephone.core.brain.DemoScript
import com.vtubephone.core.brain.RuleBrain
import org.json.JSONObject

class VtubePhoneApplication : Application() {
    val haptics: HapticsService by lazy {
        HapticsService(this).apply { setEnabled(settings.hapticsOn); setStrength(settings.hapticStrength) }
    }
    val sensing: SensingService by lazy { SensingService(this) }
    val voice: VoiceServices by lazy { VoiceServices(this) }
    val settings: AppSettings by lazy { AppSettings(this) }
    val brain: BrainService by lazy {
        val rules = RuleBrain()
        var demo = DemoScript()
        var wasDemo = false
        BrainService(haptics, sensing, settings,
            sourceFor = { s ->
                if (s.demoMode && !wasDemo) demo = DemoScript() // each demo run starts from the top
                wasDemo = s.demoMode
                if (s.demoMode) demo else voice.llm ?: rules
            },
            speak = { text -> voice.player.speak(text, settings.speaker); false },
        ).also { b -> voice.player.onMouth = { open -> b.toAvatar?.invoke(JSONObject().put("type", "mouth").put("open", open.toDouble())) } }
    }
}
