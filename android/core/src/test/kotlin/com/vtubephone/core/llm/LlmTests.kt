package com.vtubephone.core.llm

import com.vtubephone.core.brain.*
import com.vtubephone.core.gesture.*
import com.vtubephone.core.haptics.HapticClipId
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class MockHttp(var respond: (String, Map<String, String>, ByteArray?) -> HttpResult) : HttpTransport {
    override fun post(url: String, headers: Map<String, String>, body: ByteArray) = respond(url, headers, body)
    override fun get(url: String, headers: Map<String, String>) = respond(url, headers, null)
}
internal fun jsonResult(text: String, status: Int = 200) = HttpResult(status, text.toByteArray())
internal fun context(trigger: Trigger = Trigger.Initiative) = BrainContext(5000, CharacterState(), EventLog(), trigger, null)
private fun candidate(text: String) = buildJsonObject {
    put("candidates", buildJsonArray { add(buildJsonObject { put("content", buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
    }) }) })
}.toString()

class LlmTests {
    @Test fun systemUsesAllPmOwnedTextsAndDescriptions() {
        val p = PromptBuilder.systemInstruction()
        assertTrue(p.contains(Persona.character)); assertTrue(p.contains(Persona.rules))
        Persona.gestures.forEach { (id, text) -> assertTrue(p.contains("${id.wire}: $text")) }
        Persona.haptics.forEach { (id, text) -> assertTrue(p.contains("$id: $text")) }
        Persona.expressions.forEach { (id, text) -> assertTrue(p.contains("${id.wire}: $text")) }
        Persona.motions.forEach { (id, text) -> assertTrue(p.contains("${id.wire}: $text")) }
    }

    @Test fun everyTriggerIncludesContextAndSpecificOutcome() {
        val event = GestureEvent(GestureId.Knock, 0, count = 2)
        val option = DialogOption(GestureId.Knock, "返事した", "2回ノック", 2)
        val turn = AgentTurn("きこえる？", options = listOf(option))
        val triggers = listOf(Trigger.Start, Trigger.Initiative, Trigger.Answered(turn, option, event),
            Trigger.Timeout(turn), Trigger.Gesture(event, turn), Trigger.Voice(byteArrayOf(1), "こんにちは"))
        val expected = listOf("会話を開始", "しばらく", "返事した", "返答待ち時間", "2回ノック", "こんにちは")
        triggers.zip(expected).forEach { (trigger, text) ->
            val ctx = context(trigger).copy(lastTurn = turn)
            ctx.log.add(4000, "say", "一秒前の記憶")
            val prompt = PromptBuilder.userTurn(ctx)
            assertTrue(prompt.contains(ctx.state.describe())); assertTrue(prompt.contains("1秒前: 一秒前の記憶"))
            assertTrue(prompt.contains(turn.say)); assertTrue(prompt.contains(text))
        }
        assertTrue(PromptBuilder.userTurn(context(Trigger.Voice(null, null))).contains("添付音声"))
    }

    @Test fun schemaEnumsFollowRuntimeTypesAndAllowedGestures() {
        val properties = TurnSchema.schema["properties"]!!.jsonObject
        fun values(o: JsonElement) = o.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Expression.entries.map { it.wire }, values(properties.getValue("expression")))
        assertEquals(Motion.entries.map { it.wire }, values(properties.getValue("motion")))
        val haptic = properties["haptic"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(HapticClipId.entries.map { it.key }, values(haptic.getValue("id")))
        val options = properties["options"]!!.jsonObject
        assertEquals(3, options["maxItems"]!!.jsonPrimitive.int)
        assertEquals(Persona.gestures.keys.map { it.wire }, values(options["items"]!!.jsonObject["properties"]!!.jsonObject.getValue("gesture")))
    }

    @Test fun parserDropsUnknownIdsAndClampsEveryBound() {
        val json = """{"say":"${"長".repeat(50)}","expression":"missing","motion":"missing",
            "haptic":{"id":"missing"},"options":[{"gesture":"missing","meaning":"x","label":"x"},
            {"gesture":"knock","meaning":"1","label":"1","knockCount":0},
            {"gesture":"knock","meaning":"4","label":"4","knockCount":99},
            {"gesture":"nod","meaning":"yes","label":"yes"},{"gesture":"stroke","meaning":"x","label":"x"}],
            "timeoutSec":99,"stateDelta":{"mood":9,"energy":-9,"intimacy":2,"curiosity":-2,"grudge":9},"tag":"revenge"}"""
        val turn = requireNotNull(TurnJson.parse(json))
        assertEquals(40, turn.say.length); assertEquals(Expression.Neutral, turn.expression)
        assertEquals(Motion.None, turn.motion); assertNull(turn.haptic); assertEquals("llm", turn.tag)
        assertEquals(3, turn.options.size); assertEquals(1, turn.options[0].knockCount); assertEquals(4, turn.options[1].knockCount)
        assertEquals(15, turn.timeoutSec); assertEquals(StateDelta(.3, -.3, .3, -.3, .3), turn.stateDelta)
        assertEquals(4, TurnJson.parse("""{"say":"x","timeoutSec":-999}""")!!.timeoutSec)
        assertFalse(turn.chain)
    }

