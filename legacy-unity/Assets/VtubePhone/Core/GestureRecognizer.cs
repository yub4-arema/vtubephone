using System;
using System.Collections.Generic;

namespace VtubePhone.Core
{
    public sealed class GestureRecognizer
    {
        private readonly GestureConfig _config;
        private readonly MotionState _state = new MotionState();
        private readonly NeutralDetector _neutral;
        private readonly IMotionDetector[] _detectors;
        private readonly BackKnockDetector _back;
        private readonly GestureArbiter _arbiter;
        private readonly KnockSequencer _knocks;
        private readonly TouchGestureRecognizer _touch;
        private readonly Queue<GestureEvent> _pendingBack = new Queue<GestureEvent>();
        private readonly Queue<long> _touchTimes = new Queue<long>();
        private readonly HashSet<int> _activeTouches = new HashSet<int>();
        private long _lastMotion = -1;
        public event Action<GestureEvent> Detected;
        public MotionVector Gravity => _state.Gravity;
        public MotionVector AngularVelocity => _state.Gyro;
        public double Roll => _state.Roll;
        public double Pitch => _state.Pitch;
        public double LinearRms => _state.LinearWindow.Rms;
        public double BackKnockHighPass => _back.HighPass;
        public bool HasNeutral => _state.HasNeutral;
        public GestureRect AvatarRect { get => _touch.AvatarRect; set => _touch.AvatarRect = value; }
        public int DiscardedSamples { get; private set; }
        public GestureRecognizer(GestureConfig config = null, GestureRect avatarRect = default)
        {
            _config = config ?? new GestureConfig(); _config.Validate();
            _neutral = new NeutralDetector(_config); _back = new BackKnockDetector(_config);
            _detectors = new IMotionDetector[] { new ShakeDetector(_config), new FlipDetector(_config), new OscillationDetector(_config, false),
                new OscillationDetector(_config, true), new TiltDetector(_config), new StillPickupDetector(_config), _back };
            _arbiter = new GestureArbiter(_config); _arbiter.Detected += value => Detected?.Invoke(value);
            _knocks = new KnockSequencer(_config); _knocks.Detected += value => Detected?.Invoke(value);
            _touch = new TouchGestureRecognizer(_config, avatarRect);
            _touch.Detected += value => { Detected?.Invoke(value); if (value.Id == GestureId.Tap) _knocks.Add(value.TNs); };
        }
        public void Push(MotionSample sample)
        {
            if (sample.TNs < _lastMotion || sample.TNs < 0 || !Finite(sample.Value.X) || !Finite(sample.Value.Y) || !Finite(sample.Value.Z) || !Finite(sample.W))
            { DiscardedSamples++; return; }
            _lastMotion = sample.TNs;
            switch (sample.Kind)
            {
                case MotionSensorKind.Gravity: _state.Gravity = sample.Value; _state.GravityNs = sample.TNs; break;
                case MotionSensorKind.Gyroscope: _state.Gyro = sample.Value; _state.GyroNs = sample.TNs; break;
                case MotionSensorKind.LinearAcceleration:
                    _state.Linear = sample.Value; _state.LinearNs = sample.TNs; _state.LinearWindow.Add(sample, _config.ShakeWindowMs); break;
            }
            _neutral.Push(sample, _state);
            foreach (var detector in _detectors) detector.Push(sample, _state, Candidate);
        }
        private static bool Finite(double value) => !double.IsNaN(value) && !double.IsInfinity(value);
        private void Candidate(GestureEvent value)
        {
            if (value.Id == GestureId.BackKnock) { _pendingBack.Enqueue(value); return; }
            _arbiter.Add(value);
        }
        public void Push(TouchSample sample)
        {
            _touchTimes.Enqueue(sample.TNs);
            if (sample.Phase == TouchPhase.Down) _activeTouches.Add(sample.FingerId);
            if (sample.Phase == TouchPhase.Up || sample.Phase == TouchPhase.Cancel) _activeTouches.Remove(sample.FingerId);
            _touch.Push(sample);
        }
        // Call after merging this frame's motion and touch events by timestamp, never between raw samples.
        public void Advance(long now)
        {
            long guard = GestureConfig.Ns(_config.TouchSuppressionMs);
            while (_pendingBack.Count > 0 && now - _pendingBack.Peek().TNs >= guard)
            {
                var value = _pendingBack.Dequeue(); bool blocked = _activeTouches.Count > 0;
                foreach (long time in _touchTimes) if (Math.Abs(time - value.TNs) <= guard) blocked = true;
                if (!blocked && _state.LinearWindow.Rms <= _config.BackKnockMaxRms && now >= _state.SuppressedUntil)
                { Detected?.Invoke(value); _knocks.Add(value.TNs); }
            }
            while (_touchTimes.Count > 0 && now - _touchTimes.Peek() > guard * 3) _touchTimes.Dequeue();
            _touch.Advance(now); _knocks.Advance(now - guard); _arbiter.Advance(now);
        }
    }
}
