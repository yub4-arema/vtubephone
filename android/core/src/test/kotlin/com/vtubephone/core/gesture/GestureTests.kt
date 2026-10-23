package com.vtubephone.core.gesture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Builds synthetic sensor streams: accelerometer at 500 Hz, gravity/gyro/linear at 200 Hz. */
private class Synth {
    val records = ArrayList<MotionCsv.Record>()
    var tNs = 1_000_000_000L
    private val zero = Vec3.Zero

    fun segment(
        ms: Int, up: (Double) -> Vec3, gyro: (Double) -> Vec3 = { zero }, linear: (Double) -> Vec3 = { zero },
        accelExtra: (Double) -> Vec3 = { zero },
    ): Synth {
        val steps = ms / 2
        for (i in 0 until steps) {
            val s = i * 0.002
            val u = up(s).normalized()
            val accel = u * GRAVITY + linear(s) + accelExtra(s)
            records += sample(SensorKind.Accel, accel)
            if (i % 5 == 0) {
                records += sample(SensorKind.Gravity, u * GRAVITY)
                records += sample(SensorKind.Gyro, gyro(s))
                records += sample(SensorKind.Linear, linear(s))
            }
            tNs += 2 * MS
        }
        return this
    }

    /** A hand-held pose has physiological tremor (~9 Hz); a phone lying on a table ([tremor] = false) does not. */
    fun hold(ms: Int, up: Vec3, tremor: Boolean = true) = if (!tremor) segment(ms, { up }) else segment(ms, { up },
        gyro = { s -> Vec3(0.08 * sin(2 * PI * 9 * s), 0.06 * cos(2 * PI * 9 * s), 0.0) },
        linear = { s -> Vec3(0.2 * sin(2 * PI * 7 * s), 0.0, 0.0) })
    fun touch(offsetMs: Int, phase: TouchPhase, x: Float = 100f, y: Float = 100f, onAvatar: Boolean = false) {
        records += MotionCsv.Touch(TouchSample(tNs + offsetMs * MS, 0, phase, x, y, onAvatar))
    }
    private fun sample(kind: SensorKind, v: Vec3) = MotionCsv.Sample(MotionSample(tNs, kind, v.x.toFloat(), v.y.toFloat(), v.z.toFloat()))
    fun replay(recognizer: GestureRecognizer = GestureRecognizer()) = MotionCsv.replay(records, recognizer)
}

/** Device "up" for a posture: screen tilted back by [pitchDeg] (0 = upright, 90 = flat face-up), left edge down by [rollDeg]. */
private fun posture(pitchDeg: Double = 45.0, rollDeg: Double = 0.0): Vec3 {
    val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
    return Vec3(sin(r), cos(r) * cos(p), cos(r) * sin(p))
}
/** Walking does not start instantly. */
private fun ramp(s: Double) = (s / 0.5).coerceIn(0.0, 1.0)
private fun lerp(a: Double, b: Double, s: Double) = a + (b - a) * s.coerceIn(0.0, 1.0)
private fun spike(at: Double, peak: Double, widthS: Double = 0.010): (Double) -> Vec3 = { s ->
    val d = kotlin.math.abs(s - at)
    if (d < widthS / 2) Vec3(0.0, 0.0, peak * (1 - d / (widthS / 2))) else Vec3.Zero
}
private fun List<GestureEvent>.ids() = map { it.id }

class GestureTests {
    @Test fun tiltLeftFromHeldPosture() {
        val e = Synth().apply {
            hold(2000, posture())
            segment(300, { s -> posture(rollDeg = lerp(0.0, 30.0, s / 0.3)) }, gyro = { Vec3(0.0, 0.0, 1.7) })
            hold(800, posture(rollDeg = 30.0))
        }.replay()
        assertEquals(listOf(GestureId.TiltLeft), e.ids())
    }

    @Test fun tiltRightWhileLyingDownUsesNeutralNotAbsoluteAngle() {
        val e = Synth().apply {
            hold(2000, posture(pitchDeg = 80.0))
            segment(300, { s -> posture(80.0, lerp(0.0, -35.0, s / 0.3)) }, gyro = { Vec3(0.0, -1.6, 0.0) })
            hold(800, posture(80.0, -35.0))
        }.replay()
        assertEquals(listOf(GestureId.TiltRight), e.ids())
    }

