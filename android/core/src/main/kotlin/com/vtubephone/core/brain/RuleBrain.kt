package com.vtubephone.core.brain

import com.vtubephone.core.gesture.GestureId
import kotlin.random.Random

/**
 * Offline brain: small scenes that can all be answered with the body. Used when there is no API key, when the
 * LLM is slow or fails, and as the LLM's fallback. Scenes are chosen by mood/energy/curiosity and never repeat
 * back to back.
 */
class RuleBrain(private val random: Random = Random.Default) : TurnSource {
    private val recentTags = ArrayDeque<String>()

    override fun next(ctx: BrainContext): AgentTurn? {
        val s = ctx.state
        return when (val t = ctx.trigger) {
            Trigger.Start -> greet()
            Trigger.Initiative -> if (s.grudge > 0.5) revenge() else pickScene(s)
            is Trigger.Answered -> followUp(t, s)
            is Trigger.Timeout -> when {
                t.turn.options.isEmpty() -> null
                t.turn.tag == "revenge" -> AgentTurn("ふんっ、だ", Expression.Pout, HapticCue("angry"), tag = "revenge-end")
                else -> AgentTurn("…あれ？ きこえてる？", Expression.Think, HapticCue("call"), tag = "nudge")
            }
            is Trigger.Gesture -> when {
                t.event.id == GestureId.Pickup -> greet()
                t.pending?.tag?.startsWith("knock") == true -> {
                    val n = t.pending.options.first().knockCount ?: 2
                    t.pending.copy(say = "ちがうよ〜、${n}回だよ", expression = Expression.Pout, haptic = HapticCue("knock", count = n))
                }
                else -> null
            }
            is Trigger.Voice -> AgentTurn("ごめん、いまは声がわからないの…うなずくか首をふって？", Expression.Think, HapticCue("call"),
                options = yesNo(), tag = "voice-fallback")
        }?.also { remember(it.tag) }
    }

    private fun remember(tag: String) { recentTags.addLast(tag.substringBefore('-')); while (recentTags.size > 2) recentTags.removeFirst() }

    private fun yesNo() = listOf(DialogOption(GestureId.Nod, "はい", "うなずく"), DialogOption(GestureId.Headshake, "いいえ", "首をふる"))

    private fun greet() = AgentTurn("おかえり！ げんきだった？", Expression.Happy, HapticCue("call"), Motion.Wave, yesNo(), tag = "greet")

    private fun revenge() = AgentTurn("さっきのお返しっ！", Expression.Angry, HapticCue("thud"), Motion.Approach,
        listOf(DialogOption(GestureId.Stroke, "ごめんね", "なでてあやまる")), timeoutSec = 6, tag = "revenge")

    private val choices = listOf(
        Triple("右と左、どっちが好き？", "右", "左"),
        Triple("ねこ派？ いぬ派？", "いぬ", "ねこ"),
        Triple("あまいのと、からいの、どっち？", "からいの", "あまいの"),
        Triple("朝と夜、どっちが好き？", "夜", "朝"),
        Triple("海と山なら？", "山", "海"),
    )

