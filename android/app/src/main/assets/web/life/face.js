// Blends expression presets, idle mood, blinking, breathing, speech and motions into parameter values.
// Pure (no DOM): `frame(dt, t)` returns the parameter map and body offsets for this frame.
import { BASE, EXPRESSIONS, MOTIONS, idleFace } from './presets.js';

export class Face {
  constructor(random = Math.random) {
    this.random = random;
    this.current = { ...BASE };
    this.mood = {};
    this.expression = { name: 'neutral', since: 0, until: Infinity };
    this.base = 'neutral'; // what a reflex falls back to (the current turn's expression)
    this.motions = [];
    this.nextBlink = 2;
    this.blinkStart = -1;
    this.speakUntil = 0;
    this.nativeMouth = { value: 0, until: 0 };
    this.flinch = { until: 0, amount: 0 };
  }

  /** `hold` seconds, or Infinity to keep it (turn expressions keep until the next turn). */
  setExpression(name, t, hold = Infinity) {
    if (!EXPRESSIONS[name]) name = 'neutral';
    this.expression = { name, since: t, until: t + hold };
  }
  setBase(name, t) { this.base = EXPRESSIONS[name] ? name : 'neutral'; this.setExpression(this.base, t); }
  playMotion(name, t) { if (MOTIONS[name]?.duration) this.motions.push({ name, start: t }); }
  speak(text, t) { this.speakUntil = t + Math.min(6, Math.max(0.8, [...text].length * 0.11)); }
  mouth(value, t) { this.nativeMouth = { value: Math.max(0, Math.min(1, value)), until: t + 0.3 }; }
  stopSpeaking(t) { this.speakUntil = t; }
  /** A short startle (knocks, bumps): eyes widen, then return. */
  startle(amount, t) { this.flinch = { until: t + 0.35, amount: t < this.flinch.until ? Math.max(this.flinch.amount, amount) : amount }; }

  frame(dt, t) {
    if (t >= this.expression.until) this.setExpression(this.base, t);
    const preset = EXPRESSIONS[this.expression.name];
    const target = { ...BASE, ...this.mood, ...withoutAnim(preset), ...(preset.anim?.(t - this.expression.since) ?? {}) };
    // Smooth toward the target; animated presets still move because their target moves every frame.
    const k = 1 - Math.exp(-dt / 0.12);
    for (const key of Object.keys(target)) {
      const cur = this.current[key] ?? target[key];
      this.current[key] = dt <= 0 ? target[key] : cur + (target[key] - cur) * k;
    }
    const out = { ...this.current };
    // Blink: never while eyes are already mostly closed or in faces whose eyes are drawn (stars/hearts).
    if (t >= this.nextBlink && this.blinkStart < 0) this.blinkStart = t;
    if (this.blinkStart >= 0) {
      const p = (t - this.blinkStart) / 0.16;
      if (p >= 1) { this.blinkStart = -1; this.nextBlink = t + 1.8 + this.random() * 4.2; }
      else { const f = 1 - Math.sin(p * Math.PI); out.ParamEyeLOpen *= f; out.ParamEyeROpen *= f; }
    }
    if (t < this.flinch.until) {
      const f = Math.sin((1 - (this.flinch.until - t) / 0.35) * Math.PI) * this.flinch.amount;
      out.ParamEyeLOpen = Math.max(out.ParamEyeLOpen, 1 + 0.35 * f); out.ParamEyeROpen = Math.max(out.ParamEyeROpen, 1 + 0.35 * f);
      out.ParamBrowLY = Math.max(out.ParamBrowLY, f);
    }
    out.ParamBreath = 0.5 + 0.5 * Math.sin(t * (this.mood.ParamEyeLOpen === 0 ? 1.1 : 1.8));
    // Idle life: slow head and body drift so she is never frozen.
    out.ParamAngleX += 4 * Math.sin(t * 0.53) + 2 * Math.sin(t * 1.31);
    out.ParamAngleY += 2.5 * Math.sin(t * 0.71 + 1);
    out.ParamBodyAngleX += 3 * Math.sin(t * 0.37);
    // Mouth: native lip sync wins; otherwise flap while the caption is being "spoken".
    const flap = t < this.speakUntil ? 0.15 + 0.55 * Math.abs(Math.sin(t * 13)) * (0.6 + 0.4 * Math.sin(t * 3.1)) : 0;
    const lip = t < this.nativeMouth.until ? this.nativeMouth.value : flap;
    out.ParamMouthOpenY = Math.max(out.ParamMouthOpenY, lip);
    // Motions add on top.
    const body = { dx: 0, dy: 0, rot: 0, scale: 1 };
    this.motions = this.motions.filter(m => t - m.start < MOTIONS[m.name].duration);
    for (const m of this.motions) {
      const o = MOTIONS[m.name].at(t - m.start);
      body.dx += o.dx ?? 0; body.dy += o.dy ?? 0; body.rot += o.rot ?? 0; body.scale *= o.scale ?? 1;
      for (const [key, v] of Object.entries(o.params ?? {})) out[key] = (out[key] ?? 0) + v;
    }
    return { params: out, body };
  }

  setMood(state) { this.mood = idleFace(state); }
}

function withoutAnim(preset) { const { anim, ...rest } = preset; return rest; }