    @Test fun tiltForwardAndBack() {
        val forward = Synth().apply {
            hold(2000, posture(30.0)); segment(300, { s -> posture(lerp(30.0, 60.0, s / 0.3)) }); hold(800, posture(60.0))
        }.replay()
        assertEquals(listOf(GestureId.TiltForward), forward.ids())
        val back = Synth().apply {
            hold(2000, posture(50.0)); segment(300, { s -> posture(lerp(50.0, 20.0, s / 0.3)) }); hold(800, posture(20.0))
        }.replay()
        assertEquals(listOf(GestureId.TiltBack), back.ids())
    }

    @Test fun briefTiltBelowHoldTimeIsIgnored() {
        val e = Synth().apply {
            hold(2000, posture()); segment(150, { s -> posture(rollDeg = lerp(0.0, 30.0, s / 0.15)) })
            hold(250, posture(rollDeg = 30.0)); segment(150, { s -> posture(rollDeg = lerp(30.0, 0.0, s / 0.15)) }); hold(1000, posture())
        }.replay()
        assertTrue(e.isEmpty())
    }

    @Test fun expectationMakesASmallTiltCount() {
        fun run(expect: Boolean): List<GestureEvent> {
            val r = GestureRecognizer()
            if (expect) r.expect(setOf(GestureId.TiltLeft), Long.MAX_VALUE)
            return Synth().apply {
                hold(2000, posture()); segment(300, { s -> posture(rollDeg = lerp(0.0, 25.0, s / 0.3)) }); hold(800, posture(rollDeg = 25.0))
            }.replay(r)
        }
        assertTrue(run(false).isEmpty())
        assertEquals(listOf(GestureId.TiltLeft), run(true).ids())
    }

    @Test fun holdingANewPostureIsAdoptedAndReturningIsNotATilt() {
        val e = Synth().apply {
            hold(2000, posture())
            segment(300, { s -> posture(rollDeg = lerp(0.0, 30.0, s / 0.3)) }); hold(9000, posture(rollDeg = 30.0))
            segment(300, { s -> posture(rollDeg = lerp(30.0, 0.0, s / 0.3)) }); hold(1500, posture())
        }.replay()
        assertEquals(listOf(GestureId.TiltLeft), e.ids())
    }

    private fun swing(hz: Double, peak: Double, cycles: Double): (Double) -> Double = { s ->
        if (s <= cycles / hz) peak * sin(2 * PI * hz * s) else 0.0
    }

    @Test fun nodIsRecognisedAndNotAlsoATiltOrShake() {
        val w = swing(2.5, 3.0, 1.5)
        val e = Synth().apply {
            hold(2000, posture())
            segment(900, { s -> posture(45.0 + 8 * sin(2 * PI * 2.5 * s) * if (s < 0.6) 1.0 else 0.0) },
                gyro = { s -> Vec3(w(s), 0.1, 0.05) },
                linear = { s -> Vec3(0.0, 1.5 * w(s) / 3.0, 2.0 * w(s) / 3.0) })
            hold(1000, posture())
        }.replay()
        assertEquals(listOf(GestureId.Nod), e.ids())
    }

    @Test fun headshakeIsATwistAboutGravity() {
        val u = posture()
        val w = swing(2.5, 3.0, 1.5)
        val e = Synth().apply {
            hold(2000, u)
            segment(900, { u }, gyro = { s -> u * w(s) + Vec3(0.1, 0.0, 0.0) })
            hold(1000, u)
        }.replay()
        assertEquals(listOf(GestureId.Headshake), e.ids())
    }

    @Test fun walkingProducesNothing() {
        val u = posture()
        val e = Synth().apply {
            hold(1000, u)
            segment(6000, { s -> posture(45.0 + 3 * sin(2 * PI * 2 * s), 2 * sin(2 * PI * 1 * s)) },
                gyro = { s -> Vec3(0.8 * sin(2 * PI * 2 * s), 0.7 * cos(2 * PI * 2 * s), 0.6 * sin(2 * PI * 1 * s)) },
                linear = { s -> Vec3(0.5 * sin(2 * PI * 1 * s), 3 * sin(2 * PI * 2 * s), 1.0 * cos(2 * PI * 2 * s)) * ramp(s) },
                // Heel strikes reach the hand damped by the arm: ~80 ms wide.
                accelExtra = { s -> val ph = (s * 2) % 1.0; if (ph < 0.16) Vec3(0.0, 4 * ramp(s) * sin(PI * ph / 0.16), 0.0) else Vec3.Zero })
        }.replay()
        assertTrue("walking produced ${e.map { it.id.wire + "@" + (it.tNs - 1_000_000_000L) / 1_000_000 + " n=" + it.count }}", e.isEmpty())
    }

