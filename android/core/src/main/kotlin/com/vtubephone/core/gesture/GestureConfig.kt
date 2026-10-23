package com.vtubephone.core.gesture

import kotlinx.serialization.json.*
import kotlin.reflect.KMutableProperty1

/**
 * Every tunable threshold in one place. Defaults are first guesses to be tuned with GestureLab recordings.
 * Units: degrees, ms, rad/s, m/s², px (CSS/dp).
 */
class GestureConfig {
    // Posture / tilt
    var tiltDeg = 28.0; var tiltRearmDeg = 12.0; var tiltHoldMs = 600.0
    var neutralTauMs = 3000.0; var postureAdoptMs = 8000.0; var postureGuardMs = 1500.0
    // Nod / headshake oscillation
    var swingPeakRad = 2.0; var swingDeadbandRad = 0.4; var swingMinLobeMs = 80.0; var swingMaxLobeMs = 500.0
    var swingMinLobes = 3.0; var swingWindowMs = 1500.0; var swingDominance = 1.5; var swingSettleMs = 80.0; var swingRefractoryMs = 800.0
    // Shake
    var shakeWindowMs = 250.0; var shakeLightRms = 5.0; var shakeHardPeak = 30.0; var shakeHardRms = 17.0
    var shakeMinMs = 500.0; var shakeEndMs = 250.0; var shakeSuppressMs = 500.0
    // Steadiness EMAs
    var steadyTauMs = 150.0; var steadyGyro = 0.15; var steadyLinear = 0.4; var quietGyro = 0.03; var quietLinear = 0.08
    // Flip / still / pickup
    var flipDownZ = -0.85; var flipRearmZ = -0.5; var flipHoldMs = 700.0
    var stillFlatZ = 0.9; var stillHoldMs = 1500.0; var pickupGyro = 0.4; var pickupLinear = 0.8; var pickupAngleDeg = 12.0
    // Back knock
    var backKnockHighpassTauMs = 25.0; var backKnockThreshold = 2.0; var backKnockMaxWidthMs = 45.0; var backKnockRefractoryMs = 80.0
    var backKnockMaxGyro = 0.6; var backKnockMaxLinear = 1.2; var backKnockMaxRiseMs = 12.0; var backKnockMaxTurnDeg = 12.0; var backKnockTurnWindowMs = 400.0
    var touchGuardBeforeMs = 80.0; var touchGuardAfterMs = 120.0
    // Knock sequences
    var knockGapMs = 600.0; var knockFinalizeMs = 700.0; var knockMaxCount = 8.0
    // Touch
    var tapMaxMs = 300.0; var tapSlopPx = 24.0; var holdMs = 500.0; var strokeDistPx = 120.0; var strokeCooldownMs = 1500.0
    // Expectation: thresholds of expected gestures are multiplied by this (<1 = more sensitive)
    var expectFactor = 0.8

    fun copy() = GestureConfig().also { c -> fields.values.forEach { it.set(c, it.get(this)) } }

    fun toJson(): String = JsonObject(fields.mapValues { JsonPrimitive(it.value.get(this)) }).toString()

    companion object {
        private val fields: Map<String, KMutableProperty1<GestureConfig, Double>> = listOf(
            GestureConfig::tiltDeg, GestureConfig::tiltRearmDeg, GestureConfig::tiltHoldMs, GestureConfig::neutralTauMs,
            GestureConfig::postureAdoptMs, GestureConfig::postureGuardMs,
            GestureConfig::swingPeakRad, GestureConfig::swingDeadbandRad, GestureConfig::swingMinLobeMs, GestureConfig::swingMaxLobeMs,
            GestureConfig::swingMinLobes, GestureConfig::swingWindowMs, GestureConfig::swingDominance, GestureConfig::swingSettleMs,
            GestureConfig::swingRefractoryMs,
            GestureConfig::shakeWindowMs, GestureConfig::shakeLightRms, GestureConfig::shakeHardPeak, GestureConfig::shakeHardRms,
            GestureConfig::shakeMinMs, GestureConfig::shakeEndMs, GestureConfig::shakeSuppressMs,
            GestureConfig::steadyTauMs, GestureConfig::steadyGyro, GestureConfig::steadyLinear, GestureConfig::quietGyro, GestureConfig::quietLinear,
            GestureConfig::flipDownZ, GestureConfig::flipRearmZ, GestureConfig::flipHoldMs, GestureConfig::stillFlatZ, GestureConfig::stillHoldMs,
            GestureConfig::pickupGyro, GestureConfig::pickupLinear, GestureConfig::pickupAngleDeg,
            GestureConfig::backKnockHighpassTauMs, GestureConfig::backKnockThreshold, GestureConfig::backKnockMaxWidthMs,
            GestureConfig::backKnockRefractoryMs, GestureConfig::backKnockMaxGyro, GestureConfig::backKnockMaxLinear, GestureConfig::backKnockMaxRiseMs, GestureConfig::backKnockMaxTurnDeg,
            GestureConfig::backKnockTurnWindowMs, GestureConfig::touchGuardBeforeMs, GestureConfig::touchGuardAfterMs,
            GestureConfig::knockGapMs, GestureConfig::knockFinalizeMs, GestureConfig::knockMaxCount,
            GestureConfig::tapMaxMs, GestureConfig::tapSlopPx, GestureConfig::holdMs, GestureConfig::strokeDistPx,
            GestureConfig::strokeCooldownMs, GestureConfig::expectFactor,
        ).associateBy { it.name }

        /** Defaults overridden by any matching numeric keys in [json]; unknown keys are rejected to catch typos. */
        fun fromJson(json: String): GestureConfig {
            val config = GestureConfig()
            for ((key, value) in Json.parseToJsonElement(json).jsonObject) {
                val field = requireNotNull(fields[key]) { "Unknown gesture config key: $key" }
                val number = requireNotNull(value.jsonPrimitive.doubleOrNull) { "$key must be a number" }
                require(number.isFinite()) { "$key must be finite" }
                field.set(config, number)
            }
            return config
        }
    }
}
