package com.vtubephone.core.brain

import com.vtubephone.core.gesture.GestureEvent
import com.vtubephone.core.gesture.GestureId

/**
 * Instant, local reactions. The same gesture means different things depending on how the character feels:
 * a hard shake is a game when she is in a good mood, and an offence (grudge) when she is not.
 * Reflexes are short on purpose; the dialog layer may follow up with a full turn.
 */
object ReflexTable {
    fun react(e: GestureEvent, s: CharacterState): Reflex? = when (e.id) {
        GestureId.ShakeHard -> if (s.mood > 0.55 && s.grudge < 0.3)
            Reflex(Expression.Star, HapticCue("giggle"), Motion.Spin, "きゃーっ！", StateDelta(mood = -0.05, energy = 0.1, grudge = 0.1))
        else Reflex(Expression.Dizzy, HapticCue("dizzy"), Motion.Spin, "め、目がまわる〜", StateDelta(mood = -0.25, grudge = 0.4))

        GestureId.ShakeLight -> if (s.sleeping) Reflex(Expression.Sleepy, HapticCue("sleepy"), say = "ん〜…")
        else Reflex(Expression.Happy, HapticCue("giggle", intensity = 0.5), Motion.Bounce, "ゆらゆら〜", StateDelta(mood = 0.05, energy = 0.05))

        GestureId.Stroke -> if (s.grudge > 0.5)
            Reflex(Expression.Pout, HapticCue("purr", intensity = 0.5), say = "…ゆるしてないもん", delta = StateDelta(mood = 0.08, grudge = -0.15))
        else Reflex(Expression.Happy, HapticCue("purr"), say = if (s.intimacy > 0.6) "えへへ…もっと" else "えへへ",
            delta = StateDelta(mood = 0.1, intimacy = 0.03, grudge = -0.1))

        GestureId.Hold -> Reflex(Expression.Shy, HapticCue("heartbeat"), say = "…て、手あったかいね",
            delta = StateDelta(mood = 0.05, intimacy = 0.05))

        // Knocking back with the same rhythm is the core "she is there" moment: echo it.
        GestureId.Knock, GestureId.BackKnock -> if (s.sleeping) Reflex(Expression.Sleepy, HapticCue("knock", count = 1, intensity = 0.3), say = "…だれ〜？")
        else Reflex(Expression.Surprise, HapticCue("knock", count = e.count.coerceIn(1, 4)), Motion.Peek,
            if (e.target == "back") "うしろから!?" else "はーい？", StateDelta(curiosity = 0.1))

        GestureId.Nod -> Reflex(Expression.Happy, HapticCue("yes"), Motion.Nod, delta = StateDelta(mood = 0.02))
        GestureId.Headshake -> Reflex(Expression.Think, HapticCue("no"), Motion.ShakeHead)

        GestureId.TiltLeft, GestureId.TiltRight, GestureId.TiltForward, GestureId.TiltBack ->
            if (e.strength > 0.8) Reflex(Expression.Surprise, say = "わわっ") else null

        GestureId.FlipDown -> Reflex(Expression.Sleepy, HapticCue("sleepy"), Motion.Hide, "おやすみ…", StateDelta(energy = -0.1))

        GestureId.Pickup -> if (s.sleeping) Reflex(Expression.Sleepy, HapticCue("sigh"), Motion.Peek, "ふぁ…おはよ")
        else Reflex(Expression.Surprise, HapticCue("poke"), Motion.Wave, "わっ、おかえり！", StateDelta(mood = 0.1, intimacy = 0.01))

        GestureId.Still -> Reflex(Expression.Think, say = "…")
    }
}
