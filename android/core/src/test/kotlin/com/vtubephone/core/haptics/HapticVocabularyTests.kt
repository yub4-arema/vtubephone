package com.vtubephone.core.haptics

import org.junit.Assert.*
import org.junit.Test

class HapticVocabularyTests {
    private fun galaxy() = HapticsCapabilities(36, true, true, manufacturer = "Samsung", model = "SM-S942Q",
        primitives = listOf(20, 300, 130, 150, 500, 100, 20, 20).mapIndexed { i, ms -> PrimitiveCapability(i + 1, true, ms) })
    private fun clip(id: String) = HapticClipParser.parse(javaClass.getResource("/haptics/$id.json")!!.readText())
    private fun primitive(name: HapticPrimitive, t: Int = 0, scale: Double = 1.0) = HapticEvent.primitive(t, name, scale)
    private fun samples(command: HapticCommand): List<Int> = command.timings.indices.flatMap { i ->
        List(command.timings[i].toInt()) { command.amplitudes[i] }
    }

    @Test fun parserAcceptsAllPrimitiveNamesScalesAndStrictTiming() {
        HapticPrimitive.entries.forEach { p ->
            val parsed = HapticClipParser.parse("""{"id":"test","strictTiming":true,"events":[{"t":15,"type":"primitive","name":"${p.key}","scale":0.8}]}""")
            assertTrue(parsed.strictTiming)
            assertEquals(p, parsed.events.single().primitive)
            assertEquals(15, parsed.events.single().timeMs)
            assertEquals(.8, parsed.events.single().intensity.at(0), 0.0)
        }
        assertFalse(clip("poke").strictTiming)
    }

    @Test fun parserRejectsUnknownMissingAndInvalidPrimitiveData() {
        val valid = """{"id":"test","events":[{"t":0,"type":"primitive","name":"spin","scale":0.8}]}"""
        listOf(valid.replace("spin", "SPIN"), valid.replace("spin", "buzz"),
            valid.replace(",\"scale\":0.8", ""), valid.replace("\"name\":\"spin\",", ""),
            valid.replace("0.8", "1.01"), valid.replace("0.8", "-0.1"), valid.replace("0.8", "\"0.8\""),
            valid.replace("\"events\"", "\"strictTiming\":\"true\",\"events\""),
            valid.replace("\"events\"", "\"strictTiming\":1,\"events\""))
            .forEach { json -> assertThrows(Exception::class.java) { HapticClipParser.parse(json) } }
    }

    @Test fun oldTransientAndContinuousFormatStillParsesAndRenders() {
        val old = HapticClipParser.parse("""{"id":"legacy","events":[{"t":0,"type":"transient","intensity":0.9,"sharpness":0.8},{"t":40,"type":"continuous","duration":100,"intensity":[[0,0],[50,0.8],[100,0]],"sharpness":0.2}]}""")
        assertFalse(old.strictTiming)
        assertTrue(old.events.all { it.primitive == null })
        assertEquals(HapticTier.C, HapticRenderer.render(old, HapticTier.B, galaxy()).tier)
        assertEquals(140, old.durationMs)
    }

    @Test fun overlapsShiftOnlyAsFarAsNecessaryAndRecordEachShift() {
        val events = listOf(primitive(HapticPrimitive.Thud), primitive(HapticPrimitive.Tick, 220),
            primitive(HapticPrimitive.LowTick, 230), primitive(HapticPrimitive.Click, 600))
        val rendered = HapticRenderer.render(HapticClip("overlap", events), HapticTier.B, galaxy())
        assertEquals(HapticTier.B, rendered.tier)
        assertEquals(listOf(0, 0, 0, 260), rendered.primitives.map { it.delayMs })
        assertEquals(620, rendered.durationMs)
        assertTrue(rendered.reason.contains("event[1] shifted +80ms (220→300ms)"))
        assertTrue(rendered.reason.contains("event[2] shifted +90ms (230→320ms)"))
        assertFalse(rendered.reason.contains("event[3] shifted"))
        val strict = HapticClip("strict", events, strictTiming = true)
        assertEquals(HapticTier.C, HapticRenderer.render(strict, HapticTier.B, galaxy()).tier)
        assertEquals(HapticTier.D, HapticRenderer.render(strict, HapticTier.B, galaxy().copy(hasAmplitudeControl = false)).tier)
    }

    @Test fun legacyTransientsAlsoRespectStrictTimingOptIn() {
        val events = List(2) { HapticEvent(it * 10, false, 0, HapticParameter(.6), HapticParameter(.8)) }
        assertEquals(listOf(0, 0), HapticRenderer.render(HapticClip("old", events), HapticTier.B, galaxy()).primitives.map { it.delayMs })
        assertEquals(HapticTier.C, HapticRenderer.render(HapticClip("old", events, true), HapticTier.B, galaxy()).tier)
    }

