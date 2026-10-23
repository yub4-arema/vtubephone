package com.vtubephone.core.brain

import com.vtubephone.core.gesture.GestureEvent
import com.vtubephone.core.gesture.GestureId

sealed interface Trigger {
    data object Start : Trigger
    data object Initiative : Trigger
    data class Answered(val turn: AgentTurn, val option: DialogOption, val event: GestureEvent) : Trigger
    data class Timeout(val turn: AgentTurn) : Trigger
    data class Gesture(val event: GestureEvent, val pending: AgentTurn?) : Trigger
    /** Optional voice input (transcribed by the LLM itself); [audioWav] is passed through untouched. */
    data class Voice(val audioWav: ByteArray?, val text: String?) : Trigger
}

data class BrainContext(val nowMs: Long, val state: CharacterState, val log: EventLog, val trigger: Trigger, val lastTurn: AgentTurn?) {
    /** Copy that is safe to hand to another thread while the engine keeps running. */
    fun snapshot() = copy(state = state.snapshot(), log = log.snapshot())
}

/** Decides the next turn. Rule brain and demo script answer synchronously; the LLM brain is wrapped by the app. */
fun interface TurnSource { fun next(ctx: BrainContext): AgentTurn? }

/** Things the app must do, in order. */
sealed interface BrainAction {
    data class Perform(val turn: AgentTurn) : BrainAction
    data class React(val reflex: Reflex) : BrainAction
    data class Expect(val gestures: Set<GestureId>, val seconds: Double) : BrainAction
    data object ClearOptions : BrainAction
    data class RequestTurn(val context: BrainContext) : BrainAction
}

/** When the character speaks on her own: after being ignored for a while, never too often, sooner for revenge. */
class InitiativeScheduler(var minIntervalMs: Long = 45_000, var idleMs: Long = 15_000) {
    var lastInitiativeMs = Long.MIN_VALUE / 2; private set
    fun due(nowMs: Long, lastInteractionMs: Long, state: CharacterState, lastShakeMs: Long?): Boolean {
        if (state.sleeping) return false
        val idle = nowMs - lastInteractionMs
        // Revenge comes sooner than boredom, but never back to back (an LLM turn may not settle the grudge).
        val revengeReady = state.grudge > 0.5 && lastShakeMs != null && nowMs - lastShakeMs >= 6_000 && idle >= 3_000 &&
            nowMs - lastInitiativeMs >= 10_000
        val bored = idle >= idleMs - (state.energy * 5_000).toLong() && nowMs - lastInitiativeMs >= minIntervalMs
        return (revengeReady || bored).also { if (it) lastInitiativeMs = nowMs }
    }
}

/**
 * Orchestrates reflexes, the pending question and turn requests. Pure and clock-injected so the 30-second demo
 * can be simulated in unit tests. Gestures that answer the pending question get a silent reflex (face and body
 * only): the follow-up turn carries the vibration, so the user never feels two vibrations for one action.
 */