    @Test fun hardShakeFiresOnceAndSuppressesOtherGestures() {
        val e = Synth().apply {
            hold(1500, posture())
            segment(900, { s -> posture(45 + 25 * sin(2 * PI * 4 * s), 25 * cos(2 * PI * 3 * s)) },
                gyro = { s -> Vec3(6 * sin(2 * PI * 4 * s), 5 * cos(2 * PI * 3 * s), 4 * sin(2 * PI * 5 * s)) },
                linear = { s -> Vec3(30 * sin(2 * PI * 8 * s), 12 * cos(2 * PI * 8 * s), 5.0) })
            hold(1500, posture())
        }.replay()
        assertEquals(listOf(GestureId.ShakeHard), e.ids())
    }

    @Test fun lightShake() {
        val e = Synth().apply {
            hold(1500, posture())
            segment(800, { posture() }, gyro = { s -> Vec3(0.3 * sin(2 * PI * 5 * s), 0.0, 0.0) },
                linear = { s -> Vec3(7 * sin(2 * PI * 5 * s), 0.0, 0.0) })
            hold(1500, posture())
        }.replay()
        assertEquals(listOf(GestureId.ShakeLight), e.ids())
    }

    @Test fun slowTiltIsNotAShake() {
        val e = Synth().apply {
            hold(1500, posture()); segment(1500, { s -> posture(rollDeg = lerp(0.0, 8.0, s / 1.5)) }); hold(500, posture(rollDeg = 8.0))
        }.replay()
        assertTrue(e.isEmpty())
    }

    @Test fun flipDownThenPickup() {
        val e = Synth().apply {
            hold(1500, posture())
            segment(500, { s -> posture(lerp(45.0, -90.0, s / 0.5)) }, gyro = { Vec3(-4.5, 0.0, 0.0) })
            hold(2500, posture(-90.0), tremor = false)
            segment(400, { s -> posture(lerp(-90.0, 45.0, s / 0.4)) }, gyro = { Vec3(5.0, 0.0, 0.0) }, linear = { Vec3(0.0, 1.5, 1.0) })
            hold(800, posture())
        }.replay()
        assertEquals(listOf(GestureId.FlipDown, GestureId.Pickup), e.ids())
    }

    @Test fun stillOnTableThenPickup() {
        val e = Synth().apply {
            hold(2500, posture(90.0), tremor = false)
            segment(300, { s -> posture(lerp(90.0, 45.0, s / 0.3)) }, gyro = { Vec3(-2.5, 0.0, 0.0) }, linear = { Vec3(0.0, 2.0, 1.0) })
            hold(500, posture())
        }.replay()
        assertEquals(e.map { it.id.wire + "@" + (it.tNs - 1_000_000_000L) / 1_000_000 }.toString(), listOf(GestureId.Still, GestureId.Pickup), e.ids())
    }

    @Test fun twoBackKnocksBecomeOneSequenceWithRhythm() {
        val e = Synth().apply {
            hold(1500, posture())
            segment(400, { posture() }, accelExtra = { s -> spike(0.05, 6.0)(s) + spike(0.30, 6.0)(s) })
            hold(1500, posture())
        }.replay()
        assertEquals(listOf(GestureId.BackKnock), e.ids())
        assertEquals(2, e[0].count)
        assertEquals("back", e[0].target)
        assertEquals(250.0, e[0].intervalsMs.single().toDouble(), 6.0)
    }

    @Test fun screenTapSuppressesItsOwnAccelerometerSpike() {
        val e = Synth().apply {
            hold(1500, posture())
            touch(40, TouchPhase.Down); touch(110, TouchPhase.Up)
            segment(400, { posture() }, accelExtra = spike(0.06, -6.0))
            hold(1500, posture())
        }.replay()
        assertEquals(listOf(GestureId.Knock), e.ids())
        assertEquals("screen", e[0].target)
        assertEquals(1, e[0].count)
    }

    @Test fun puttingThePhoneDownIsNotAKnock() {
        val e = Synth().apply {
            hold(1500, posture())
            segment(300, { s -> posture(lerp(45.0, 90.0, s / 0.3)) }, gyro = { Vec3(2.6, 0.0, 0.0) })
            segment(100, { posture(90.0) }, accelExtra = spike(0.01, 9.0))
            hold(2500, posture(90.0))
        }.replay()
        assertFalse(e.ids().any { it == GestureId.BackKnock || it == GestureId.Knock })
    }