    @Test fun unknownDurationUnsupportedPrimitiveAndExcessiveShiftFallBack() {
        val spin = HapticClip("spin", listOf(primitive(HapticPrimitive.Spin)))
        for (replacement in listOf(PrimitiveCapability(3, false, 130), PrimitiveCapability(3, true, 0))) {
            val caps = galaxy().copy(primitives = galaxy().primitives.map { if (it.id == 3) replacement else it })
            assertEquals(HapticTier.C, HapticRenderer.render(spin, HapticTier.B, caps).tier)
        }
        val many = HapticClip("long", List(121) { primitive(HapticPrimitive.SlowRise) })
        assertEquals(HapticTier.C, HapticRenderer.render(many, HapticTier.B, galaxy()).tier)
        assertEquals(HapticTier.C, HapticRenderer.render(spin, HapticTier.B, galaxy().copy(sdkInt = 30)).tier)
    }

    @Test fun bestTierDoesNotAdvertiseCompositionWithoutUsableDeviceDurations() {
        assertEquals(HapticTier.C, galaxy().copy(sdkInt = 30).bestTier)
        val unknown = galaxy().copy(primitives = galaxy().primitives.map { it.copy(duration = 0) })
        assertEquals(HapticTier.C, unknown.bestTier)
        assertFalse(unknown.supports(HapticTier.B))
        assertEquals(HapticTier.D, unknown.copy(hasAmplitudeControl = false).bestTier)
    }

    @Test fun allEighteenClipsAndKnockOneToFourUseTierBWithMeasuredGalaxyDurations() {
        val caps = galaxy()
        assertEquals(HapticTier.B, caps.bestTier)
        assertEquals(18, HapticClipId.entries.size)
        val all = HapticClipId.entries.map { clip(it.key) } + (1..4).map { HapticClipFactory.knock(it) }
        // Soft feelings are deliberately smooth waveforms (emotion chooses the rendering), everything else is crisp.
        val soft = setOf("purr", "sleepy", "sigh")
        all.filter { it.id in soft }.forEach { assertEquals(it.id, HapticTier.C, HapticRenderer.render(it, caps.bestTier, caps).tier) }
        val clips = all.filter { it.id !in soft }
        clips.forEach { clip ->
            val rendered = HapticRenderer.render(clip, caps.bestTier, caps)
            assertEquals(clip.id, HapticTier.B, rendered.tier)
            assertEquals(clip.events.size, rendered.primitives.size)
            var end = 0
            rendered.primitives.forEachIndexed { i, command ->
                val event = clip.events[i]
                assertEquals(event.primitive!!.id, command.id)
                assertEquals(caps.primitive(command.id)!!.duration, command.durationMs)
                assertEquals(event.timeMs, end + command.delayMs)
                end += command.delayMs + command.durationMs
            }
            assertEquals(end, rendered.durationMs)
        }
        assertTrue(clip("knock").strictTiming)
        assertTrue((1..4).all { HapticClipFactory.knock(it).strictTiming })
        assertEquals((1..8).toSet(), clips.flatMap { it.events.map { e -> e.primitive!!.id } }.toSet())
    }

    @Test fun bumpSwitchesFromClickToThudAndScalesWithImpact() {
        listOf(-1.0, 0.0, .2, .59, .6, .8, 1.0, 2.0).forEach { impact ->
            val c = HapticRenderer.render(HapticClipFactory.bump(impact), HapticTier.B, galaxy(), .5)
            assertEquals(HapticTier.B, c.tier)
            assertEquals(if (impact < .6) 1 else 2, c.primitives.single().id)
            assertEquals((impact.coerceIn(0.0, 1.0) * .5).toFloat(), c.primitives.single().scale, 0f)
        }
    }