    @Test fun parserAcceptsValidTurnAndClampsHaptic() {
        val turn = TurnJson.parse("""{"say":"はい","expression":"happy","motion":"nod",
            "haptic":{"id":"knock","count":10,"intensity":-2},"timeoutSec":9}""")!!
        assertEquals(Expression.Happy, turn.expression); assertEquals(Motion.Nod, turn.motion)
        assertEquals(HapticCue("knock", 4, 0.0), turn.haptic)
        assertEquals(9, turn.timeoutSec)
    }

    @Test fun malformedJsonAndRequiredSayAreRejectedWithoutThrowing() {
        listOf("", "no JSON", "[]", "null", "{}", "{\"say\":[]}", "{\"say\":42}", "{\"say\":\" \"}")
            .forEach { assertNull(it, TurnJson.parse(it)) }
        val corrected = TurnJson.parse("""{"say":"ok","expression":[],"haptic":[],"options":[null,{},
            {"gesture":"pickup","meaning":"x","label":"x"}],"timeoutSec":"1","stateDelta":{"mood":"NaN"}}""")!!
        assertTrue(corrected.options.isEmpty()); assertEquals(8, corrected.timeoutSec)
        assertEquals(StateDelta(), corrected.stateDelta)
    }

    @Test fun unicodeLimitDoesNotSplitSurrogatePairs() {
        val turn = TurnJson.parse("""{"say":"${"🌟".repeat(45)}"}""")!!
        assertEquals(40, turn.say.codePointCount(0, turn.say.length)); assertEquals("🌟".repeat(40), turn.say)
    }

    @Test fun geminiRequestUsesOfficialFieldsAndWavInlineDataAndJoinsParts() {
        val wav = PcmWav.encode(byteArrayOf(0, 0))
        val transport = MockHttp { url, headers, body ->
            assertTrue(url.endsWith("/gemini-3.5-flash-lite:generateContent"))
            assertEquals("test-key", headers["x-goog-api-key"])
            val request = Json.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject
            assertEquals(PromptBuilder.systemInstruction(), request["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject.string("text"))
            val parts = request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            val audio = parts[1].jsonObject["inline_data"]!!.jsonObject
            assertEquals("audio/wav", audio.string("mime_type"))
            assertArrayEquals(wav, Base64.getDecoder().decode(audio.string("data")))
            val config = request["generationConfig"]!!.jsonObject
            assertEquals("application/json", config.string("responseMimeType"))
            assertEquals(TurnSchema.schema, config["responseJsonSchema"])
            jsonResult("""{"candidates":[{"content":{"parts":[{"text":"{\"say\":"},{"text":"\"はい\"}"}]}}]}""")
        }
        assertEquals("はい", GeminiClient(transport, "test-key").generate(context(Trigger.Voice(wav, null)))!!.say)
    }

    @Test fun emptyKeyMakesNoRequestAndMissingCandidatesIsNull() {
        val never = MockHttp { _, _, _ -> fail("must not call transport"); error("unreachable") }
        assertNull(GeminiClient(never, " ").generate(context()))
        listOf("{}", "{\"candidates\":[]}", "not JSON").forEach { response ->
            assertNull(GeminiClient(MockHttp { _, _, _ -> jsonResult(response) }, "key").generate(context()))
        }
    }

    @Test fun llmFallsBackOnExceptionInvalidJsonAndHttpFailure() {
        val fallback = AgentTurn("fallback")
        val cases = listOf<() -> HttpResult>({ throw IllegalStateException("offline") },
            { jsonResult(candidate("bad JSON")) }, { jsonResult("unavailable", 503) }, { jsonResult("{}") })
        cases.forEach { response ->
            LlmTurnSource(GeminiClient(MockHttp { _, _, _ -> response() }, "key"), TurnSource { fallback }).use {
                assertEquals(fallback, it.next(context()))
            }
        }
    }

    @Test fun timeoutCancelsRequestAndReturnsFallback() {
        val interrupted = CountDownLatch(1)
        val transport = MockHttp { _, _, _ ->
            try { CountDownLatch(1).await(); error("unreachable") }
            catch (e: InterruptedException) { interrupted.countDown(); throw e }
        }
        val fallback = AgentTurn("fallback")
        LlmTurnSource(GeminiClient(transport, "key"), TurnSource { fallback }, timeoutMs = 100).use {
            val start = System.nanoTime()
            assertEquals(fallback, it.next(context()))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun intervalLimitFallsBackButVoiceBypassesIt() {
        var now = 0L
        var requests = 0
        val transport = MockHttp { _, _, _ -> requests++; jsonResult(candidate("""{"say":"LLM"}""")) }
        val fallback = AgentTurn("fallback")
        LlmTurnSource(GeminiClient(transport, "key"), TurnSource { fallback }, clock = { now }).use { source ->
            assertEquals("LLM", source.next(context())!!.say)
            now = 100
            assertEquals(fallback, source.next(context()))
            assertEquals("LLM", source.next(context(Trigger.Voice(null, "声")))!!.say)
            now = 1600
            assertEquals("LLM", source.next(context())!!.say)
            assertEquals(3, requests)
        }
    }

    @Test fun startAndOptionlessTimeoutGoDirectlyToFallback() {
        val never = MockHttp { _, _, _ -> fail("must not call transport"); error("unreachable") }
        val fallback = AgentTurn("fallback")
        LlmTurnSource(GeminiClient(never, "key"), TurnSource { fallback }).use {
            assertEquals(fallback, it.next(context(Trigger.Start)))
            assertEquals(fallback, it.next(context(Trigger.Timeout(AgentTurn("x")))))
        }
    }
}
