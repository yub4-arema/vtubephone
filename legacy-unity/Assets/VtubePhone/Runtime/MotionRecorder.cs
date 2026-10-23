using System;
using System.IO;
using System.Text;
using UnityEngine;
using VtubePhone.Core;

namespace VtubePhone.Runtime
{
    public sealed class MotionRecorder : IDisposable
    {
        private StreamWriter _writer;
        public bool Recording => _writer != null;
        public string FilePath { get; private set; }
        public void Start(long nowNs, string label, GestureConfig config)
        {
            Stop();
            string directory = Path.Combine(Application.persistentDataPath, "recordings"); Directory.CreateDirectory(directory);
            FilePath = Path.Combine(directory, DateTime.UtcNow.ToString("yyyyMMdd-HHmmss-fff") + "-" + Guid.NewGuid().ToString("N").Substring(0, 6) + ".csv");
            File.WriteAllText(Path.ChangeExtension(FilePath, ".json"), JsonUtility.ToJson(config, true));
            _writer = new StreamWriter(FilePath, false, new UTF8Encoding(false));
            _writer.WriteLine(MotionCsv.Header); _writer.WriteLine(MotionCsv.Label(nowNs, label));
        }
        public void Write(MotionSample sample) => _writer?.WriteLine(MotionCsv.Row(sample));
        public void Write(TouchSample sample) => _writer?.WriteLine(MotionCsv.Row(sample));
        public void Stop() { _writer?.Dispose(); _writer = null; }
        public void Dispose() => Stop();
    }
}
