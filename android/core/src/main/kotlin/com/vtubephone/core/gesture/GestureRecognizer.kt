package com.vtubephone.core.gesture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns raw sensor and touch streams into body-language [GestureEvent]s.
 *
 * Arbitration (docs/tasks-unity-legacy/T02-sensing.md): shake_hard > flip_down > nod/headshake > tilt.
 *  - A hard shake suppresses tilt, swings and back knocks until it settles (+shakeSuppressMs).
 *  - Face-down suppresses tilt; the still that follows a flip is not reported (flip_down already says it).
 *  - A nod or headshake resets the tilt candidate and blocks the other swing detector.
 *  - A light shake is only reported when no swing was recognised during the same motion episode.
 * False positives cost more than misses: every detector requires a sharp, sustained or dominant signal.
 *
 * Thread-safe: sensor samples and touches may arrive from different threads.
 */
class GestureRecognizer(config: GestureConfig = GestureConfig()) {
    @Volatile var config: GestureConfig = config
        set(value) { synchronized(this) { field = value; rebuild() } }

    private var nod = SwingDetector(config)
    private var headshake = SwingDetector(config)
    private var sequencer = KnockSequencer(config)
    private var touch = TouchGestureRecognizer(config) { t, target -> sequencer.add(t, target) }
    private fun rebuild() {
        nod = SwingDetector(config); headshake = SwingDetector(config); sequencer = KnockSequencer(config)
        touch = TouchGestureRecognizer(config) { t, target -> sequencer.add(t, target) }
    }

    // Latest vectors
    private var up: Vec3? = null
    private var hasGravitySensor = false
    private var hasLinearSensor = false
    private var accelLowPass: Vec3? = null
    private var gyro = Vec3.Zero
    private var lastGyroNs: Long? = null
    private var lastLinearNs: Long? = null

    // Steadiness (exponential moving averages of |ω| and |linear|)
    private var gyroEma = 0.0
    private var linearEma = 0.0

    // Expectation
    private var expected: Set<GestureId> = emptySet()
    private var expectedUntilNs = Long.MIN_VALUE
    private fun sensitivity(id: GestureId, tNs: Long) = if (tNs <= expectedUntilNs && id in expected) config.expectFactor else 1.0

    /** The dialog is waiting for one of [ids]; make those slightly easier to trigger until [untilNs]. */
    fun expect(ids: Set<GestureId>, untilNs: Long) = synchronized(this) { expected = ids; expectedUntilNs = untilNs }

    // Posture / tilt
    private var neutral: Vec3? = null
    private var tiltCandidate: GestureId? = null
    private var tiltSinceNs = 0L
    private var tiltActive: GestureId? = null
    private var tiltActiveSinceNs = 0L
    private var guardNeutral: Vec3? = null
    private var guardUntilNs = Long.MIN_VALUE
    private var tiltBlockedUntilNs = Long.MIN_VALUE
    private var lastPostureNs: Long? = null
    /** Degrees from the neutral posture (positive roll = left edge down, positive pitch = top edge away). */
    var roll = 0.0; private set
    var pitch = 0.0; private set

    // Shake
    private val shakeWindow = ArrayDeque<Pair<Long, Double>>()
    private var shakeSum = 0.0
    private var shaking = false
    private var shakeStartNs = 0L
    private var shakeQuietSinceNs: Long? = null
    private var shakeHardFired = false
    private var lastSwingNs = Long.MIN_VALUE / 2
    private var shakePeak = 0.0
    private var shakeReversals = 0
    private var shakeLastDir: Vec3? = null
    private var shakeSuppressUntilNs = Long.MIN_VALUE
    var shakeRms = 0.0; private set

    // Flip / still / pickup
    private var flipSinceNs: Long? = null
    private var flipFired = false
    private var quietSinceNs: Long? = null
    private var stillActive = false
    private var stillUp: Vec3? = null
    private var lastPickupNs = Long.MIN_VALUE / 2

    // Back knock
    private var hpLowPass: Vec3? = null
    private var lastAccelNs: Long? = null
    private var spikeStartNs: Long? = null
    private var spikePeak = 0.0
    private var spikeCalm = false
    private var lastLowNs = Long.MIN_VALUE / 2
    private var spikeRefractoryUntilNs = Long.MIN_VALUE
    private val pendingKnocks = ArrayDeque<Pair<Long, Double>>()
    private val upHistory = ArrayDeque<Pair<Long, Vec3>>()
    var highPass = 0.0; private set
    /** Peak of the last accepted or rejected spike, for calibration UIs. */
    var lastSpikePeak = 0.0; private set

