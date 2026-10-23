package com.vtubephone.core.brain

import com.vtubephone.core.gesture.GestureEvent
import com.vtubephone.core.gesture.GestureId
import com.vtubephone.core.gesture.RhythmMatcher

/** Facial expressions the avatar layer understands (see assets/web avatar expression presets). */
enum class Expression(val wire: String) {
    Neutral("neutral"), Happy("happy"), Love("love"), Star("star"), Cry("cry"), Angry("angry"), Gloom("gloom"),
    Pout("pout"), Dizzy("dizzy"), Surprise("surprise"), Sleepy("sleepy"), Shy("shy"), Think("think");
    companion object { fun fromWire(w: String?) = entries.firstOrNull { it.wire == w } }
}

/** Whole-body motions of the avatar inside the "phone box". */
enum class Motion(val wire: String) {
    None("none"), Wave("wave"), Approach("approach"), Bounce("bounce"), Nod("nod"), ShakeHead("shake_head"),
    Lean("lean"), Hide("hide"), Peek("peek"), Spin("spin");
    companion object { fun fromWire(w: String?) = entries.firstOrNull { it.wire == w } }
}

data class HapticCue(val id: String, val count: Int? = null, val intensity: Double? = null)

/**
 * One answer the user can give with their body. Knock options may require a [knockCount] and/or a [rhythmMs]
 * (inter-onset intervals) which RhythmMatcher checks; screen and back knocks are interchangeable.
 */
data class DialogOption(val gesture: GestureId, val meaning: String, val label: String,
    val knockCount: Int? = null, val rhythmMs: List<Long>? = null) {
    fun matches(e: GestureEvent): Boolean {
        val knock = gesture == GestureId.Knock || gesture == GestureId.BackKnock
        if (!knock) return e.id == gesture
        if (e.id != GestureId.Knock && e.id != GestureId.BackKnock) return false
        rhythmMs?.let { return RhythmMatcher.matches(it, e) }
        return knockCount == null || e.count == knockCount
    }
}

data class StateDelta(val mood: Double = 0.0, val energy: Double = 0.0, val intimacy: Double = 0.0,
    val curiosity: Double = 0.0, val grudge: Double = 0.0)

/** One turn of the character, produced by the rule brain, the demo script or the LLM. */
data class AgentTurn(
    val say: String, val expression: Expression = Expression.Neutral, val haptic: HapticCue? = null,
    val motion: Motion = Motion.None, val options: List<DialogOption> = emptyList(), val timeoutSec: Int = 8,
    val stateDelta: StateDelta? = null, val tag: String = "",
    /** An option-less turn with [chain] = true asks for a follow-up turn after [timeoutSec] (used by scripts). */
    val chain: Boolean = false,
) { init { require(options.size <= 3) { "At most 3 options" } } }

/** The immediate, local reaction to a gesture (no network): it must feel instantaneous. */
data class Reflex(val expression: Expression, val haptic: HapticCue? = null, val motion: Motion = Motion.None,
    val say: String? = null, val delta: StateDelta = StateDelta())
