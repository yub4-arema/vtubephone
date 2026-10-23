// Expression and motion vocabulary for the 魔女 model (PM-owned). Wire names match core/brain/Turn.kt.
// Toggle parameters (Param59 星星 … Param69 黒脸) are 0..30 switches; we drive them directly so they can fade.

/** Every parameter an expression touches, with its resting value. Faces always blend back to these. */
export const BASE = {
  ParamEyeLOpen: 1, ParamEyeROpen: 1, ParamEyeLSmile: 0, ParamEyeRSmile: 0,
  ParamEyeBallX: 0, ParamEyeBallY: 0, ParamBrowLY: 0, ParamBrowLForm: 0,
  ParamMouthForm: 0.1, ParamMouthOpenY: 0,
  Param59: 0, Param60: 0, Param67: 0, Param68: 0, Param69: 0, // star eyes, heart eyes, angry face, cry face, gloom
  Param25: 0, Param26: 0, Param27: 0, Param53: 0, Param21: 0, Param45: 0, Param48: 0, Param51: 0, Param52: 0, Param20: 0, Param46: 0,
  ParamAngleX: 0, ParamAngleY: 0, ParamAngleZ: 0,
  // Written every frame so motion offsets never stick after a motion ends.
  ParamBodyAngleX: 0, ParamBodyAngleY: 0, ParamBodyAngleZ: 0, Param28: 0, Param30: 0,
};

const eyes = v => ({ ParamEyeLOpen: v, ParamEyeROpen: v });
const smile = v => ({ ParamEyeLSmile: v, ParamEyeRSmile: v });

/** Static targets plus an optional animated layer `anim(t)` (t = seconds since the expression started). */
export const EXPRESSIONS = {
  neutral: {},
  happy: { ...smile(1), ParamMouthForm: 1, ParamMouthOpenY: 0.35, ParamBrowLY: 0.4 },
  love: { Param60: 30, ...smile(0.5), ParamMouthForm: 1, ParamMouthOpenY: 0.25, ParamBrowLY: 0.3,
    anim: t => ({ ParamAngleZ: 4 * Math.sin(t * 2.2) }) },
  star: { Param59: 30, ParamMouthForm: 1, ParamMouthOpenY: 0.6, ParamBrowLY: 0.6, ...eyes(1.2) },
  cry: { Param68: 30, Param25: 30, Param27: 30, ParamBrowLForm: 1, ParamBrowLY: -0.8, ParamMouthForm: -1, ParamMouthOpenY: 0.2, ...eyes(0.8),
    anim: t => ({ Param26: 15 + 15 * Math.sin(t * 5), ParamAngleZ: 2 * Math.sin(t * 9) }) },
  angry: { Param67: 30, Param53: 30, ParamBrowLForm: -0.9, ParamBrowLY: -0.75, ParamMouthForm: -0.7, Param51: 0.5, Param52: 0.5, Param21: 0.6 },
  gloom: { Param69: 30, ...eyes(0.55), ParamMouthForm: -0.6, ParamBrowLY: -0.4, ParamEyeBallY: -0.5, ParamAngleY: -10 },
  pout: { Param21: 1, Param45: 0.6, Param48: 0.7, ParamMouthForm: -0.8, ParamBrowLForm: -0.5, ParamBrowLY: -0.3, ...eyes(0.85), ParamEyeBallX: -0.5, ParamAngleX: -12 },
  dizzy: { ...eyes(0.55), Param51: 0.6, Param52: 0.6, ParamMouthOpenY: 0.4, ParamMouthForm: -0.4, ParamBrowLY: -0.3, Param46: 0.4,
    anim: t => ({ ParamEyeBallX: Math.cos(t * 9), ParamEyeBallY: Math.sin(t * 9),
      ParamAngleZ: 14 * Math.sin(t * 3.5), ParamAngleX: 10 * Math.cos(t * 3.5), Param20: 20 * Math.sin(t * 7) }) },
  surprise: { ...eyes(1.4), ParamMouthOpenY: 0.85, Param45: 0.5, ParamBrowLY: 1, ParamMouthForm: -0.2 },
  sleepy: { ...eyes(0.2), ParamMouthOpenY: 0.1, ParamBrowLY: -0.3, ParamAngleY: -12, ParamAngleZ: 9,
    anim: t => ({ ParamAngleZ: 9 + 4 * Math.sin(t * 0.9) }) },
  shy: { ...smile(0.6), ParamMouthForm: 0.4, ParamEyeBallX: -0.7, ParamEyeBallY: -0.5, ParamAngleX: -14, ParamAngleY: -6, Param48: 0.4 },
  think: { ParamEyeBallX: 0.6, ParamEyeBallY: 0.8, ParamAngleZ: 9, ParamAngleY: 5, ParamMouthForm: -0.2, Param20: 12, ParamBrowLY: 0.4 },
};

