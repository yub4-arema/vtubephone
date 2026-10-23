using System;
using System.Collections.Generic;
using System.Linq;

namespace VtubePhone.Core
{
    public enum HapticClipId { Knock, Thud, Bump, Yes, No, Call, Purr, Heartbeat, Nervous, Giggle, Dizzy, Poke }
    public enum HapticTier { A, B, C, D }

    public sealed class HapticParameter
    {
        private readonly double[][] _points;
        public HapticParameter(double value) : this(new[] { new[] { 0.0, value } }) { }
        public HapticParameter(double[][] points)
        {
            if (points == null || points.Length == 0) throw new ArgumentException("Empty curve");
            _points = points.Select(p => (double[])p.Clone()).ToArray();
            for (int i = 0; i < _points.Length; i++)
            {
                var p = _points[i];
                if (p.Length != 2 || !Finite(p[0]) || p[0] < 0 || !Finite(p[1]) || p[1] < 0 || p[1] > 1 ||
                    (i == 0 ? p[0] != 0 : p[0] <= _points[i - 1][0])) throw new ArgumentException("Invalid curve");
            }
        }
        public static bool Finite(double value) => !double.IsNaN(value) && !double.IsInfinity(value);
        public double At(double ms)
        {
            for (int i = 1; i < _points.Length; i++)
                if (ms < _points[i][0])
                {
                    var a = _points[i - 1]; var b = _points[i];
                    return a[1] + (b[1] - a[1]) * Math.Max(0, (ms - a[0]) / (b[0] - a[0]));
                }
            return _points[_points.Length - 1][1];
        }
    }

    public sealed class HapticEvent
    {
        public int TimeMs { get; }
        public bool Continuous { get; }
        public int DurationMs { get; }
        public HapticParameter Intensity { get; }
        public HapticParameter Sharpness { get; }
        public HapticEvent(int timeMs, bool continuous, int durationMs, HapticParameter intensity, HapticParameter sharpness)
        {
            if (timeMs < 0 || timeMs > 60000 || (continuous && (durationMs <= 0 || durationMs > 60000)))
                throw new ArgumentOutOfRangeException(nameof(timeMs));
            TimeMs = timeMs; Continuous = continuous;
            Intensity = intensity ?? throw new ArgumentNullException(nameof(intensity));
            Sharpness = sharpness ?? throw new ArgumentNullException(nameof(sharpness));
            DurationMs = continuous ? durationMs : (int)Math.Round(20 - 10 * sharpness.At(0));
        }
    }

    public sealed class HapticClip
    {
        public string Id { get; }
        public IReadOnlyList<HapticEvent> Events { get; }
        public int DurationMs { get; }
        public HapticClip(string id, IEnumerable<HapticEvent> events)
        {
            if (string.IsNullOrWhiteSpace(id)) throw new ArgumentException("Missing id");
            var ordered = events.OrderBy(e => e.TimeMs).ToArray();
            if (ordered.Length == 0 || ordered.Length > 256) throw new ArgumentException("Invalid event count");
            Id = id; Events = Array.AsReadOnly(ordered);
            DurationMs = ordered.Max(e => e.TimeMs + e.DurationMs);
            if (DurationMs > 60000) throw new ArgumentException("Clip exceeds one minute");
        }
    }

    public static class HapticClipFactory
    {
        public static HapticClip Knock(int count, int intervalMs = 170, double intensity = 0.9)
        {
            if (count < 1 || count > 4 || intervalMs < 20 || intervalMs > 1000) throw new ArgumentOutOfRangeException(nameof(count));
            return new HapticClip("knock", Enumerable.Range(0, count).Select(i =>
                new HapticEvent(i * intervalMs, false, 0, new HapticParameter(intensity), new HapticParameter(0.25))));
        }
        public static HapticClip Bump(double impact)
        {
            if (!HapticParameter.Finite(impact)) throw new ArgumentOutOfRangeException(nameof(impact));
            impact = Math.Max(0, Math.Min(1, impact));
            return new HapticClip("bump", new[] { new HapticEvent(0, false, 0,
                new HapticParameter(impact), new HapticParameter(0.8 - 0.6 * impact)) });
        }
    }

    [Serializable] public sealed class PrimitiveCapability
    {
        public int id;
        public bool supported;
        public int duration;
    }
    [Serializable] public sealed class EnvelopeCapability
    {
        public bool supported;
        public int maxSize, minControlPointMs, maxControlPointMs, maxDuration;
    }
    [Serializable] public sealed class HapticsCapabilities
    {
        public int sdkInt;
        public bool hasVibrator, hasAmplitudeControl;
        public string manufacturer, model, diagnostics;
        public float resonantFrequency = -1, qFactor = -1;
        public PrimitiveCapability[] primitives = Array.Empty<PrimitiveCapability>();
        public EnvelopeCapability envelope = new EnvelopeCapability();
        public bool Supports(HapticTier tier) => hasVibrator && (tier == HapticTier.D ||
            (tier == HapticTier.C && sdkInt >= 26 && hasAmplitudeControl) ||
            (tier == HapticTier.B && sdkInt >= 30 && primitives.Any(p => p.supported)) ||
            (tier == HapticTier.A && sdkInt >= 36 && envelope.supported));
        public HapticTier BestTier => hasVibrator ? Enum.GetValues(typeof(HapticTier)).Cast<HapticTier>().First(Supports) : HapticTier.D;
        public PrimitiveCapability Primitive(int id) => primitives.FirstOrDefault(p => p.id == id && p.supported);
    }
}
