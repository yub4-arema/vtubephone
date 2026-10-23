using System;
using System.Collections.Generic;

namespace VtubePhone.Core
{
    public sealed class GestureArbiter
    {
        // Same-band priority table; all other gestures use independent channels.
        public static readonly IReadOnlyDictionary<GestureId, int> Priorities = new Dictionary<GestureId, int> {
            { GestureId.ShakeHard, 50 }, { GestureId.FlipDown, 40 }, { GestureId.Nod, 30 }, { GestureId.Headshake, 30 },
            { GestureId.ShakeLight, 20 }, { GestureId.TiltLeft, 10 }, { GestureId.TiltRight, 10 }, { GestureId.TiltForward, 10 }, { GestureId.TiltBack, 10 }
        };
        private readonly GestureConfig _config;
        private readonly List<GestureEvent> _pending = new List<GestureEvent>();
        private long _last = long.MinValue / 2;
        private int _lastPriority;
        public event Action<GestureEvent> Detected;
        public GestureArbiter(GestureConfig config) { _config = config; }
        public void Add(GestureEvent value)
        {
            if (!Priorities.ContainsKey(value.Id)) { Detected?.Invoke(value); return; }
            _pending.Add(value);
        }
        public void Advance(long now)
        {
            while (_pending.Count > 0)
            {
                var first = _pending[0];
                if (now - first.TNs < GestureConfig.Ns(_config.ArbitrationMs)) return;
                var winner = first; int consumed = 0;
                foreach (var item in _pending)
                {
                    if (item.TNs - first.TNs > GestureConfig.Ns(_config.ArbitrationMs)) break;
                    consumed++;
                    if (Priorities[item.Id] > Priorities[winner.Id]) winner = item;
                }
                _pending.RemoveRange(0, consumed);
                int priority = Priorities[winner.Id];
                if (winner.TNs - _last < GestureConfig.Ns(_config.GestureCooldownMs) && priority <= _lastPriority) continue;
                _last = winner.TNs; _lastPriority = priority; Detected?.Invoke(winner);
            }
        }
    }
}
