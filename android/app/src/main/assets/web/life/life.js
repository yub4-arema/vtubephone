// Glue between native brain messages and the avatar: body physics, faces, motions, captions, bumps.
// Native → JS: perform, react, clearOptions, gesture, mood, mouth, speaking, thinking, settings, motion (up vector).
// JS → native: bump {impact}.
import { BoxBody } from './body.js';
import { Face } from './face.js';
import { Ui } from './ui.js';

// Framing: chest-up, so faces read on a phone. Her face sits at FACE_AT of the model height from the top
// (measured on the 魔女 model) and is placed at FRAME.faceY of the screen height, whatever the aspect ratio.
const FRAME = { scale: 2.6, faceY: 0.36 };
const FACE_AT = 0.207;
const send = message => window.vtubephone?.postMessage(JSON.stringify(message));

function attach(avatar) {
  const body = new BoxBody();
  const face = new Face();
  const ui = new Ui();
  let up = [0, 1, 0];
  const now = () => performance.now() / 1000;
  const view = () => avatar.view ?? { width: innerWidth, height: innerHeight };

  // The "box" edges: her centre travels ±30% of the width, so she ends up half-pressed against the side.
  let frameY = 0;
  const updateWall = () => {
    body.setWall(view().width * 0.3);
    avatar.setTransform({ x: 0, y: 0, rotation: 0, scale: FRAME.scale });
    const b = avatar.getBounds();
    frameY = FRAME.faceY * view().height - (b.y + FACE_AT * b.height);
  };
  updateWall();
  addEventListener('resize', updateWall);

  const on = (type, fn) => addEventListener(`vtubephone:${type}`, e => { try { fn(e.detail); } catch (error) { console.warn(type, error); } });
  on('motion', m => { if (Array.isArray(m.up) && m.up.length === 3) up = m.up; });
  on('perform', m => {
    const t = now();
    face.setBase(m.expression, t);
    face.playMotion(m.motion, t);
    if (m.say) { ui.say(m.say, 2500 + [...m.say].length * 140 + (m.options?.length ? m.timeoutSec * 1000 : 0)); if (!m.voiced) face.speak(m.say, t); }
    ui.showOptions(m.options, m.timeoutSec);
    ui.thinking(false);
  });
  on('react', m => {
    const t = now();
    face.setExpression(m.expression, t, 2.4);
    face.playMotion(m.motion, t);
    if (m.say) { ui.say(m.say, 2200); face.speak(m.say, t); }
  });
  on('clearOptions', () => ui.clearOptions());
  on('thinking', m => ui.thinking(m.on));
  on('mood', m => face.setMood(m));
  on('mouth', m => face.mouth(m.open, now()));
  on('speaking', m => { if (!m.on) face.stopSpeaking(now()); });
  on('settings', m => { if (Number.isFinite(m.captionScale)) ui.setCaptionScale(m.captionScale); });
  on('gesture', m => {
    const t = now(), s = Math.max(0, Math.min(1, m.strength ?? 0.6));
    switch (m.id) {
      case 'knock': case 'back_knock': face.startle(0.6 + 0.4 * s, t); body.impulse(0, (Math.random() - 0.5) * 2); break;
      case 'shake_hard': body.impulse((Math.random() < 0.5 ? -1 : 1) * 900 * s, 6 * (Math.random() - 0.5)); break;
      case 'shake_light': body.impulse((Math.random() < 0.5 ? -1 : 1) * 250, 1.5 * (Math.random() - 0.5)); break;
      case 'pickup': face.startle(0.5, t); break;
    }
  });

  avatar.onFrame((dt, timeMs) => {
    const t = timeMs / 1000;
    const { bump, side } = body.step(dt, up, timeMs);
    if (bump > 0) {
      send({ type: 'bump', impact: bump });
      face.startle(bump, t);
      if (bump > 0.5) face.setExpression('surprise', t, 0.8);
    }
    const { params, body: m } = face.frame(dt, t);
    // Head counter-rotates a little, body leans with the slide.
    params.ParamAngleZ = (params.ParamAngleZ ?? 0) - body.rot * 25;
    params.ParamBodyAngleZ = (params.ParamBodyAngleZ ?? 0) + Math.max(-10, Math.min(10, body.vx * 0.02));
    params.ParamBodyAngleX = (params.ParamBodyAngleX ?? 0) + Math.max(-15, Math.min(15, body.vx * 0.04));
    params.ParamEyeBallX = Math.max(-1, Math.min(1, (params.ParamEyeBallX ?? 0) + body.vx * 0.002));
    if (side) params.ParamAngleX = (params.ParamAngleX ?? 0) - side * 12 * bump;
    for (const [id, v] of Object.entries(params)) if (Number.isFinite(v)) avatar.setParam(id, v);
    avatar.setPhysicsGravity(body.gravity.x, body.gravity.y);
    const { width, height } = view();
    avatar.setTransform({ x: body.x + m.dx * width, y: frameY + m.dy * height, rotation: body.rot + m.rot, scale: FRAME.scale * m.scale });
  });
  window.life = { body, face, ui };
  send({ type: 'lifeReady' });
}

if (window.avatar) attach(window.avatar);
else addEventListener('vtubephone:avatarready', e => attach(e.detail), { once: true });
