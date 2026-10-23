import test from 'node:test';
import assert from 'node:assert/strict';
import { Avatar, fetchAsset } from '../../main/assets/web/avatar/avatar.js';
import { CubismFramework } from '../../main/assets/web/vendor/cubism/framework.js';
import { assetUrl, placement, toScreen, toModel, screenBounds } from '../../main/assets/web/avatar/geometry.js';
import { bindTouches } from '../../main/assets/web/avatar/touch.js';

const bounds = { x: -.5, y: -1, width: 1, height: 2 };
test('screen/model round trip includes translation, rotation, scale and portrait fitting', () => {
  for (const rotation of [0, .4, Math.PI / 2, -Math.PI]) {
    const p = placement({ width: 400, height: 800 }, bounds, { x: 25, y: -30, scale: .7, rotation });
    const screen = toScreen(.2, -.7, p), model = toModel(screen.x, screen.y, p);
    assert.ok(Math.abs(model.x - .2) < 1e-10 && Math.abs(model.y + .7) < 1e-10);
    const b = screenBounds(bounds, p);
    assert.ok(screen.x >= b.x && screen.x <= b.x + b.width && screen.y >= b.y && screen.y <= b.y + b.height);
  }
});
test('bounds rotate around their center and hitTest uses inverse transform', () => {
  const a = new Avatar({});
  a.view = { width: 400, height: 800 }; a.localBounds = bounds;
  a.setTransform({ x: 20, y: 10, rotation: Math.PI / 2 });
  const b = a.getBounds();
  assert.ok(Math.abs(b.width - 720) < 1e-8 && Math.abs(b.height - 360) < 1e-8);
  assert.equal(a.hitTest(220, 410), true);
  assert.equal(a.hitTest(220, 10), false);
});
test('asset URLs encode Unicode, spaces, hash and percent without losing folders', () => {
  assert.equal(assetUrl('魔女.8192/a #%.png', 'https://example.test/models/default/'),
    'https://example.test/models/default/%E9%AD%94%E5%A5%B3.8192/a%20%23%25.png');
  for (const name of ['../secret', '/outside', 'a\\b']) assert.throws(() => assetUrl(name, 'https://example.test/'));
});
test('API rejects invalid transforms and parameters without changing valid state', () => {
  const a = new Avatar({});
  for (const scale of [0, -1, NaN, Infinity]) assert.throws(() => a.setTransform({ scale }));
  assert.equal(a.transform.scale, 1);
  assert.throws(() => a.setParam('id', NaN));
  a.setParam('id', 2, 4);
  assert.deepEqual(a.params.get('id'), { value: 2, weight: 1 });
});
test('expression lookup handles owner exp files and clear cancels in-flight loads', async () => {
  const a = new Avatar({});
  a.base = new URL('https://example.test/models/default/');
  a.expressionFiles = new Map([['x', 'x.exp3.json']]);
  const original = globalThis.fetch;
  try {
    let complete;
    globalThis.fetch = () => new Promise(resolve => { complete = resolve; });
    const pending = a.applyExpressionFile('x');
    a.clearExpressions();
    complete({ ok: true, json: async () => ({ Parameters: [{ Id: 'Eye', Value: 1, Blend: 'Add' }] }) });
    await pending;
    assert.equal(a.expressions.size, 0);
    globalThis.fetch = async () => ({ ok: true, json: async () => ({ Parameters: [{ Id: 'Eye', Value: 1 }] }) });
    await a.applyExpressionFile('x.exp3.json');
    assert.equal(a.expressions.size, 1);
    await assert.rejects(a.applyExpressionFile('missing'));
  } finally { globalThis.fetch = original; }
});
test('each frame restores baseline: expression Add/Multiply do not accumulate', () => {
  const a = new Avatar({ width: 400, height: 800 });
  a.view = { width: 400, height: 800 }; a.localBounds = bounds;
  let value;
  a.model = { loadParameters() { value = 2; }, update() {},
    setParameterValueById(_, v, weight = 1) { value = value * (1 - weight) + v * weight; },
    addParameterValueById(_, v) { value += v; }, multiplyParameterValueById(_, v) { value *= v; } };
  a.renderer = { setMvpMatrix() {}, setRenderState() {}, drawModel() {} };
  a.gl = { viewport() {}, clearColor() {}, clear() {} };
  const original = CubismFramework.getIdManager;
  CubismFramework.getIdManager = () => ({ getId: x => x });
  try {
    let called = 0;
    const off = a.onFrame(() => { called++; a.setParam('p', 4, .5); });
    a.expressions.set('x', [{ Id: 'p', Value: 2, Blend: 'Add' }, { Id: 'p', Value: 2, Blend: 'Multiply' }]);
    a.render(.016, 1); assert.equal(value, 10);
    a.render(.016, 2); assert.equal(value, 10);
    off(); assert.equal(called, 2);
    a.clearExpressions(); a.render(.016, 3); assert.equal(value, 3);
  } finally { CubismFramework.getIdManager = original; }
});
test('gravity is passed to Cubism options without choosing a device mapping', () => {
  const a = new Avatar({}), options = { gravity: { x: 0, y: -1 } };
  a.physics = { getOption: () => options, setOptions: v => assert.equal(v, options) };
  a.setPhysicsGravity(.2, -.8);
  assert.deepEqual(options.gravity, { x: .2, y: -.8 });
});
test('touch stream preserves pointer identity, coordinates, cancellation and event timestamps', () => {
  const handlers = new Map(), messages = [];
  const canvas = { addEventListener: (t, f) => handlers.set(t, f), removeEventListener: t => handlers.delete(t),
    getBoundingClientRect: () => ({ left: 10, top: 20 }), setPointerCapture() {} };
  const off = bindTouches(canvas, { hitTest: x => x < 50, getBounds: () => bounds }, m => messages.push(m));
  const event = { pointerId: 7, clientX: 40, clientY: 60, timeStamp: performance.now(), preventDefault() {} };
  handlers.get('pointerdown')(event);
  handlers.get('pointermove')(event);
  handlers.get('lostpointercapture')(event);
  handlers.get('pointerup')(event);
  assert.deepEqual(messages.map(m => m.phase), ['down', 'move', 'cancel']);
  assert.equal(messages[0].pointerId, 7); assert.equal(messages[0].x, 30); assert.equal(messages[0].y, 40);
  assert.equal(messages[0].timeMs, event.timeStamp); assert.equal(messages[0].inAvatar, true);
  off(); assert.equal(handlers.size, 0);
});
test('asset HTTP failure rejects so bootstrap can show status instead of throwing unhandled', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => ({ ok: false, status: 404 });
    await assert.rejects(fetchAsset('/missing'), /404/);
  } finally { globalThis.fetch = original; }
});
