package com.vtubephone.core.haptics

import kotlin.math.*

data class PrimitiveCommand(val id: Int, val delayMs: Int, val durationMs: Int, val scale: Float)
data class EnvelopePoint(val durationMs: Int = 0, val intensity: Float = 0f, val sharpness: Float = 0f)
data class HapticCommand(val requestedTier: HapticTier, val tier: HapticTier, val reason: String,
    val durationMs: Int, val timings: LongArray, val amplitudes: IntArray, val onOffTimings: LongArray,
    val primitives: List<PrimitiveCommand> = emptyList(), val points: List<EnvelopePoint> = emptyList())

object HapticRenderer {
    fun render(clip: HapticClip, requested: HapticTier, caps: HapticsCapabilities, strength: Double = 1.0): HapticCommand {
        require(strength.isFinite() && strength in 0.0..1.0)
        val fallback = waveform(clip, caps.hasAmplitudeControl, strength, requested)
        if (requested == HapticTier.D) return fallback.copy(tier = HapticTier.D, reason = "1ms pulse-density PWM; 5ms continuous source samples")
        if (requested == HapticTier.C) return fallback.copy(reason = "5ms amplitude samples; 1ms transient boundaries")
        if (!caps.supports(requested)) return fallback
        if (requested == HapticTier.A && clip.events.all { it.continuous }) {
            envelope(clip, caps.envelope, strength)?.let {
                return fallback.copy(tier = HapticTier.A, points = it, reason = "Continuous envelope; constrained uniform sampling")
            }
        }
        // Envelopes are smooth too; only primitives are refused for soft clips.
        if (clip.renderStyle == HapticRenderStyle.Waveform)
            return fallback.copy(reason = "Clip prefers a smooth waveform over primitives")
        if (clip.events.all { !it.continuous }) composition(clip, caps, strength)?.let {
            return fallback.copy(tier = HapticTier.B, primitives = it.commands,
                durationMs = it.commands.sumOf { p -> p.delayMs + p.durationMs },
                reason = "Primitives; device durations compensate pauses" +
                    if (it.shifts.isEmpty()) "; no timing shifts" else "; " + it.shifts.joinToString("; "))
        }
        return fallback
    }

    private fun waveform(clip: HapticClip, amplitude: Boolean, strength: Double, requested: HapticTier): HapticCommand {
        val values = IntArray(clip.durationMs) { t ->
            var level = 0.0
            clip.events.forEach { e ->
                val local = t - e.timeMs
                if (local in 0 until e.durationMs) {
                    val sample = if (e.continuous) local / 5 * 5 else 0
                    val gain = e.primitive?.amplitudeAt(local) ?: if (e.continuous) 1.0 else 0.65 + 0.35 * e.sharpness.at(0)
                    level = max(level, e.intensity.at(sample) * gain * strength)
                }
            }
            if (level <= 0) 0 else max(1, Math.rint(level * 255).toInt())
        }
        val timings = mutableListOf<Long>(); val amplitudes = mutableListOf<Int>()
        values.forEach { value ->
            if (amplitudes.lastOrNull() == value) timings[timings.lastIndex]++
            else { timings.add(1); amplitudes.add(value) }
        }
        val pwm = mutableListOf(0L); var on = false; var accumulated = 0.0
        values.forEach { value ->
            accumulated += value / 255.0
            val next = accumulated >= 1
            if (next) accumulated -= 1
            if (next != on) { pwm.add(0); on = next }
            pwm[pwm.lastIndex]++
        }
        return HapticCommand(requested, if (amplitude) HapticTier.C else HapticTier.D,
            "Waveform fallback: unsupported tier, mixed events, unknown duration, overlap or envelope constraints",
            clip.durationMs, timings.toLongArray(), amplitudes.toIntArray(), pwm.toLongArray())
    }

    private data class Composition(val commands: List<PrimitiveCommand>, val shifts: List<String>)
    private fun composition(clip: HapticClip, caps: HapticsCapabilities, strength: Double): Composition? {
        val result = mutableListOf<PrimitiveCommand>(); var end = 0
        val shifts = mutableListOf<String>()
        for ((index, e) in clip.events.withIndex()) {
            val sharp = e.sharpness.at(0)
            val id = e.primitive?.id ?: when { sharp >= .75 -> 7; sharp >= .5 -> 1; sharp >= .25 -> 8; else -> 2 }
            val capability = caps.primitive(id)
            if (caps.sdkInt < 31 || capability == null || capability.duration <= 0 || (clip.strictTiming && e.timeMs < end)) return null
            val start = max(e.timeMs, end)
            // Keep the one-minute bound even if many long device primitives must be shifted.
            if (capability.duration > 60000 - start) return null
            if (start > e.timeMs) shifts.add("event[$index] shifted +${start - e.timeMs}ms (${e.timeMs}→${start}ms)")
            result.add(PrimitiveCommand(id, start - end, capability.duration, (e.intensity.at(0) * strength).toFloat()))
            end = start + capability.duration
        }
        return Composition(result, shifts)
    }

    private fun envelope(clip: HapticClip, caps: EnvelopeCapability, strength: Double): List<EnvelopePoint>? {
        if (!caps.supported || caps.minControlPointMs <= 0 || caps.maxControlPointMs < caps.minControlPointMs || clip.durationMs > caps.maxDuration) return null
        val minimum = ceil(clip.durationMs.toDouble() / caps.maxControlPointMs).toInt()
        val maximum = min(caps.maxSize.toLong(), clip.durationMs / caps.minControlPointMs).toInt()
        if (minimum > maximum || maximum < 2) return null
        val segments = max(minimum, min(maximum, max(2, clip.durationMs / 20)))
        val points = mutableListOf(EnvelopePoint(sharpness = clip.events.first().sharpness.at(0).toFloat()))
        var previous = 0
        for (i in 1..segments) {
            val t = i * clip.durationMs / segments; var intensity = 0.0; var sharpness = 0.0
            clip.events.forEach { e ->
                val local = t - e.timeMs
                if (local in 0..e.durationMs) {
                    val value = e.intensity.at(local)
                    if (value >= intensity) { intensity = value; sharpness = e.sharpness.at(local) }
                }
            }
            points.add(EnvelopePoint(t - previous, if (i == segments) 0f else (intensity * strength).toFloat(), sharpness.toFloat()))
            previous = t
        }
        return points
    }
}
