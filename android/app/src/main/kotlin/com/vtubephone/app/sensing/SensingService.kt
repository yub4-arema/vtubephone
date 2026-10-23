package com.vtubephone.app.sensing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.vtubephone.core.gesture.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedWriter
import java.io.File

/**
 * Owns the sensor listeners and the [GestureRecognizer]. Samples are processed on a dedicated thread at
 * SENSOR_DELAY_FASTEST; recognised gestures are published on [gestures] for the brain, the avatar and GestureLab.
 */
class SensingService(private val context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("vtubephone-sensing").apply { start() }
    private val handler = Handler(thread.looper)
    private val configFile = File(context.filesDir, "gesture-config.json")
    val recognizer = GestureRecognizer(loadConfig())

    private val _gestures = MutableSharedFlow<GestureEvent>(extraBufferCapacity = 64)
    val gestures: SharedFlow<GestureEvent> = _gestures
    /** Called on the sensing thread for every gesture; cheaper than collecting the flow for latency-critical reflexes. */
    @Volatile var listener: ((GestureEvent) -> Unit)? = null

    /** Latest "up" unit vector in device axes (gravity reaction), for the avatar's physics. */
    @Volatile var up = floatArrayOf(0f, 1f, 0f); private set

    private val kinds = mapOf(
        Sensor.TYPE_ACCELEROMETER to SensorKind.Accel, Sensor.TYPE_GYROSCOPE to SensorKind.Gyro,
        Sensor.TYPE_GRAVITY to SensorKind.Gravity, Sensor.TYPE_LINEAR_ACCELERATION to SensorKind.Linear,
        Sensor.TYPE_GAME_ROTATION_VECTOR to SensorKind.Rotation,
    )
    private val counts = IntArray(SensorKind.entries.size)
    private var rateWindowStartNs = 0L
    @Volatile var ratesHz: Map<SensorKind, Int> = emptyMap(); private set
    val missingSensors: List<SensorKind> = kinds.filter { manager.getDefaultSensor(it.key) == null }.map { it.value }
    private var running = false
    private var recorder: BufferedWriter? = null
    var recordingFile: File? = null; private set

    fun start() = handler.post {
        if (running) return@post
        running = true
        rateWindowStartNs = SystemClock.elapsedRealtimeNanos()
        kinds.keys.mapNotNull { manager.getDefaultSensor(it) }.forEach {
            // FASTEST needs HIGH_SAMPLING_RATE_SENSORS (API 31+, declared in the manifest); never crash without it.
            try { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler) }
            catch (e: SecurityException) {
                Log.w(TAG, "High-rate sensors denied; falling back to GAME rate", e)
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        }
        Log.i(TAG, "Sensing started; missing: $missingSensors")
    }

    fun stop() = handler.post {
        if (!running) return@post
        running = false
        manager.unregisterListener(this)
        stopRecordingLocked()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = kinds[event.sensor.type] ?: return
        val v = event.values
        val sample = MotionSample(event.timestamp, kind, v[0], v[1], v[2])
        recorder?.let { it.write(MotionCsv.format(MotionCsv.Sample(sample))); it.newLine() }
        if (kind == SensorKind.Gravity) {
            val n = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).takeIf { it > 1e-3f } ?: 1f
            up = floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
        }
        counts[kind.ordinal]++
        if (event.timestamp - rateWindowStartNs >= 2_000_000_000L) {
            val seconds = (event.timestamp - rateWindowStartNs) / 1e9
            ratesHz = SensorKind.entries.associateWith { (counts[it.ordinal] / seconds).toInt() }
            counts.fill(0); rateWindowStartNs = event.timestamp
        }
        publish(recognizer.onSample(sample))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** Touch from a native view: [eventTimeMs] is MotionEvent.getEventTime() (uptime clock). */
    fun onNativeTouch(eventTimeMs: Long, pointerId: Int, phase: TouchPhase, x: Float, y: Float, onAvatar: Boolean) =
        onTouch(TouchSample(uptimeToSensorNs(eventTimeMs), pointerId, phase, x, y, onAvatar))

    /**
     * Touch from the WebView: [ageMs] = performance.now() - event.timeStamp measured in JS just before posting,
     * so the event time is reconstructed against the receive time (bridge transit is ~1 ms and ignored).
     */
    fun onWebTouch(ageMs: Double, pointerId: Int, phase: TouchPhase, x: Float, y: Float, onAvatar: Boolean) =
        onTouch(TouchSample(SystemClock.elapsedRealtimeNanos() - (ageMs.coerceIn(0.0, 1000.0) * 1e6).toLong(), pointerId, phase, x, y, onAvatar))

    private fun onTouch(t: TouchSample) = handler.post {
        recorder?.let { it.write(MotionCsv.format(MotionCsv.Touch(t))); it.newLine() }
        publish(recognizer.onTouch(t))
    }

    /** The dialog waits for [ids] for [seconds]; they become slightly easier to trigger. */
    fun expect(ids: Set<GestureId>, seconds: Double) =
        recognizer.expect(ids, SystemClock.elapsedRealtimeNanos() + (seconds * 1e9).toLong())

    fun startRecording(label: String) = handler.post {
        stopRecordingLocked()
        val dir = context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")
        dir.mkdirs()
        val file = File(dir, "${System.currentTimeMillis()}-${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.csv")
        recordingFile = file
        recorder = file.bufferedWriter().also {
            it.write("# vtubephone motion recording, label=$label, config=${recognizer.config.toJson()}"); it.newLine()
            it.write(MotionCsv.format(MotionCsv.Label(SystemClock.elapsedRealtimeNanos(), label))); it.newLine()
        }
    }

    fun stopRecording() = handler.post { stopRecordingLocked() }
    private fun stopRecordingLocked() { recorder?.close(); recorder = null }

    fun updateConfig(config: GestureConfig) {
        recognizer.config = config
        configFile.writeText(config.toJson())
    }

    fun resetConfig() { configFile.delete(); recognizer.config = GestureConfig() }

    private fun loadConfig(): GestureConfig = try {
        if (configFile.isFile) GestureConfig.fromJson(configFile.readText()) else GestureConfig()
    } catch (e: IllegalArgumentException) { Log.w(TAG, "Ignoring bad gesture config", e); GestureConfig() }

    private fun publish(events: List<GestureEvent>) {
        for (e in events) {
            Log.i(TAG, "gesture ${e.id.wire} strength=${"%.2f".format(e.strength)} count=${e.count} target=${e.target}")
            listener?.invoke(e)
            _gestures.tryEmit(e)
        }
    }

    companion object {
        private const val TAG = "VtubePhone"
        fun uptimeToSensorNs(uptimeMs: Long) =
            uptimeMs * 1_000_000L + (SystemClock.elapsedRealtimeNanos() - SystemClock.uptimeMillis() * 1_000_000L)
    }
}
