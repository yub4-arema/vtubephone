using System;
using System.Collections.Generic;

namespace VtubePhone.Core
{
    internal sealed class VectorWindow
    {
        private readonly Queue<MotionSample> _samples = new Queue<MotionSample>();
        public double Rms { get; private set; }
        public double Peak { get; private set; }
        public double Variance { get; private set; }
        public long SpanNs { get; private set; }
        public void Add(MotionSample sample, double windowMs)
        {
            _samples.Enqueue(sample);
            while (_samples.Count > 1 && sample.TNs - _samples.Peek().TNs > GestureConfig.Ns(windowMs)) _samples.Dequeue();
            MotionVector mean = new MotionVector(); double square = 0; Peak = 0;
            foreach (var item in _samples) { mean += item.Value; double length = item.Value.Length; square += length * length; Peak = Math.Max(Peak, length); }
            mean *= 1.0 / _samples.Count;
            Rms = Math.Sqrt(square / _samples.Count); Variance = Math.Max(0, square / _samples.Count - mean.Length * mean.Length);
            SpanNs = sample.TNs - _samples.Peek().TNs;
        }
    }
    internal sealed class MotionState
    {
        public MotionVector Gravity, Gyro, Linear, Neutral;
        public bool HasNeutral;
        public double Roll, Pitch;
        public long GravityNs = -1, GyroNs = -1, LinearNs = -1;
        public long SuppressedUntil;
        public readonly VectorWindow LinearWindow = new VectorWindow();
        public bool Quiet(GestureConfig c) => Linear.Length < c.QuietAcceleration && Gyro.Length < c.QuietAngularSpeed;
        public bool Fresh(long now, GestureConfig c) => GravityNs >= 0 && now - GravityNs <= GestureConfig.Ns(c.SampleStaleMs) &&
            (GyroNs < 0 || now - GyroNs <= GestureConfig.Ns(c.SampleStaleMs)) && (LinearNs < 0 || now - LinearNs <= GestureConfig.Ns(c.SampleStaleMs));
    }
    internal interface IMotionDetector { void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit); }

    internal sealed class NeutralDetector
    {
        private readonly GestureConfig _config;
        private long _quietStart = -1, _lastNs = -1;
        private MotionVector _average;
        public NeutralDetector(GestureConfig config) { _config = config; }
        public void Push(MotionSample sample, MotionState state)
        {
            if (sample.Kind != MotionSensorKind.Gravity || sample.Value.Length < 1) return;
            double dt = _lastNs < 0 ? 0 : (sample.TNs - _lastNs) / 1000000.0; _lastNs = sample.TNs;
            var gravity = sample.Value.Normalized;
            if (!state.Quiet(_config) || dt > _config.MaxSampleGapMs) _quietStart = -1;
            else
            {
                if (_quietStart < 0) { _quietStart = sample.TNs; _average = gravity; }
                double angle = state.HasNeutral ? Math.Acos(Math.Max(-1, Math.Min(1, MotionVector.Dot(gravity, state.Neutral)))) * 180 / Math.PI : 0;
                // Adapt only inside the return zone, so a held choice cannot erase itself.
                if (!state.HasNeutral || angle < _config.NeutralAdaptDegrees)
                {
                    _average = (_average * Math.Exp(-dt / _config.NeutralAverageMs) + gravity * (1 - Math.Exp(-dt / _config.NeutralAverageMs))).Normalized;
                    if (sample.TNs - _quietStart >= GestureConfig.Ns(_config.NeutralSettleMs)) { state.Neutral = _average; state.HasNeutral = true; }
                }
            }
            if (!state.HasNeutral) return;
            var x = new MotionVector(1, 0, 0) - state.Neutral * state.Neutral.X;
            if (x.Length < 0.1) x = new MotionVector(0, 0, 1) - state.Neutral * state.Neutral.Z;
            x = x.Normalized;
            var y = MotionVector.Cross(state.Neutral, x).Normalized;
            double normal = MotionVector.Dot(gravity, state.Neutral);
            state.Roll = Math.Atan2(MotionVector.Dot(gravity, x), normal) * 180 / Math.PI;
            state.Pitch = Math.Atan2(MotionVector.Dot(gravity, y), normal) * 180 / Math.PI;
        }
    }
    internal sealed class TiltDetector : IMotionDetector
    {
        private readonly GestureConfig _config;
        private long _start = -1;
        private GestureId _id;
        private bool _latched;
        public TiltDetector(GestureConfig config) { _config = config; }
        public void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit)
        {
            if (sample.Kind != MotionSensorKind.Gravity) return;
            double angle = Math.Max(Math.Abs(state.Roll), Math.Abs(state.Pitch));
            if (!state.HasNeutral || sample.TNs < state.SuppressedUntil || !state.Fresh(sample.TNs, _config) || state.Gyro.Length > _config.OscillationSpeed)
            { _start = -1; return; }
            if (angle < _config.TiltReleaseDegrees) { _latched = false; _start = -1; }
            if (_latched || angle < _config.TiltDegrees) { _start = -1; return; }
            GestureId id = Math.Abs(state.Roll) > Math.Abs(state.Pitch) ? (state.Roll > 0 ? GestureId.TiltLeft : GestureId.TiltRight) :
                (state.Pitch > 0 ? GestureId.TiltForward : GestureId.TiltBack);
            if (_start < 0 || _id != id) { _start = sample.TNs; _id = id; }
            if (sample.TNs - _start < GestureConfig.Ns(_config.TiltHoldMs)) return;
            _latched = true;
            var direction = id == GestureId.TiltLeft ? GestureDirection.Left : id == GestureId.TiltRight ? GestureDirection.Right :
                id == GestureId.TiltForward ? GestureDirection.Forward : GestureDirection.Back;
            emit(new GestureEvent(id, sample.TNs, angle / 90, 0.9, direction));
        }
    }
    internal sealed class OscillationDetector : IMotionDetector
    {
        private readonly GestureConfig _config;
        private readonly bool _yaw;
        private int _sign, _lobes;
        private long _start = -1, _lobeStart;
        private bool _counted;
        public OscillationDetector(GestureConfig config, bool yaw) { _config = config; _yaw = yaw; }
        private void Reset() { _start = -1; _lobes = 0; _sign = 0; _counted = false; }
        public void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit)
        {
            if (sample.Kind != MotionSensorKind.Gyroscope) return;
            if (sample.TNs < state.SuppressedUntil || !state.Fresh(sample.TNs, _config) || state.LinearWindow.Rms > _config.BackKnockMaxRms)
            { Reset(); return; }
            var axis = _yaw ? state.Gravity.Normalized : new MotionVector(1, 0, 0);
            // When X and gravity are parallel, yaw and pitch cannot be distinguished reliably.
            if (!_yaw && Math.Abs(state.Gravity.Normalized.X) > 0.85) { Reset(); return; }
            double speed = MotionVector.Dot(sample.Value, axis);
            double other = (sample.Value - axis * speed).Length;
            if (other > Math.Max(_config.QuietAngularSpeed, Math.Abs(speed) * _config.OscillationCrossAxisRatio)) { Reset(); return; }
            if (_start >= 0 && sample.TNs - _start > GestureConfig.Ns(_config.OscillationWindowMs)) Reset();
            if (Math.Abs(speed) < _config.OscillationSpeed) return;
            int sign = Math.Sign(speed);
            if (_start < 0) _start = sample.TNs;
            if (sign != _sign) { _sign = sign; _lobeStart = sample.TNs; _counted = false; }
            if (!_counted && sample.TNs - _lobeStart >= GestureConfig.Ns(_config.OscillationMinLobeMs)) { _lobes++; _counted = true; }
            if (_lobes < _config.OscillationLobes) return;
            emit(new GestureEvent(_yaw ? GestureId.Headshake : GestureId.Nod, sample.TNs, Math.Abs(speed) / (_config.OscillationSpeed * 3), 0.9)); Reset();
        }
    }
    internal sealed class ShakeDetector : IMotionDetector
    {
        private readonly GestureConfig _config;
        private bool _light, _hard;
        public ShakeDetector(GestureConfig config) { _config = config; }
        public void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit)
        {
            if (sample.Kind != MotionSensorKind.LinearAcceleration) return;
            var window = state.LinearWindow;
            bool hard = window.Rms >= _config.ShakeHardRms && window.Peak >= _config.ShakeHardPeak;
            bool light = window.Rms >= _config.ShakeLightRms && window.Peak >= _config.ShakeLightPeak;
            if (!light) _light = _hard = false;
            if (window.SpanNs < GestureConfig.Ns(_config.ShakeMinMs)) return;
            if (hard) state.SuppressedUntil = sample.TNs + GestureConfig.Ns(_config.ShakeSuppressionMs);
            if (hard && !_hard) { _hard = _light = true; emit(new GestureEvent(GestureId.ShakeHard, sample.TNs, window.Rms / (_config.ShakeHardRms * 2), 0.95)); }
            else if (light && !_light) { _light = true; emit(new GestureEvent(GestureId.ShakeLight, sample.TNs, window.Rms / _config.ShakeHardRms, 0.85)); }
        }
    }
    internal sealed class FlipDetector : IMotionDetector
    {
        private readonly GestureConfig _config;
        private long _start = -1;
        private bool _latched;
        public FlipDetector(GestureConfig config) { _config = config; }
        public void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit)
        {
            if (sample.Kind != MotionSensorKind.Gravity) return;
            bool down = state.Gravity.Normalized.Z <= -_config.FaceDownCosine;
            if (!down) _latched = false;
            if (!down || !state.Quiet(_config) || !state.Fresh(sample.TNs, _config)) { _start = -1; return; }
            if (_start < 0) _start = sample.TNs;
            if (!_latched && sample.TNs - _start >= GestureConfig.Ns(_config.FlipHoldMs))
            { _latched = true; emit(new GestureEvent(GestureId.FlipDown, sample.TNs)); }
        }
    }
    internal sealed class StillPickupDetector : IMotionDetector
    {
        private readonly GestureConfig _config;
        private readonly VectorWindow _acceleration = new VectorWindow();
        private long _quietStart = -1, _movingStart = -1;
        private bool _still;
        public StillPickupDetector(GestureConfig config) { _config = config; }
        public void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit)
        {
            if (sample.Kind != MotionSensorKind.Accelerometer) return;
            _acceleration.Add(sample, _config.StillWindowMs);
            bool quiet = _acceleration.Variance < _config.StillVariance && state.Quiet(_config) && state.Fresh(sample.TNs, _config) &&
                Math.Abs(state.Gravity.Normalized.Z) >= _config.HorizontalCosine;
            if (!quiet) _quietStart = -1;
            else if (_quietStart < 0) _quietStart = sample.TNs;
            if (!_still && quiet && sample.TNs - _quietStart >= GestureConfig.Ns(_config.StillHoldMs))
            { _still = true; emit(new GestureEvent(GestureId.Still, sample.TNs)); }
            bool moving = state.Linear.Length >= _config.PickupAcceleration || state.Gyro.Length >= _config.PickupAngularSpeed;
            if (!moving) _movingStart = -1;
            else if (_movingStart < 0) _movingStart = sample.TNs;
            if (_still && _movingStart >= 0 && sample.TNs - _movingStart >= GestureConfig.Ns(_config.PickupHoldMs))
            { _still = false; _quietStart = -1; emit(new GestureEvent(GestureId.Pickup, sample.TNs)); }
        }
    }
    internal sealed class BackKnockDetector : IMotionDetector
    {
        private readonly GestureConfig _config;
        private MotionVector _low;
        private long _last = -1, _spikeStart = -1, _lastHit = long.MinValue / 2;
        private double _peak;
        public double HighPass { get; private set; }
        public BackKnockDetector(GestureConfig config) { _config = config; }
        public void Push(MotionSample sample, MotionState state, Action<GestureEvent> emit)
        {
            if (sample.Kind != MotionSensorKind.Accelerometer) return;
            double dt = _last < 0 ? 0 : (sample.TNs - _last) / 1000000.0;
            if (_last < 0 || dt > _config.MaxSampleGapMs) { _low = sample.Value; _spikeStart = -1; }
            _last = sample.TNs;
            _low += (sample.Value - _low) * (1 - Math.Exp(-dt / _config.BackKnockHighPassMs));
            var high = sample.Value - _low; HighPass = high.Z;
            bool quiet = state.LinearWindow.Rms <= _config.BackKnockMaxRms && state.Gyro.Length <= _config.BackKnockMaxAngularSpeed && sample.TNs >= state.SuppressedUntil;
            if (!quiet) { _spikeStart = -1; return; }
            if (_spikeStart < 0 && Math.Abs(high.Z) >= _config.BackKnockThreshold &&
                Math.Abs(high.Z) >= Math.Sqrt(high.X * high.X + high.Y * high.Y) * _config.BackKnockAxisRatio &&
                sample.TNs - _lastHit >= GestureConfig.Ns(_config.BackKnockRefractoryMs))
            { _spikeStart = sample.TNs; _peak = Math.Abs(high.Z); }
            if (_spikeStart < 0) return;
            _peak = Math.Max(_peak, Math.Abs(high.Z));
            if (sample.TNs - _spikeStart > GestureConfig.Ns(_config.BackKnockMaxMs)) { _spikeStart = -1; return; }
            if (Math.Abs(high.Z) > _config.BackKnockRelease) return;
            _lastHit = _spikeStart; _spikeStart = -1;
            emit(new GestureEvent(GestureId.BackKnock, _lastHit, _peak / (_config.BackKnockThreshold * 3), 0.85));
        }
    }
}
