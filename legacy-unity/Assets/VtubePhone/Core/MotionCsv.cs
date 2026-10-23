using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;

namespace VtubePhone.Core
{
    public sealed class MotionRecord
    {
        public long TNs;
        public string Type, Label;
        public MotionSample Motion;
        public TouchSample Touch;
    }
    public static class MotionCsv
    {
        public const string Header = "type,tNs,kind,x,y,z,w,fingerId,phase,label";
        private static readonly CultureInfo Culture = CultureInfo.InvariantCulture;
        public static string Row(MotionSample sample) => string.Join(",", "motion", sample.TNs.ToString(Culture), ((int)sample.Kind).ToString(Culture),
            sample.Value.X.ToString("R", Culture), sample.Value.Y.ToString("R", Culture), sample.Value.Z.ToString("R", Culture), sample.W.ToString("R", Culture), "", "", "");
        public static string Row(TouchSample sample) => string.Join(",", "touch", sample.TNs.ToString(Culture), "", sample.X.ToString("R", Culture),
            sample.Y.ToString("R", Culture), "", "", sample.FingerId.ToString(Culture), sample.Phase.ToString(), "");
        public static string Label(long tNs, string label)
        {
            if (label == null || label.IndexOfAny(new[] { ',', '\n', '\r', '"' }) >= 0) throw new ArgumentException("Use gesture IDs separated by semicolons, or none");
            return "label," + tNs.ToString(Culture) + ",,,,,,,," + label;
        }
        public static List<MotionRecord> Read(TextReader reader)
        {
            if (reader.ReadLine() != Header) throw new FormatException("Unknown motion CSV header");
            var rows = new List<MotionRecord>(); string line;
            while ((line = reader.ReadLine()) != null)
            {
                if (line.Length == 0) continue;
                var cells = line.Split(','); if (cells.Length != 10) throw new FormatException("Motion CSV must have ten columns");
                long time = long.Parse(cells[1], Culture); if (time < 0) throw new FormatException("Negative timestamp");
                var row = new MotionRecord { Type = cells[0], TNs = time, Label = cells[9] };
                if (row.Type == "motion") row.Motion = new MotionSample(time, (MotionSensorKind)int.Parse(cells[2], Culture), Number(cells[3]), Number(cells[4]), Number(cells[5]), Number(cells[6]));
                else if (row.Type == "touch") row.Touch = new TouchSample(time, int.Parse(cells[7], Culture), Number(cells[3]), Number(cells[4]), (TouchPhase)Enum.Parse(typeof(TouchPhase), cells[8]));
                else if (row.Type != "label") throw new FormatException("Unknown record type: " + row.Type);
                rows.Add(row);
            }
            return rows.OrderBy(row => row.TNs).ToList();
        }
        private static double Number(string value)
        {
            double number = double.Parse(value, Culture);
            if (double.IsNaN(number) || double.IsInfinity(number)) throw new FormatException("Nonfinite sample");
            return number;
        }
        public static List<GestureEvent> Replay(IEnumerable<MotionRecord> rows, GestureRecognizer recognizer, long tailNs)
        {
            var result = new List<GestureEvent>(); Action<GestureEvent> receive = result.Add;
            recognizer.Detected += receive;
            try
            {
                long last = 0;
                foreach (var group in rows.OrderBy(row => row.TNs).GroupBy(row => row.TNs))
                {
                    foreach (var row in group) { if (row.Type == "motion") recognizer.Push(row.Motion); else if (row.Type == "touch") recognizer.Push(row.Touch); }
                    last = group.Key; recognizer.Advance(last);
                }
                recognizer.Advance(last + tailNs); return result;
            }
            finally { recognizer.Detected -= receive; }
        }
    }
}
