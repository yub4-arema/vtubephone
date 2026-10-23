using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.Controls;
using VtubePhone.Core;
using CoreTouchPhase = VtubePhone.Core.TouchPhase;

namespace VtubePhone.Runtime
{
    // Actions report every state change, including multiple taps in one Unity frame. Use callback time, never frame time.
    public sealed class TouchSource : IDisposable
    {
        private readonly InputAction _press = new InputAction("Gesture press", InputActionType.PassThrough);
        private readonly InputAction _position = new InputAction("Gesture position", InputActionType.PassThrough);
        private readonly Dictionary<InputControl, int> _active = new Dictionary<InputControl, int>();
        private readonly List<TouchSample> _pending = new List<TouchSample>();
        public Func<Vector2, bool> Accept { get; set; }
        public TouchSource()
        {
            _press.AddBinding("<Touchscreen>/touch*/press"); _press.AddBinding("<Mouse>/leftButton");
            _position.AddBinding("<Touchscreen>/touch*/position"); _position.AddBinding("<Mouse>/position");
            _press.performed += Press; _position.performed += Move;
        }
        private static InputControl Parent(InputAction.CallbackContext context) => context.control.parent is TouchControl touch ? (InputControl)touch : context.control.device;
        private static Vector2 Position(InputControl parent) => parent is TouchControl touch ? touch.position.ReadValue() : ((Mouse)parent).position.ReadValue();
        private void Press(InputAction.CallbackContext context)
        {
            var parent = Parent(context); var position = Position(parent);
            if (context.ReadValue<float>() > 0.5f)
            {
                if (Accept != null && !Accept(position)) return;
                int id = parent is TouchControl touch ? touch.touchId.ReadValue() : -1;
                _active[parent] = id; Queue(context.time, id, position, CoreTouchPhase.Down);
            }
            else if (_active.TryGetValue(parent, out int id))
            {
                var phase = parent is TouchControl touch && touch.phase.ReadValue() == UnityEngine.InputSystem.TouchPhase.Canceled ? CoreTouchPhase.Cancel : CoreTouchPhase.Up;
                Queue(context.time, id, position, phase); _active.Remove(parent);
            }
        }
        private void Move(InputAction.CallbackContext context)
        {
            var parent = Parent(context);
            if (_active.TryGetValue(parent, out int id)) Queue(context.time, id, context.ReadValue<Vector2>(), CoreTouchPhase.Move);
        }
        private void Queue(double seconds, int id, Vector2 position, CoreTouchPhase phase) => _pending.Add(new TouchSample((long)(seconds * 1e9), id, position.x, position.y, phase));
        public void Start() { _press.Enable(); _position.Enable(); }
        public void Stop() { _press.Disable(); _position.Disable(); _active.Clear(); _pending.Clear(); }
        public void Drain(List<TouchSample> samples, long clockOffsetNs)
        {
            foreach (var sample in _pending) samples.Add(new TouchSample(sample.TNs + clockOffsetNs, sample.FingerId, sample.X, sample.Y, sample.Phase));
            _pending.Clear();
        }
        public void Dispose() { Stop(); _press.Dispose(); _position.Dispose(); }
    }
}
