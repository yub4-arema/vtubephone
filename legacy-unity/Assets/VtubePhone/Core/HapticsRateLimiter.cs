using System;
using System.Collections.Generic;

namespace VtubePhone.Core
{
    public sealed class HapticsRateLimiter
    {
        private readonly Queue<double> _plays = new Queue<double>();
        private double _lastSpontaneous = double.NegativeInfinity;
        private double _strength = 1;
        public bool Enabled { get; set; } = true;
        public int MaxPerMinute { get; set; } = 60;
        public double SpontaneousCooldownSeconds { get; set; } = 2;
        public double Strength
        {
            get => _strength;
            set { if (!HapticParameter.Finite(value)) throw new ArgumentOutOfRangeException(nameof(value)); _strength = Math.Max(0, Math.Min(1, value)); }
        }
        public bool TryAcquire(double nowSeconds, bool spontaneous = false)
        {
            if (!HapticParameter.Finite(nowSeconds) || !Enabled || Strength <= 0 || MaxPerMinute <= 0) return false;
            while (_plays.Count > 0 && nowSeconds - _plays.Peek() >= 60) _plays.Dequeue();
            if (_plays.Count >= MaxPerMinute || (spontaneous && nowSeconds - _lastSpontaneous < SpontaneousCooldownSeconds)) return false;
            _plays.Enqueue(nowSeconds);
            if (spontaneous) _lastSpontaneous = nowSeconds;
            return true;
        }
    }
}
