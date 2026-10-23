using System;
using System.Collections.Generic;
using System.Diagnostics;
using UnityEngine;
using VtubePhone.Core;

namespace VtubePhone.Runtime
{
    public sealed class HapticsService : IDisposable
    {
        private readonly IHapticsBackend _backend;
        private readonly Dictionary<string, HapticClip> _clips = new Dictionary<string, HapticClip>();
        private readonly Dictionary<string, string> _descriptions = new Dictionary<string, string>();
        private bool _suspended;
        public HapticsCapabilities Capabilities { get; }
        public string CapabilityReport { get; }
        public HapticTier Tier { get; private set; }
        public HapticsRateLimiter Limiter { get; } = new HapticsRateLimiter();
        public event Action<string> Logged;
        public bool BlindMode { get; set; }
        public double LastCallMicroseconds { get; private set; }
        public long LastNativeNs { get; private set; }

        public HapticsService(IHapticsBackend backend, IEnumerable<HapticClip> clips)
        {
            _backend = backend;
            CapabilityReport = backend.GetCapabilities();
            Capabilities = JsonUtility.FromJson<HapticsCapabilities>(CapabilityReport);
            Tier = Capabilities.hasVibrator ? Capabilities.BestTier : HapticTier.D;
            foreach (var clip in clips) _clips.Add(clip.Id, clip);
            RebuildCache();
            UnityEngine.Debug.Log(CapabilityReport);
        }
        private void Log(string message) { Logged?.Invoke(message); UnityEngine.Debug.Log(message); }
        public bool SetTier(HapticTier tier)
        {
            if (!Capabilities.Supports(tier)) return false;
            _backend.Cancel(); Tier = tier; RebuildCache(); return true;
        }
        public void SetStrength(double value)
        {
            Limiter.Strength = value; _backend.Cancel(); RebuildCache();
        }
        public void SetEnabled(bool value) { Limiter.Enabled = value; if (!value) _backend.Cancel(); }
        public void Suspend(bool suspended)
        {
            _suspended = suspended;
            if (suspended) _backend.Cancel();
        }
        private string Prepare(string key, HapticClip clip)
        {
            var command = HapticRenderer.Render(clip, Tier, Capabilities, Limiter.Strength);
            string status = _backend.Prepare(key, JsonUtility.ToJson(command));
            return $"requested {Tier}, rendered {command.tier}, native {status}: {command.reason}";
        }
        private void RebuildCache()
        {
            _backend.ClearCache(); _descriptions.Clear();
            if (!Capabilities.hasVibrator) return;
            foreach (var pair in _clips) _descriptions[pair.Key] = Prepare(pair.Key, pair.Value);
            for (int count = 1; count <= 4; count++)
                _descriptions["knock" + count] = Prepare("knock" + count, HapticClipFactory.Knock(count));
        }
        public bool Play(HapticClipId id, bool spontaneous = false) => PlayKey(id.ToString().ToLowerInvariant(), spontaneous);
        public bool PlayKnock(int count)
        {
            if (count < 1 || count > 4) throw new ArgumentOutOfRangeException(nameof(count));
            return PlayKey("knock" + count, false);
        }
        private bool Allowed(bool spontaneous) => !_suspended && Capabilities.hasVibrator &&
            Limiter.TryAcquire(Time.realtimeSinceStartupAsDouble, spontaneous);
        private bool PlayKey(string key, bool spontaneous)
        {
            long start = Stopwatch.GetTimestamp();
            if (!Allowed(spontaneous)) { Log("Playback blocked by safety settings or device capability"); return false; }
            bool played;
            try { played = _backend.Play(key); }
            catch (Exception e) { Log("Playback failed: " + e.Message); return false; }
            LastCallMicroseconds = (Stopwatch.GetTimestamp() - start) * 1000000.0 / Stopwatch.Frequency;
            LastNativeNs = _backend.LastVibrateNs;
            Log($"{(BlindMode ? "Blind sample" : key)}: call → vibrate return ≤ {LastCallMicroseconds:F0} μs; native {LastNativeNs} ns; " +
                (BlindMode ? "" : _descriptions[key]) + (played ? "" : " (muted / silent)"));
            return played;
        }
        public bool PlayBump(double impact)
        {
            long start = Stopwatch.GetTimestamp();
            var clip = HapticClipFactory.Bump(impact);
            if (!Allowed(false)) { Log("Playback blocked by safety settings or device capability"); return false; }
            var command = HapticRenderer.Render(clip, Tier, Capabilities, Limiter.Strength);
            bool played;
            try { played = _backend.PlayOnce(JsonUtility.ToJson(command)); }
            catch (Exception e) { Log("Playback failed: " + e.Message); return false; }
            LastCallMicroseconds = (Stopwatch.GetTimestamp() - start) * 1000000.0 / Stopwatch.Frequency;
            LastNativeNs = _backend.LastVibrateNs;
            Log($"bump (includes construction): ≤ {LastCallMicroseconds:F0} μs; native {LastNativeNs} ns; rendered {command.tier}");
            return played;
        }
        public void Dispose() => _backend.Dispose();
    }
}