    @Test fun primitiveApproximationsMatchTheDocumentedTableForEveryFiveMsSample() {
        // Independent specification fixtures: (duration, piecewise-linear control points).
        val table = mapOf(
            "click" to (20 to listOf(0 to 1.0, 5 to .75, 20 to 0.0)),
            "thud" to (300 to listOf(0 to 1.0, 20 to .8, 80 to .35, 300 to 0.0)),
            "spin" to (130 to listOf(0 to .15, 30 to .85, 60 to .2, 90 to 1.0, 130 to 0.0)),
            "quick_rise" to (150 to listOf(0 to 0.0, 150 to 1.0)),
            "slow_rise" to (500 to listOf(0 to 0.0, 500 to 1.0)),
            "quick_fall" to (100 to listOf(0 to 1.0, 100 to 0.0)),
            "tick" to (20 to listOf(0 to 1.0, 5 to .35, 20 to 0.0)),
            "low_tick" to (20 to listOf(0 to .55, 10 to .4, 20 to 0.0)))
        HapticPrimitive.entries.forEach { p ->
            val (duration, points) = table.getValue(p.key)
            val clip = HapticClip("approx", listOf(primitive(p, 7, .8)))
            val command = HapticRenderer.render(clip, HapticTier.C, galaxy(), .5)
            assertEquals(HapticTier.C, command.tier)
            val expected = List(7) { 0 } + List(duration) { ms ->
                val sampleMs = ms / 5 * 5
                val (a, b) = points.zipWithNext().first { (a, b) -> sampleMs >= a.first && sampleMs < b.first }
                val amplitude = a.second + (b.second - a.second) * (sampleMs - a.first).toDouble() / (b.first - a.first)
                if (amplitude <= 0.0) 0 else maxOf(1, Math.rint(amplitude * .8 * .5 * 255).toInt())
            }
            assertEquals(p.key, expected, samples(command))
            assertEquals((7 + duration).toLong(), command.timings.sum())
            val pwm = HapticRenderer.render(clip, HapticTier.D, galaxy(), .5)
            assertEquals(HapticTier.D, pwm.tier)
            assertEquals(command.timings.sum(), pwm.onOffTimings.sum())
            val onMs = pwm.onOffTimings.filterIndexed { i, _ -> i % 2 == 1 }.sum()
            val targetOnMs = expected.sum() / 255.0
            assertTrue(p.key, onMs <= targetOnMs && targetOnMs - onMs < 1.000001)
            val muted = HapticRenderer.render(clip, HapticTier.C, galaxy(), 0.0)
            assertTrue(muted.amplitudes.all { it == 0 })
            assertArrayEquals(longArrayOf((7 + duration).toLong()),
                HapticRenderer.render(clip, HapticTier.D, galaxy(), 0.0).onOffTimings)
        }
    }

    @Test fun sequenceUsesDeviceEndToStartPausesAndExportRoundTrips() {
        val sequence = HapticSequence(listOf(HapticSequenceStep(HapticPrimitive.Thud, .8, 25),
            HapticSequenceStep(HapticPrimitive.Spin, .5, 80), HapticSequenceStep(HapticPrimitive.Tick, .2, 0)))
        val clip = sequence.toClip(galaxy())
        assertEquals(listOf(25, 405, 535), clip.events.map { it.timeMs })
        val parsed = HapticClipParser.parse(sequence.toJson(galaxy()))
        assertEquals(clip.events.map { it.timeMs }, parsed.events.map { it.timeMs })
        assertEquals(clip.events.map { it.primitive }, parsed.events.map { it.primitive })
        assertEquals(listOf(.8, .5, .2), parsed.events.map { it.intensity.at(0) })
        val rendered = HapticRenderer.render(parsed, HapticTier.B, galaxy(), .5)
        assertEquals(HapticTier.B, rendered.tier)
        assertEquals(listOf(25, 80, 0), rendered.primitives.map { it.delayMs })
        assertEquals(listOf(.4f, .25f, .1f), rendered.primitives.map { it.scale })
    }

    @Test fun sequenceFallsBackToApproximationLengthWhenDurationIsUnknown() {
        val s = HapticSequence(listOf(HapticSequenceStep(HapticPrimitive.Spin, .5, 0), HapticSequenceStep(HapticPrimitive.Tick, .2, 10)))
        val unknown = galaxy().copy(primitives = emptyList())
        assertEquals(listOf(0, 140), s.toClip(unknown).events.map { it.timeMs })
        assertEquals(HapticTier.C, HapticRenderer.render(s.toClip(unknown), HapticTier.B, unknown).tier)
        assertEquals(HapticTier.D, HapticRenderer.render(s.toClip(unknown), HapticTier.B, unknown.copy(hasAmplitudeControl = false)).tier)
    }

    @Test fun sequenceRejectsInvalidDataAndOversizedDuration() {
        assertThrows(IllegalArgumentException::class.java) { HapticSequence(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { HapticSequenceStep(HapticPrimitive.Tick, Double.NaN, 0) }
        assertThrows(IllegalArgumentException::class.java) { HapticSequenceStep(HapticPrimitive.Tick, 1.1, 0) }
        assertThrows(IllegalArgumentException::class.java) { HapticSequenceStep(HapticPrimitive.Tick, .5, -1) }
        val long = List(32) { HapticSequenceStep(HapticPrimitive.SlowRise, .5, 2000) }
        assertThrows(IllegalArgumentException::class.java) { HapticSequence(long).toClip(galaxy()) }
        assertThrows(IllegalArgumentException::class.java) { HapticSequence(long + long.first()) }
    }
}
