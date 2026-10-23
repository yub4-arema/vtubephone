package com.vtubephone.core.llm

import com.vtubephone.core.brain.*
import java.util.Base64
import kotlinx.serialization.json.*

/** REST field names: https://ai.google.dev/api/generate-content and /gemini-api/docs/audio. */
class GeminiClient(private val transport: HttpTransport, private val apiKey: String,
    private val model: String = "gemini-3.5-flash-lite") {
    init { require(model.matches(Regex("[a-zA-Z0-9._-]+"))) }

    fun generate(ctx: BrainContext): AgentTurn? {
        if (apiKey.isBlank()) return null
        val request = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", PromptBuilder.systemInstruction()) }) })
            })
            put("contents", buildJsonArray { add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", PromptBuilder.userTurn(ctx)) })
                    (ctx.trigger as? Trigger.Voice)?.audioWav?.takeIf { it.isNotEmpty() }?.let { audio ->
                        add(buildJsonObject { put("inline_data", buildJsonObject {
                            put("mime_type", "audio/wav"); put("data", Base64.getEncoder().encodeToString(audio))
                        }) })
                    }
                })
            }) })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json"); put("responseJsonSchema", TurnSchema.schema)
                put("temperature", 0.9)
                // She must answer within the 4 s turn budget; default thinking adds ~5 s (measured 2026-10-23).
                put("thinkingConfig", buildJsonObject { put("thinkingLevel", "minimal") })
            })
        }
        val response = transport.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"), request.toString().toByteArray())
        if (!response.successful) return null
        return try {
            val root = Json.parseToJsonElement(response.text()).jsonObject
            val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val content = candidate["content"] as? JsonObject ?: return null
            val parts = content["parts"] as? JsonArray ?: return null
            TurnJson.parse(parts.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString(""))
        } catch (_: IllegalArgumentException) { null }
    }
}
