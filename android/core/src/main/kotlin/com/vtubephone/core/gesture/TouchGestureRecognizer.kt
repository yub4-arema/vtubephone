package com.vtubephone.core.gesture

import kotlin.math.hypot
import kotlin.math.max

/**
 * Single-pointer touch gestures. Taps are not emitted directly: they are handed to [onTap] so the knock
 * sequencer can merge them with back knocks. Strokes repeat while the finger keeps moving over the avatar.
 */
internal class TouchGestureRecognizer(private val config: GestureConfig, private val onTap: (Long, String) -> GestureEvent?) {
    private var pointer: Int? = null
    private var downNs = 0L
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var maxSlop = 0.0
    private var strokeDistance = 0.0
    private var strokeStartNs = 0L
    private var lastStrokeNs = Long.MIN_VALUE / 2
    private var holdFired = false
    private var onAvatarAtDown = false
    val touchTimesNs = ArrayDeque<Long>()

    fun onTouch(t: TouchSample): List<GestureEvent> {
        val out = ArrayList<GestureEvent>()
        touchTimesNs.addLast(t.tNs)
        while (touchTimesNs.size > 64) touchTimesNs.removeFirst()
        when (t.phase) {
            TouchPhase.Down -> if (pointer == null) {
                pointer = t.pointerId; downNs = t.tNs; downX = t.x; downY = t.y; lastX = t.x; lastY = t.y
                maxSlop = 0.0; strokeDistance = 0.0; strokeStartNs = t.tNs; holdFired = false; onAvatarAtDown = t.onAvatar
            }
            TouchPhase.Move -> if (t.pointerId == pointer) {
                val step = hypot((t.x - lastX).toDouble(), (t.y - lastY).toDouble())
                lastX = t.x; lastY = t.y
                maxSlop = max(maxSlop, hypot((t.x - downX).toDouble(), (t.y - downY).toDouble()))
                if (t.onAvatar) strokeDistance += step
                if (strokeDistance >= config.strokeDistPx && t.tNs - lastStrokeNs >= config.strokeCooldownMs * MS) {
                    val seconds = max((t.tNs - strokeStartNs) / 1e9, 0.05)
                    val speed = strokeDistance / seconds // px/s; a calm stroke is ~300 px/s
                    out += GestureEvent(GestureId.Stroke, t.tNs, strength = (speed / 1200).coerceIn(0.1, 1.0), target = "avatar")
                    strokeDistance = 0.0; strokeStartNs = t.tNs; lastStrokeNs = t.tNs
                }
            }
            TouchPhase.Up, TouchPhase.Cancel -> if (t.pointerId == pointer) {
                val quick = (t.tNs - downNs) <= config.tapMaxMs * MS && maxSlop <= config.tapSlopPx
                if (t.phase == TouchPhase.Up && quick && !holdFired) onTap(downNs, if (onAvatarAtDown) "avatar" else "screen")?.let { out += it }
                pointer = null
            }
        }
        return out
    }

    fun tick(tNs: Long): GestureEvent? {
        if (pointer == null || holdFired || maxSlop > config.tapSlopPx || tNs - downNs < config.holdMs * MS) return null
        holdFired = true
        return GestureEvent(GestureId.Hold, tNs, target = if (onAvatarAtDown) "avatar" else "screen")
    }

    fun touchedNear(tNs: Long, beforeMs: Double, afterMs: Double) =
        touchTimesNs.any { it >= tNs - (beforeMs * MS).toLong() && it <= tNs + (afterMs * MS).toLong() } ||
            (pointer != null && tNs >= downNs - (beforeMs * MS).toLong())
}
