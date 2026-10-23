package com.vtubephone.core.brain

import com.vtubephone.core.gesture.GestureId

/**
 * The fixed 30-second JPHACKS demo (docs/product.md). Same gesture, haptic and avatar paths as the real brain;
 * only the choice of turns is scripted. A missed answer repeats the step once with a hint, then moves on.
 */
class DemoScript : TurnSource {
    private val retried = mutableSetOf<String>()

    override fun next(ctx: BrainContext): AgentTurn? = when (val t = ctx.trigger) {
        Trigger.Start -> knock
        is Trigger.Gesture -> if (t.event.id == GestureId.Pickup) knock else null
        is Trigger.Answered -> after(t.turn.tag)
        is Trigger.Timeout -> when {
            t.turn.options.isEmpty() -> after(t.turn.tag)
            retried.add(t.turn.tag) -> t.turn.copy(say = "（${t.turn.options.first().label}してね）", haptic = HapticCue("call"))
            else -> after(t.turn.tag)
        }
        Trigger.Initiative, is Trigger.Voice -> null
    }

    private val knock = AgentTurn("ねえねえ！ トントン", Expression.Happy, HapticCue("knock", count = 2), Motion.Peek,
        listOf(DialogOption(GestureId.Knock, "返事", "2回ノック", knockCount = 2)), timeoutSec = 8, tag = "demo1")
    private val choice = AgentTurn("返事した！ 右と左、どっちが好き？", Expression.Star, HapticCue("yes"), Motion.Lean, listOf(
        DialogOption(GestureId.TiltRight, "右", "右に傾ける"), DialogOption(GestureId.TiltLeft, "左", "左に傾ける")), timeoutSec = 8, tag = "demo2")
    private val slide = AgentTurn("わあぁっ、ちょっと待って〜！", Expression.Surprise, null, Motion.None,
        listOf(DialogOption(GestureId.ShakeHard, "いたずら", "思いきり振る")), timeoutSec = 10, tag = "demo3")
    private val remember = AgentTurn("め、目がまわる…今の覚えたからね", Expression.Dizzy, HapticCue("dizzy"), Motion.Spin,
        timeoutSec = 4, stateDelta = StateDelta(grudge = 0.6), tag = "demo4", chain = true)
    private val revenge = AgentTurn("お返しっ！", Expression.Angry, HapticCue("thud"), Motion.Approach,
        listOf(DialogOption(GestureId.Stroke, "ごめんね", "なでる")), timeoutSec = 8, tag = "revenge")
    private val forgive = AgentTurn("えへへ、ゆるしてあげる", Expression.Love, HapticCue("purr"), Motion.Bounce, tag = "demo6")

    private fun after(tag: String): AgentTurn? = when (tag) {
        "demo1" -> choice
        "demo2" -> slide
        "demo3" -> remember
        "demo4" -> revenge
        "revenge" -> forgive
        else -> null
    }
}
