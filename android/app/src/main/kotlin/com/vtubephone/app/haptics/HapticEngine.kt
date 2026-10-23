package com.vtubephone.app.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import com.vtubephone.core.haptics.*

/** Android adapter only: selection, timing and signal rendering live in core. */
class HapticEngine(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator = context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private data class Prepared(val effect: VibrationEffect?, val tier: HapticTier, val status: String)
    private val cache = mutableMapOf<String, Prepared>()
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val mediaAttributes = if (Build.VERSION.SDK_INT >= 33)
        VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build() else null
    var lastVibrateNs = 0L; private set
    var lastStatus = "Not played"; private set

    fun getCapabilities(): HapticsCapabilities {
        val v = vibrator
        val primitives = (1..8).map { id ->
            val introduced = id !in listOf(2, 3, 8) || Build.VERSION.SDK_INT >= 31
            val supported = v != null && Build.VERSION.SDK_INT >= 30 && introduced && v.arePrimitivesSupported(id)[0]
            val duration = if (supported && Build.VERSION.SDK_INT >= 31) v.getPrimitiveDurations(id)[0] else 0
            PrimitiveCapability(id, supported, duration)
        }
        val envelope = if (v != null && Build.VERSION.SDK_INT >= 36 && v.areEnvelopeEffectsSupported()) {
            val info = v.envelopeEffectInfo
            EnvelopeCapability(true, info.maxSize, info.minControlPointDurationMillis,
                info.maxControlPointDurationMillis, info.maxDurationMillis)
        } else EnvelopeCapability()
        fun metadata(value: Float) = if (value.isFinite()) value else -1f
        return HapticsCapabilities(Build.VERSION.SDK_INT, v?.hasVibrator() == true, v?.hasAmplitudeControl() == true,
            Build.MANUFACTURER, Build.MODEL,
            diagnostics = if (mediaAttributes != null) "VibrationAttributes.USAGE_MEDIA" else "AudioAttributes.USAGE_GAME",
            resonantFrequency = if (v != null && Build.VERSION.SDK_INT >= 34) metadata(v.resonantFrequency) else -1f,
            qFactor = if (v != null && Build.VERSION.SDK_INT >= 34) metadata(v.qFactor) else -1f,
            primitives = primitives, envelope = envelope)
    }

    fun prepare(key: String, command: HapticCommand): String {
        val prepared = try {
            Prepared(build(command), command.tier, command.tier.name)
        } catch (e: IllegalArgumentException) {
            fallback(command, e)
        } catch (e: IllegalStateException) {
            fallback(command, e)
        }
        cache[key] = prepared
        lastStatus = prepared.status
        return prepared.status
    }
    fun preparedTier(key: String): HapticTier? = cache[key]?.tier
    private fun fallback(command: HapticCommand, e: RuntimeException): Prepared {
        val tier = if (command.tier != HapticTier.D && vibrator?.hasAmplitudeControl() == true) HapticTier.C else HapticTier.D
        return Prepared(waveform(command), tier, "Fallback $tier: ${e.message}")
    }

    private fun build(command: HapticCommand): VibrationEffect? {
        // Avoid creating even a primitive with scale=0: some hardware treats 0 as a weak tick.
        if (command.amplitudes.none { it > 0 }) return null
        return when (command.tier) {
            HapticTier.A -> {
                if (Build.VERSION.SDK_INT >= 36 && vibrator?.areEnvelopeEffectsSupported() == true) {
                    val builder = VibrationEffect.BasicEnvelopeBuilder().setInitialSharpness(command.points.first().sharpness)
                    command.points.drop(1).forEach { builder.addControlPoint(it.intensity, it.sharpness, it.durationMs.toLong()) }
                    builder.build()
                } else throw IllegalStateException("Envelope unavailable")
            }
            HapticTier.B -> {
                if (Build.VERSION.SDK_INT >= 30) {
                    val builder = VibrationEffect.startComposition()
                    command.primitives.forEach {
                        check(vibrator?.arePrimitivesSupported(it.id)?.first() == true)
                        builder.addPrimitive(it.id, it.scale, it.delayMs)
                    }
                    builder.compose()
                } else throw IllegalStateException("Composition unavailable")
            }
            else -> waveform(command)
        }
    }
    private fun waveform(command: HapticCommand): VibrationEffect? {
        if (command.tier != HapticTier.D && vibrator?.hasAmplitudeControl() == true) {
            if (command.amplitudes.none { it > 0 }) return null
            return VibrationEffect.createWaveform(command.timings, command.amplitudes, -1)
        }
        if (command.onOffTimings.filterIndexed { i, _ -> i % 2 == 1 }.none { it > 0 }) return null
        return VibrationEffect.createWaveform(command.onOffTimings, -1)
    }

    fun play(key: String): Boolean {
        val start = System.nanoTime()
        lastVibrateNs = 0
        val prepared = requireNotNull(cache[key]) { "Unprepared key $key" }
        lastStatus = prepared.status
        val effect = prepared.effect ?: return false
        val v = vibrator ?: return false
        if (!v.hasVibrator()) return false
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, requireNotNull(mediaAttributes))
        else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, audioAttributes)
        }
        lastVibrateNs = System.nanoTime() - start
        return true // OS call accepted; physical onset and OS suppression require a real device.
    }
    fun playOnce(command: HapticCommand): Boolean {
        prepare("__once", command)
        return try { play("__once") } finally { cache.remove("__once") }
    }
    fun cancel() { vibrator?.cancel() }
    fun clearCache() { cache.clear() }
}
