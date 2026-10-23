package com.vtubephone.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vtubephone.app.VtubePhoneApplication
import com.vtubephone.core.haptics.*
import kotlin.math.roundToInt
import kotlin.random.Random

class HapticsBenchActivity : ComponentActivity() {
    private val service get() = (application as VtubePhoneApplication).haptics
    private val blindTest = HapticsBlindTest()
    private var revision by mutableIntStateOf(0)
    private val logs = mutableStateListOf<String>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        service.logged = { logs.add(it); while (logs.size > 30) logs.removeAt(0); revision++ }
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Bench() } } }
    }
    private fun cancelTrial() { blindTest.cancel(); revision++ }
    @Composable private fun Bench() {
        // Read invalidation for non-Compose service / blind-test state.
        @Suppress("UNUSED_VARIABLE") val currentRevision = revision
        var count by remember { mutableFloatStateOf(2f) }
        var impact by remember { mutableFloatStateOf(.5f) }
        var strength by remember { mutableFloatStateOf(service.strength.toFloat()) }
        var enabled by remember { mutableStateOf(service.enabled) }
        var blind by remember { mutableStateOf(false) }
        LaunchedEffect(impact) { service.prepareBump(impact.toDouble()); revision++ }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("vtubephone / Haptics Bench", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { finish() }) { Text("← 戻る") }
            val overridden = service.tier != service.capabilities.bestTier
            Card(colors = CardDefaults.cardColors(containerColor = if (overridden) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("現在 Tier ${service.tier}", style = MaterialTheme.typography.headlineMedium)
                    Text("この端末の Best Tier ${service.capabilities.bestTier}", style = MaterialTheme.typography.titleLarge)
                    if (overridden) Text("⚠ Best 以外に上書き中。触感が変わります。", style = MaterialTheme.typography.titleMedium)
                    Text("クリップごとの再生 Tier は各ボタンに表示")
                    TextButton(enabled = !blind && overridden, onClick = { cancelTrial(); service.setTier(service.capabilities.bestTier); revision++ }) { Text("Best に戻す") }
                }
            }
            Text(service.capabilityReport, style = MaterialTheme.typography.bodySmall)
            Text("呼出→vibrate return: ${"%.0f".format(service.lastCallMicroseconds)} μs / native: ${"%.0f".format(service.lastNativeMicroseconds)} μs")
            Text("目標 ≤3000μs。物理的な振動開始時間ではありません。OS が再生を抑制する場合があります。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HapticTier.entries.forEach { tier ->
                    OutlinedButton(enabled = !blind && service.capabilities.supports(tier), onClick = { cancelTrial(); service.setTier(tier); revision++ }) { Text(tier.name) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("触覚 ON（OFF で即停止）")
                Switch(checked = enabled, onCheckedChange = { enabled = it; cancelTrial(); service.setEnabled(it) })
            }
            Text("全体の強さ: ${"%.2f".format(strength)}")
            Slider(value = strength, enabled = !blind, onValueChange = { strength = it }, onValueChangeFinished = { cancelTrial(); service.setStrength(strength.toDouble()); revision++ })
            Text("安全制限: 60回/分、自発的な振動は2秒間隔、バックグラウンド時は停止")
            HorizontalDivider()
            Text("ノック: ${count.roundToInt()} 回")
            Slider(value = count, valueRange = 1f..4f, steps = 2, enabled = !blind, onValueChange = { count = it })
            Text("衝突の強さ: ${"%.2f".format(impact)}")
            Slider(value = impact, enabled = !blind, onValueChange = { impact = it })
            HapticClipId.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { id ->
                        Button(modifier = Modifier.weight(1f), enabled = !blind && enabled && service.capabilities.hasVibrator, onClick = {
                            when (id) {
                                HapticClipId.Knock -> service.playKnock(count.roundToInt())
                                HapticClipId.Bump -> service.playBump(impact.toDouble())
                                else -> service.play(id.key)
                            }
                        }) { Text("${id.key}\nTier ${service.clipTier(id, count.roundToInt()) ?: "—"}") }
                    }
                }
            }
            PrimitivePalette(enabled && service.capabilities.hasVibrator, visible = !blind)
            SequenceEditor(enabled && service.capabilities.hasVibrator, visible = !blind)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("はい／いいえ ブラインドテスト")
                Switch(checked = blind, onCheckedChange = {
                    blind = it; cancelTrial(); service.blindMode = it; logs.clear()
                })
            }
            Text("画面を見ずに振動で回答してください。端末のメディア振動設定も確認してください。")
            Button(enabled = blind && enabled && !blindTest.awaitingAnswer, onClick = {
                val yes = Random.nextBoolean()
                if (service.play(if (yes) "yes" else "no")) blindTest.begin(yes)
                revision++
            }) { Text("ランダムな振動を再生") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(true, false).forEach { yes ->
                    Button(enabled = blind && enabled && blindTest.awaitingAnswer, onClick = { blindTest.answer(yes); revision++ }) { Text(if (yes) "はい" else "いいえ") }
                }
            }
            Text("正解 ${blindTest.correct} / ${blindTest.total}（${"%.0f".format(blindTest.accuracy * 100)}%）")
            Text("OS 呼び出し成功後に1回答のみ。OFF・画面離脱時は未回答試行を破棄。", style = MaterialTheme.typography.bodySmall)
            Text("ログ", style = MaterialTheme.typography.titleMedium)
            Text(logs.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable private fun PrimitivePicker(value: HapticPrimitive, onChange: (HapticPrimitive) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(value.key) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                HapticPrimitive.entries.forEach { primitive ->
                    DropdownMenuItem(text = { Text(primitive.key) }, onClick = { onChange(primitive); expanded = false })
                }
            }
        }
    }

    @Composable private fun PrimitivePalette(canPlay: Boolean, visible: Boolean) {
        @Suppress("UNUSED_VARIABLE") val currentRevision = revision
        var scale by remember { mutableDoubleStateOf(.5) }
        if (!visible) return
        HorizontalDivider()
        Text("プリミティブのパレット", style = MaterialTheme.typography.titleLarge)
        Text("scale を選んで単発再生（全体の強さも適用）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(.2, .5, 1.0).forEach { value ->
                FilterChip(selected = scale == value, onClick = { scale = value }, label = { Text(value.toString()) })
            }
        }
        HapticPrimitive.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { primitive ->
                    val capability = service.capabilities.primitive(primitive.id)
                    Button(modifier = Modifier.weight(1f), enabled = canPlay, onClick = { service.playPrimitive(primitive, scale) }) {
                        Text("${primitive.key}\nTier ${service.paletteTier(primitive, scale) ?: "—"}" +
                            if (capability == null) " / 近似" else " / ${capability.duration}ms")
                    }
                }
            }
        }
    }

    @Composable private fun SequenceEditor(canPlay: Boolean, visible: Boolean) {
        @Suppress("UNUSED_VARIABLE") val currentRevision = revision
        val steps = remember { mutableStateListOf<HapticSequenceStep>() }
        var nextPrimitive by remember { mutableStateOf(HapticPrimitive.Click) }
        var copyStatus by remember { mutableStateOf("") }
        val snapshot = steps.toList()
        val draft = remember(snapshot) {
            if (snapshot.isEmpty()) null else runCatching {
                val sequence = HapticSequence(snapshot)
                sequence.toClip(service.capabilities) to sequence.toJson(service.capabilities)
            }
        }
        LaunchedEffect(snapshot, service.tier, service.strength) {
            copyStatus = ""
            val clip = draft?.getOrNull()?.first
            if (clip == null) service.clearSequence() else service.prepareSequence(clip)
            revision++
        }
        if (!visible) return
        HorizontalDivider()
        Text("簡易シーケンサー", style = MaterialTheme.typography.titleLarge)
        Text("順に並べ、各段の scale と間隔を調整。間隔は前の振動の終了から（1段目は開始待ち）。最大32段・1分。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimitivePicker(nextPrimitive) { nextPrimitive = it }
            Button(enabled = steps.size < 32, onClick = {
                steps.add(HapticSequenceStep(nextPrimitive, .5, if (steps.isEmpty()) 0 else 100))
            }) { Text("追加") }
        }
        steps.forEachIndexed { index, step ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${index + 1} 段目", style = MaterialTheme.typography.titleMedium)
                    PrimitivePicker(step.primitive) { steps[index] = step.copy(primitive = it) }
                    Text("scale ${"%.2f".format(step.scale)}")
                    Slider(value = step.scale.toFloat(), onValueChange = { steps[index] = step.copy(scale = it.toDouble()) })
                    Text("間隔 ${step.pauseMs} ms")
                    Slider(value = step.pauseMs.toFloat(), valueRange = 0f..2000f,
                        onValueChange = { steps[index] = step.copy(pauseMs = it.roundToInt()) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = index > 0, onClick = {
                            steps[index] = steps[index - 1]; steps[index - 1] = step
                        }) { Text("↑ 前へ") }
                        TextButton(enabled = index < steps.lastIndex, onClick = {
                            steps[index] = steps[index + 1]; steps[index + 1] = step
                        }) { Text("↓ 後へ") }
                        TextButton(onClick = { steps.removeAt(index) }) { Text("削除") }
                    }
                }
            }
        }
        val preview = draft?.getOrNull()
        draft?.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
        Text("${steps.size} 段 / 再生 Tier ${service.sequenceTier ?: "—"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canPlay && preview != null, onClick = {
                preview?.let { service.logSequence(it.second); service.playSequence() }
            }) { Text("再生") }
            OutlinedButton(enabled = preview != null, onClick = {
                preview?.let {
                    service.logSequence(it.second)
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Haptic clip JSON", it.second))
                    copyStatus = "JSON をログに出力・コピーしました"
                }
            }) { Text("JSON コピー") }
        }
        TextButton(enabled = steps.isNotEmpty(), onClick = { steps.clear() }) { Text("すべて消去") }
        if (copyStatus.isNotEmpty()) Text(copyStatus)
        preview?.let { SelectionContainer { Text(it.second, style = MaterialTheme.typography.bodySmall) } }
    }
    override fun onResume() { super.onResume(); service.suspend(false) }
    override fun onPause() { service.suspend(true); cancelTrial(); super.onPause() }
    override fun onDestroy() { service.logged = null; service.blindMode = false; super.onDestroy() }
}