    fun onSample(s: MotionSample): List<GestureEvent> = synchronized(this) {
        val out = ArrayList<GestureEvent>(2)
        when (s.kind) {
            SensorKind.Accel -> onAccel(s, out)
            SensorKind.Gravity -> { hasGravitySensor = true; setUp(s.tNs, Vec3.of(s).normalized(), out) }
            SensorKind.Gyro -> onGyro(s, out)
            SensorKind.Linear -> { hasLinearSensor = true; onLinear(s.tNs, Vec3.of(s), out) }
            SensorKind.Rotation -> Unit
        }
        tickLocked(s.tNs, out)
        out
    }

    fun onTouch(t: TouchSample): List<GestureEvent> = synchronized(this) {
        val out = ArrayList(touch.onTouch(t))
        tickLocked(t.tNs, out)
        out
    }

    /** Lets time-based gestures (knock sequence end, hold) finish when no samples arrive. */
    fun tick(tNs: Long): List<GestureEvent> = synchronized(this) { ArrayList<GestureEvent>().also { tickLocked(tNs, it) } }

    private fun tickLocked(tNs: Long, out: MutableList<GestureEvent>) {
        touch.tick(tNs)?.let { out += it }
        confirmBackKnocks(tNs, out)
        sequencer.tick(tNs)?.let { out += it }
    }

    // ---- Accelerometer: gravity fallback, linear fallback, back knock ----
    private fun onAccel(s: MotionSample, out: MutableList<GestureEvent>) {
        val a = Vec3.of(s)
        val dt = lastAccelNs?.let { (s.tNs - it).coerceIn(0, 50 * MS) / 1e9 } ?: 0.0
        lastAccelNs = s.tNs
        if (!hasGravitySensor) {
            val lp = accelLowPass?.let { it + (a - it) * alpha(dt, 200.0) } ?: a
            accelLowPass = lp
            setUp(s.tNs, lp.normalized(), out)
            if (!hasLinearSensor) onLinear(s.tNs, a - lp, out)
        } else if (!hasLinearSensor) up?.let { onLinear(s.tNs, a - it * GRAVITY, out) }
        val lp = hpLowPass?.let { it + (a - it) * alpha(dt, config.backKnockHighpassTauMs) } ?: a
        hpLowPass = lp
        highPass = (a - lp).length
        detectSpike(s.tNs, highPass)
    }

    private fun detectSpike(tNs: Long, value: Double) {
        val threshold = config.backKnockThreshold * sensitivity(GestureId.BackKnock, tNs)
        val start = spikeStartNs
        if (start == null) {
            if (value > threshold) {
                // Knocks are impulsive: the high-pass value jumps from near zero within a few ms. Slopes ramp up slowly.
                spikeStartNs = tNs; spikePeak = value
                spikeCalm = tNs - lastLowNs <= config.backKnockMaxRiseMs * MS && !shaking && tNs > shakeSuppressUntilNs &&
                    gyroEma < config.backKnockMaxGyro && linearEma < config.backKnockMaxLinear
            } else if (value < threshold * 0.25) lastLowNs = tNs
            return
        }
        spikePeak = max(spikePeak, value)
        if (value >= threshold * 0.5) return
        spikeStartNs = null
        lastSpikePeak = spikePeak
        val widthMs = (tNs - start) / MS.toDouble()
        if (start < spikeRefractoryUntilNs) return
        spikeRefractoryUntilNs = start + (config.backKnockRefractoryMs * MS).toLong()
        if (widthMs <= config.backKnockMaxWidthMs && spikeCalm && !turnedRecently(start)) pendingKnocks.addLast(start to spikePeak)
    }

    private fun turnedRecently(tNs: Long): Boolean {
        val now = upHistory.lastOrNull()?.second ?: return false
        val windowStart = tNs - (config.backKnockTurnWindowMs * MS).toLong()
        return upHistory.any { it.first >= windowStart && angleDeg(it.second, now) > config.backKnockMaxTurnDeg }
    }

    private fun confirmBackKnocks(tNs: Long, out: MutableList<GestureEvent>) {
        while (pendingKnocks.isNotEmpty()) {
            val (spikeNs, _) = pendingKnocks.first()
            if (tNs - spikeNs < config.touchGuardAfterMs * MS) return
            pendingKnocks.removeFirst()
            if (touch.touchedNear(spikeNs, config.touchGuardBeforeMs, config.touchGuardAfterMs)) continue
            // A spike can finish being measured after the pickup that caused it.
            if (spikeNs >= lastPickupNs - 600 * MS && spikeNs <= lastPickupNs + 300 * MS) continue
            sequencer.add(spikeNs, "back")?.let { out += it }
        }
    }

