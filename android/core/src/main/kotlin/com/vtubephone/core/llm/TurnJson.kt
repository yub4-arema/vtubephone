package com.vtubephone.core.llm

import com.vtubephone.core.brain.*
import com.vtubephone.core.gesture.GestureId
import com.vtubephone.core.haptics.HapticClipId
import kotlinx.serialization.json.*

object TurnJson {
    fun parse(json: String): AgentTurn? = try {
        val o = Json.parseToJsonElement(json) as? JsonObject
        o?.string("say")?.takeIf { it.isNotBlank() }?.let { say ->
            val h = o["haptic"] as? JsonObject
            val haptic = h?.string("id")?.takeIf { id -> HapticClipId.entries.any { it.key == id } }?.let {
                HapticCue(it, h.integer("count")?.coerceIn(1, 4), h.number("intensity")?.coerceIn(0.0, 1.0))
            }
            val options = (o["options"] as? JsonArray).orEmpty().mapNotNull { v ->
                val option = v as? JsonObject ?: return@mapNotNull null
                val gesture = option.string("gesture")?.let(GestureId::fromWire)
                    ?.takeIf { it in Persona.gestures } ?: return@mapNotNull null
                val meaning = option.string("meaning")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = option.string("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DialogOption(gesture, meaning, label, option.integer("knockCount")?.coerceIn(1, 4))
            }.take(3)
            val d = o["stateDelta"] as? JsonObject
            fun delta(key: String) = d?.number(key)?.coerceIn(-0.3, 0.3) ?: 0.0
            // Bound Unicode code points without splitting surrogate pairs.
            val end = say.offsetByCodePoints(0, minOf(40, say.codePointCount(0, say.length)))
            AgentTurn(say.substring(0, end), Expression.fromWire(o.string("expression")) ?: Expression.Neutral,
                haptic, Motion.fromWire(o.string("motion")) ?: Motion.None, options,
                o.integer("timeoutSec")?.coerceIn(4, 15) ?: 8,
                d?.let { StateDelta(delta("mood"), delta("energy"), delta("intimacy"), delta("curiosity"), delta("grudge")) },
                tag = "llm")
        }
    } catch (_: IllegalArgumentException) { null }
}

internal fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }
    ?.doubleOrNull?.takeIf { it.isFinite() }
internal fun JsonObject.integer(key: String) = number(key)?.takeIf { it == Math.rint(it) }
    ?.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())?.toInt()
