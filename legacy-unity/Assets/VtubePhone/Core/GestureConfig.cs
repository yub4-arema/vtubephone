using System;

namespace VtubePhone.Core
{
    [Serializable]
    public sealed class GestureConfig
    {
        public double NeutralSettleMs = 500, NeutralAverageMs = 1000, NeutralAdaptDegrees = 8;
        public double QuietAcceleration = 0.6, QuietAngularSpeed = 0.25;
        public double TiltDegrees = 20, TiltReleaseDegrees = 12, TiltHoldMs = 400;
        public double OscillationSpeed = 1.2, OscillationCrossAxisRatio = 0.6, OscillationWindowMs = 1200, OscillationMinLobeMs = 40;
        public int OscillationLobes = 4;
        public double ShakeWindowMs = 200, ShakeMinMs = 120, ShakeLightRms = 3, ShakeLightPeak = 5, ShakeHardRms = 9, ShakeHardPeak = 14;
        public double ShakeSuppressionMs = 500, GestureCooldownMs = 600, ArbitrationMs = 120;
        public double FlipHoldMs = 700, FaceDownCosine = 0.9, HorizontalCosine = 0.94, StillHoldMs = 1500;
        public double StillWindowMs = 300, StillVariance = 0.12, PickupAcceleration = 1.5, PickupAngularSpeed = 0.5, PickupHoldMs = 80;
        public double BackKnockHighPassMs = 60, BackKnockThreshold = 5, BackKnockRelease = 2, BackKnockMaxMs = 30;
        public double BackKnockRefractoryMs = 80, TouchSuppressionMs = 80, BackKnockMaxRms = 1.5, BackKnockMaxAngularSpeed = 0.6, BackKnockAxisRatio = 1.8;
        public double KnockJoinMs = 600, KnockSilenceMs = 700, RhythmTolerance = 0.35;
        public double HoldMs = 500, TapMaxMs = 350, TouchMovePixels = 24, StrokePixels = 100;
        public double MaxSampleGapMs = 100, SampleStaleMs = 150;
        public void Validate()
        {
            foreach (var field in typeof(GestureConfig).GetFields())
            {
                double value = Convert.ToDouble(field.GetValue(this));
                if (double.IsNaN(value) || double.IsInfinity(value) || value <= 0) throw new ArgumentException("Invalid gesture parameter: " + field.Name);
            }
            if (TiltReleaseDegrees >= TiltDegrees || NeutralAdaptDegrees >= TiltReleaseDegrees || TiltDegrees >= 90 ||
                ShakeHardRms <= ShakeLightRms || ShakeHardPeak <= ShakeLightPeak || BackKnockRelease >= BackKnockThreshold ||
                KnockSilenceMs < KnockJoinMs || FaceDownCosine > 1 || HorizontalCosine > 1 || RhythmTolerance >= 1 || OscillationLobes < 4)
                throw new ArgumentException("Inconsistent gesture thresholds");
        }
        public static long Ns(double ms) => (long)(ms * 1000000);
    }
}