class BrainEngine(
    val state: CharacterState = CharacterState(),
    val log: EventLog = EventLog(),
    val initiative: InitiativeScheduler = InitiativeScheduler(),
) {
    var pending: AgentTurn? = null; private set
    private var deadlineMs = Long.MAX_VALUE
    private var lastTurn: AgentTurn? = null
    private var lastInteractionMs = 0L
    private var lastTickMs: Long? = null
    var awaitingTurn = false; private set

    fun start(nowMs: Long): List<BrainAction> { lastInteractionMs = nowMs; return request(nowMs, Trigger.Start) }

    fun voice(nowMs: Long, audioWav: ByteArray?, text: String?): List<BrainAction> {
        lastInteractionMs = nowMs
        log.add(nowMs, "voice", "ユーザーが声で話しかけた" + (text?.let { ":「$it」" } ?: ""))
        val out = ArrayList<BrainAction>()
        if (pending != null) { pending = null; out += BrainAction.ClearOptions }
        return out + request(nowMs, Trigger.Voice(audioWav, text), force = true)
    }

    fun onGesture(e: GestureEvent, nowMs: Long): List<BrainAction> {
        lastInteractionMs = nowMs
        log.add(nowMs, e.id.wire, GestureText.describe(e))
        val out = ArrayList<BrainAction>()
        val p = pending
        val option = p?.options?.firstOrNull { it.matches(e) }
        if (p != null && option != null) {
            pending = null
            out += BrainAction.ClearOptions
            // Show at once that she understood (the LLM may take seconds). Face and body only: the follow-up
            // turn brings the vibration, so one action never feels like two buzzes.
            ReflexTable.react(e, state)?.let { out += BrainAction.React(it.copy(haptic = null, say = null)) }
            log.add(nowMs, "answer", "「${p.say}」に${option.label}で答えた（${option.meaning}）")
            out += request(nowMs, Trigger.Answered(p, option, e), force = true)
            updateSleep(e)
            return out
        }
        ReflexTable.react(e, state)?.let { state.apply(it.delta); out += BrainAction.React(it) }
        updateSleep(e)
        if (e.id == GestureId.FlipDown) { deadlineMs = Long.MAX_VALUE; if (p != null) { pending = null; out += BrainAction.ClearOptions } }
        val worthATurn = e.id == GestureId.Pickup || (p != null && (e.id == GestureId.Knock || e.id == GestureId.BackKnock))
        if (worthATurn) out += request(nowMs, Trigger.Gesture(e, p))
        return out
    }

    fun tick(nowMs: Long): List<BrainAction> {
        lastTickMs?.let { state.decay((nowMs - it) / 1000.0) }
        lastTickMs = nowMs
        val p = pending
        val timedOut = p ?: lastTurn
        if (timedOut != null && nowMs >= deadlineMs) {
            pending = null; deadlineMs = Long.MAX_VALUE
            return (if (p != null) listOf(BrainAction.ClearOptions) else emptyList()) + request(nowMs, Trigger.Timeout(timedOut))
        }
        if (p == null && !awaitingTurn && deadlineMs == Long.MAX_VALUE &&
            initiative.due(nowMs, lastInteractionMs, state, log.lastOf(GestureId.ShakeHard.wire)?.atMs))
            return request(nowMs, Trigger.Initiative)
        return emptyList()
    }

    /** Called with the source's answer to a [BrainAction.RequestTurn] (null = stay quiet). */
    fun deliver(turn: AgentTurn?, nowMs: Long): List<BrainAction> {
        awaitingTurn = false
        if (turn == null) { deadlineMs = Long.MAX_VALUE; return emptyList() }
        lastTurn = turn
        turn.stateDelta?.let(state::apply)
        if (turn.isRevenge()) state.apply(StateDelta(grudge = -1.0))
        log.add(nowMs, "say", "わたし:「${turn.say}」" + if (turn.options.isEmpty()) "" else "（${turn.options.joinToString("／") { "${it.label}=${it.meaning}" }}で答えてもらう）")
        pending = turn.takeIf { it.options.isNotEmpty() }
        // Option-less turns may still time out so a script can move on.
        deadlineMs = nowMs + turn.timeoutSec * 1000L
        if (turn.options.isEmpty() && !turn.chain) deadlineMs = Long.MAX_VALUE
        val out = arrayListOf<BrainAction>(BrainAction.Perform(turn))
        if (turn.options.isNotEmpty()) out += BrainAction.Expect(turn.options.map { it.gesture }.toSet(), turn.timeoutSec.toDouble())
        return out
    }

    private fun request(nowMs: Long, trigger: Trigger, force: Boolean = false): List<BrainAction> {
        if (awaitingTurn && !force) return emptyList()
        awaitingTurn = true
        return listOf(BrainAction.RequestTurn(BrainContext(nowMs, state, log, trigger, lastTurn)))
    }

    private fun updateSleep(e: GestureEvent) {
        if (e.id == GestureId.FlipDown) state.sleeping = true
        if (e.id == GestureId.Pickup) state.sleeping = false
    }
}

/** The signature "ドン" from inside the screen settles the grudge, whoever wrote the turn. */
fun AgentTurn.isRevenge() = tag == "revenge" || (motion == Motion.Approach && haptic?.id == "thud")

object GestureText {
    fun describe(e: GestureEvent): String = when (e.id) {
        GestureId.Nod -> "うなずいた"
        GestureId.Headshake -> "首を横に振った"
        GestureId.TiltLeft -> "スマホを左に傾けた"
        GestureId.TiltRight -> "スマホを右に傾けた"
        GestureId.TiltForward -> "スマホを奥に倒した"
        GestureId.TiltBack -> "スマホを手前に倒した"
        GestureId.Knock -> "画面を${e.count}回ノックした" + if (e.target == "avatar") "（わたしを直接）" else ""
        GestureId.BackKnock -> "スマホの背面を${e.count}回ノックした"
        GestureId.Stroke -> "わたしをなでた"
        GestureId.Hold -> "わたしに指を重ねて長押しした"
        GestureId.ShakeLight -> "スマホを軽く揺らした"
        GestureId.ShakeHard -> "スマホを激しく振った！"
        GestureId.FlipDown -> "スマホを画面を下にして置いた"
        GestureId.Pickup -> "スマホを持ち上げた"
        GestureId.Still -> "スマホを机に置いた"
    }
}
