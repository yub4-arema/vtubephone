package com.vtubephone.app.haptics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.vtubephone.core.haptics.*

class HapticsService(context: Context) {
    private val engine = HapticEngine(context)
    val capabilities = engine.getCapabilities()
    val capabilityReport = buildString {
        appendLine("${capabilities.manufacturer} ${capabilities.model} / API ${capabilities.sdkInt}")
        appendLine("Best Tier: ${capabilities.bestTier}; vibrator: ${capabilities.hasVibrator}; amplitude control: ${capabilities.hasAmplitudeControl}")
        appendLine(capabilities.diagnostics)
        capabilities.primitives.forEach { p -> appendLine("${HapticPrimitive.entries.first { it.id == p.id }.key}: supported=${p.supported}, duration=${p.duration}ms (0=unknown)") }
        appendLine("Envelope: ${capabilities.envelope}")
        append("Resonance: ${capabilities.resonantFrequency}Hz; Q: ${capabilities.qFactor} (-1=unknown)")
    }
    private val limiter = HapticsRateLimiter()
    private val clips = context.assets.list("haptics")!!.filter { it.endsWith(".json") }.associate { file ->
        val clip = context.assets.open("haptics/$file").bufferedReader().use { HapticClipParser.parse(it.readText()) }
        clip.id to clip
    }
    private val descriptions = mutableMapOf<String, String>()
    private val preparedTiers = mutableMapOf<String, HapticTier>()
    private var bumpImpact = .5
    private var sequenceClip: HapticClip? = null
    private var suspended = true
    var tier = capabilities.bestTier; private set
    val strength get() = limiter.strength
    val enabled get() = limiter.enabled
    var blindMode = false
    var lastCallMicroseconds = 0.0; private set
    var lastNativeMicroseconds = 0.0; private set
    var logged: ((String) -> Unit)? = null
    init { rebuildCache(); Log.i(TAG, capabilityReport) }
    private fun log(message: String) { Log.i(TAG, message); logged?.invoke(message) }
    fun setTier(value: HapticTier): Boolean {
        if (!capabilities.supports(value)) return false
        engine.cancel(); tier = value; rebuildCache(); return true
    }
    fun setStrength(value: Double) { limiter.strength = value; engine.cancel(); rebuildCache() }
    fun setEnabled(value: Boolean) { limiter.enabled = value; if (!value) engine.cancel() }
    fun suspend(value: Boolean) { suspended = value; if (value) engine.cancel() }
    private fun prepare(key: String, clip: HapticClip) {
        val c = HapticRenderer.render(clip, tier, capabilities, strength)
        val status = engine.prepare(key, c)
        engine.preparedTier(key)?.let { preparedTiers[key] = it }
        descriptions[key] = "requested $tier; rendered ${c.tier}; native $status; ${c.reason}"
    }
    private fun rebuildCache() {
        engine.clearCache(); descriptions.clear(); preparedTiers.clear()
        if (!capabilities.hasVibrator) return
        clips.forEach { (key, clip) -> prepare(key, clip) }
        (1..4).forEach { prepare("knock$it", HapticClipFactory.knock(it)) }
        HapticPrimitive.entries.forEach { primitive ->
            listOf(.2, .5, 1.0).forEach { scale ->
                prepare(paletteKey(primitive, scale), HapticClip("palette", listOf(HapticEvent.primitive(0, primitive, scale))))
            }
        }
        prepare("__bump", HapticClipFactory.bump(bumpImpact))
        sequenceClip?.let { prepare("__sequence", it) }
    }
    fun play(id: String, spontaneous: Boolean = false): Boolean {
        require(clips.containsKey(id)) { "Unknown clip $id" }
        return playKey(id, spontaneous)
    }
    fun playKnock(count: Int): Boolean { require(count in 1..4); return playKey("knock$count", false) }
    fun clipTier(id: HapticClipId, count: Int): HapticTier? = preparedTiers[when (id) {
        HapticClipId.Knock -> "knock$count"
        HapticClipId.Bump -> "__bump"
        else -> id.key
    }]
    private fun paletteKey(primitive: HapticPrimitive, scale: Double) = "palette:${primitive.key}:$scale"
    fun paletteTier(primitive: HapticPrimitive, scale: Double) = preparedTiers[paletteKey(primitive, scale)]
    fun playPrimitive(primitive: HapticPrimitive, scale: Double): Boolean {
        require(scale in listOf(.2, .5, 1.0))
        return playKey(paletteKey(primitive, scale), false)
    }
    fun prepareSequence(clip: HapticClip) { sequenceClip = clip; prepare("__sequence", clip) }
    fun clearSequence() { sequenceClip = null; preparedTiers.remove("__sequence") }
    val sequenceTier get() = preparedTiers["__sequence"]
    fun playSequence(): Boolean = sequenceClip != null && playKey("__sequence", false)
    fun logSequence(json: String) { log("Sequence JSON: $json") }
    fun prepareBump(impact: Double) {
        require(impact.isFinite())
        bumpImpact = impact.coerceIn(0.0, 1.0)
        prepare("__bump", HapticClipFactory.bump(bumpImpact))
    }
    private fun allowed(spontaneous: Boolean) = !suspended && capabilities.hasVibrator &&
        limiter.tryAcquire(SystemClock.elapsedRealtimeNanos() / 1e9, spontaneous)
    private fun playKey(key: String, spontaneous: Boolean): Boolean = measured(key) {
        if (!allowed(spontaneous)) false else engine.play(key)
    }
    fun playBump(impact: Double): Boolean {
        require(impact.isFinite())
        return measured("__bump") {
            if (!allowed(false)) false else {
                // The bench prepares on editing; a new live collision includes construction in call timing.
                if (bumpImpact != impact.coerceIn(0.0, 1.0)) prepareBump(impact)
                engine.play("__bump")
            }
        }
    }
    private fun measured(key: String, action: () -> Boolean): Boolean {
        val start = System.nanoTime()
        val played = try { action() } catch (e: RuntimeException) { log("Playback failed: ${e.message}"); false }
        lastCallMicroseconds = (System.nanoTime() - start) / 1000.0
        lastNativeMicroseconds = if (played) engine.lastVibrateNs / 1000.0 else 0.0
        val detail = if (blindMode) "Blind sample" else "$key: ${descriptions[key].orEmpty()}"
        log("$detail; accepted=$played; call→return=${"%.0f".format(lastCallMicroseconds)}μs; native=${if (played) "%.0f".format(lastNativeMicroseconds) else "—"}μs")
        return played
    }
    companion object { const val TAG = "VtubePhone" }
}