/** Low-intensity resting faces derived from the character state (sent by native as `mood`). */
export function idleFace({ mood = 0.3, grudge = 0, sleeping = false, energy = 0.6 } = {}) {
  if (sleeping) return { ...eyes(0), ParamMouthOpenY: 0.05, ParamAngleY: -14, ParamAngleZ: 10 };
  if (grudge > 0.5) return { Param53: 12, ParamBrowLY: -0.4, ParamBrowLForm: -0.4, Param21: 0.35 };
  const f = {};
  if (mood > 0.55) Object.assign(f, smile(0.35), { ParamMouthForm: 0.6 });
  else if (mood < -0.35) Object.assign(f, { Param21: 0.3, ParamMouthForm: -0.4, ParamBrowLY: -0.3 });
  if (energy < 0.3) Object.assign(f, eyes(0.7), { ParamAngleY: -6 });
  return f;
}

const clamp01 = v => Math.max(0, Math.min(1, v));
const ease = v => { v = clamp01(v); return v * v * (3 - 2 * v); };
/** Rises over `a`, holds until `b`, falls until `c` (seconds). */
const envelope = (t, a, b, c) => t < a ? ease(t / a) : t < b ? 1 : 1 - ease((t - b) / (c - b));

/**
 * Whole-body motions. Each returns additive offsets: dx/dy in units of the view (width/height), rot (radians,
 * clockwise), scale multiplier, and model parameters. `duration` in seconds.
 */
export const MOTIONS = {
  none: { duration: 0, at: () => ({}) },
  wave: { duration: 1.8, at: t => { const e = envelope(t, 0.25, 1.4, 1.8);
    return { params: { Param28: 30 * e, Param30: 30 * e * Math.sin(t * 14), ParamAngleZ: 6 * e, ParamBodyAngleZ: 4 * e } }; } },
  approach: { duration: 1.3, at: t => { const e = envelope(t, 0.2, 0.6, 1.3);
    return { scale: 1 + 0.55 * e, dy: -0.08 * e, params: { ParamAngleY: 10 * e, ParamBodyAngleY: 10 * e } }; } },
  bounce: { duration: 0.9, at: t => ({ dy: -0.06 * Math.abs(Math.sin(t * Math.PI * 2.2)) * (1 - t / 0.9),
    params: { ParamBodyAngleY: 6 * Math.sin(t * 14) * (1 - t / 0.9) } }) },
  nod: { duration: 0.8, at: t => ({ params: { ParamAngleY: -18 * Math.max(0, Math.sin(t * Math.PI * 2.5)) * (1 - t / 0.8) } }) },
  shake_head: { duration: 0.9, at: t => ({ params: { ParamAngleX: 22 * Math.sin(t * Math.PI * 4.4) * (1 - t / 0.9) } }) },
  lean: { duration: 1.6, at: t => { const e = envelope(t, 0.3, 1.1, 1.6);
    return { scale: 1 + 0.1 * e, params: { ParamAngleY: 8 * e, ParamAngleZ: 10 * e, ParamBodyAngleX: 10 * e } }; } },
  hide: { duration: 2.8, at: t => ({ dy: 0.75 * envelope(t, 0.45, 2.2, 2.8) }) },
  peek: { duration: 1.0, at: t => { const e = 1 - ease(t / 0.55);
    return { dy: 0.45 * e - 0.03 * Math.sin(Math.min(1, t / 1.0) * Math.PI), params: { ParamAngleZ: 16 * envelope(t, 0.2, 0.6, 1.0) } }; } },
  spin: { duration: 0.9, at: t => ({ rot: 2 * Math.PI * ease(t / 0.8), scale: 1 - 0.12 * Math.sin(Math.min(1, t / 0.8) * Math.PI) }) },
};

/** How to show the expected answer. Glyphs are paired with words: never rely on icons alone. */
export const GESTURE_HINTS = {
  nod: { glyph: '⇣', verb: 'うなずく' },
  headshake: { glyph: '⇆', verb: '首をふる' },
  tilt_left: { glyph: '⟲', verb: '左に傾ける' },
  tilt_right: { glyph: '⟳', verb: '右に傾ける' },
  tilt_forward: { glyph: '⤒', verb: '奥に倒す' },
  tilt_back: { glyph: '⤓', verb: '手前に倒す' },
  knock: { glyph: '✊', verb: 'ノック' },
  back_knock: { glyph: '✊', verb: '背面ノック' },
  stroke: { glyph: '〰', verb: 'なでる' },
  hold: { glyph: '●', verb: '長押し' },
  shake_light: { glyph: '≈', verb: '軽くゆらす' },
  shake_hard: { glyph: '⚡', verb: '思いきり振る' },
  flip_down: { glyph: '⤵', verb: 'ふせる' },
  pickup: { glyph: '⤴', verb: '持ち上げる' },
  still: { glyph: '▁', verb: '置く' },
};
