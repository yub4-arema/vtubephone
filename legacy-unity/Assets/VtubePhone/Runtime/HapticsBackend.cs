using System;
using UnityEngine;
using VtubePhone.Core;

namespace VtubePhone.Runtime
{
    public interface IHapticsBackend : IDisposable
    {
        string GetCapabilities();
        string Prepare(string key, string commandJson);
        bool Play(string key);
        bool PlayOnce(string commandJson);
        long LastVibrateNs { get; }
        void Cancel();
        void ClearCache();
    }

    public sealed class AndroidHapticsBackend : IHapticsBackend
    {
        private readonly AndroidJavaObject _engine;
        public AndroidHapticsBackend()
        {
            using var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
            using var activity = player.GetStatic<AndroidJavaObject>("currentActivity");
            _engine = new AndroidJavaObject("com.vtubephone.haptics.HapticEngine", activity);
        }
        public string GetCapabilities() => _engine.Call<string>("getCapabilities");
        public string Prepare(string key, string commandJson) => _engine.Call<string>("prepare", key, commandJson);
        public bool Play(string key) => _engine.Call<bool>("play", key);
        public bool PlayOnce(string commandJson) => _engine.Call<bool>("playOnce", commandJson);
        public long LastVibrateNs => _engine.Call<long>("getLastVibrateNs");
        public void Cancel() => _engine.Call("cancel");
        public void ClearCache() => _engine.Call("clearCache");
        public void Dispose() { Cancel(); _engine.Dispose(); }
    }

    public sealed class EditorHapticsBackend : IHapticsBackend
    {
        public string GetCapabilities() => JsonUtility.ToJson(new HapticsCapabilities {
            sdkInt = 36, hasVibrator = true, hasAmplitudeControl = true, manufacturer = "Editor", model = "Simulation (no vibration)",
            primitives = new[] { 1, 2, 3, 4, 5, 6, 7, 8 }.MapPrimitives(),
            envelope = new EnvelopeCapability { supported = true, maxSize = 64, minControlPointMs = 10, maxControlPointMs = 1000, maxDuration = 64000 }
        });
        public string Prepare(string key, string commandJson) => "Editor simulated";
        public bool Play(string key) { Debug.Log("[Haptics simulated] cached effect"); return true; }
        public bool PlayOnce(string commandJson) { Debug.Log("[Haptics simulated] dynamic waveform"); return true; }
        public long LastVibrateNs => 0;
        public void Cancel() { }
        public void ClearCache() { }
        public void Dispose() { }
    }
    internal static class EditorCapabilities
    {
        public static PrimitiveCapability[] MapPrimitives(this int[] ids) => Array.ConvertAll(ids,
            id => new PrimitiveCapability { id = id, supported = true, duration = 12 });
    }
}
