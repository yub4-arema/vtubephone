package com.vtubephone.core.haptics

enum class HapticClipId { Knock, Thud, Bump, Yes, No, Call, Purr, Heartbeat, Nervous, Giggle, Dizzy, Poke,
    Surprise, Sigh, Excited, Sleepy, Angry, Shy;
    val key: String get() = name.lowercase(java.util.Locale.ROOT)
}
enum class HapticTier { A, B, C, D }

class HapticParameter(points: List<List<Double>>) {
    private val points = points.map { it.toList() }
    constructor(value: Double) : this(listOf(listOf(0.0, value)))
    init {
        require(this.points.isNotEmpty()) { "Empty curve" }
        this.points.forEachIndexed { i, p ->
            require(p.size == 2 && p[0].isFinite() && p[0] >= 0 && p[1].isFinite() && p[1] in 0.0..1.0 &&
                if (i == 0) p[0] == 0.0 else p[0] > this.points[i - 1][0]) { "Invalid curve" }
        }
    }
    fun at(ms: Double): Double {
        for (i in 1 until points.size) if (ms < points[i][0]) {
            val a = points[i - 1]; val b = points[i]
            return a[1] + (b[1] - a[1]) * ((ms - a[0]) / (b[0] - a[0])).coerceAtLeast(0.0)
        }
        return points.last()[1]
    }
    fun at(ms: Int): Double = at(ms.toDouble())
}

class HapticEvent(val timeMs: Int, val continuous: Boolean, durationMs: Int,
    val intensity: HapticParameter, val sharpness: HapticParameter, val primitive: HapticPrimitive? = null) {
    val durationMs: Int
    init {
        require(timeMs in 0..60000 && (!continuous || durationMs in 1..60000)) { "Invalid timing" }
        require(primitive == null || !continuous) { "Primitive cannot be continuous" }
        // C# Math.Round uses ties-to-even, unlike Kotlin roundToInt.
        this.durationMs = primitive?.approximationMs ?: if (continuous) durationMs else Math.rint(20 - 10 * sharpness.at(0)).toInt()
    }
    companion object {
        fun primitive(timeMs: Int, name: HapticPrimitive, scale: Double) =
            HapticEvent(timeMs, false, 0, HapticParameter(scale), HapticParameter(0.0), name)
    }
}

/**
 * How a clip prefers to be felt, chosen per emotion rather than per device: [Waveform] keeps soft, blurry feelings
 * (purr, sleepy, sigh) as smooth amplitude curves even on devices with crisp primitives; [Auto] uses the best tier.
 */
enum class HapticRenderStyle(val key: String) {
    Auto("auto"), Waveform("waveform");
    companion object { fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown render style $key") }
}

class HapticClip(val id: String, events: List<HapticEvent>, val strictTiming: Boolean = false,
    val renderStyle: HapticRenderStyle = HapticRenderStyle.Auto) {
    val events: List<HapticEvent> = events.sortedBy { it.timeMs }
    val durationMs: Int
    init {
        require(id.isNotBlank() && events.size in 1..256) { "Invalid clip" }
        durationMs = this.events.maxOf { it.timeMs + it.durationMs }
        require(durationMs <= 60000) { "Clip exceeds one minute" }
    }
}

object HapticClipFactory {
    fun knock(count: Int, intervalMs: Int = 170, intensity: Double = 0.65): HapticClip {
        require(count in 1..4 && intervalMs in 20..1000)
        return HapticClip("knock", List(count) {
            HapticEvent.primitive(it * intervalMs, HapticPrimitive.LowTick, intensity)
        }, strictTiming = true)
    }
    fun bump(impact: Double): HapticClip {
        require(impact.isFinite())
        val value = impact.coerceIn(0.0, 1.0)
        return HapticClip("bump", listOf(HapticEvent.primitive(0,
            if (value < .6) HapticPrimitive.Click else HapticPrimitive.Thud, value)))
    }
}

data class PrimitiveCapability(val id: Int, val supported: Boolean, val duration: Int)
data class EnvelopeCapability(val supported: Boolean = false, val maxSize: Int = 0,
    val minControlPointMs: Long = 0, val maxControlPointMs: Long = 0, val maxDuration: Long = 0)
data class HapticsCapabilities(val sdkInt: Int, val hasVibrator: Boolean, val hasAmplitudeControl: Boolean,
    val manufacturer: String = "", val model: String = "", val diagnostics: String = "",
    val resonantFrequency: Float = -1f, val qFactor: Float = -1f,
    val primitives: List<PrimitiveCapability> = emptyList(), val envelope: EnvelopeCapability = EnvelopeCapability()) {
    fun supports(tier: HapticTier): Boolean = hasVibrator && when (tier) {
        HapticTier.A -> sdkInt >= 36 && envelope.supported
        HapticTier.B -> sdkInt >= 31 && primitives.any { it.supported && it.duration > 0 }
        HapticTier.C -> sdkInt >= 26 && hasAmplitudeControl
        HapticTier.D -> true
    }
    val bestTier: HapticTier get() = HapticTier.entries.firstOrNull(::supports) ?: HapticTier.D
    fun primitive(id: Int): PrimitiveCapability? = primitives.firstOrNull { it.id == id && it.supported }
}
