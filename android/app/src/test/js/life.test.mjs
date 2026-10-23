import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { BoxBody } from '../../main/assets/web/life/body.js';
import { Face } from '../../main/assets/web/life/face.js';
import { BASE, EXPRESSIONS, MOTIONS, GESTURE_HINTS, idleFace } from '../../main/assets/web/life/presets.js';

const deg = d => d * Math.PI / 180;
/** Device up vector when the left edge is lowered by `d` degrees (phone upright). */
const tiltLeft = d => [Math.sin(deg(d)), Math.cos(deg(d)), 0];
function run(body, up, seconds, start = 0) {
  const bumps = [];
  for (let i = 0; i < seconds * 60; i++) {
    const t = start + i * 1000 / 60;
    const r = body.step(1 / 60, up, t);
    if (r.bump) bumps.push({ t, ...r });
  }
  return bumps;
}

test('upright and small tilts keep her in place', () => {
  const body = new BoxBody();
  assert.equal(run(body, [0, 1, 0], 2).length, 0);
  assert.ok(Math.abs(body.x) < 1e-6);
  run(body, tiltLeft(5), 2);
  assert.ok(Math.abs(body.x) < 1, 'friction absorbs a 5° wobble');
});

test('tilting slides her toward the lower edge and bumps the wall once', () => {
  const body = new BoxBody();
  body.setWall(100);
  const bumps = run(body, tiltLeft(35), 3);
  assert.ok(body.x < -99, 'resting against the left wall');
  assert.ok(bumps.length >= 1 && bumps.length <= 3, `bounces settle (${bumps.length})`);
  assert.equal(bumps[0].side, -1);
  assert.ok(bumps[0].bump > 0.2 && bumps[0].bump <= 1);
  for (let i = 1; i < bumps.length; i++) assert.ok(bumps[i].bump < bumps[i - 1].bump, 'each bounce is softer');
  const right = run(body, [-Math.sin(deg(35)), Math.cos(deg(35)), 0], 3, 3000);
  assert.ok(body.x > 99 && right[0].side === 1);
});

test('a gentle 20° tilt slides without hitting the wall', () => {
  const body = new BoxBody();
  body.setWall(100);
  assert.equal(run(body, tiltLeft(20), 3).length, 0);
  assert.ok(body.x < -30 && body.x > -100);
});

test('bumps respect the refractory period', () => {
  const body = new BoxBody({ restitution: 1, spring: 0, damping: 0 });
  body.setWall(1);
  body.vx = -500;
  const bumps = run(body, [0, 0, 1], 0.1);
  for (let i = 1; i < bumps.length; i++) assert.ok(bumps[i].t - bumps[i - 1].t >= 120);
});

test('lying flat: no slide, hair hangs straight down; tilted: gravity stays a unit vector', () => {
  const flat = new BoxBody();
  run(flat, [0.02, 0.01, 1], 2);
  assert.ok(Math.abs(flat.x) < 1);
  assert.ok(Math.abs(flat.gravity.x) < 1e-9 && flat.gravity.y === -1);
  const tilted = new BoxBody();
  run(tilted, tiltLeft(40), 2);
  assert.ok(Math.abs(Math.hypot(tilted.gravity.x, tilted.gravity.y) - 1) < 1e-6);
  assert.ok(tilted.gravity.x < 0, 'hair swings toward the lowered edge');
  assert.ok(tilted.rot > 0 && tilted.rot <= 0.6, 'leans clockwise to stay upright');
});

// Parameter ranges of the owner model (moc3), for the parameters this layer drives.
const RANGES = { ParamEyeLOpen: [0, 1.4], ParamEyeROpen: [0, 1.4], ParamEyeLSmile: [0, 1], ParamEyeRSmile: [0, 1],
  ParamEyeBallX: [-1, 1], ParamEyeBallY: [-1, 1], ParamBrowLY: [-1, 1], ParamBrowLForm: [-1, 1], ParamMouthForm: [-1, 1],
  ParamMouthOpenY: [0, 1], ParamAngleX: [-30, 30], ParamAngleY: [-20, 20], ParamAngleZ: [-30, 30],
  ParamBodyAngleX: [-30, 30], ParamBodyAngleY: [-30, 30], ParamBodyAngleZ: [-10, 10], ParamBreath: [0, 1],
  Param59: [0, 30], Param60: [0, 30], Param67: [0, 30], Param68: [0, 30], Param69: [0, 30], Param25: [0, 30], Param26: [0, 30], Param27: [0, 30],
  Param53: [0, 30], Param21: [0, 1], Param45: [0, 1], Param48: [0, 1], Param51: [0, 1], Param52: [0, 1], Param20: [-30, 30],
  Param46: [0, 1], Param28: [-30, 30], Param30: [-30, 30] };