    // ---- Gyroscope: nod (about the device x axis) and headshake (twist about gravity) ----
    private fun onGyro(s: MotionSample, out: MutableList<GestureEvent>) {
        val w = Vec3.of(s)
        gyro = w
        val dt = lastGyroNs?.let { (s.tNs - it).coerceIn(0, 50 * MS) / 1e9 } ?: 0.0
        lastGyroNs = s.tNs
        gyroEma += (w.length - gyroEma) * alpha(dt, config.steadyTauMs)
        val u = up ?: return
        val suppressed = s.tNs <= shakeSuppressUntilNs || shakeHardFired || flipFired
        val twist = w dot u
        val nodOther = sqrt(w.y * w.y + w.z * w.z)
        val twistOther = (w - u * twist).length
        nod.update(s.tNs, w.x, nodOther, sensitivity(GestureId.Nod, s.tNs), suppressed)?.let { strength ->
            swingFired(s.tNs, GestureId.Nod, strength, out); headshake.blockUntil(s.tNs + (config.swingRefractoryMs * MS).toLong())
        }
        headshake.update(s.tNs, twist, twistOther, sensitivity(GestureId.Headshake, s.tNs), suppressed)?.let { strength ->
            swingFired(s.tNs, GestureId.Headshake, strength, out); nod.blockUntil(s.tNs + (config.swingRefractoryMs * MS).toLong())
        }
    }

    private fun swingFired(tNs: Long, id: GestureId, strength: Double, out: MutableList<GestureEvent>) {
        out += GestureEvent(id, tNs, strength = strength)
        lastSwingNs = tNs
        tiltCandidate = null
        tiltBlockedUntilNs = tNs + 400 * MS
    }

    // ---- Linear acceleration: shake episodes and steadiness ----
    private fun onLinear(tNs: Long, lin: Vec3, out: MutableList<GestureEvent>) {
        val dt = lastLinearNs?.let { (tNs - it).coerceIn(0, 50 * MS) / 1e9 } ?: 0.0
        lastLinearNs = tNs
        val magnitude = lin.length
        linearEma += (magnitude - linearEma) * alpha(dt, config.steadyTauMs)
        shakeWindow.addLast(tNs to magnitude * magnitude); shakeSum += magnitude * magnitude
        while (shakeWindow.isNotEmpty() && tNs - shakeWindow.first().first > config.shakeWindowMs * MS) shakeSum -= shakeWindow.removeFirst().second
        shakeRms = sqrt(max(shakeSum, 0.0) / shakeWindow.size)

        if (!shaking && shakeRms > config.shakeLightRms) {
            shaking = true; shakeStartNs = tNs; shakeQuietSinceNs = null; shakeHardFired = false
            shakePeak = 0.0; shakeReversals = 0; shakeLastDir = null
        }
        if (!shaking) return
        shakePeak = max(shakePeak, magnitude)
        if (magnitude > config.shakeLightRms) {
            val dir = lin.normalized()
            shakeLastDir?.let { if ((it dot dir) < -0.3) shakeReversals++ }
            shakeLastDir = dir
        }
        if (!shakeHardFired && (shakePeak >= config.shakeHardPeak || shakeRms >= config.shakeHardRms)) {
            shakeHardFired = true
            out += GestureEvent(GestureId.ShakeHard, tNs, strength = (shakePeak / (config.shakeHardPeak * 2)).coerceIn(0.0, 1.0))
            tiltCandidate = null; nod.reset(); headshake.reset(); pendingKnocks.clear(); sequencer.clear()
        }
        if (shakeRms < config.shakeLightRms * 0.5) {
            val since = shakeQuietSinceNs ?: tNs.also { shakeQuietSinceNs = it }
            if (tNs - since >= config.shakeEndMs * MS) endShake(tNs, out)
        } else shakeQuietSinceNs = null
    }

    private fun endShake(tNs: Long, out: MutableList<GestureEvent>) {
        shaking = false
        val durationMs = (tNs - shakeStartNs) / MS.toDouble()
        // A nod or headshake moves the phone too; never report that motion again as a light shake.
        val hadSwing = lastSwingNs >= shakeStartNs - 500 * MS
        if (!shakeHardFired && !hadSwing && durationMs >= config.shakeMinMs && shakeReversals >= 2)
            out += GestureEvent(GestureId.ShakeLight, tNs, strength = (shakePeak / config.shakeHardPeak).coerceIn(0.0, 1.0))
        shakeSuppressUntilNs = tNs + (config.shakeSuppressMs * MS).toLong()
        shakeHardFired = false
    }

    // ---- Gravity: posture, tilt, flip, still, pickup ----
    private fun setUp(tNs: Long, u: Vec3, out: MutableList<GestureEvent>) {
        up = u
        upHistory.addLast(tNs to u)
        while (upHistory.isNotEmpty() && tNs - upHistory.first().first > 1000 * MS) upHistory.removeFirst()
        val dt = lastPostureNs?.let { (tNs - it).coerceIn(0, 100 * MS) / 1e9 } ?: 0.0
        lastPostureNs = tNs
        updateTilt(tNs, u, dt, out)
        updateFlipAndStill(tNs, u, out)
    }