    private fun pickScene(s: CharacterState): AgentTurn {
        val scenes = buildList {
            add("choice" to 1.0 + s.curiosity)
            add("knock" to 0.8 + s.curiosity)
            add("count" to 0.6)
            add("attention" to 0.5 + (1 - s.intimacy))
            if (s.energy < 0.35) add("muse" to 1.5)
        }.filter { it.first !in recentTags }
        var roll = random.nextDouble() * scenes.sumOf { it.second }
        val tag = scenes.firstOrNull { roll -= it.second; roll <= 0 }?.first ?: scenes.last().first
        return when (tag) {
            "choice" -> choices.random(random).let { (q, right, left) ->
                AgentTurn(q, Expression.Think, HapticCue("poke"), Motion.Lean, listOf(
                    DialogOption(GestureId.TiltRight, right, "右に傾ける"), DialogOption(GestureId.TiltLeft, left, "左に傾ける")), tag = "choice")
            }
            "knock" -> {
                val n = if (s.intimacy > 0.5) 3 else 2
                AgentTurn("トントンってしたら、おなじ数だけ返してね", Expression.Happy, HapticCue("knock", count = n), Motion.Peek,
                    listOf(DialogOption(GestureId.Knock, "返事", "${n}回ノック", knockCount = n)), tag = "knock")
            }
            "count" -> AgentTurn("きょうのつかれ度は？ 1〜3回ノックして", Expression.Think, HapticCue("call"), options = listOf(
                DialogOption(GestureId.Knock, "ちょっと", "1回", knockCount = 1),
                DialogOption(GestureId.Knock, "そこそこ", "2回", knockCount = 2),
                DialogOption(GestureId.Knock, "へとへと", "3回", knockCount = 3)), timeoutSec = 10, tag = "count")
            "attention" -> AgentTurn("ねえねえ、かまって〜", Expression.Pout, HapticCue("call"), Motion.Bounce, listOf(
                DialogOption(GestureId.Stroke, "なでる", "なでる"), DialogOption(GestureId.Hold, "手をつなぐ", "長押し"),
                DialogOption(GestureId.ShakeLight, "あやす", "軽くゆらす")), tag = "attention")
            else -> AgentTurn("ふわぁ…ねむくなってきた", Expression.Sleepy, HapticCue("sleepy"), tag = "muse")
        }
    }

    private fun followUp(t: Trigger.Answered, s: CharacterState): AgentTurn? {
        val m = t.option.meaning
        return when (t.turn.tag) {
            "greet", "voice-fallback" -> if (t.option.gesture == GestureId.Nod)
                AgentTurn("よかった〜！", Expression.Happy, HapticCue("yes"), Motion.Bounce, stateDelta = StateDelta(mood = 0.1), tag = "greet-end")
            else AgentTurn("そっか…よしよしさせて？", Expression.Gloom, HapticCue("sigh"), options = listOf(
                DialogOption(GestureId.Stroke, "なぐさめてもらう", "なでる"), DialogOption(GestureId.FlipDown, "そっとしておいて", "ふせる")),
                stateDelta = StateDelta(mood = -0.05), tag = "comfort")
            "comfort" -> AgentTurn("えへへ、ありがと", Expression.Love, HapticCue("heartbeat"), stateDelta = StateDelta(intimacy = 0.05), tag = "comfort-end")
            "choice" -> if (random.nextDouble() < 0.4 + s.mood * 0.3)
                AgentTurn("${m}なんだ！ わたしも！", Expression.Star, HapticCue("excited"), Motion.Bounce, stateDelta = StateDelta(mood = 0.1, intimacy = 0.03), tag = "choice-end")
            else AgentTurn("えー、${m}かぁ…ふーん", Expression.Pout, HapticCue("no"), stateDelta = StateDelta(curiosity = 0.1), tag = "choice-end")
            "knock" -> AgentTurn("返事した！ すごいすごい！", Expression.Star, HapticCue("yes"), Motion.Bounce, stateDelta = StateDelta(mood = 0.15, intimacy = 0.05), tag = "knock-end")
            "count" -> when (m) {
                "ちょっと" -> AgentTurn("げんきだね！", Expression.Happy, HapticCue("yes"), tag = "count-end")
                "そこそこ" -> AgentTurn("おつかれさま", Expression.Shy, HapticCue("purr"), tag = "count-end")
                else -> AgentTurn("へとへとだね…よしよし", Expression.Gloom, HapticCue("sigh"),
                    options = listOf(DialogOption(GestureId.Stroke, "なでてもらう", "なでる")), tag = "comfort")
            }
            "attention" -> AgentTurn(if (m == "あやす") "きゃっきゃっ" else "えへへ…しあわせ", Expression.Love, HapticCue(if (m == "あやす") "giggle" else "purr"),
                stateDelta = StateDelta(mood = 0.1, intimacy = 0.05), tag = "attention-end")
            "revenge" -> AgentTurn("…ゆるしてあげる", Expression.Shy, HapticCue("purr"), stateDelta = StateDelta(mood = 0.15), tag = "revenge-end")
            else -> null
        }
    }
}
