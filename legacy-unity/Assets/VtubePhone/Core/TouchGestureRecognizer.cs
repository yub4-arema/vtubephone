using System;
using System.Collections.Generic;

namespace VtubePhone.Core
{
    public sealed class TouchGestureRecognizer
    {
        private sealed class Finger
        {
            public TouchSample Start, Last;
            public double Distance, MaxDisplacement;
            public bool InAvatar, Emitted;
        }
        private readonly Dictionary<int, Finger> _fingers = new Dictionary<int, Finger>();
        private readonly GestureConfig _config;
        public GestureRect AvatarRect { get; set; }
        public event Action<GestureEvent> Detected;
        public TouchGestureRecognizer(GestureConfig config, GestureRect avatarRect) { _config = config; AvatarRect = avatarRect; }
        private static double Distance(TouchSample a, TouchSample b) => Math.Sqrt((a.X - b.X) * (a.X - b.X) + (a.Y - b.Y) * (a.Y - b.Y));
        public void Push(TouchSample sample)
        {
            if (sample.Phase == TouchPhase.Down)
            { _fingers[sample.FingerId] = new Finger { Start = sample, Last = sample, InAvatar = AvatarRect.Contains(sample.X, sample.Y) }; return; }
            if (!_fingers.TryGetValue(sample.FingerId, out var finger)) return;
            if (sample.TNs < finger.Last.TNs) throw new ArgumentException("Touch events must be ordered");
            finger.Distance += Distance(sample, finger.Last); finger.Last = sample;
            finger.MaxDisplacement = Math.Max(finger.MaxDisplacement, Distance(sample, finger.Start));
            finger.InAvatar &= AvatarRect.Contains(sample.X, sample.Y);
            if (sample.Phase != TouchPhase.Cancel) Evaluate(finger, sample.TNs);
            if (sample.Phase == TouchPhase.Up)
            {
                if (!finger.Emitted && finger.MaxDisplacement <= _config.TouchMovePixels && sample.TNs - finger.Start.TNs <= GestureConfig.Ns(_config.TapMaxMs))
                    Detected?.Invoke(new GestureEvent(GestureId.Tap, sample.TNs));
                _fingers.Remove(sample.FingerId);
            }
            else if (sample.Phase == TouchPhase.Cancel) _fingers.Remove(sample.FingerId);
        }
        public void Advance(long tNs) { foreach (var finger in _fingers.Values) Evaluate(finger, tNs); }
        private void Evaluate(Finger finger, long tNs)
        {
            if (finger.Emitted || !finger.InAvatar) return;
            if (finger.Distance >= _config.StrokePixels)
            { finger.Emitted = true; Detected?.Invoke(new GestureEvent(GestureId.Stroke, tNs)); }
            else if (finger.MaxDisplacement <= _config.TouchMovePixels && tNs - finger.Start.TNs >= GestureConfig.Ns(_config.HoldMs))
            { finger.Emitted = true; Detected?.Invoke(new GestureEvent(GestureId.Hold, tNs)); }
        }
    }
}
