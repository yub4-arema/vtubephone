package com.vtubephone.core.haptics

import org.junit.Assert.*
import org.junit.Test

class HapticsTests {
    private fun device() = HapticsCapabilities(36, true, true,
        primitives = (1..8).map { PrimitiveCapability(it, true, 12) },
        envelope = EnvelopeCapability(true, 64, 10, 100, 6400))
    private fun realistic(thudMs: Int = 150) = device().let { d -> d.copy(primitives = d.primitives.map {
        it.copy(duration = when (it.id) { 1, 7 -> 20; 2 -> thudMs; 8 -> 40; else -> it.duration })
    }) }
    private fun clip(id: String) = HapticClipParser.parse(javaClass.getResource("/haptics/$id.json")!!.readText())
    private fun rejected(block: () -> Unit) { assertThrows(Exception::class.java, block) }
    private fun knock(count: Int) {
        val c = HapticClipFactory.knock(count)
        assertEquals(count, c.events.size)
        assertEquals((0 until count).map { it * 170 }, c.events.map { it.timeMs })
        assertTrue(c.events.all { !it.continuous })
    }
    @Test fun knock1() = knock(1)
    @Test fun knock2() = knock(2)
    @Test fun knock3() = knock(3)
    @Test fun knock4() = knock(4)
    @Test fun factoryRejectsInvalidCountAndNonfiniteImpact() {
        rejected { HapticClipFactory.knock(0) }; rejected { HapticClipFactory.knock(5) }
        rejected { HapticClipFactory.bump(Double.NaN) }
    }
    @Test fun bumpStrengthIncreasesAndSwitchesMaterial() {
        var intensity = -1.0
        for (i in 0..100) {
            val e = HapticClipFactory.bump(i / 100.0).events.first()
            assertTrue(e.intensity.at(0) >= intensity)
            assertEquals(if (i < 60) HapticPrimitive.Click else HapticPrimitive.Thud, e.primitive)
            intensity = e.intensity.at(0)
        }
    }
    @Test fun allEighteenResourcesParseAndWaveformsPreserveDuration() {
        val directory = java.io.File(javaClass.getResource("/haptics")!!.toURI())
        assertEquals(18, directory.listFiles { f -> f.extension == "json" }!!.size)
        for (id in HapticClipId.entries) {
            val clip = clip(id.key); assertEquals(id.key, clip.id)
            for (tier in HapticTier.entries) {
                val c = HapticRenderer.render(clip, tier, device())
                assertEquals(clip.durationMs.toLong(), c.timings.sum()); assertEquals(clip.durationMs.toLong(), c.onOffTimings.sum())
                assertEquals(c.timings.size, c.amplitudes.size); assertTrue(c.amplitudes.all { it in 0..255 })
            }
        }
    }
    @Test fun curvesInterpolateAndContinuousSamplesEveryFiveMilliseconds() {
        val curve = HapticParameter(listOf(listOf(0.0, 0.0), listOf(20.0, 1.0)))
        assertEquals(.5, curve.at(10), 0.0)
        val c = HapticRenderer.render(HapticClip("curve", listOf(HapticEvent(0, true, 20, curve, HapticParameter(.2)))), HapticTier.C, device())
        assertArrayEquals(longArrayOf(5, 5, 5, 5), c.timings); assertArrayEquals(intArrayOf(0, 64, 128, 191), c.amplitudes)
    }
    @Test fun sharpTransientsAreShorterAndStronger() {
        val dull = HapticRenderer.render(HapticClip("dull", listOf(HapticEvent(0, false, 0, HapticParameter(1.0), HapticParameter(0.0)))), HapticTier.C, device())
        val sharp = HapticRenderer.render(HapticClip("sharp", listOf(HapticEvent(0, false, 0, HapticParameter(1.0), HapticParameter(1.0)))), HapticTier.C, device())
        assertTrue(sharp.durationMs < dull.durationMs); assertTrue(sharp.amplitudes.max() > dull.amplitudes.max())
    }
    @Test fun envelopeHonorsStartEndAndAllDeviceConstraints() {
        val d = device(); val clip = HapticClip("continuous", listOf(HapticEvent(0, true, 600, HapticParameter(.6), HapticParameter(.2)))); val c = HapticRenderer.render(clip, HapticTier.A, d)
        assertEquals(HapticTier.A, c.tier); assertEquals(0f, c.points.first().intensity); assertEquals(0f, c.points.last().intensity)
        assertTrue(c.points.size - 1 <= d.envelope.maxSize)
        assertTrue(c.points.drop(1).all { it.durationMs in 10..100 && it.intensity in 0f..1f && it.sharpness in 0f..1f })
        assertEquals(clip.durationMs, c.points.sumOf { it.durationMs })
        assertEquals(HapticTier.C, HapticRenderer.render(clip, HapticTier.A, d.copy(envelope = d.envelope.copy(maxSize = 2))).tier)
        assertEquals(HapticTier.C, HapticRenderer.render(clip, HapticTier.A, d.copy(envelope = d.envelope.copy(maxDuration = 100))).tier)
    }
    @Test fun envelopeSupportsNonDivisibleDuration() {
        val c = HapticRenderer.render(HapticClip("odd", listOf(HapticEvent(0, true, 101, HapticParameter(.6), HapticParameter(.2)))), HapticTier.A, device())
        assertEquals(HapticTier.A, c.tier); assertEquals(101, c.points.sumOf { it.durationMs })
    }
    @Test fun compositionCompensatesPrimitiveDurationAndMapsSharpness() {
        val clip = HapticClip("mapping", listOf(1.0, .6, .3, .1).mapIndexed { i, s -> HapticEvent(i * 100, false, 0, HapticParameter(.7), HapticParameter(s)) })
        val c = HapticRenderer.render(clip, HapticTier.B, device())
        assertEquals(HapticTier.B, c.tier); assertEquals(listOf(7, 1, 8, 2), c.primitives.map { it.id })
        assertEquals(listOf(0, 88, 88, 88), c.primitives.map { it.delayMs }); assertEquals(312, c.durationMs)
    }
    @Test fun unsupportedUnknownAndOverlappingPrimitivesFallBack() {
        val d = device(); val clip = HapticClipFactory.knock(2)
        assertEquals(HapticTier.C, HapticRenderer.render(clip, HapticTier.B, d.copy(primitives = d.primitives.map { if (it.id == 8) it.copy(supported = false) else it })).tier)
        assertEquals(HapticTier.C, HapticRenderer.render(clip, HapticTier.B, d.copy(sdkInt = 30)).tier)
        val overlap = d.copy(primitives = d.primitives.map { if (it.id == 8) it.copy(duration = 200) else it })
        assertEquals(HapticTier.C, HapticRenderer.render(clip, HapticTier.B, overlap).tier)
        assertEquals(HapticTier.D, HapticRenderer.render(clip, HapticTier.B, overlap.copy(hasAmplitudeControl = false)).tier)
    }
    @Test fun mixedEventsAlwaysUseOneWaveform() {
        val clip = HapticClip("mixed", listOf(HapticEvent(0, false, 0, HapticParameter(1.0), HapticParameter(.1)), HapticEvent(25, true, 180, HapticParameter(.65), HapticParameter(.1))))
        for (tier in listOf(HapticTier.A, HapticTier.B)) {
            val c = HapticRenderer.render(clip, tier, device())
            assertEquals(HapticTier.C, c.tier); assertTrue(c.primitives.isEmpty()); assertTrue(c.points.isEmpty())
        }
    }
    private fun keyPhrases(thudMs: Int) {
        val d = realistic(thudMs).copy(sdkInt = 31, envelope = EnvelopeCapability())
        val clips = listOf("thud", "knock", "yes", "call").map(::clip) + (1..4).map { HapticClipFactory.knock(it) }
        for (clip in clips) {
            val c = HapticRenderer.render(clip, d.bestTier, d)
            assertEquals(clip.id, HapticTier.B, c.tier); assertEquals(clip.events.size, c.primitives.size)
            var end = 0
            c.primitives.forEachIndexed { i, p ->
                assertTrue(p.delayMs >= 0); assertEquals(clip.events[i].timeMs, end + p.delayMs)
                end += p.delayMs + p.durationMs
            }
            assertEquals(end, c.durationMs)
        }
    }
    @Test fun keyPhrases60ms() = keyPhrases(60)
    @Test fun keyPhrases100ms() = keyPhrases(100)
    @Test fun keyPhrases150ms() = keyPhrases(150)
    @Test fun thudUsesOneHeavyStrikeAndTwoDecayingLowTicks() {
        val clip = clip("thud"); val c = HapticRenderer.render(clip, HapticTier.B, realistic())
        assertTrue(clip.events.all { !it.continuous }); assertEquals(HapticTier.B, c.tier)
        assertEquals(listOf(2, 8, 8), c.primitives.map { it.id }); assertEquals(1f, c.primitives.first().scale)
        assertTrue(c.primitives.drop(1).all { it.delayMs >= 50 })
        assertTrue(c.primitives.zipWithNext().all { (a, b) -> a.scale > b.scale })
    }
    private fun resourceMatrix(tier: HapticTier) {
        var d = realistic()
        if (tier != HapticTier.A) d = d.copy(envelope = EnvelopeCapability())
        if (tier == HapticTier.B) d = d.copy(sdkInt = 31)
        if (tier == HapticTier.C) d = d.copy(sdkInt = 26, primitives = emptyList())
        assertEquals(tier, d.bestTier)
        HapticClipId.entries.forEach {
            // Soft feelings are continuous and prefer smooth rendering: envelopes on A, waveforms elsewhere.
            val soft = it.key in setOf("purr", "sleepy", "sigh")
            val expected = when {
                tier == HapticTier.C -> HapticTier.C
                soft -> if (tier == HapticTier.A) HapticTier.A else HapticTier.C
                else -> HapticTier.B
            }
            assertEquals(it.key, expected, HapticRenderer.render(clip(it.key), d.bestTier, d).tier)
        }
    }
    @Test fun resourceMatrixA() = resourceMatrix(HapticTier.A)
    @Test fun resourceMatrixB() = resourceMatrix(HapticTier.B)
    @Test fun resourceMatrixC() = resourceMatrix(HapticTier.C)
    @Test fun pwmDutyAndStrengthAreMonotonic() {
        val clip = HapticClip("pwm", listOf(HapticEvent(0, true, 100, HapticParameter(1.0), HapticParameter(.2))))
        var previous = -1L
        for (i in 0..10) {
            val c = HapticRenderer.render(clip, HapticTier.D, device(), i / 10.0)
            val on = c.onOffTimings.filterIndexed { index, _ -> index % 2 == 1 }.sum()
            assertTrue(on in maxOf(0, i * 10 - 1).toLong()..(i * 10).toLong()); assertTrue(on >= previous); previous = on
        }
    }
    @Test fun limiterEnforcesRollingWindowOffZeroStrengthAndCooldown() {
        val l = HapticsRateLimiter().apply { maxPerMinute = 2 }
        assertTrue(l.tryAcquire(0.0, true)); assertFalse(l.tryAcquire(1.0, true)); assertTrue(l.tryAcquire(2.0, true))
        assertFalse(l.tryAcquire(59.0)); assertTrue(l.tryAcquire(60.0))
        l.enabled = false; assertFalse(l.tryAcquire(120.0)); l.enabled = true; l.strength = 0.0; assertFalse(l.tryAcquire(120.0))
        l.strength = .5; assertTrue(l.tryAcquire(120.0))
        assertEquals(.325, HapticRenderer.render(HapticClipFactory.knock(1), HapticTier.B, device(), l.strength).primitives[0].scale.toDouble(), .0001)
    }
    @Test fun yesAndNoHaveDistinctTempoAndDuration() {
        val yes = clip("yes"); val no = clip("no")
        assertEquals(listOf(HapticPrimitive.Tick, HapticPrimitive.Tick), yes.events.map { it.primitive })
        assertEquals(listOf(HapticPrimitive.SlowRise, HapticPrimitive.QuickFall), no.events.map { it.primitive })
        assertTrue(no.durationMs > yes.durationMs * 2)
        assertTrue(yes.events[0].intensity.at(0) < yes.events[1].intensity.at(0))
    }
    @Test fun parserRejectsMalformedOrUnsafeValues() {
        listOf("{}", "{", """{"id":"bad","events":[]}""", """{"id":"bad","events":[{"t":0,"type":"unknown","intensity":1,"sharpness":0}]}""").forEach { rejected { HapticClipParser.parse(it) } }
        rejected { HapticParameter(1.1) }; rejected { HapticParameter(Double.NaN) }
    }
    @Test fun blindTestCountsOneAnswerPerSuccessfullyStartedSample() {
        val t = HapticsBlindTest()
        assertFalse(t.answer(true)); assertTrue(t.begin(true)); assertFalse(t.begin(false)); assertTrue(t.answer(true)); assertFalse(t.answer(true))
        t.begin(false); t.answer(true); assertEquals(2, t.total); assertEquals(1, t.correct); assertEquals(.5, t.accuracy, 0.0)
        t.begin(true); t.cancel(); assertFalse(t.answer(true)); assertEquals(2, t.total)
    }
    @Test fun deviceTierSelectionNeverEnablesUnsupportedTiers() {
        var d = device(); assertEquals(HapticTier.A, d.bestTier)
        d = d.copy(envelope = EnvelopeCapability()); assertEquals(HapticTier.B, d.bestTier)
        d = d.copy(primitives = emptyList()); assertEquals(HapticTier.C, d.bestTier)
        d = d.copy(hasAmplitudeControl = false); assertEquals(HapticTier.D, d.bestTier)
        d = d.copy(hasVibrator = false); assertEquals(HapticTier.D, d.bestTier); assertFalse(HapticTier.entries.any(d::supports))
    }
    @Test fun parserRejectsFractionalNegativeAndOversizedTimesAndInvalidCurves() {
        listOf("-1", "0.5", "60001", "\"0\"").forEach { t -> rejected {
            HapticClipParser.parse("""{"id":"bad","events":[{"t":$t,"type":"transient","intensity":1,"sharpness":0}]}""")
        } }
        rejected { HapticParameter(listOf(listOf(1.0, .5))) }
        rejected { HapticParameter(listOf(listOf(0.0, .5), listOf(0.0, 1.0))) }
        rejected { HapticClip("long", listOf(HapticEvent(60000, true, 1, HapticParameter(.5), HapticParameter(.5)))) }
    }
    @Test fun bumpClampsAndRoundingMatchesCSharp() {
        assertEquals(0.0, HapticClipFactory.bump(-1.0).events.first().intensity.at(0), 0.0)
        assertEquals(1.0, HapticClipFactory.bump(2.0).events.first().intensity.at(0), 0.0)
        assertEquals(18, HapticEvent(0, false, 0, HapticParameter(.9), HapticParameter(.25)).durationMs)
    }
}
