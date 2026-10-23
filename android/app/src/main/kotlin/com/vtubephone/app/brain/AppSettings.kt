package com.vtubephone.app.brain

import android.content.Context

/** User settings, persisted in SharedPreferences. Haptic strength/on-off live in HapticsService. */
class AppSettings(context: Context) : Settings {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override var demoMode: Boolean
        get() = prefs.getBoolean("demoMode", false)
        set(v) = prefs.edit().putBoolean("demoMode", v).apply()
    override var voice: Boolean
        get() = prefs.getBoolean("voice", false)
        set(v) = prefs.edit().putBoolean("voice", v).apply()
    override var captionScale: Double
        get() = prefs.getFloat("captionScale", 1f).toDouble()
        set(v) = prefs.edit().putFloat("captionScale", v.toFloat()).apply()
    /** 0 = 少なめ, 1 = ふつう, 2 = 多め */
    var initiativeLevel: Int
        get() = prefs.getInt("initiativeLevel", 1)
        set(v) = prefs.edit().putInt("initiativeLevel", v.coerceIn(0, 2)).apply()
    override val initiativeIntervalMs: Long get() = longArrayOf(120_000, 45_000, 25_000)[initiativeLevel]
    /** VOICEVOX speaker id (su-shiki list: https://static.tts.quest/voicevox_speakers_utf8.json, index = id). */
    var speaker: Int
        get() = prefs.getInt("speaker", 42)
        set(v) = prefs.edit().putInt("speaker", v.coerceAtLeast(0)).apply()
    var hapticStrength: Double
        get() = prefs.getFloat("hapticStrength", 1f).toDouble()
        set(v) = prefs.edit().putFloat("hapticStrength", v.toFloat()).apply()
    var hapticsOn: Boolean
        get() = prefs.getBoolean("hapticsOn", true)
        set(v) = prefs.edit().putBoolean("hapticsOn", v).apply()
}
