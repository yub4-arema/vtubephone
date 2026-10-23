using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace VtubePhone.Core
{
    public static class HapticClipParser
    {
        public static HapticClip Parse(string json)
        {
            var root = (Dictionary<string, object>)new JsonReader(json).Read();
            var events = ((List<object>)root["events"]).Select(value =>
            {
                var e = (Dictionary<string, object>)value;
                string type = (string)e["type"];
                if (type != "transient" && type != "continuous") throw new FormatException("Unknown event type");
                return new HapticEvent(Integer(e["t"]), type == "continuous",
                    type == "continuous" ? Integer(e["duration"]) : 0, Parameter(e["intensity"]), Parameter(e["sharpness"]));
            });
            return new HapticClip((string)root["id"], events);
        }
        private static int Integer(object value)
        {
            double number = (double)value;
            if (number != Math.Truncate(number) || number < 0 || number > 60000) throw new FormatException("Invalid milliseconds");
            return (int)number;
        }
        private static HapticParameter Parameter(object value) => value is double number ? new HapticParameter(number) :
            new HapticParameter(((List<object>)value).Select(p => ((List<object>)p).Cast<double>().ToArray()).ToArray());
    }

    // Small strict JSON reader; no Unity or external JSON dependency in Core.
    internal sealed class JsonReader
    {
        private readonly string _json;
        private int _position;
        public JsonReader(string json) { _json = json ?? throw new ArgumentNullException(nameof(json)); }
        public object Read()
        {
            var result = Value(0); Space();
            if (_position != _json.Length) throw new FormatException("Trailing JSON");
            return result;
        }
        private void Space() { while (_position < _json.Length && char.IsWhiteSpace(_json[_position])) _position++; }
        private char Peek { get { Space(); return _position < _json.Length ? _json[_position] : '\0'; } }
        private void Require(char c) { if (Peek != c) throw new FormatException("Expected " + c); _position++; }
        private object Value(int depth)
        {
            if (depth > 16) throw new FormatException("JSON too deep");
            char c = Peek;
            if (c == '{')
            {
                _position++; var map = new Dictionary<string, object>();
                if (Peek == '}') { _position++; return map; }
                while (true)
                {
                    string key = String(); Require(':'); map.Add(key, Value(depth + 1));
                    if (Peek == '}') { _position++; return map; } Require(',');
                }
            }
            if (c == '[')
            {
                _position++; var list = new List<object>();
                if (Peek == ']') { _position++; return list; }
                while (true)
                {
                    list.Add(Value(depth + 1)); if (Peek == ']') { _position++; return list; } Require(',');
                }
            }
            if (c == '"') return String();
            int start = _position;
            while (_position < _json.Length && "-+0123456789.eE".IndexOf(_json[_position]) >= 0) _position++;
            string token = _json.Substring(start, _position - start);
            if (!Regex.IsMatch(token, @"^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$") || !double.TryParse(token, NumberStyles.Float,
                CultureInfo.InvariantCulture, out double number) || !HapticParameter.Finite(number)) throw new FormatException("Invalid JSON value");
            return number;
        }
        private string String()
        {
            Require('"'); var text = new StringBuilder();
            while (_position < _json.Length)
            {
                char c = _json[_position++];
                if (c == '"') return text.ToString();
                if (c < 32) throw new FormatException("Control character");
                if (c == '\\')
                {
                    if (_position >= _json.Length) break;
                    c = _json[_position++];
                    switch (c)
                    {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u':
                            if (_position + 4 > _json.Length) throw new FormatException("Invalid unicode escape");
                            c = (char)int.Parse(_json.Substring(_position, 4), NumberStyles.HexNumber); _position += 4; break;
                        default: throw new FormatException("Invalid escape");
                    }
                }
                text.Append(c);
            }
            throw new FormatException("Unterminated string");
        }
    }
}
