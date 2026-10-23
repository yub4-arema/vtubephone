import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';

const assets = path.resolve('android/app/build/generated/live2d/assets');
const core = fs.readFileSync(path.join(assets, 'web/vendor/live2dcubismcore.min.js'), 'utf8');
globalThis.__dirname = path.join(assets, 'web/vendor');
globalThis.require = createRequire(import.meta.url);
try { vm.runInThisContext(core, { filename: 'live2dcubismcore.min.js' }); }
catch (error) { throw new Error(`Core initialization: ${error.message}`); }
// Core initializes its embedded WASM asynchronously.
for (let i = 0; i < 100; i++) {
  try { if (Live2DCubismCore.Version.csmGetVersion()) break; } catch {}
  await new Promise(resolve => setTimeout(resolve, 20));
}
const { CubismFramework, CubismMoc } = await import('../android/app/src/main/assets/web/vendor/cubism/framework.js');
CubismFramework.startUp();
CubismFramework.initialize();
const setting = JSON.parse(fs.readFileSync(path.join(assets, 'models/default/model3.json')));
const bytes = fs.readFileSync(path.join(assets, 'models/default', setting.FileReferences.Moc));
const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
const moc = CubismMoc.create(buffer, true);
assert.ok(moc, 'moc3 must load with the bundled Framework and downloaded Core');
assert.equal(moc.getMocVersion(), 5, 'owner model must be moc3 version 5');
const model = moc.createModel();
assert.ok(model);
model.update();
assert.ok(model.getDrawableCount() > 0);
console.log(JSON.stringify({ coreVersion: Live2DCubismCore.Version.csmGetVersion(), mocVersion: moc.getMocVersion(),
  drawableCount: model.getDrawableCount(), canvasWidth: model.getCanvasWidth(), canvasHeight: model.getCanvasHeight() }));
for (const file of setting.FileReferences.Textures) {
  const png = fs.readFileSync(path.join(assets, 'models/default', file));
  assert.ok(png.readUInt32BE(16) <= 4096 && png.readUInt32BE(20) <= 4096);
  console.log(`${file}: ${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`);
}
moc.deleteModel(model);
moc.release();
CubismFramework.dispose();
