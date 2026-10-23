using System;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;
using UnityEngine.InputSystem.UI;
using UnityEngine.EventSystems;
using UnityEngine.UI;
using VtubePhone.Core;

namespace VtubePhone.Runtime
{
    public sealed class HapticsBench : MonoBehaviour
    {
        private HapticsService _service;
        private Text _report, _log, _score;
        private readonly Queue<string> _lines = new Queue<string>();
        private readonly List<Button> _clipButtons = new List<Button>();
        private readonly List<Selectable> _settings = new List<Selectable>();
        private Button _sample, _answerYes, _answerNo;
        private int _count = 2;
        private float _impact = 0.5f;
        private readonly HapticsBlindTest _blindTest = new HapticsBlindTest();
        private bool _blind;
        private bool _paused, _focused = true;
        private Font _font;

        private void Start()
        {
            _font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            IHapticsBackend backend;
#if UNITY_ANDROID && !UNITY_EDITOR
            backend = new AndroidHapticsBackend();
#else
            backend = new EditorHapticsBackend();
#endif
            try
            {
                var clips = Resources.LoadAll<TextAsset>("HapticClips").Select(asset => HapticClipParser.Parse(asset.text));
                _service = new HapticsService(backend, clips);
                BuildUi();
                _service.Logged += AddLog;
                _report.text = $"Selected tier: {_service.Tier}\n{_service.CapabilityReport}";
                AddLog(Application.isEditor ? "EDITOR SIMULATION — no physical vibration / latency measurement" : "Ready; target latency ≤ 3000 μs");
                UpdateSuspension();
            }
            catch (Exception e) { Debug.LogException(e); backend.Dispose(); _service = null; enabled = false; }
        }