    @Test fun wideBumpIsNotAKnock() {
        val e = Synth().apply {
            hold(1500, posture()); segment(400, { posture() }, accelExtra = spike(0.1, 6.0, widthS = 0.12)); hold(1500, posture())
        }.replay()
        assertTrue("wide bump produced ${e.ids()}", e.isEmpty())
    }

    @Test fun screenTapsSequenceAndHoldAndStroke() {
        val r = GestureRecognizer()
        val t0 = 5_000_000_000L
        fun touch(ms: Int, phase: TouchPhase, x: Float = 50f, onAvatar: Boolean = true) =
            r.onTouch(TouchSample(t0 + ms * MS, 0, phase, x, 50f, onAvatar))
        val out = ArrayList<GestureEvent>()
        listOf(0, 200, 380).forEach { out += touch(it, TouchPhase.Down); out += touch(it + 60, TouchPhase.Up) }
        out += r.tick(t0 + 1200 * MS)
        assertEquals(listOf(GestureId.Knock), out.ids())
        assertEquals(3, out[0].count); assertEquals("avatar", out[0].target); assertEquals(listOf(200L, 180L), out[0].intervalsMs)

        out.clear()
        out += touch(2000, TouchPhase.Down); out += r.tick(t0 + 2600 * MS); out += touch(2700, TouchPhase.Up)
        assertEquals(listOf(GestureId.Hold), out.ids())

        out.clear()
        out += touch(4000, TouchPhase.Down, 0f)
        for (i in 1..30) out += touch(4000 + i * 20, TouchPhase.Move, i * 10f)
        out += touch(4700, TouchPhase.Up, 300f)
        out += r.tick(t0 + 6000 * MS)
        assertEquals("one stroke = one event (cooldown)", listOf(GestureId.Stroke), out.ids())
    }

    @Test fun rhythmMatcher() {
        fun knock(vararg intervals: Long) = GestureEvent(GestureId.Knock, 0, count = intervals.size + 1, intervalsMs = intervals.toList())
        assertTrue(RhythmMatcher.matches(listOf(170), knock(240)))
        assertTrue(RhythmMatcher.matches(listOf(150, 300), knock(200, 380)))
        assertFalse(RhythmMatcher.matches(listOf(150, 300), knock(300, 150)))
        assertFalse(RhythmMatcher.matches(listOf(170), knock(170, 170)))
        assertFalse(RhythmMatcher.matches(listOf(170), knock(500)))
        assertTrue(RhythmMatcher.matches(emptyList(), knock()))
        assertEquals(3, RhythmMatcher.count(knock(100, 100)))
    }

    @Test fun calibratorUsesHalfTheMedianPeak() {
        assertEquals(2.5, BackKnockCalibrator.threshold(listOf(4.0, 5.0, 9.0, 6.0, 4.5)), 1e-9)
        assertEquals(0.8, BackKnockCalibrator.threshold(listOf(0.5, 0.6, 0.7)), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { BackKnockCalibrator.threshold(listOf(1.0, 2.0)) }
    }

    @Test fun configJsonOverridesAndRejectsTypos() {
        val c = GestureConfig.fromJson("""{"tiltDeg": 25, "backKnockThreshold": 3.5}""")
        assertEquals(25.0, c.tiltDeg, 0.0); assertEquals(3.5, c.backKnockThreshold, 0.0); assertEquals(600.0, c.tiltHoldMs, 0.0)
        assertEquals(c.tiltDeg, GestureConfig.fromJson(c.toJson()).tiltDeg, 0.0)
        assertThrows(IllegalArgumentException::class.java) { GestureConfig.fromJson("""{"tiltDegree": 25}""") }
    }

    @Test fun csvRoundTripReplaysTheSameGestures() {
        val synth = Synth().apply {
            hold(2000, posture()); segment(300, { s -> posture(rollDeg = lerp(0.0, 30.0, s / 0.3)) }); hold(800, posture(rollDeg = 30.0))
            records += MotionCsv.Label(tNs, "tilt_left")
        }
        val text = synth.records.joinToString("\n") { MotionCsv.format(it) }
        val parsed = MotionCsv.parse("# recording\n$text")
        assertEquals(synth.records.size, parsed.size)
        assertEquals(listOf(GestureId.TiltLeft), MotionCsv.replay(parsed).ids())
        assertEquals("tilt_left", (parsed.last() as MotionCsv.Label).label)
    }

    @Test fun gestureWireIdsMatchProductVocabulary() {
        assertEquals(GestureId.BackKnock, GestureId.fromWire("back_knock"))
        assertEquals(15, GestureId.entries.map { it.wire }.toSet().size)
    }
}