test('every expression and motion stays inside the model ranges', () => {
  for (const name of Object.keys(EXPRESSIONS)) for (const motion of Object.keys(MOTIONS)) {
    const face = new Face(() => 0.5);
    face.setBase(name, 0); face.playMotion(motion, 0);
    for (let i = 0; i <= 240; i++) {
      const { params, body } = face.frame(1 / 60, i / 60);
      for (const [id, v] of Object.entries(params)) {
        assert.ok(RANGES[id], `unknown parameter ${id}`);
        assert.ok(Number.isFinite(v), `${name}/${motion} ${id}`);
        // Additive layers may exceed slightly; Cubism clamps, but nothing should be wildly off.
        assert.ok(v >= RANGES[id][0] - 12 && v <= RANGES[id][1] + 12, `${name}/${motion} ${id}=${v}`);
      }
      assert.ok([body.dx, body.dy, body.rot, body.scale].every(Number.isFinite) && body.scale > 0);
    }
  }
  for (const state of [{ sleeping: true }, { grudge: 0.8 }, { mood: 0.9 }, { mood: -0.8, energy: 0.1 }])
    for (const id of Object.keys(idleFace(state))) assert.ok(id in BASE, id);
});

test('motions end cleanly: wave leaves no raised arm behind', () => {
  const face = new Face(() => 0.5);
  face.playMotion('wave', 0);
  let peak = 0;
  for (let i = 0; i < 60 * 2.5; i++) peak = Math.max(peak, face.frame(1 / 60, i / 60).params.Param28);
  assert.ok(peak > 20);
  assert.ok(Math.abs(face.frame(1 / 60, 3).params.Param28) < 1e-6);
});

test('a reflex face returns to the turn face', () => {
  const face = new Face(() => 0.5);
  face.setBase('happy', 0);
  face.setExpression('surprise', 1, 2.4);
  assert.equal(face.expression.name, 'surprise');
  face.frame(1 / 60, 3.5);
  assert.equal(face.expression.name, 'happy');
});

test('speech flaps the mouth and native lip sync overrides it', () => {
  const face = new Face(() => 0.5);
  face.speak('こんにちは', 0);
  const open = Array.from({ length: 30 }, (_, i) => face.frame(1 / 60, i / 60).params.ParamMouthOpenY);
  assert.ok(Math.max(...open) > 0.4 && Math.min(...open) < 0.3);
  face.mouth(0, 0.5);
  assert.ok(face.frame(1 / 60, 0.55).params.ParamMouthOpenY < 0.05);
  assert.ok(face.frame(1 / 60, 5).params.ParamMouthOpenY < 0.05, 'stops after the line');
});

test('blinks happen and reopen', () => {
  const face = new Face(() => 0.5);
  const eyes = Array.from({ length: 60 * 8 }, (_, i) => face.frame(1 / 60, i / 60).params.ParamEyeLOpen);
  assert.ok(Math.min(...eyes) < 0.2);
  assert.ok(eyes.at(-1) > 0.9);
});

// Contract with the Kotlin side: every wire name the brain can send has a presentation here.
const kotlin = p => fs.readFileSync(new URL(`../../../../core/src/main/kotlin/com/vtubephone/core/${p}`, import.meta.url), 'utf8');
const wires = (src, enumName) => [...src.slice(src.indexOf(`enum class ${enumName}`)).split(';')[0].matchAll(/\("([a-z_]+)"\)/g)].map(m => m[1]);

test('wire names match the Kotlin enums', () => {
  const turn = kotlin('brain/Turn.kt');
  assert.deepEqual(Object.keys(EXPRESSIONS).sort(), wires(turn, 'Expression').sort());
  assert.deepEqual(Object.keys(MOTIONS).sort(), wires(turn, 'Motion').sort());
  assert.deepEqual(Object.keys(GESTURE_HINTS).sort(), wires(kotlin('gesture/Gesture.kt'), 'GestureId').sort());
});
