package com.vtubephone.core.llm

import com.vtubephone.core.brain.Expression
import com.vtubephone.core.brain.Motion
import com.vtubephone.core.haptics.HapticClipId
import kotlinx.serialization.json.*

object TurnSchema {
    private fun scalar(type: String) = buildJsonObject { put("type", type) }
    private fun enum(values: List<String>) = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    private fun number(type: String, min: Number, max: Number) = buildJsonObject {
        put("type", type); put("minimum", min); put("maximum", max)
    }
    private fun obj(properties: Map<String, JsonElement>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    }

    val schema: JsonObject get() = obj(linkedMapOf(
        "say" to buildJsonObject { put("type", "string"); put("maxLength", 40) },
        "expression" to enum(Expression.entries.map { it.wire }),
        "haptic" to obj(mapOf("id" to enum(HapticClipId.entries.map { it.key }),
            "count" to number("integer", 1, 4), "intensity" to number("number", 0, 1)), listOf("id")),
        "motion" to enum(Motion.entries.map { it.wire }),
        "options" to buildJsonObject {
            put("type", "array"); put("maxItems", 3)
            put("items", obj(mapOf("gesture" to enum(Persona.gestures.keys.map { it.wire }),
                "meaning" to scalar("string"), "label" to scalar("string"),
                "knockCount" to number("integer", 1, 4)), listOf("gesture", "meaning", "label")))
        },
        "timeoutSec" to number("integer", 4, 15),
        "stateDelta" to obj(listOf("mood", "energy", "intimacy", "curiosity", "grudge")
            .associateWith { number("number", -0.3, 0.3) }),
    ), listOf("say", "expression", "haptic", "motion", "options", "timeoutSec"))

    fun json(): String = schema.toString()
}
