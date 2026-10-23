package com.vtubephone.core.haptics

/** Android Composition IDs, kept here without an Android dependency.
 * Curves are deliberate C/D approximations, not measurements of the motor output.
 * See docs/haptic-vocabulary.md for the fixed approximation table.
 */
enum class HapticPrimitive(val id: Int, val key: String, val approximationMs: Int,
    private vararg val curve: Pair<Int, Double>) {
    Click(1, "click", 20, 0 to 1.0, 5 to .75, 20 to 0.0),
    Thud(2, "thud", 300, 0 to 1.0, 20 to .8, 80 to .35, 300 to 0.0),
    Spin(3, "spin", 130, 0 to .15, 30 to .85, 60 to .2, 90 to 1.0, 130 to 0.0),
    QuickRise(4, "quick_rise", 150, 0 to 0.0, 150 to 1.0),
    SlowRise(5, "slow_rise", 500, 0 to 0.0, 500 to 1.0),
    QuickFall(6, "quick_fall", 100, 0 to 1.0, 100 to 0.0),
    Tick(7, "tick", 20, 0 to 1.0, 5 to .35, 20 to 0.0),
    LowTick(8, "low_tick", 20, 0 to .55, 10 to .4, 20 to 0.0);

    private val approximation = HapticParameter(curve.map { (ms, value) -> listOf(ms.toDouble(), value) })
    fun amplitudeAt(ms: Int): Double = approximation.at(ms / 5 * 5)

    companion object {
        fun fromKey(key: String): HapticPrimitive = entries.firstOrNull { it.key == key }
            ?: throw IllegalArgumentException("Unknown primitive: $key")
    }
}
