package com.vtubephone.core.brain

import kotlin.math.exp

/**
 * The character's inner state. Every value drifts back to its baseline over time, so a single bad moment fades,
 * but repeated treatment accumulates. [grudge] remembers rough handling for a later "仕返し".
 */
class CharacterState {
    var mood = 0.3; private set          // -1..1
    var energy = 0.6; private set        // 0..1
    var intimacy = 0.2; private set      // 0..1
    var curiosity = 0.6; private set     // 0..1
    var grudge = 0.0; private set        // 0..1
    var sleeping = false

    fun apply(d: StateDelta) {
        mood = (mood + d.mood).coerceIn(-1.0, 1.0)
        energy = (energy + d.energy).coerceIn(0.0, 1.0)
        intimacy = (intimacy + d.intimacy).coerceIn(0.0, 1.0)
        curiosity = (curiosity + d.curiosity).coerceIn(0.0, 1.0)
        grudge = (grudge + d.grudge).coerceIn(0.0, 1.0)
    }

    /** Exponential return to baselines; intimacy is sticky (it only decays very slowly). */
    fun decay(seconds: Double) {
        if (seconds <= 0) return
        fun toward(v: Double, base: Double, tau: Double) = base + (v - base) * exp(-seconds / tau)
        mood = toward(mood, 0.3, 90.0)
        energy = toward(energy, if (sleeping) 0.2 else 0.6, 120.0)
        intimacy = toward(intimacy, 0.2, 3600.0)
        curiosity = toward(curiosity, 0.6, 60.0)
        grudge = toward(grudge, 0.0, 180.0)
    }

    /** Independent copy for readers on other threads (the LLM prompt is built off the brain thread). */
    fun snapshot() = CharacterState().also {
        it.mood = mood; it.energy = energy; it.intimacy = intimacy; it.curiosity = curiosity; it.grudge = grudge; it.sleeping = sleeping
    }

    fun describe() = "mood=%.2f energy=%.2f intimacy=%.2f curiosity=%.2f grudge=%.2f sleeping=%s"
        .format(mood, energy, intimacy, curiosity, grudge, sleeping)
}

/** Short natural-language memory of recent events, used by the LLM prompt and by the rule brain. */
class EventLog(private val capacity: Int = 30) {
    data class Entry(val atMs: Long, val text: String, val kind: String)
    private val entries = ArrayDeque<Entry>()
    fun add(atMs: Long, kind: String, text: String) {
        entries.addLast(Entry(atMs, text, kind))
        while (entries.size > capacity) entries.removeFirst()
    }
    fun recent(n: Int = capacity): List<Entry> = entries.toList().takeLast(n)
    fun snapshot() = EventLog(capacity).also { it.entries.addAll(entries) }
    fun lastOf(kind: String): Entry? = entries.lastOrNull { it.kind == kind }
    fun render(nowMs: Long, n: Int = 12) = recent(n).joinToString("\n") { "- ${(nowMs - it.atMs) / 1000}秒前: ${it.text}" }
}
