package com.vtubephone.core.gesture

import kotlin.math.sqrt

/** Body-language vocabulary from docs/product.md. [wire] is the id used by the bridge, LLM and logs. */
enum class GestureId(val wire: String) {
    Nod("nod"), Headshake("headshake"),
    TiltLeft("tilt_left"), TiltRight("tilt_right"), TiltForward("tilt_forward"), TiltBack("tilt_back"),
    Knock("knock"), BackKnock("back_knock"), Stroke("stroke"), Hold("hold"),
    ShakeLight("shake_light"), ShakeHard("shake_hard"), FlipDown("flip_down"), Pickup("pickup"), Still("still");
    companion object { fun fromWire(wire: String) = entries.firstOrNull { it.wire == wire } }
}

/**
 * One recognised gesture. Times are SensorEvent clock nanoseconds (elapsedRealtimeNanos).
 * Knock/BackKnock carry [count] and the inter-onset [intervalsMs]; [target] is "avatar", "screen" or "back".
 */
data class GestureEvent(
    val id: GestureId, val tNs: Long, val strength: Double = 1.0, val count: Int = 0,
    val intervalsMs: List<Long> = emptyList(), val confidence: Double = 1.0, val target: String? = null,
)

enum class SensorKind { Accel, Gyro, Gravity, Linear, Rotation }

/** Raw Android sensor axes: x right, y towards the top edge, z out of the screen. Gravity points "up" (reaction). */
data class MotionSample(val tNs: Long, val kind: SensorKind, val x: Float, val y: Float, val z: Float)

enum class TouchPhase { Down, Move, Up, Cancel }

/** Touch in CSS/dp pixels, already converted to the sensor clock. */
data class TouchSample(val tNs: Long, val pointerId: Int, val phase: TouchPhase, val x: Float, val y: Float, val onAvatar: Boolean)

internal data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    val length get() = sqrt(this dot this)
    fun normalized() = length.let { if (it < 1e-9) this else this * (1 / it) }
    companion object { val Zero = Vec3(0.0, 0.0, 0.0); fun of(s: MotionSample) = Vec3(s.x.toDouble(), s.y.toDouble(), s.z.toDouble()) }
}

internal fun angleDeg(a: Vec3, b: Vec3) = Math.toDegrees(kotlin.math.acos((a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0)))
internal fun wrapDeg(d: Double) = ((d + 540.0) % 360.0) - 180.0
internal const val MS = 1_000_000L
internal const val GRAVITY = 9.80665
