using System;
using System.IO;
using System.Linq;
using NUnit.Framework;
using VtubePhone.Core;

namespace VtubePhone.Tests
{
    public sealed class HapticsTests
    {
        private static HapticsCapabilities Device() => new HapticsCapabilities {
            sdkInt = 36, hasVibrator = true, hasAmplitudeControl = true,
            primitives = Enumerable.Range(1, 8).Select(id => new PrimitiveCapability { id = id, supported = true, duration = 12 }).ToArray(),
            envelope = new EnvelopeCapability { supported = true, maxSize = 64, minControlPointMs = 10, maxControlPointMs = 100, maxDuration = 6400 }
        };
        private static HapticsCapabilities DeviceWithRealisticDurations(int thudMs = 150)
        {
            var device = Device();
            // Representative mock durations, not a guarantee for every device.
            device.primitives.Single(p => p.id == 1).duration = 20; // CLICK
            device.primitives.Single(p => p.id == 2).duration = thudMs; // THUD
            device.primitives.Single(p => p.id == 7).duration = 20; // TICK
            device.primitives.Single(p => p.id == 8).duration = 40; // LOW_TICK
            return device;
        }
        private static string ClipsPath
        {
            get
            {
#if UNITY_EDITOR
                return Path.Combine(UnityEngine.Application.dataPath, "VtubePhone/Resources/HapticClips");
#else
                return Path.Combine(TestContext.CurrentContext.TestDirectory, "HapticClips");
#endif
            }
        }
        [TestCase(1)] [TestCase(2)] [TestCase(3)] [TestCase(4)]
        public void KnockHasExactCountAndStartTimes(int count)
        {
            var clip = HapticClipFactory.Knock(count);
            Assert.That(clip.Events.Count, Is.EqualTo(count));
            Assert.That(clip.Events.Select(e => e.TimeMs), Is.EqualTo(Enumerable.Range(0, count).Select(i => i * 170)));
            Assert.That(clip.Events.All(e => !e.Continuous), Is.True);
        }
        [Test] public void FactoryRejectsInvalidCountAndNonfiniteImpact()
        {
            Assert.Throws<ArgumentOutOfRangeException>(() => HapticClipFactory.Knock(0));
            Assert.Throws<ArgumentOutOfRangeException>(() => HapticClipFactory.Knock(5));
            Assert.Throws<ArgumentOutOfRangeException>(() => HapticClipFactory.Bump(double.NaN));
        }
        [Test] public void BumpStrengthIncreasesAndSharpnessDecreases()
        {
            double intensity = -1, sharpness = 1;
            for (int i = 0; i <= 100; i++)
            {
                var e = HapticClipFactory.Bump(i / 100.0).Events[0];
                Assert.That(e.Intensity.At(0), Is.GreaterThanOrEqualTo(intensity));
                Assert.That(e.Sharpness.At(0), Is.LessThanOrEqualTo(sharpness));
                intensity = e.Intensity.At(0); sharpness = e.Sharpness.At(0);
            }
        }
        [Test] public void AllTwelveResourcesParseAndWaveformsPreserveDuration()
        {
            var paths = Directory.GetFiles(ClipsPath, "*.json"); Assert.That(paths.Length, Is.EqualTo(12));
            foreach (var path in paths)
            {
                var clip = HapticClipParser.Parse(File.ReadAllText(path));
                Assert.That(clip.Id, Is.EqualTo(Path.GetFileNameWithoutExtension(path)));
                foreach (var tier in new[] { HapticTier.A, HapticTier.B, HapticTier.C, HapticTier.D })
                {
                    var command = HapticRenderer.Render(clip, tier, Device());
                    Assert.That(command.timings.Sum(), Is.EqualTo(clip.DurationMs));
                    Assert.That(command.onOffTimings.Sum(), Is.EqualTo(clip.DurationMs));
                    Assert.That(command.timings.Length, Is.EqualTo(command.amplitudes.Length));
                    Assert.That(command.amplitudes.All(a => a == 0 || a >= 1 && a <= 255), Is.True);
                }
            }
        }
        [Test] public void CurvesInterpolateAndContinuousSamplesEveryFiveMilliseconds()
        {
            var curve = new HapticParameter(new[] { new[] { 0.0, 0.0 }, new[] { 20.0, 1.0 } });
            Assert.That(curve.At(10), Is.EqualTo(.5));
            var clip = new HapticClip("curve", new[] { new HapticEvent(0, true, 20, curve, new HapticParameter(.2)) });
            var c = HapticRenderer.Render(clip, HapticTier.C, Device());
            Assert.That(c.timings, Is.EqualTo(new long[] { 5, 5, 5, 5 }));
            Assert.That(c.amplitudes, Is.EqualTo(new[] { 0, 64, 128, 191 }));
        }
        [Test] public void SharpTransientsAreShorterAndStronger()
        {
            var dull = HapticRenderer.Render(HapticClipFactory.Bump(1), HapticTier.C, Device());
            var sharpClip = new HapticClip("sharp", new[] { new HapticEvent(0, false, 0, new HapticParameter(1), new HapticParameter(1)) });
            var sharp = HapticRenderer.Render(sharpClip, HapticTier.C, Device());
            Assert.That(sharp.durationMs, Is.LessThan(dull.durationMs));
            Assert.That(sharp.amplitudes.Max(), Is.GreaterThan(dull.amplitudes.Max()));
        }
        [Test] public void EnvelopeHonorsStartEndAndAllDeviceConstraints()
        {
            var device = Device(); var clip = HapticClipParser.Parse(File.ReadAllText(Path.Combine(ClipsPath, "dizzy.json")));
            var c = HapticRenderer.Render(clip, HapticTier.A, device);
            Assert.That(c.tier, Is.EqualTo("A"));
            Assert.That(c.points.First().intensity, Is.Zero); Assert.That(c.points.Last().intensity, Is.Zero);
            Assert.That(c.points.Length - 1, Is.LessThanOrEqualTo(device.envelope.maxSize));
            Assert.That(c.points.Skip(1).All(p => p.durationMs >= 10 && p.durationMs <= 100 && p.intensity >= 0 && p.intensity <= 1 && p.sharpness >= 0 && p.sharpness <= 1), Is.True);
            Assert.That(c.points.Sum(p => p.durationMs), Is.EqualTo(clip.DurationMs));
            device.envelope.maxSize = 2;
            Assert.That(HapticRenderer.Render(clip, HapticTier.A, device).tier, Is.EqualTo("C"));
            device.envelope.maxDuration = 100;
            Assert.That(HapticRenderer.Render(clip, HapticTier.A, device).tier, Is.EqualTo("C"));
        }
        [Test] public void EnvelopeSupportsNonDivisibleDuration()
        {
            var clip = new HapticClip("odd", new[] { new HapticEvent(0, true, 101, new HapticParameter(.6), new HapticParameter(.2)) });
            var c = HapticRenderer.Render(clip, HapticTier.A, Device());
            Assert.That(c.tier, Is.EqualTo("A")); Assert.That(c.points.Sum(p => p.durationMs), Is.EqualTo(101));
        }
        [Test] public void CompositionCompensatesPrimitiveDurationAndMapsSharpness()
        {
            var clip = new HapticClip("mapping", new[] { 1.0, .6, .3, .1 }.Select((s, i) => new HapticEvent(i * 100, false, 0, new HapticParameter(.7), new HapticParameter(s))));
            var c = HapticRenderer.Render(clip, HapticTier.B, Device());
            Assert.That(c.tier, Is.EqualTo("B"));
            Assert.That(c.primitives.Select(p => p.id), Is.EqualTo(new[] { 7, 1, 8, 2 }));
            Assert.That(c.primitives.Select(p => p.delayMs), Is.EqualTo(new[] { 0, 88, 88, 88 }));
            Assert.That(c.durationMs, Is.EqualTo(312));
        }
        [Test] public void UnsupportedUnknownAndOverlappingPrimitivesFallBack()
        {
            var device = Device(); var clip = HapticClipFactory.Knock(2);
            device.primitives.Single(p => p.id == 8).supported = false;
            Assert.That(HapticRenderer.Render(clip, HapticTier.B, device).tier, Is.EqualTo("C"));
            device = Device(); device.sdkInt = 30;
            Assert.That(HapticRenderer.Render(clip, HapticTier.B, device).tier, Is.EqualTo("C"));
            device = Device(); device.primitives.Single(p => p.id == 8).duration = 200;
            Assert.That(HapticRenderer.Render(clip, HapticTier.B, device).tier, Is.EqualTo("C"));
            device.hasAmplitudeControl = false;
            Assert.That(HapticRenderer.Render(clip, HapticTier.B, device).tier, Is.EqualTo("D"));
        }
        [Test] public void MixedEventsAlwaysUseOneWaveform()
        {
            var clip = new HapticClip("mixed", new[] {
                new HapticEvent(0, false, 0, new HapticParameter(1), new HapticParameter(.1)),
                new HapticEvent(25, true, 180, new HapticParameter(.65), new HapticParameter(.1))
            });
            foreach (var tier in new[] { HapticTier.A, HapticTier.B })
            {
                var c = HapticRenderer.Render(clip, tier, Device());
                Assert.That(c.tier, Is.EqualTo("C")); Assert.That(c.primitives, Is.Empty); Assert.That(c.points, Is.Empty);
            }
        }
        [TestCase(60)] [TestCase(100)] [TestCase(150)]
        public void KeyPhrasesUseCompositionWithRealisticPrimitiveDurations(int thudMs)
        {
            var device = DeviceWithRealisticDurations(thudMs);
            device.sdkInt = 31; device.envelope.supported = false;
            var clips = new[] { "thud", "knock", "yes", "call" }
                .Select(id => HapticClipParser.Parse(File.ReadAllText(Path.Combine(ClipsPath, id + ".json"))))
                .Concat(Enumerable.Range(1, 4).Select(count => HapticClipFactory.Knock(count)));
            foreach (var clip in clips)
            {
                var command = HapticRenderer.Render(clip, device.BestTier, device);
                Assert.That(command.tier, Is.EqualTo("B"), clip.Id);
                Assert.That(command.primitives.Length, Is.EqualTo(clip.Events.Count), clip.Id);
                int end = 0;
                for (int i = 0; i < command.primitives.Length; i++)
                {
                    var primitive = command.primitives[i];
                    Assert.That(primitive.delayMs, Is.GreaterThanOrEqualTo(0), clip.Id);
                    Assert.That(end + primitive.delayMs, Is.EqualTo(clip.Events[i].TimeMs), clip.Id);
                    end += primitive.delayMs + primitive.durationMs;
                }
                Assert.That(command.durationMs, Is.EqualTo(end), clip.Id);
            }
        }
        [Test] public void ThudUsesOneHeavyStrikeAndThreeDecayingLowTicks()
        {
            var clip = HapticClipParser.Parse(File.ReadAllText(Path.Combine(ClipsPath, "thud.json")));
            var command = HapticRenderer.Render(clip, HapticTier.B, DeviceWithRealisticDurations());
            Assert.That(clip.Events.All(e => !e.Continuous), Is.True);
            Assert.That(command.tier, Is.EqualTo("B"));
            Assert.That(command.primitives.Select(p => p.id), Is.EqualTo(new[] { 2, 8, 8, 8 }));
            Assert.That(command.primitives[0].scale, Is.EqualTo(1));
            Assert.That(command.primitives.Skip(1).All(p => p.delayMs >= 50), Is.True);
            for (int i = 1; i < command.primitives.Length; i++)
                Assert.That(command.primitives[i].scale, Is.LessThan(command.primitives[i - 1].scale));
        }
        [TestCase(HapticTier.A)] [TestCase(HapticTier.B)] [TestCase(HapticTier.C)]
        public void ResourceTiersMatchDocumentedDeviceMatrix(HapticTier tier)
        {
            var device = DeviceWithRealisticDurations();
            if (tier != HapticTier.A) device.envelope.supported = false;
            if (tier == HapticTier.B) device.sdkInt = 31;
            if (tier == HapticTier.C) { device.sdkInt = 26; device.primitives = Array.Empty<PrimitiveCapability>(); }
            Assert.That(device.BestTier, Is.EqualTo(tier));
            foreach (var path in Directory.GetFiles(ClipsPath, "*.json"))
            {
                var clip = HapticClipParser.Parse(File.ReadAllText(path));
                bool continuous = new[] { "no", "purr", "dizzy" }.Contains(clip.Id);
                string expected = tier == HapticTier.C ? "C" : continuous ? (tier == HapticTier.A ? "A" : "C") : "B";
                Assert.That(HapticRenderer.Render(clip, device.BestTier, device).tier, Is.EqualTo(expected), clip.Id);
            }
        }
        [Test] public void PwmDutyAndStrengthAreMonotonic()
        {
            var clip = new HapticClip("pwm", new[] { new HapticEvent(0, true, 100, new HapticParameter(1), new HapticParameter(.2)) });
            long previous = -1;
            for (int i = 0; i <= 10; i++)
            {
                var c = HapticRenderer.Render(clip, HapticTier.D, Device(), i / 10.0);
                long on = c.onOffTimings.Where((_, index) => index % 2 == 1).Sum();
                Assert.That(on, Is.InRange(Math.Max(0, i * 10 - 1), i * 10)); Assert.That(on, Is.GreaterThanOrEqualTo(previous)); previous = on;
            }
        }
        [Test] public void LimiterEnforcesRollingWindowOffZeroStrengthAndCooldown()
        {
            var limiter = new HapticsRateLimiter { MaxPerMinute = 2 };
            Assert.That(limiter.TryAcquire(0, true), Is.True);
            Assert.That(limiter.TryAcquire(1, true), Is.False);
            Assert.That(limiter.TryAcquire(2, true), Is.True);
            Assert.That(limiter.TryAcquire(59), Is.False);
            Assert.That(limiter.TryAcquire(60), Is.True);
            limiter.Enabled = false; Assert.That(limiter.TryAcquire(120), Is.False);
            limiter.Enabled = true; limiter.Strength = 0; Assert.That(limiter.TryAcquire(120), Is.False);
            limiter.Strength = .5; Assert.That(limiter.TryAcquire(120), Is.True);
            var c = HapticRenderer.Render(HapticClipFactory.Knock(1), HapticTier.B, Device(), limiter.Strength);
            Assert.That(c.primitives[0].scale, Is.EqualTo(.45).Within(.0001));
        }
        [Test] public void YesAndNoHaveDistinctTempoAndDuration()
        {
            var yes = HapticClipParser.Parse(File.ReadAllText(Path.Combine(ClipsPath, "yes.json")));
            var no = HapticClipParser.Parse(File.ReadAllText(Path.Combine(ClipsPath, "no.json")));
            Assert.That(yes.Events.Count, Is.EqualTo(2)); Assert.That(yes.Events.All(e => !e.Continuous), Is.True);
            Assert.That(no.Events.Single().Continuous, Is.True); Assert.That(no.DurationMs, Is.GreaterThan(yes.DurationMs * 2));
            Assert.That(yes.Events.Min(e => e.Sharpness.At(0)), Is.GreaterThan(.8));
            Assert.That(no.Events.Single().Sharpness.At(0), Is.LessThan(.1));
        }
        [Test] public void ParserRejectsMalformedOrUnsafeValues()
        {
            foreach (string json in new[] { "{}", "{", "{\"id\":\"bad\",\"events\":[]}", "{\"id\":\"bad\",\"events\":[{\"t\":0,\"type\":\"unknown\",\"intensity\":1,\"sharpness\":0}]}" })
                Assert.Catch(() => HapticClipParser.Parse(json));
            Assert.Throws<ArgumentException>(() => new HapticParameter(1.1));
            Assert.Throws<ArgumentException>(() => new HapticParameter(double.NaN));
        }
        [Test] public void BlindTestCountsOneAnswerPerSuccessfullyStartedSample()
        {
            var test = new HapticsBlindTest();
            Assert.That(test.Answer(true), Is.False);
            Assert.That(test.Begin(true), Is.True);
            Assert.That(test.Begin(false), Is.False);
            Assert.That(test.Answer(true), Is.True);
            Assert.That(test.Answer(true), Is.False);
            test.Begin(false); test.Answer(true);
            Assert.That(test.Total, Is.EqualTo(2)); Assert.That(test.Correct, Is.EqualTo(1));
            Assert.That(test.Accuracy, Is.EqualTo(.5));
            test.Begin(true); test.Cancel(); Assert.That(test.Answer(true), Is.False);
            Assert.That(test.Total, Is.EqualTo(2));
        }
        [Test] public void DeviceTierSelectionNeverEnablesUnsupportedTiers()
        {
            var device = Device(); Assert.That(device.BestTier, Is.EqualTo(HapticTier.A));
            device.envelope.supported = false; Assert.That(device.BestTier, Is.EqualTo(HapticTier.B));
            device.primitives = Array.Empty<PrimitiveCapability>(); Assert.That(device.BestTier, Is.EqualTo(HapticTier.C));
            device.hasAmplitudeControl = false; Assert.That(device.BestTier, Is.EqualTo(HapticTier.D));
            device.hasVibrator = false;
            Assert.That(device.BestTier, Is.EqualTo(HapticTier.D));
            Assert.That(Enum.GetValues(typeof(HapticTier)).Cast<HapticTier>().Any(device.Supports), Is.False);
        }
    }
}