        private void BuildUi()
        {
            var canvasObject = new GameObject("Canvas", typeof(RectTransform), typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
            canvasObject.transform.SetParent(transform, false);
            canvasObject.GetComponent<Canvas>().renderMode = RenderMode.ScreenSpaceOverlay;
            var scaler = canvasObject.GetComponent<CanvasScaler>();
            scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize; scaler.referenceResolution = new Vector2(720, 1280); scaler.matchWidthOrHeight = 0;
            var events = new GameObject("EventSystem", typeof(EventSystem), typeof(InputSystemUIInputModule));
            events.transform.SetParent(transform, false);
            var page = Scroll(canvasObject.transform, "Page", 0, out _);
            Label(page, "vtubephone / Haptics Bench", 48, 30);
            var capability = Scroll(page, "Capabilities", 240, out _report);
            capability.gameObject.SetActive(true);
            Scroll(page, "Playback log", 220, out _log);
            foreach (HapticClipId id in Enum.GetValues(typeof(HapticClipId)))
            {
                var captured = id;
                _clipButtons.Add(Button(page, id.ToString(), () => {
                    if (captured == HapticClipId.Knock) _service.PlayKnock(_count);
                    else if (captured == HapticClipId.Bump) _service.PlayBump(_impact);
                    else _service.Play(captured);
                }));
            }
            _settings.Add(Slider(page, "Knock count", 1, 4, 2, true, v => _count = (int)v));
            _settings.Add(Slider(page, "Bump impact", 0, 1, 0.5f, false, v => _impact = v));
            foreach (HapticTier tier in Enum.GetValues(typeof(HapticTier)))
            {
                var captured = tier;
                var button = Button(page, "Force tier " + tier, () => {
                    if (_service.SetTier(captured)) _report.text = $"Selected tier: {_service.Tier}\n{_service.CapabilityReport}";
                });
                button.interactable = _service.Capabilities.Supports(tier); _settings.Add(button);
            }
            _settings.Add(Slider(page, "Global strength", 0, 1, 1, false, v => _service.SetStrength(v)));
            Toggle(page, "All OFF", false, value => { _service.SetEnabled(!value); if (value) ResetTrial(); });
            Toggle(page, "Blind test (yes / no)", false, value => {
                _blind = value; _service.BlindMode = value; ResetTrial(); _lines.Clear(); _log.text = "";
                foreach (var button in _clipButtons) button.interactable = !value;
                foreach (var setting in _settings) setting.interactable = !value;
                // Restore capability restrictions when leaving blind mode.
                if (!value) for (int i = 0; i < 4; i++) _settings[i + 2].interactable = _service.Capabilities.Supports((HapticTier)i);
                UpdateTrialUi();
            });
            _score = Label(page, "No answers yet", 70, 24);
            _sample = Button(page, "Play hidden sample", PlaySample);
            _answerYes = Button(page, "Answer YES", () => Answer(HapticClipId.Yes));
            _answerNo = Button(page, "Answer NO", () => Answer(HapticClipId.No));
            UpdateTrialUi();
        }
        private void PlaySample()
        {
            if (!_blind || _blindTest.AwaitingAnswer) return;
            var id = UnityEngine.Random.value < 0.5f ? HapticClipId.Yes : HapticClipId.No;
            if (_service.Play(id)) _blindTest.Begin(id == HapticClipId.Yes);
            UpdateTrialUi();
        }
        private void Answer(HapticClipId answer)
        {
            if (!_blindTest.Answer(answer == HapticClipId.Yes)) return;
            _score.text = $"Correct: {_blindTest.Correct}/{_blindTest.Total} ({100 * _blindTest.Accuracy:F0}%)";
            UpdateTrialUi();
        }
        private void ResetTrial() { _blindTest.Cancel(); UpdateTrialUi(); }
        private void UpdateTrialUi()
        {
            if (_sample == null) return;
            _sample.interactable = _blind && !_blindTest.AwaitingAnswer;
            _answerYes.interactable = _answerNo.interactable = _blind && _blindTest.AwaitingAnswer;
        }
        private void AddLog(string message)
        {
            _lines.Enqueue(message); while (_lines.Count > 30) _lines.Dequeue();
            _log.text = string.Join("\n", _lines);
        }
        private RectTransform Box(Transform parent, string name, float height)
        {
            var go = new GameObject(name, typeof(RectTransform)); go.transform.SetParent(parent, false);
            if (height > 0) go.AddComponent<LayoutElement>().preferredHeight = height;
            return go.GetComponent<RectTransform>();
        }
        private Text Label(Transform parent, string value, float height, int size = 22)
        {
            var rect = Box(parent, "Label", height); var text = rect.gameObject.AddComponent<Text>();
            text.font = _font; text.fontSize = size; text.color = Color.white; text.text = value;
            text.alignment = TextAnchor.MiddleLeft; text.raycastTarget = false; return text;
        }
        private RectTransform Scroll(Transform parent, string name, float height, out Text text)
        {
            var root = Box(parent, name, height);
            if (height == 0) { root.anchorMin = Vector2.zero; root.anchorMax = Vector2.one; root.offsetMin = new Vector2(20, 25); root.offsetMax = new Vector2(-20, -25); }
            root.gameObject.AddComponent<Image>().color = new Color(0.08f, 0.1f, 0.15f);
            var scroll = root.gameObject.AddComponent<ScrollRect>(); scroll.horizontal = false;
            var viewport = Box(root, "Viewport", 0); viewport.anchorMin = Vector2.zero; viewport.anchorMax = Vector2.one; viewport.sizeDelta = Vector2.zero;
            viewport.gameObject.AddComponent<RectMask2D>(); scroll.viewport = viewport;
            var content = Box(viewport, "Content", 0); content.anchorMin = new Vector2(0, 1); content.anchorMax = Vector2.one; content.pivot = new Vector2(0.5f, 1); content.sizeDelta = Vector2.zero;
            var layout = content.gameObject.AddComponent<VerticalLayoutGroup>(); layout.spacing = 12; layout.padding = new RectOffset(12, 12, 12, 12);
            layout.childControlWidth = layout.childControlHeight = true; layout.childForceExpandHeight = false;
            content.gameObject.AddComponent<ContentSizeFitter>().verticalFit = ContentSizeFitter.FitMode.PreferredSize; scroll.content = content;
            text = height > 0 ? Label(content, "", 0, 20) : null;
            return content;
        }
        private Button Button(Transform parent, string label, UnityEngine.Events.UnityAction action)
        {
            var root = Box(parent, label, 76); var image = root.gameObject.AddComponent<Image>(); image.color = new Color(0.18f, 0.28f, 0.42f);
            var button = root.gameObject.AddComponent<Button>(); button.targetGraphic = image; button.onClick.AddListener(action);
            var text = Label(root, label, 0, 26); Stretch(text.rectTransform); text.alignment = TextAnchor.MiddleCenter; return button;
        }
        private Slider Slider(Transform parent, string name, float min, float max, float value, bool whole, Action<float> action)
        {
            var root = Box(parent, name, 110);
            var label = Label(root, $"{name}: {value:0.##}", 0); Stretch(label.rectTransform); label.rectTransform.anchorMin = new Vector2(0, 0.5f);
            var track = Box(root, "Slider", 0); Stretch(track); track.anchorMax = new Vector2(1, 0.45f);
            var image = track.gameObject.AddComponent<Image>(); image.color = Color.gray;
            var slider = track.gameObject.AddComponent<Slider>(); slider.minValue = min; slider.maxValue = max; slider.wholeNumbers = whole;
            var handle = Box(track, "Handle", 0); handle.sizeDelta = new Vector2(44, 0); var handleImage = handle.gameObject.AddComponent<Image>(); handleImage.color = Color.cyan;
            slider.handleRect = handle; slider.targetGraphic = handleImage; slider.SetValueWithoutNotify(value);
            slider.onValueChanged.AddListener(v => { label.text = $"{name}: {v:0.##}"; action(v); }); return slider;
        }
        private void Toggle(Transform parent, string name, bool value, Action<bool> action)
        {
            var root = Box(parent, name, 76); var image = root.gameObject.AddComponent<Image>(); image.color = new Color(0.2f, 0.2f, 0.25f);
            var toggle = root.gameObject.AddComponent<UnityEngine.UI.Toggle>(); toggle.targetGraphic = image;
            var mark = Box(root, "Check", 0); mark.anchorMin = new Vector2(0, 0.2f); mark.anchorMax = new Vector2(0, 0.8f); mark.sizeDelta = new Vector2(38, 0); mark.anchoredPosition = new Vector2(26, 0);
            toggle.graphic = mark.gameObject.AddComponent<Image>(); toggle.graphic.color = Color.cyan;
            var label = Label(root, "      " + name, 0, 26); Stretch(label.rectTransform);
            toggle.SetIsOnWithoutNotify(value); toggle.onValueChanged.AddListener(v => action(v));
        }
        private static void Stretch(RectTransform rect) { rect.anchorMin = Vector2.zero; rect.anchorMax = Vector2.one; rect.sizeDelta = Vector2.zero; }
        private void UpdateSuspension()
        {
            bool suspended = _paused || !_focused;
            _service?.Suspend(suspended);
            if (suspended) ResetTrial();
        }
        private void OnApplicationPause(bool pause) { _paused = pause; UpdateSuspension(); }
        private void OnApplicationFocus(bool focus) { _focused = focus; UpdateSuspension(); }
        private void OnDestroy() => _service?.Dispose();
    }
}