    private fun steady() = gyroEma < config.steadyGyro && linearEma < config.steadyLinear

    private fun updateTilt(tNs: Long, u: Vec3, dt: Double, out: MutableList<GestureEvent>) {
        // After a pickup the neutral posture is re-learned once the hand is steady.
        val n = neutral ?: if (steady()) u.also { neutral = it } else return
        // Left edge down tilts the device x axis upwards, so the "up" vector gains +x.
        roll = wrapDeg(Math.toDegrees(atan2(u.x, hypot(u.y, u.z)) - atan2(n.x, hypot(n.y, n.z))))
        pitch = wrapDeg(Math.toDegrees(atan2(u.z, u.y) - atan2(n.z, n.y)))
        val faceDown = u.z < config.flipRearmZ
        val blocked = faceDown || shaking || tNs <= shakeSuppressUntilNs || tNs <= tiltBlockedUntilNs
        val (dir, magnitude) = if (abs(roll) >= abs(pitch))
            (if (roll > 0) GestureId.TiltLeft else GestureId.TiltRight) to abs(roll)
        else (if (pitch > 0) GestureId.TiltForward else GestureId.TiltBack) to abs(pitch)

        // Returning to the previous posture right after adopting a new one is not a gesture.
        guardNeutral?.let { old ->
            if (tNs <= guardUntilNs && angleDeg(u, old) < config.tiltRearmDeg) { neutral = old; guardNeutral = null; tiltCandidate = null; tiltActive = null; return }
            if (tNs > guardUntilNs) guardNeutral = null
        }

        if (magnitude < config.tiltRearmDeg) {
            tiltCandidate = null; tiltActive = null
            if (steady() && !faceDown) neutral = (n + (u - n) * alpha(dt, config.neutralTauMs)).normalized()
            return
        }
        val active = tiltActive
        if (active != null) {
            if (tNs - tiltActiveSinceNs >= config.postureAdoptMs * MS && steady()) {
                guardNeutral = n; guardUntilNs = tNs + (config.postureGuardMs * MS).toLong()
                neutral = u; tiltActive = null; tiltCandidate = null
            }
            return
        }
        if (blocked || magnitude < config.tiltDeg * sensitivity(dir, tNs)) { tiltCandidate = null; return }
        if (tiltCandidate != dir) { tiltCandidate = dir; tiltSinceNs = tNs; return }
        if (tNs - tiltSinceNs >= config.tiltHoldMs * sensitivity(dir, tNs) * MS) {
            tiltActive = dir; tiltActiveSinceNs = tNs; tiltCandidate = null
            out += GestureEvent(dir, tNs, strength = (magnitude / (config.tiltDeg * 2.5)).coerceIn(0.0, 1.0))
        }
    }

    private fun updateFlipAndStill(tNs: Long, u: Vec3, out: MutableList<GestureEvent>) {
        if (u.z > config.flipRearmZ) { flipFired = false; flipSinceNs = null }
        if (!flipFired && u.z < config.flipDownZ && steady()) {
            val since = flipSinceNs ?: tNs.also { flipSinceNs = it }
            if (tNs - since >= config.flipHoldMs * MS) { flipFired = true; out += GestureEvent(GestureId.FlipDown, tNs) }
        } else if (u.z >= config.flipDownZ) flipSinceNs = null

        val quiet = gyroEma < config.quietGyro && linearEma < config.quietLinear
        if (!stillActive) {
            if (quiet && abs(u.z) > config.stillFlatZ) {
                val since = quietSinceNs ?: tNs.also { quietSinceNs = it }
                if (tNs - since >= config.stillHoldMs * MS) {
                    stillActive = true; stillUp = u
                    if (u.z > 0) out += GestureEvent(GestureId.Still, tNs)
                }
            } else quietSinceNs = null
        } else {
            val moved = gyroEma > config.pickupGyro || linearEma > config.pickupLinear ||
                (stillUp?.let { angleDeg(it, u) > config.pickupAngleDeg } ?: false)
            if (moved) {
                stillActive = false; quietSinceNs = null
                out += GestureEvent(GestureId.Pickup, tNs)
                lastPickupNs = tNs
                // The lift itself jolts the phone and changes its posture: neither is a knock or a tilt.
                pendingKnocks.removeAll { it.first >= tNs - 600 * MS }
                neutral = null; tiltCandidate = null; tiltActive = null; tiltBlockedUntilNs = tNs + 1000 * MS
            }
        }
    }

    private fun alpha(dtSeconds: Double, tauMs: Double) = if (dtSeconds <= 0) 0.0 else 1 - exp(-dtSeconds / (tauMs / 1000))
}
