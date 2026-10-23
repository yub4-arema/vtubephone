package com.vtubephone.core.gesture

import kotlin.math.abs
import kotlin.math.max

/** Groups taps (screen or back) into one knock sequence: "トントン" = count 2 with its rhythm. */
internal class KnockSequencer(private val config: GestureConfig) {
    private val taps = ArrayList<Pair<Long, String>>()

    /** Adds a tap and returns a finished earlier sequence if this tap started a new one. */
    fun add(tNs: Long, target: String): GestureEvent? {
        val finished = if (taps.isNotEmpty() && tNs - taps.last().first > config.knockGapMs * MS) flush() else null
        if (taps.size < config.knockMaxCount.toInt()) taps += tNs to target
        return finished
    }

    fun tick(tNs: Long): GestureEvent? =
        if (taps.isNotEmpty() && tNs - taps.last().first > config.knockFinalizeMs * MS) flush() else null

    fun clear() = taps.clear()

    private fun flush(): GestureEvent {
        val targets = taps.map { it.second }.toSet()
        val target = targets.singleOrNull() ?: "mixed"
        val id = if (target == "back") GestureId.BackKnock else GestureId.Knock
        val intervals = taps.zipWithNext { a, b -> (b.first - a.first) / MS }
        return GestureEvent(id, taps.first().first, count = taps.size, intervalsMs = intervals, target = target).also { taps.clear() }
    }
}

object RhythmMatcher {
    /**
     * True when [actual] has the same count as [target] and a similar rhythm: each interval, normalised by the
     * sequence's mean interval, is within [tolerance] of the target's, and the overall tempo is within 2x.
     */
    fun matches(targetIntervalsMs: List<Long>, actual: GestureEvent, tolerance: Double = 0.35): Boolean {
        if (actual.id != GestureId.Knock && actual.id != GestureId.BackKnock) return false
        if (actual.count != targetIntervalsMs.size + 1) return false
        if (targetIntervalsMs.isEmpty()) return true
        val tMean = targetIntervalsMs.average(); val aMean = actual.intervalsMs.average()
        if (tMean <= 0 || aMean <= 0 || aMean / tMean !in 0.5..2.0) return false
        return targetIntervalsMs.zip(actual.intervalsMs).all { (t, a) ->
            val tn = t / tMean; val an = a / aMean
            abs(an - tn) <= tolerance * max(tn, 0.25)
        }
    }

    /** Knock reply where only the count matters (e.g. "1〜3 回ノックして"). */
    fun count(actual: GestureEvent) = if (actual.id == GestureId.Knock || actual.id == GestureId.BackKnock) actual.count else 0
}

/** Picks a back-knock threshold from a few calibration taps: half of the median peak, clamped to a sane range. */
object BackKnockCalibrator {
    fun threshold(peaks: List<Double>): Double {
        require(peaks.size >= 3) { "Need at least 3 calibration taps" }
        val sorted = peaks.filter { it.isFinite() && it > 0 }.sorted()
        require(sorted.size >= 3) { "Need at least 3 valid calibration taps" }
        return (sorted[sorted.size / 2] * 0.5).coerceIn(0.8, 8.0)
    }
}
