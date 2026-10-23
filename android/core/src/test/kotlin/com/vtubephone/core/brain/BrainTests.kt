package com.vtubephone.core.brain

import com.vtubephone.core.gesture.GestureEvent
import com.vtubephone.core.gesture.GestureId
import com.vtubephone.core.haptics.HapticClipId
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Runs a BrainEngine against a synchronous source, resolving turn requests immediately like the app does. */
private class Harness(val source: TurnSource, val engine: BrainEngine = BrainEngine()) {
    var now = 0L
    val performed = ArrayList<AgentTurn>()
    val reflexes = ArrayList<Reflex>()
    private fun run(actions: List<BrainAction>) {
        for (a in actions) when (a) {
            is BrainAction.RequestTurn -> run(engine.deliver(source.next(a.context), now))
            is BrainAction.Perform -> performed += a.turn
            is BrainAction.React -> reflexes += a.reflex
            else -> Unit
        }
    }
    fun start() = run(engine.start(now))
    fun gesture(id: GestureId, count: Int = 0, strength: Double = 0.6, intervals: List<Long> = emptyList(), target: String? = null) =
        run(engine.onGesture(GestureEvent(id, now * 1_000_000, strength, count, intervals, target = target), now))
    fun advance(ms: Long, stepMs: Long = 250) { val end = now + ms; while (now < end) { now += stepMs; run(engine.tick(now)) } }
}

class BrainTests {
    @Test fun thirtySecondDemoRunsEndToEndWithGesturesOnly() {
        val h = Harness(DemoScript())
        h.start()
        assertEquals("demo1", h.performed.last().tag)
        assertEquals(HapticCue("knock", count = 2), h.performed.last().haptic)
        h.advance(1500); h.gesture(GestureId.Knock, count = 2, intervals = listOf(180), target = "screen")
        assertEquals("demo2", h.performed.last().tag)
        h.advance(1500); h.gesture(GestureId.TiltRight)
        assertEquals("demo3", h.performed.last().tag)
        h.advance(2000); h.gesture(GestureId.ShakeHard, strength = 0.9)
        assertEquals("demo4", h.performed.last().tag)
        assertTrue("answers react silently: no second vibration, no second line", h.reflexes.all { it.haptic == null && it.say == null })
        assertEquals(0.6, h.engine.state.grudge, 0.05)
        h.advance(4500)
        val revenge = h.performed.last()
        assertEquals("revenge", revenge.tag); assertEquals(Motion.Approach, revenge.motion); assertEquals("thud", revenge.haptic?.id)
        h.gesture(GestureId.Stroke)
        assertEquals("demo6", h.performed.last().tag)
        assertEquals(listOf("demo1", "demo2", "demo3", "demo4", "revenge", "demo6"), h.performed.map { it.tag })
        assertTrue(h.now < 30_000)
        assertTrue(h.engine.state.grudge < 0.1)
    }

    @Test fun demoRepeatsAMissedStepOnceWithAHintThenMovesOn() {
        val h = Harness(DemoScript())
        h.start(); h.advance(8_250)
        assertEquals("demo1", h.performed.last().tag); assertTrue(h.performed.last().say.contains("2回ノック"))
        h.advance(8_250)
        assertEquals("demo2", h.performed.last().tag)
    }

    @Test fun sameGestureMeansDifferentThingsByMood() {
        val happy = CharacterState().apply { apply(StateDelta(mood = 0.5)) }
        val grumpy = CharacterState().apply { apply(StateDelta(mood = -0.5)) }
        val shake = GestureEvent(GestureId.ShakeHard, 0, 0.9)
        assertEquals(Expression.Star, ReflexTable.react(shake, happy)!!.expression)
        val r = ReflexTable.react(shake, grumpy)!!
        assertEquals(Expression.Dizzy, r.expression); assertEquals("dizzy", r.haptic?.id); assertTrue(r.delta.grudge > 0.3)
        assertEquals(Expression.Pout, ReflexTable.react(GestureEvent(GestureId.Stroke, 0), CharacterState().apply { apply(StateDelta(grudge = 0.8)) })!!.expression)
    }

    @Test fun knocksAreEchoedWithTheSameCount() {
        val r = ReflexTable.react(GestureEvent(GestureId.BackKnock, 0, count = 3, target = "back"), CharacterState())!!
        assertEquals(HapticCue("knock", count = 3), r.haptic)
        assertEquals(4, ReflexTable.react(GestureEvent(GestureId.Knock, 0, count = 7), CharacterState())!!.haptic!!.count)
    }

    @Test fun wrongKnockCountGetsAReminderAndKeepsWaiting() {
        val h = Harness(TurnSource { ctx ->
            when (ctx.trigger) {
                Trigger.Start -> AgentTurn("トントン", haptic = HapticCue("knock", count = 2),
                    options = listOf(DialogOption(GestureId.Knock, "返事", "2回ノック", knockCount = 2)), tag = "knock")
                else -> RuleBrain(Random(1)).next(ctx)
            }
        })
        h.start(); h.gesture(GestureId.Knock, count = 3, intervals = listOf(150, 150))
        assertEquals("ちがうよ〜、2回だよ", h.performed.last().say)
        assertNotNull(h.engine.pending)
        h.gesture(GestureId.BackKnock, count = 2, intervals = listOf(200), target = "back")
        assertEquals("knock-end", h.performed.last().tag)
    }

