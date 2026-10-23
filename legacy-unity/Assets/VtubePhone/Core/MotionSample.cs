using System;

namespace VtubePhone.Core
{
    // Android device axes, m/s² and rad/s. Gravity is +Z with the screen facing up.
    public enum MotionSensorKind { Accelerometer = 1, Gyroscope = 4, Gravity = 9, LinearAcceleration = 10, GameRotationVector = 15 }
    public enum GestureId { Tap, Hold, Stroke, Nod, Headshake, TiltLeft, TiltRight, TiltForward, TiltBack, ShakeLight, ShakeHard, FlipDown, Still, Pickup, BackKnock, Knock }
    public enum GestureDirection { None, Left, Right, Forward, Back }
    public enum TouchPhase { Down, Move, Up, Cancel }

    public readonly struct MotionVector
    {
        public readonly double X, Y, Z;
        public MotionVector(double x, double y, double z) { X = x; Y = y; Z = z; }
        public double Length => Math.Sqrt(Dot(this, this));
        public MotionVector Normalized => Length > 0.000001 ? this * (1 / Length) : new MotionVector();
        public static double Dot(MotionVector a, MotionVector b) => a.X * b.X + a.Y * b.Y + a.Z * b.Z;
        public static MotionVector Cross(MotionVector a, MotionVector b) => new MotionVector(a.Y * b.Z - a.Z * b.Y, a.Z * b.X - a.X * b.Z, a.X * b.Y - a.Y * b.X);
        public static MotionVector operator +(MotionVector a, MotionVector b) => new MotionVector(a.X + b.X, a.Y + b.Y, a.Z + b.Z);
        public static MotionVector operator -(MotionVector a, MotionVector b) => new MotionVector(a.X - b.X, a.Y - b.Y, a.Z - b.Z);
        public static MotionVector operator *(MotionVector a, double scale) => new MotionVector(a.X * scale, a.Y * scale, a.Z * scale);
    }
    public readonly struct MotionSample
    {
        public readonly long TNs;
        public readonly MotionSensorKind Kind;
        public readonly MotionVector Value;
        public readonly double W;
        public MotionSample(long tNs, MotionSensorKind kind, double x, double y, double z, double w = 0)
        { TNs = tNs; Kind = kind; Value = new MotionVector(x, y, z); W = w; }
    }
    public readonly struct TouchSample
    {
        public readonly long TNs;
        public readonly int FingerId;
        public readonly double X, Y;
        public readonly TouchPhase Phase;
        public TouchSample(long tNs, int fingerId, double x, double y, TouchPhase phase)
        { TNs = tNs; FingerId = fingerId; X = x; Y = y; Phase = phase; }
    }
    public readonly struct GestureRect
    {
        public readonly double X, Y, Width, Height;
        public GestureRect(double x, double y, double width, double height) { X = x; Y = y; Width = width; Height = height; }
        public bool Contains(double x, double y) => x >= X && y >= Y && x <= X + Width && y <= Y + Height;
    }
    public sealed class GestureEvent
    {
        public GestureId Id { get; }
        public long TNs { get; }
        public GestureDirection Direction { get; }
        public double Strength { get; }
        public int Count { get; }
        public double[] IntervalsMs { get; }
        public double Confidence { get; }
        public GestureEvent(GestureId id, long tNs, double strength = 1, double confidence = 1,
            GestureDirection direction = GestureDirection.None, int count = 1, double[] intervalsMs = null)
        {
            Id = id; TNs = tNs; Strength = Math.Max(0, Math.Min(1, strength)); Confidence = Math.Max(0, Math.Min(1, confidence));
            Direction = direction; Count = count; IntervalsMs = intervalsMs == null ? Array.Empty<double>() : (double[])intervalsMs.Clone();
        }
    }
    public static class GestureNames
    {
        public static string Id(GestureId id)
        {
            switch (id)
            {
                case GestureId.Headshake: return "headshake";
                case GestureId.TiltLeft: return "tilt_left";
                case GestureId.TiltRight: return "tilt_right";
                case GestureId.TiltForward: return "tilt_forward";
                case GestureId.TiltBack: return "tilt_back";
                case GestureId.ShakeLight: return "shake_light";
                case GestureId.ShakeHard: return "shake_hard";
                case GestureId.FlipDown: return "flip_down";
                case GestureId.BackKnock: return "back_knock";
                default: return id.ToString().ToLowerInvariant();
            }
        }
    }
}
