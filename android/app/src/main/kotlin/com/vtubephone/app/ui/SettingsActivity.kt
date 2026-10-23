package com.vtubephone.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtubephone.app.BuildConfig
import com.vtubephone.app.VtubePhoneApplication

/** Settings sheet: demo mode, haptics, captions, how often she speaks up, voice. Plus the developer tools. */
class SettingsActivity : ComponentActivity() {
    private val app get() = application as VtubePhoneApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    @Composable private fun Screen() {
        val s = app.settings
        var demo by remember { mutableStateOf(s.demoMode) }
        var hapticsOn by remember { mutableStateOf(s.hapticsOn) }
        var strength by remember { mutableFloatStateOf(s.hapticStrength.toFloat()) }
        var caption by remember { mutableFloatStateOf(s.captionScale.toFloat()) }
        var level by remember { mutableIntStateOf(s.initiativeLevel) }
        var voice by remember { mutableStateOf(s.voice) }
        var speakerText by remember { mutableStateOf(s.speaker.toString()) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("設定", style = MaterialTheme.typography.headlineSmall)
            Toggle("デモモード（30秒の台本）", "ネットなしで同じ流れを再現します。切り替えると最初から始まります", demo) {
                demo = it; s.demoMode = it; app.brain.restart()
            }
            Toggle("振動", "OFF にすると一切振動しません", hapticsOn) { hapticsOn = it; s.hapticsOn = it; app.haptics.setEnabled(it) }
            Labeled("振動の強さ ${(strength * 100).toInt()}%") {
                Slider(strength, { strength = it }, valueRange = 0.2f..1f, enabled = hapticsOn,
                    onValueChangeFinished = { s.hapticStrength = strength.toDouble(); app.haptics.setStrength(strength.toDouble()) })
            }
            Labeled("字幕の大きさ ×${"%.1f".format(caption)}") {
                Slider(caption, { caption = it }, valueRange = 0.8f..2f,
                    onValueChangeFinished = { s.captionScale = caption.toDouble(); app.brain.sendSettings() })
            }
            Labeled("話しかけてくる頻度") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("少なめ", "ふつう", "多め").forEachIndexed { i, label ->
                        FilterChip(level == i, { level = i; s.initiativeLevel = i; app.brain.sendSettings() }, { Text(label) })
                    }
                }
            }
            val ttsNote = if (BuildConfig.VOICEVOX_API_KEY.isBlank()) "（キー未設定: 低速版。数秒遅れます）" else ""
            Toggle("読み上げ（VOICEVOX）", "字幕に加えて声で話します$ttsNote", voice) { voice = it; s.voice = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(speakerText, { v -> speakerText = v.filter(Char::isDigit).take(3); speakerText.toIntOrNull()?.let { s.speaker = it } },
                    Modifier.width(140.dp), label = { Text("話者の番号") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedButton({ app.voice.player.speak("こんにちは、わたしの声はこんな感じ", s.speaker) }) { Text("試しに話す") }
            }
            Text("番号の一覧: static.tts.quest/voicevox_speakers_utf8.json（上から 0, 1, 2…）。例: 2 四国めたん、3 ずんだもん、8 春日部つむぎ、14 冥鳴ひまり、20 もち子さん、42 ちび式じい",
                style = MaterialTheme.typography.bodySmall)
            Text(if (BuildConfig.GEMINI_API_KEY.isBlank()) "AI: オフライン（GEMINI_API_KEY 未設定。ルールで会話します）" else "AI: Gemini",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("開発者向け", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ startActivity(Intent(this@SettingsActivity, GestureLabActivity::class.java)) }) { Text("ジェスチャー練習") }
                OutlinedButton({ startActivity(Intent(this@SettingsActivity, HapticsBenchActivity::class.java)) }) { Text("触覚ベンチ") }
            }
        }
    }

    @Composable private fun Toggle(title: String, note: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
            Switch(value, onChange)
        }
    }

    @Composable private fun Labeled(title: String, content: @Composable () -> Unit) {
        Column { Text(title, style = MaterialTheme.typography.titleMedium); content() }
    }
}
