package com.vtubephone.app.brain

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.vtubephone.app.haptics.HapticsService
import com.vtubephone.app.sensing.SensingService
import com.vtubephone.core.brain.AgentTurn
import com.vtubephone.core.brain.BrainAction
import com.vtubephone.core.brain.BrainEngine
import com.vtubephone.core.brain.HapticCue
import com.vtubephone.core.brain.Reflex
import com.vtubephone.core.brain.Trigger
import com.vtubephone.core.brain.TurnSource
import com.vtubephone.core.gesture.GestureEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Runs the [BrainEngine] on its own thread and turns its actions into haptics and avatar messages.
 * Turn sources (rule brain, demo script, LLM) run on a worker so a slow network never blocks reflexes.
 */
class BrainService(
    private val haptics: HapticsService,
    private val sensing: SensingService,
    private val settings: Settings,
    /** Chosen per request so switching demo mode / keys takes effect immediately. */
    private val sourceFor: (Settings) -> TurnSource,
    /** Optional speech for a turn; returns true when audio will play (the avatar then waits for lip sync). */
    private val speak: (String) -> Boolean = { false },
) {
    /** Messages for the avatar page. Called on the brain thread; the receiver hops to the UI thread. */
    @Volatile var toAvatar: ((JSONObject) -> Unit)? = null

    private val thread = HandlerThread("vtubephone-brain").apply { start() }
    private val handler = Handler(thread.looper)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vtubephone-turns") }
    private var engine = BrainEngine()
    private var started = false
    private var running = false
    private var generation = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null
    private var lastMoodMs = 0L
    private var deliveringInitiative = false

    private fun now() = SystemClock.elapsedRealtime()

    fun resume() = handler.post {
        if (running) return@post
        running = true
        collector = scope.launch { sensing.gestures.collect { e -> handler.post { onGesture(e) } } }
        handler.post(ticker)
    }

    fun pause() = handler.post {
        running = false
        collector?.cancel(); collector = null
        handler.removeCallbacks(ticker)
    }

    /** The page is ready: greet once, or re-show the current question after a reload. */
    fun onAvatarReady() = handler.post {
        sendSettings()
        if (!started) { started = true; run(engine.start(now())) }
        else engine.pending?.let { send(performJson(it, voiced = false)) }
    }

    /** Start over (settings change such as demo mode). */
    fun restart() = handler.post {
        generation++
        engine = BrainEngine()
        engine.initiative.minIntervalMs = settings.initiativeIntervalMs
        started = true
        send(JSONObject().put("type", "clearOptions"))
        run(engine.start(now()))
    }

    fun onBump(impact: Double) { if (impact.isFinite()) haptics.playBump(impact.coerceIn(0.0, 1.0)) }

    fun onVoice(wav: ByteArray?) = handler.post { run(engine.voice(now(), wav, null)) }

    fun sendSettings() = handler.post {
        send(JSONObject().put("type", "settings").put("captionScale", settings.captionScale))
        engine.initiative.minIntervalMs = settings.initiativeIntervalMs
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val t = now()
            if (started) run(engine.tick(t))
            if (t - lastMoodMs >= 1000) {
                lastMoodMs = t
                val s = engine.state
                send(JSONObject().put("type", "mood").put("mood", s.mood).put("energy", s.energy)
                    .put("grudge", s.grudge).put("sleeping", s.sleeping))
            }
            handler.postDelayed(this, 100)
        }
    }

    private fun onGesture(e: GestureEvent) {
        send(JSONObject().put("type", "gesture").put("id", e.id.wire).put("strength", e.strength))
        if (started) run(engine.onGesture(e, now()))
    }

    private fun run(actions: List<BrainAction>) {
        for (a in actions) when (a) {
            is BrainAction.Perform -> perform(a.turn)
            is BrainAction.React -> react(a.reflex)
            is BrainAction.Expect -> sensing.expect(a.gestures, a.seconds)
            BrainAction.ClearOptions -> send(JSONObject().put("type", "clearOptions"))
            is BrainAction.RequestTurn -> request(a)
        }
    }

    private fun request(a: BrainAction.RequestTurn) {
        val ctx = a.context.snapshot()
        val gen = generation
        val source = sourceFor(settings)
        val thinking = Runnable { send(JSONObject().put("type", "thinking").put("on", true)) }
        handler.postDelayed(thinking, 700)
        worker.execute {
            val startMs = now()
            val turn = try { source.next(ctx) } catch (e: RuntimeException) { Log.w(TAG, "Turn source failed", e); null }
            val why = (source as? com.vtubephone.core.llm.LlmTurnSource)?.lastFallbackReason
            Log.i(TAG, "turn ${ctx.trigger::class.simpleName} via ${source::class.simpleName}${why?.let { " (fallback: $it)" } ?: ""} " +
                "${now() - startMs}ms tag=${turn?.tag} say=${turn?.say}")
            handler.post {
                handler.removeCallbacks(thinking)
                send(JSONObject().put("type", "thinking").put("on", false))
                if (gen == generation) { deliveringInitiative = ctx.trigger == Trigger.Initiative; run(engine.deliver(turn, now())); deliveringInitiative = false }
            }
        }
    }

    private fun perform(turn: AgentTurn) {
        // Initiative turns are "spontaneous": the haptics rate limiter may drop them, never a reply.
        play(turn.haptic, spontaneous = deliveringInitiative)
        val voiced = settings.voice && turn.say.isNotBlank() && speak(turn.say)
        send(performJson(turn, voiced))
    }

    private fun react(r: Reflex) {
        play(r.haptic, spontaneous = false)
        send(JSONObject().put("type", "react").put("expression", r.expression.wire).put("motion", r.motion.wire)
            .put("say", r.say ?: ""))
    }

    private fun play(cue: HapticCue?, spontaneous: Boolean) {
        cue ?: return
        if (cue.id == "knock") haptics.playKnock((cue.count ?: 2).coerceIn(1, 4))
        else haptics.play(cue.id, spontaneous)
    }

    private fun performJson(turn: AgentTurn, voiced: Boolean) = JSONObject().put("type", "perform")
        .put("say", turn.say).put("expression", turn.expression.wire).put("motion", turn.motion.wire)
        .put("timeoutSec", turn.timeoutSec).put("voiced", voiced)
        .put("options", JSONArray(turn.options.map { o ->
            JSONObject().put("gesture", o.gesture.wire).put("label", o.label).put("meaning", o.meaning)
                .apply { o.knockCount?.let { put("knockCount", it) } }
        }))

    private fun send(message: JSONObject) { toAvatar?.invoke(message) }

    companion object { const val TAG = "VtubePhoneBrain" }
}

/** Persisted user settings (SharedPreferences-backed in the app; plain object in tests). */
interface Settings {
    val demoMode: Boolean
    val voice: Boolean
    val captionScale: Double
    val initiativeIntervalMs: Long
}

/** Avatar messages must be posted from the UI thread. */
fun mainThreadPoster(post: (JSONObject) -> Unit): (JSONObject) -> Unit {
    val main = Handler(Looper.getMainLooper())
    return { m -> main.post { post(m) } }
}
