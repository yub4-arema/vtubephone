using System;
using System.Collections.Generic;
using System.Linq;

namespace VtubePhone.Core
{
    [Serializable] public sealed class PrimitiveCommand
    {
        public int id, delayMs, durationMs;
        public float scale;
    }
    [Serializable] public sealed class EnvelopePoint
    {
        public int durationMs;
        public float intensity, sharpness;
    }
    [Serializable] public sealed class HapticCommand
    {
        public string requestedTier, tier, reason;
        public int durationMs;
        public long[] timings = Array.Empty<long>();
        public int[] amplitudes = Array.Empty<int>();
        public long[] onOffTimings = Array.Empty<long>();
        public PrimitiveCommand[] primitives = Array.Empty<PrimitiveCommand>();
        // First point is the implicit zero-intensity start; Java skips this point.
        public EnvelopePoint[] points = Array.Empty<EnvelopePoint>();
    }

    public static class HapticRenderer
    {
        public static HapticCommand Render(HapticClip clip, HapticTier requested, HapticsCapabilities caps, double strength = 1)
        {
            if (!HapticParameter.Finite(strength) || strength < 0 || strength > 1) throw new ArgumentOutOfRangeException(nameof(strength));
            var command = Waveform(clip, caps.hasAmplitudeControl, strength);
            command.requestedTier = requested.ToString();
            command.reason = "Waveform fallback: unsupported tier, mixed events, unknown duration, overlap or envelope constraints";
            if (requested == HapticTier.D) { command.tier = "D"; command.reason = "1ms pulse-density PWM; 5ms continuous source samples"; return command; }
            if (requested == HapticTier.C) { command.reason = "5ms amplitude samples; 1ms transient boundaries"; return command; }
            if (!caps.Supports(requested)) return command;
            bool continuousOnly = clip.Events.All(e => e.Continuous);
            if (requested == HapticTier.A && continuousOnly && TryEnvelope(clip, caps.envelope, strength, out var points))
            {
                command.tier = "A"; command.points = points; command.reason = "Continuous envelope; uniform constrained sampling";
                return command;
            }
            // A uses B for transient-only clips. Mixed clips use one complete C/D waveform.
            if (clip.Events.All(e => !e.Continuous) && TryComposition(clip, caps, strength, out var primitives))
            {
                command.tier = "B"; command.primitives = primitives;
                command.durationMs = primitives.Sum(p => p.delayMs + p.durationMs);
                command.reason = "Transient primitives; pauses compensated using device durations";
            }
            return command;
        }

        private static HapticCommand Waveform(HapticClip clip, bool amplitude, double strength)
        {
            var values = new int[clip.DurationMs];
            for (int t = 0; t < values.Length; t++)
            {
                double level = 0;
                foreach (var e in clip.Events)
                {
                    int local = t - e.TimeMs;
                    if (local < 0 || local >= e.DurationMs) continue;
                    int sample = e.Continuous ? local / 5 * 5 : 0;
                    double gain = e.Continuous ? 1 : 0.65 + 0.35 * e.Sharpness.At(0);
                    level = Math.Max(level, e.Intensity.At(sample) * gain * strength);
                }
                values[t] = level <= 0 ? 0 : Math.Max(1, (int)Math.Round(level * 255));
            }
            var timings = new List<long>(); var amplitudes = new List<int>();
            foreach (int value in values)
            {
                if (amplitudes.Count > 0 && amplitudes[amplitudes.Count - 1] == value) timings[timings.Count - 1]++;
                else { timings.Add(1); amplitudes.Add(value); }
            }
            var pwm = new List<long> { 0 }; bool on = false; double accumulated = 0;
            for (int t = 0; t < values.Length; t++)
            {
                accumulated += values[t] / 255.0;
                bool next = accumulated >= 1;
                if (next) accumulated -= 1;
                if (next != on) { pwm.Add(0); on = next; }
                pwm[pwm.Count - 1]++;
            }
            return new HapticCommand { tier = amplitude ? "C" : "D", durationMs = clip.DurationMs,
                timings = timings.ToArray(), amplitudes = amplitudes.ToArray(), onOffTimings = pwm.ToArray() };
        }

        private static bool TryComposition(HapticClip clip, HapticsCapabilities caps, double strength, out PrimitiveCommand[] commands)
        {
            var list = new List<PrimitiveCommand>(); int end = 0;
            foreach (var e in clip.Events)
            {
                double sharp = e.Sharpness.At(0);
                int id = sharp >= 0.75 ? 7 : sharp >= 0.5 ? 1 : sharp >= 0.25 ? 8 : 2;
                var capability = caps.Primitive(id);
                if (caps.sdkInt < 31 || capability == null || capability.duration <= 0 || e.TimeMs < end)
                { commands = Array.Empty<PrimitiveCommand>(); return false; }
                list.Add(new PrimitiveCommand { id = id, delayMs = e.TimeMs - end,
                    durationMs = capability.duration, scale = (float)(e.Intensity.At(0) * strength) });
                end = e.TimeMs + capability.duration;
            }
            commands = list.ToArray(); return true;
        }

        private static bool TryEnvelope(HapticClip clip, EnvelopeCapability caps, double strength, out EnvelopePoint[] points)
        {
            points = Array.Empty<EnvelopePoint>();
            if (!caps.supported || caps.minControlPointMs <= 0 || caps.maxControlPointMs < caps.minControlPointMs ||
                clip.DurationMs > caps.maxDuration) return false;
            int minimumSegments = (int)Math.Ceiling((double)clip.DurationMs / caps.maxControlPointMs);
            int maximumSegments = Math.Min(caps.maxSize, clip.DurationMs / caps.minControlPointMs);
            if (minimumSegments > maximumSegments || maximumSegments < 2) return false;
            int segments = Math.Max(minimumSegments, Math.Min(maximumSegments, Math.Max(2, clip.DurationMs / 20)));
            var list = new List<EnvelopePoint> { new EnvelopePoint { intensity = 0, sharpness = (float)clip.Events[0].Sharpness.At(0) } };
            int previous = 0;
            for (int i = 1; i <= segments; i++)
            {
                int t = i * clip.DurationMs / segments; double intensity = 0, sharpness = 0;
                foreach (var e in clip.Events)
                {
                    int local = t - e.TimeMs;
                    if (local < 0 || local > e.DurationMs) continue;
                    double value = e.Intensity.At(local);
                    if (value >= intensity) { intensity = value; sharpness = e.Sharpness.At(local); }
                }
                list.Add(new EnvelopePoint { durationMs = t - previous, intensity = i == segments ? 0 : (float)(intensity * strength), sharpness = (float)sharpness });
                previous = t;
            }
            points = list.ToArray(); return true;
        }
    }
}
