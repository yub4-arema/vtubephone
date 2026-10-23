package com.vtubephone.core.haptics

import kotlinx.serialization.json.*

data class HapticSequenceStep(val primitive: HapticPrimitive, val scale: Double, val pauseMs: Int) {
    init {
        require(scale.isFinite() && scale in 0.0..1.0) { "Invalid scale" }
        require(pauseMs in 0..5000) { "Pause must be 0–5000ms" }
    }
}

/** A pause is measured from the previous primitive's end (the first pause is a lead-in). */
class HapticSequence(steps: List<HapticSequenceStep>) {
    val steps = steps.toList()
    init { require(this.steps.size in 1..32) { "Sequence must have 1–32 steps" } }

    fun toClip(caps: HapticsCapabilities): HapticClip {
        var end = 0
        val events = steps.map { step ->
            val start = end + step.pauseMs
            val deviceDuration = if (caps.sdkInt >= 31) caps.primitive(step.primitive.id)?.duration else null
            val duration = deviceDuration?.takeIf { it > 0 } ?: step.primitive.approximationMs
            require(start <= 60000 && duration <= 60000 - start) { "Sequence exceeds one minute" }
            end = start + duration
            HapticEvent.primitive(start, step.primitive, step.scale)
        }
        return HapticClip("sequence", events)
    }

    /** Exports the same start times used for playback on the current device. */
    fun toJson(caps: HapticsCapabilities): String {
        val clip = toClip(caps)
        return buildJsonObject {
            put("id", clip.id)
            put("strictTiming", false)
            putJsonArray("events") {
                clip.events.forEach { e -> addJsonObject {
                    put("t", e.timeMs); put("type", "primitive")
                    put("name", requireNotNull(e.primitive).key); put("scale", e.intensity.at(0))
                } }
            }
        }.toString()
    }
}
