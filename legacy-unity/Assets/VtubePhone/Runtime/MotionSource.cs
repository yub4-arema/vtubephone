using System;
using System.Collections.Generic;
using System.Text;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;
using VtubePhone.Core;

namespace VtubePhone.Runtime
{
    public interface IMotionSource : IDisposable
    {
        long NowNs { get; }
        string CapabilityReport { get; }
        void Start();
        void Stop();
        void Drain(List<MotionSample> samples);
    }
    public sealed class AndroidMotionSource : IMotionSource
    {
        private readonly AndroidJavaObject _sensor;
        public long NowNs { get; private set; }
        public string CapabilityReport { get; private set; } = "Waiting for native sensors";
        public AndroidMotionSource()
        {
            using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
                _sensor = new AndroidJavaObject("com.vtubephone.sensing.MotionSensor", activity);
        }
        public void Start() => _sensor.Call("start");
        public void Stop() => _sensor.Call("stop");
        public void Drain(List<MotionSample> samples)
        {
            var batch = _sensor.Call<double[]>("drain");
            if (batch == null || batch.Length < 22 || (batch.Length - 22) % 6 != 0) throw new InvalidOperationException("Invalid native motion batch");
            NowNs = (long)batch[0];
            var report = new StringBuilder("SENSOR_DELAY_FASTEST / dropped: " + (long)batch[1]);
            for (int i = 2; i < 22; i += 4)
                report.Append($"\n{(MotionSensorKind)(int)batch[i]}: {(batch[i + 1] == 0 ? "MISSING" : batch[i + 2] == 0 ? "REGISTRATION FAILED" : "active")} / {batch[i + 3]:F1} Hz");
            CapabilityReport = report.ToString();
            for (int i = 22; i < batch.Length; i += 6)
                samples.Add(new MotionSample((long)batch[i], (MotionSensorKind)(int)batch[i + 1], batch[i + 2], batch[i + 3], batch[i + 4], batch[i + 5]));
        }
        public void Dispose() { Stop(); _sensor.Dispose(); }
    }
    public sealed class SimulatedMotionSource : IMotionSource
    {
        private bool _running;
        private long _nextNs, _knockNs = -1;
        public long NowNs { get; private set; }
        public string CapabilityReport => "EDITOR SIMULATION / five sensors at 100 Hz\nArrows: tilt / N: nod / H: yaw / Space: shake (Shift: hard)\nF: face down / P: pickup / B: back knock / mouse: touch";
        public void Start() { _running = true; _nextNs = (long)(InputState.currentTime * 1e9); }
        public void Stop() { _running = false; _knockNs = -1; }
        public void Drain(List<MotionSample> samples)
        {
            NowNs = (long)(InputState.currentTime * 1e9);
            if (!_running) return;
            var keys = Keyboard.current;
            if (keys != null && keys.bKey.wasPressedThisFrame) _knockNs = _nextNs;
            // Limit catch-up after a debugger stall; never fabricate seconds of old input.
            if (NowNs - _nextNs > 250000000) _nextNs = NowNs;
            for (; _nextNs <= NowNs; _nextNs += 10000000)
            {
                double t = _nextNs / 1e9, roll = 0, pitch = 0;
                if (keys != null) { roll = (keys.leftArrowKey.isPressed ? 0.5 : 0) - (keys.rightArrowKey.isPressed ? 0.5 : 0); pitch = (keys.upArrowKey.isPressed ? 0.5 : 0) - (keys.downArrowKey.isPressed ? 0.5 : 0); }
                var gravity = new MotionVector(Math.Sin(roll) * 9.81, Math.Sin(pitch) * 9.81, Math.Cos(roll) * Math.Cos(pitch) * 9.81);
                if (keys != null && keys.fKey.isPressed) gravity = new MotionVector(0, 0, -9.81);
                double oscillation = Math.Sin(t * Math.PI * 6) * 2.8;
                var gyro = keys != null && keys.nKey.isPressed ? new MotionVector(oscillation, 0, 0) :
                    keys != null && keys.hKey.isPressed ? gravity.Normalized * oscillation : new MotionVector();
                double shake = keys != null && keys.spaceKey.isPressed ? Math.Sin(t * Math.PI * 12) * (keys.shiftKey.isPressed ? 22 : 7) : 0;
                var linear = new MotionVector(shake, keys != null && keys.pKey.isPressed ? 2 : 0, 0);
                var acceleration = gravity + linear;
                if (_nextNs >= _knockNs && _nextNs - _knockNs < 10000000 && _knockNs >= 0) acceleration += new MotionVector(0, 0, 10);
                Add(samples, MotionSensorKind.Gravity, gravity); Add(samples, MotionSensorKind.LinearAcceleration, linear);
                Add(samples, MotionSensorKind.Gyroscope, gyro); Add(samples, MotionSensorKind.Accelerometer, acceleration);
                samples.Add(new MotionSample(_nextNs, MotionSensorKind.GameRotationVector, 0, 0, 0, 1));
            }
        }
        private void Add(List<MotionSample> samples, MotionSensorKind kind, MotionVector value) => samples.Add(new MotionSample(_nextNs, kind, value.X, value.Y, value.Z));
        public void Dispose() => Stop();
    }
}
