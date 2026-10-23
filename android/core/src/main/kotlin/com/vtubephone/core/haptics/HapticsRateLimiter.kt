package com.vtubephone.core.haptics

class HapticsRateLimiter {
    private val plays = ArrayDeque<Double>()
    private var lastSpontaneous = Double.NEGATIVE_INFINITY
    var enabled = true
    var maxPerMinute = 60
    var spontaneousCooldownSeconds = 2.0
    var strength = 1.0
        set(value) { require(value.isFinite()); field = value.coerceIn(0.0, 1.0) }
    fun tryAcquire(nowSeconds: Double, spontaneous: Boolean = false): Boolean {
        if (!nowSeconds.isFinite() || !enabled || strength <= 0 || maxPerMinute <= 0) return false
        while (plays.isNotEmpty() && nowSeconds - plays.first() >= 60) plays.removeFirst()
        if (plays.size >= maxPerMinute || spontaneous && nowSeconds - lastSpontaneous < spontaneousCooldownSeconds) return false
        plays.addLast(nowSeconds)
        if (spontaneous) lastSpontaneous = nowSeconds
        return true
    }
}
