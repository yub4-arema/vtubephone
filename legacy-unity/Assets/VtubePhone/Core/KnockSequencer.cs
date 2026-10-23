using System;
using System.Collections.Generic;

namespace VtubePhone.Core
{
    public sealed class KnockSequencer
    {
        private readonly GestureConfig _config;
        private readonly List<long> _hits = new List<long>();
        public KnockSequencer(GestureConfig config) { _config = config; }
        public event Action<GestureEvent> Detected;
        public void Add(long tNs)
        {
            if (_hits.Count > 0)
            {
                long gap = tNs - _hits[_hits.Count - 1];
                if (gap < 0) throw new ArgumentException("Knocks must be ordered");
                if (gap > GestureConfig.Ns(_config.KnockJoinMs)) Complete();
            }
            _hits.Add(tNs);
        }
        public void Advance(long tNs)
        { if (_hits.Count > 0 && tNs - _hits[_hits.Count - 1] >= GestureConfig.Ns(_config.KnockSilenceMs)) Complete(); }
        private void Complete()
        {
            var intervals = new double[_hits.Count - 1];
            for (int i = 1; i < _hits.Count; i++) intervals[i - 1] = (_hits[i] - _hits[i - 1]) / 1000000.0;
            var result = new GestureEvent(GestureId.Knock, _hits[_hits.Count - 1], count: _hits.Count, intervalsMs: intervals);
            _hits.Clear(); Detected?.Invoke(result);
        }
    }
    public static class RhythmMatcher
    {
        public static bool Match(GestureEvent target, GestureEvent actual, double tolerance = 0.35)
        {
            if (target == null || actual == null || target.Id != GestureId.Knock || actual.Id != GestureId.Knock || target.Count != actual.Count ||
                target.Count < 1 || target.IntervalsMs.Length != target.Count - 1 || actual.IntervalsMs.Length != actual.Count - 1 ||
                double.IsNaN(tolerance) || tolerance < 0 || tolerance >= 1) return false;
            if (target.Count == 1) return true;
            double targetSum = Sum(target.IntervalsMs), actualSum = Sum(actual.IntervalsMs);
            if (targetSum <= 0 || actualSum <= 0) return false;
            for (int i = 0; i < target.IntervalsMs.Length; i++)
                if (Math.Abs(actual.IntervalsMs[i] / actualSum / (target.IntervalsMs[i] / targetSum) - 1) > tolerance) return false;
            return true;
        }
        private static double Sum(double[] values)
        {
            double sum = 0;
            foreach (double value in values) { if (double.IsNaN(value) || double.IsInfinity(value) || value <= 0) return -1; sum += value; }
            return sum;
        }
    }
}
