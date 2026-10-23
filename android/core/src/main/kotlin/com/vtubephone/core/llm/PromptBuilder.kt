package com.vtubephone.core.llm

import com.vtubephone.core.brain.*

object PromptBuilder {
    fun systemInstruction(): String = listOf(
        Persona.character,
        "ジェスチャー:\n" + Persona.gestures.entries.joinToString("\n") { "${it.key.wire}: ${it.value}" },
        "触覚:\n" + Persona.haptics.entries.joinToString("\n") { "${it.key}: ${it.value}" },
        "表情:\n" + Persona.expressions.entries.joinToString("\n") { "${it.key.wire}: ${it.value}" },
        "動作:\n" + Persona.motions.entries.joinToString("\n") { "${it.key.wire}: ${it.value}" },
        Persona.rules,
    ).joinToString("\n\n")

    fun userTurn(ctx: BrainContext): String = buildString {
        appendLine("状態: ${ctx.state.describe()}")
        appendLine("出来事:\n${ctx.log.render(ctx.nowMs)}")
        ctx.lastTurn?.let { appendLine("直前の発話: ${it.say}") }
        append(when (val t = ctx.trigger) {
            Trigger.Start -> "会話を開始した。相手に短く呼びかけてほしい。"
            Trigger.Initiative -> if (ctx.state.grudge > 0.5)
                "さっき激しく振られたのを根に持っている。いまが仕返しの時。motion は approach、haptic は thud にして、ごめんねの options（stroke）を付けてほしい。"
            else "しばらくやりとりがない。状態とログに合わせて話しかけてほしい。"
            is Trigger.Answered -> "「${t.turn.say}」に${GestureText.describe(t.event)}（${t.option.meaning}）と答えた。\n答えを受けて会話を続けてほしい。"
            is Trigger.Timeout -> "「${t.turn.say}」の返答待ち時間が過ぎた。\n返事がなかったことを踏まえて次のターンを作ってほしい。"
            is Trigger.Gesture -> "${GestureText.describe(t.event)}。" +
                (t.pending?.let { "質問「${it.say}」への選択肢に一致しなかった。" } ?: "") + "\n動作と文脈に合わせて返答してほしい。"
            is Trigger.Voice -> "ユーザーが声で話しかけた。${t.text?.let { "発言: $it" } ?: "添付音声を聞いてほしい。"}\n内容に返答してほしい。"
        })
    }
}
