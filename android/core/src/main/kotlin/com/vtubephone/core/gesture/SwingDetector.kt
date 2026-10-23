package com.vtubephone.core.gesture

import kotlin.math.abs
import kotlin.math.max

/**
 * Detects a back-and-forth swing (nod / headshake) on one angular-velocity signal.
 * A "lobe" is one swing in one direction; a gesture is >= minLobes alternating, sharp lobes inside the window
 * whose energy dominates the energy on the other axes (so a wobbly wrist or walking does not count).
 */
internal class SwingDetector(private val config: GestureConfig) {
    private data class Lobe(val sign: Int, val startNs: Long, val endNs: Long, val energy: Double, val other: Double)
    private val lobes = ArrayDeque<Lobe>()
    private var sign = 0
    private var startNs = 0L
    private var peak = 0.0
    private var energy = 0.0
    private var other = 0.0
    private var quietSinceNs: Long? = null
    private var lastNs: Long? = null
    private var refractoryUntilNs = Long.MIN_VALUE
    var lastPeak = 0.0; private set

    fun reset() { lobes.clear(); sign = 0; quietSinceNs = null }

    /** Returns the fired strength (0..1) or null. [sensitivity] < 1 lowers the peak threshold. */
    fun update(tNs: Long, signal: Double, otherAxes: Double, sensitivity: Double = 1.0, suppressed: Boolean = false): Double? {
        val dt = lastNs?.let { (tNs - it).coerceIn(0, 50 * MS) / 1e9 } ?: 0.0
        lastNs = tNs
        if (suppressed) { reset(); return null }
        val band = config.swingDeadbandRad * sensitivity
        val s = if (signal > band) 1 else if (signal < -band) -1 else 0
        var fired: Double? = null
        if (s != 0) {
            quietSinceNs = null
            if (s != sign) { if (sign != 0) fired = close(tNs, sensitivity); open(s, tNs) }
        } else if (sign != 0) {
            val since = quietSinceNs ?: tNs.also { quietSinceNs = it }
            if (tNs - since >= config.swingSettleMs * MS) { fired = close(since, sensitivity); sign = 0 }
        }
        if (sign != 0) {
            peak = max(peak, abs(signal)); energy += signal * signal * dt; other += otherAxes * otherAxes * dt
        }
        return fired
    }

    private fun open(s: Int, tNs: Long) { sign = s; startNs = tNs; peak = 0.0; energy = 0.0; other = 0.0 }

    private fun close(endNs: Long, sensitivity: Double): Double? {
        val ms = (endNs - startNs) / MS.toDouble()
        val valid = peak >= config.swingPeakRad * sensitivity && ms >= config.swingMinLobeMs && ms <= config.swingMaxLobeMs
        if (!valid) { lobes.clear(); return null }
        val previous = lobes.lastOrNull()
        if (previous != null && (previous.sign == sign || startNs - previous.endNs > config.swingMaxLobeMs * MS)) lobes.clear()
        lobes.addLast(Lobe(sign, startNs, endNs, energy, other))
        lastPeak = peak
        while (lobes.isNotEmpty() && endNs - lobes.first().startNs > config.swingWindowMs * MS) lobes.removeFirst()
        if (lobes.size < config.swingMinLobes.toInt() || endNs < refractoryUntilNs) return null
        val e = lobes.sumOf { it.energy }; val o = lobes.sumOf { it.other }
        if (e < config.swingDominance * o) return null
        lobes.clear()
        refractoryUntilNs = endNs + (config.swingRefractoryMs * MS).toLong()
        return (lastPeak / (config.swingPeakRad * 3)).coerceIn(0.0, 1.0)
    }

    /** Called when the other swing detector fires so a single motion is never both a nod and a headshake. */
    fun blockUntil(tNs: Long) { reset(); refractoryUntilNs = max(refractoryUntilNs, tNs) }
}
