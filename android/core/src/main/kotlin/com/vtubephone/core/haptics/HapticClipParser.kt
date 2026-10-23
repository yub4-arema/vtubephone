package com.vtubephone.core.haptics

import kotlinx.serialization.json.*

object HapticClipParser {
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
    fun parse(text: String): HapticClip {
        val root = json.parseToJsonElement(text).jsonObject
        val id = root.getValue("id").jsonPrimitive.also { require(it.isString) }.content
        val events = root.getValue("events").jsonArray.map { value ->
            val e = value.jsonObject
            val type = e.getValue("type").jsonPrimitive.also { require(it.isString) }.content
            require(type in listOf("transient", "continuous", "primitive")) { "Unknown event type" }
            if (type == "primitive") HapticEvent.primitive(integer(e.getValue("t")),
                HapticPrimitive.fromKey(e.getValue("name").jsonPrimitive.also { require(it.isString) }.content),
                number(e.getValue("scale")))
            else HapticEvent(integer(e.getValue("t")), type == "continuous",
                if (type == "continuous") integer(e.getValue("duration")) else 0,
                parameter(e.getValue("intensity")), parameter(e.getValue("sharpness")))
        }
        val strict = root["strictTiming"]?.jsonPrimitive?.let {
            require(!it.isString) { "strictTiming must be a boolean" }; it.boolean
        } ?: false
        val render = root["render"]?.jsonPrimitive?.let {
            require(it.isString) { "render must be a string" }; HapticRenderStyle.fromKey(it.content)
        } ?: HapticRenderStyle.Auto
        return HapticClip(id, events, strict, render)
    }
    private fun number(value: JsonElement): Double = value.jsonPrimitive.let {
        require(!it.isString)
        it.double.also { n -> require(n.isFinite()) }
    }
    private fun integer(value: JsonElement): Int = number(value).let {
        require(it in 0.0..60000.0 && it == Math.rint(it)) { "Invalid milliseconds" }; it.toInt()
    }
    private fun parameter(value: JsonElement): HapticParameter = if (value is JsonArray)
        HapticParameter(value.map { point -> point.jsonArray.map(::number) }) else HapticParameter(number(value))
}