    @Test fun knockOptionsMatchCountRhythmAndEitherSurface() {
        val o = DialogOption(GestureId.Knock, "返事", "2回", knockCount = 2)
        assertTrue(o.matches(GestureEvent(GestureId.BackKnock, 0, count = 2)))
        assertFalse(o.matches(GestureEvent(GestureId.Knock, 0, count = 3)))
        val rhythm = DialogOption(GestureId.Knock, "まね", "まねする", rhythmMs = listOf(150, 400))
        assertTrue(rhythm.matches(GestureEvent(GestureId.Knock, 0, count = 3, intervalsMs = listOf(170, 420))))
        assertFalse(rhythm.matches(GestureEvent(GestureId.Knock, 0, count = 3, intervalsMs = listOf(400, 150))))
        assertFalse(DialogOption(GestureId.Nod, "はい", "うなずく").matches(GestureEvent(GestureId.Headshake, 0)))
    }

    @Test fun unansweredQuestionGetsANudge() {
        val h = Harness(RuleBrain(Random(2)))
        h.start(); assertEquals("greet", h.performed.last().tag)
        h.advance(8_250)
        assertEquals("nudge", h.performed.last().tag)
    }

    @Test fun initiativeIsRateLimitedAndRevengeComesSooner() {
        val s = CharacterState()
        val i = InitiativeScheduler(minIntervalMs = 45_000, idleMs = 15_000)
        assertFalse(i.due(5_000, 0, s, null))
        assertTrue(i.due(15_000, 0, s, null))
        assertFalse("rate limited", i.due(30_000, 0, s, null))
        assertTrue(i.due(61_000, 0, s, null))
        s.apply(StateDelta(grudge = 0.8))
        assertFalse("but not right after another initiative", i.due(70_000, 66_000, s, 62_000))
        assertTrue("revenge ignores the interval", i.due(72_000, 66_000, s, 62_000))
        s.sleeping = true
        assertFalse(i.due(200_000, 0, s, null))
    }

    @Test fun grudgeLeadsToRevengeThenForgiveness() {
        val h = Harness(RuleBrain(Random(3)))
        h.engine.initiative.minIntervalMs = 1_000_000
        h.start(); h.gesture(GestureId.Nod)
        h.engine.state.apply(StateDelta(mood = -1.0))
        h.gesture(GestureId.ShakeHard, strength = 1.0)
        assertTrue(h.engine.state.grudge > 0.35)
        h.gesture(GestureId.ShakeHard, strength = 1.0)
        h.advance(10_000)
        assertEquals("revenge", h.performed.last().tag)
        h.gesture(GestureId.Stroke)
        assertEquals("revenge-end", h.performed.last().tag)
        assertTrue(h.engine.state.grudge < 0.2)
    }

    @Test fun ruleBrainDoesNotRepeatTheSameSceneBackToBack() {
        val brain = RuleBrain(Random(4))
        val ctx = { BrainContext(0, CharacterState(), EventLog(), Trigger.Initiative, null) }
        val tags = (1..30).map { brain.next(ctx())!!.tag }
        assertTrue(tags.zipWithNext().none { (a, b) -> a == b })
        assertTrue(tags.toSet().size >= 4)
    }

    @Test fun flipDownSleepsAndPickupWakesWithAGreeting() {
        val h = Harness(RuleBrain(Random(5)))
        h.start(); h.gesture(GestureId.FlipDown)
        assertTrue(h.engine.state.sleeping); assertNull(h.engine.pending)
        assertEquals(Expression.Sleepy, h.reflexes.last().expression)
        h.advance(120_000)
        assertEquals("no initiative while asleep", "greet", h.performed.last().tag)
        h.gesture(GestureId.Pickup)
        assertFalse(h.engine.state.sleeping)
        assertEquals("ふぁ…おはよ", h.reflexes.last().say)
    }

    @Test fun everyHapticCueNamesARealClip() {
        val clips = HapticClipId.entries.map { it.key }.toSet()
        val turns = ArrayList<AgentTurn>()
        val demo = Harness(DemoScript()).apply {
            start(); gesture(GestureId.Knock, count = 2); gesture(GestureId.TiltLeft); gesture(GestureId.ShakeHard); advance(5000); gesture(GestureId.Stroke)
        }
        turns += demo.performed
        val brain = RuleBrain(Random(6))
        repeat(40) { turns += brain.next(BrainContext(0, CharacterState(), EventLog(), Trigger.Initiative, null))!! }
        val reflexCues = GestureId.entries.mapNotNull { ReflexTable.react(GestureEvent(it, 0, 1.0, count = 2), CharacterState())?.haptic }
        (turns.mapNotNull { it.haptic } + reflexCues).forEach { assertTrue("unknown haptic ${it.id}", it.id in clips) }
    }

    @Test fun stateDriftsBackToBaseline() {
        val s = CharacterState().apply { apply(StateDelta(mood = -0.8, grudge = 0.9)) }
        s.decay(600.0)
        assertEquals(0.3, s.mood, 0.05); assertTrue(s.grudge < 0.15)
    }
}
