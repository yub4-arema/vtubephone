package com.vtubephone.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import com.vtubephone.app.VtubePhoneApplication
import com.vtubephone.core.gesture.*
import kotlinx.coroutines.delay

/** Live view, practice, recording and calibration for the body-language recogniser. */
class GestureLabActivity : ComponentActivity() {
    private val app get() = application as VtubePhoneApplication
    private val sensing get() = app.sensing
    private val log = mutableStateListOf<String>()
    private val counters = mutableStateMapOf<GestureId, Int>()
    private var practiceTarget by mutableStateOf<GestureId?>(null)
    private var practiceHits by mutableIntStateOf(0)
    private var practiceMisses by mutableIntStateOf(0)
    private var calibrating by mutableStateOf(false)
    private val calibrationPeaks = mutableStateListOf<Double>()
    private var thresholdBeforeCalibration = 0.0

    private val practiceSet = listOf(
        GestureId.Nod, GestureId.Headshake, GestureId.TiltLeft, GestureId.TiltRight, GestureId.TiltForward,
        GestureId.Knock, GestureId.BackKnock, GestureId.Stroke, GestureId.Hold, GestureId.ShakeLight, GestureId.FlipDown,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Lab() } } }
    }

    override fun onResume() {
        super.onResume()
        sensing.start()
        sensing.listener = { e -> runOnUiThread { onGesture(e) } }
    }

    override fun onPause() {
        sensing.listener = null
        sensing.stopRecording()
        super.onPause()
    }

    private fun onGesture(e: GestureEvent) {
        counters[e.id] = (counters[e.id] ?: 0) + 1
        val extra = buildString {
            if (e.count > 0) append(" ×${e.count} ${e.intervalsMs}")
            e.target?.let { append(" [$it]") }
        }
        log.add(0, "${e.id.wire}  ${"%.2f".format(e.strength)}$extra")
        while (log.size > 40) log.removeAt(log.lastIndex)
        val target = practiceTarget ?: return
        if (e.id == target) { practiceHits++; app.haptics.play("yes"); practiceTarget = practiceSet.random() }
        else { practiceMisses++; app.haptics.play("no") }
    }

    @SuppressLint("ClickableViewAccessibility")
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable private fun Lab() {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(50); tick++ } }
        val r = sensing.recognizer
        @Suppress("UNUSED_VARIABLE") val t = tick

        if (calibrating && r.lastSpikePeak > 0 && calibrationPeaks.lastOrNull() != r.lastSpikePeak) {
            calibrationPeaks.add(r.lastSpikePeak)
            if (calibrationPeaks.size >= 5) finishCalibration()
        }

        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("vtubephone / Gesture Lab", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { finish() }) { Text("← 戻る") }

            Card { Column(Modifier.padding(12.dp)) {
                Text("roll ${"%+.0f".format(r.roll)}°   pitch ${"%+.0f".format(r.pitch)}°", style = MaterialTheme.typography.titleLarge)
                Text("shake RMS ${"%.1f".format(r.shakeRms)} m/s²   high-pass ${"%.1f".format(r.highPass)}")
                Meter("傾き", kotlin.math.abs(r.roll).coerceAtLeast(kotlin.math.abs(r.pitch)) / (r.config.tiltDeg * 2))
                Meter("振り", r.shakeRms / r.config.shakeHardRms)
                Meter("背面ノック", r.highPass / (r.config.backKnockThreshold * 2))
                Text("sensor Hz: ${sensing.ratesHz.entries.joinToString { "${it.key}=${it.value}" }}", style = MaterialTheme.typography.bodySmall)
                if (sensing.missingSensors.isNotEmpty()) Text("missing: ${sensing.missingSensors}", style = MaterialTheme.typography.bodySmall)
            } }

            Box(Modifier.fillMaxWidth().height(160.dp).background(MaterialTheme.colorScheme.secondaryContainer)
                .pointerInteropFilter { ev ->
                    val phase = when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> TouchPhase.Down; MotionEvent.ACTION_MOVE -> TouchPhase.Move
                        MotionEvent.ACTION_UP -> TouchPhase.Up; MotionEvent.ACTION_CANCEL -> TouchPhase.Cancel
                        else -> null
                    }
                    val density = resources.displayMetrics.density
                    if (phase != null) sensing.onNativeTouch(ev.eventTime, ev.getPointerId(0), phase, ev.x / density, ev.y / density, onAvatar = true)
                    true
                }) {
                Text("ここを アバターの代わりに タップ / なでる / 長押し", Modifier.padding(12.dp))
            }

            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val target = practiceTarget
                Text("練習モード", style = MaterialTheme.typography.titleMedium)
                if (target != null) {
                    Text("お題: ${practiceLabel(target)}", style = MaterialTheme.typography.headlineSmall)
                    Text("正解 $practiceHits / 誤検出 $practiceMisses  (正解=はい の振動、違う=いいえ の振動)")
                    OutlinedButton(onClick = { practiceTarget = null }) { Text("やめる") }
                } else Button(onClick = { practiceHits = 0; practiceMisses = 0; practiceTarget = practiceSet.random() }) { Text("練習を始める") }
            } }

            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("ラベル付き記録", style = MaterialTheme.typography.titleMedium)
                var label by remember { mutableStateOf(GestureId.Nod.wire) }
                var recording by remember { mutableStateOf(false) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (GestureId.entries.map { it.wire } + "negative").forEach { name ->
                        FilterChip(selected = label == name, enabled = !recording, onClick = { label = name }, label = { Text(name) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !recording, onClick = { sensing.startRecording(label); recording = true }) { Text("● 記録") }
                    OutlinedButton(enabled = recording, onClick = { sensing.stopRecording(); recording = false }) { Text("■ 停止") }
                }
                sensing.recordingFile?.let { Text(it.absolutePath, style = MaterialTheme.typography.bodySmall) }
            } }

            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("背面ノックの感度合わせ（今: ${"%.1f".format(r.config.backKnockThreshold)}）", style = MaterialTheme.typography.titleMedium)
                if (calibrating) {
                    Text("スマホの背面を指で 5 回、いつもの強さで叩いてください: ${calibrationPeaks.size}/5")
                    OutlinedButton(onClick = { cancelCalibration() }) { Text("中止") }
                } else Button(onClick = { startCalibration() }) { Text("感度合わせを始める") }
            } }

            Card { Column(Modifier.padding(12.dp)) {
                Text("しきい値", style = MaterialTheme.typography.titleMedium)
                ConfigSlider("傾き (°)", r.config.tiltDeg, 10.0..40.0) { c, v -> c.tiltDeg = v }
                ConfigSlider("うなずき/首振りの速さ (rad/s)", r.config.swingPeakRad, 0.6..3.0) { c, v -> c.swingPeakRad = v }
                ConfigSlider("激しい振り (m/s²)", r.config.shakeHardPeak, 12.0..40.0) { c, v -> c.shakeHardPeak = v }
                ConfigSlider("背面ノック", r.config.backKnockThreshold, 0.8..8.0) { c, v -> c.backKnockThreshold = v }
                TextButton(onClick = { sensing.resetConfig() }) { Text("初期値に戻す") }
            } }

            Text("検出回数: " + counters.entries.sortedBy { it.key.ordinal }.joinToString { "${it.key.wire}=${it.value}" })
            log.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    @Composable private fun Meter(label: String, value: Double) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { value.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
        }
    }

    @Composable private fun ConfigSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, set: (GestureConfig, Double) -> Unit) {
        Text("$label: ${"%.1f".format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value.toFloat(), valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            onValueChange = { v -> sensing.updateConfig(sensing.recognizer.config.copy().also { set(it, v.toDouble()) }) })
    }

    private fun startCalibration() {
        thresholdBeforeCalibration = sensing.recognizer.config.backKnockThreshold
        calibrationPeaks.clear()
        sensing.recognizer.config = sensing.recognizer.config.copy().also { it.backKnockThreshold = 0.8 }
        calibrating = true
    }

    private fun cancelCalibration() {
        calibrating = false
        sensing.recognizer.config = sensing.recognizer.config.copy().also { it.backKnockThreshold = thresholdBeforeCalibration }
    }

    private fun finishCalibration() {
        calibrating = false
        val threshold = BackKnockCalibrator.threshold(calibrationPeaks.toList())
        sensing.updateConfig(sensing.recognizer.config.copy().also { it.backKnockThreshold = threshold })
        app.haptics.play("yes")
        log.add(0, "背面ノックのしきい値を ${"%.2f".format(threshold)} にしました")
    }

    private fun practiceLabel(id: GestureId) = when (id) {
        GestureId.Nod -> "うなずく（手前・奥に 2 回倒す）"
        GestureId.Headshake -> "首を振る（左右にひねる）"
        GestureId.TiltLeft -> "左に傾けて止める"
        GestureId.TiltRight -> "右に傾けて止める"
        GestureId.TiltForward -> "奥に倒して止める"
        GestureId.Knock -> "画面をトントン"
        GestureId.BackKnock -> "背面をトントン"
        GestureId.Stroke -> "四角の中をなでる"
        GestureId.Hold -> "四角の中を長押し"
        GestureId.ShakeLight -> "軽く揺らす"
        GestureId.FlipDown -> "画面を下にして置く"
        else -> id.wire
    }
}
