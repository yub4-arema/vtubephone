import { CubismFramework, CubismMoc, CubismRenderer_WebGL, CubismPhysics, CubismMatrix44 }
  from '../vendor/cubism/framework.js';
import { assetUrl, placement, screenBounds, toModel } from './geometry.js';

export async function fetchAsset(url, type = 'arrayBuffer') {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Asset load failed (${response.status})`);
  return response[type]();
}

export async function loadCore(url) {
  await new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = url;
    script.onload = resolve;
    script.onerror = () => reject(new Error('Cubism Core を読み込めませんでした'));
    document.head.append(script);
  });
  // The embedded WASM can finish after the script's load event.
  const deadline = performance.now() + 15000;
  while (performance.now() < deadline) {
    try { if (globalThis.Live2DCubismCore?.Version.csmGetVersion()) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw new Error('Cubism Core の初期化がタイムアウトしました');
}

export class Avatar {
  constructor(canvas) {
    this.canvas = canvas;
    this.params = new Map();
    this.expressions = new Map();
    this.callbacks = new Set();
    this.transform = { x: 0, y: 0, rotation: 0, scale: 1 };
    this.textures = [];
    this.expressionGeneration = 0;
  }

  async load(modelUrl) {
    try {
      this.gl = this.canvas.getContext('webgl', { alpha: true, premultipliedAlpha: true });
      if (!this.gl) throw new Error('WebGL を利用できません');
      CubismFramework.startUp();
      if (!CubismFramework.isInitialized()) CubismFramework.initialize();
      const settings = await fetchAsset(modelUrl, 'json');
      this.base = new URL('.', modelUrl);
      const refs = settings.FileReferences;
      this.expressionFiles = new Map((refs.Expressions ?? []).map(e => [e.Name, e.File]));
      const bytes = await fetchAsset(assetUrl(refs.Moc, this.base));
      this.moc = CubismMoc.create(bytes, true);
      if (!this.moc) throw new Error('moc3 を読み込めません');
      this.model = this.moc.createModel();
      if (!this.model) throw new Error('モデルを生成できません');
      this.model.saveParameters();
      this.model.update();
      this.localBounds = this.measureModel();
      if (refs.Physics) {
        const bytes = await fetchAsset(assetUrl(refs.Physics, this.base));
        this.physics = CubismPhysics.create(bytes, bytes.byteLength);
      }
      this.renderer = new CubismRenderer_WebGL();
      this.renderer.initialize(this.model);
      this.renderer.startUp(this.gl);
      this.renderer.setIsPremultipliedAlpha(true);
      for (const [index, name] of refs.Textures.entries()) {
        const image = new Image();
        image.src = assetUrl(name, this.base);
        await image.decode();
        const gl = this.gl;
        if (Math.max(image.width, image.height) > gl.getParameter(gl.MAX_TEXTURE_SIZE)) {
          throw new Error('テクスチャが端末の上限を超えています');
        }
        const texture = gl.createTexture();
        if (!texture) throw new Error('テクスチャを確保できません');
        this.textures.push(texture);
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 1);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        this.renderer.bindTexture(index, texture);
      }
      this.resize();
      this.observer = new ResizeObserver(() => this.resize());
      this.observer.observe(this.canvas);
      this.contextLost = event => { event.preventDefault(); this.stop(); this.canvas.dispatchEvent(new Event('avatarerror')); };
      this.canvas.addEventListener('webglcontextlost', this.contextLost);
      return this;
    } catch (error) { this.destroy(); throw error; }
  }

  measureModel() {
    let left = Infinity, right = -Infinity, bottom = Infinity, top = -Infinity;
    for (let i = 0; i < this.model.getDrawableCount(); i++) {
      if (this.model.getDrawableOpacity(i) <= 0) continue;
      const vertices = this.model.getDrawableVertices(i);
      for (let j = 0; j < vertices.length; j += 2) {
        left = Math.min(left, vertices[j]); right = Math.max(right, vertices[j]);
        bottom = Math.min(bottom, vertices[j + 1]); top = Math.max(top, vertices[j + 1]);
      }
    }
    if (![left, right, bottom, top].every(Number.isFinite) || right <= left || top <= bottom) {
      throw new Error('モデルの描画領域がありません');
    }
    return { x: left, y: bottom, width: right - left, height: top - bottom };
  }

  setParam(id, value, weight = 1) {
    if (typeof id !== 'string' || !Number.isFinite(value) || !Number.isFinite(weight)) throw new TypeError('Invalid parameter');
    this.params.set(id, { value, weight: Math.max(0, Math.min(1, weight)) });
  }

  async applyExpressionFile(name) {
    const file = this.expressionFiles.get(name) ?? [...this.expressionFiles.values()].find(file => file === name);
    if (!file) throw new Error(`Unknown expression file: ${name}`);
    const generation = this.expressionGeneration;
    const expression = await fetchAsset(assetUrl(file, this.base), 'json');
    if (generation !== this.expressionGeneration) return;
    if (!Array.isArray(expression.Parameters) || expression.Parameters.some(p =>
      typeof p.Id !== 'string' || !Number.isFinite(p.Value) || !['Add', 'Multiply', 'Overwrite', undefined].includes(p.Blend))) {
      throw new Error('Invalid expression file');
    }
    this.expressions.set(file, expression.Parameters);
  }

  clearExpressions() { this.expressionGeneration++; this.expressions.clear(); }

  setPhysicsGravity(x, y) {
    if (![x, y].every(Number.isFinite)) throw new TypeError('Invalid gravity');
    if (!this.physics) return;
    const options = this.physics.getOption();
    options.gravity.x = x; options.gravity.y = y;
    this.physics.setOptions(options);
  }

  setTransform(next) {
    const value = { ...this.transform, ...next };
    if (!['x', 'y', 'rotation', 'scale'].every(key => Number.isFinite(value[key])) || value.scale <= 0) {
      throw new TypeError('Invalid transform');
    }
    this.transform = value;
  }

  get placement() { return placement(this.view, this.localBounds, this.transform); }
  getBounds() { return screenBounds(this.localBounds, this.placement); }
  hitTest(x, y) {
    if (![x, y].every(Number.isFinite)) return false;
    const p = toModel(x, y, this.placement), b = this.localBounds;
    return p.x >= b.x && p.x <= b.x + b.width && p.y >= b.y && p.y <= b.y + b.height;
  }
  onFrame(callback) { this.callbacks.add(callback); return () => this.callbacks.delete(callback); }

  resize() {
    const rect = this.canvas.getBoundingClientRect();
    this.view = { width: Math.max(1, rect.width), height: Math.max(1, rect.height) };
    const dpr = Math.min(globalThis.devicePixelRatio || 1, 2);
    this.canvas.width = Math.round(this.view.width * dpr);
    this.canvas.height = Math.round(this.view.height * dpr);
  }

  render(dt, timeMs) {
    this.model.loadParameters();
    for (const callback of this.callbacks) callback(dt, timeMs);
    const id = value => CubismFramework.getIdManager().getId(value);
    for (const [key, { value, weight }] of this.params) this.model.setParameterValueById(id(key), value, weight);
    for (const parameters of this.expressions.values()) for (const p of parameters) {
      if (p.Blend === 'Overwrite') this.model.setParameterValueById(id(p.Id), p.Value);
      else if (p.Blend === 'Multiply') this.model.multiplyParameterValueById(id(p.Id), p.Value);
      else this.model.addParameterValueById(id(p.Id), p.Value);
    }
    this.physics?.evaluate(this.model, dt);
    this.model.update();
    const p = this.placement, { width: w, height: h } = this.view;
    const c = Math.cos(p.rotation), s = Math.sin(p.rotation), u = p.unit;
    // Model units (y up) -> CSS pixels (y down) -> clip space. Same mapping as hitTest/getBounds.
    const matrix = new CubismMatrix44();
    matrix.setMatrix(new Float32Array([
      2*c*u/w, -2*s*u/h, 0, 0, 2*s*u/w, 2*c*u/h, 0, 0, 0, 0, 1, 0,
      2*(p.cx-u*(c*p.ox+s*p.oy))/w-1, 1-2*(p.cy-u*s*p.ox+u*c*p.oy)/h, 0, 1,
    ]));
    const gl = this.gl;
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    this.renderer.setMvpMatrix(matrix);
    this.renderer.setRenderState(null, [0, 0, this.canvas.width, this.canvas.height]);
    this.renderer.drawModel();
  }

  start() {
    if (this.running) return;
    this.running = true;
    let last;
    const tick = now => {
      if (!this.running) return;
      try { this.render(last === undefined ? 0 : Math.min((now - last) / 1000, .05), now); }
      catch (error) { console.error(error); this.stop(); this.canvas.dispatchEvent(new Event('avatarerror')); return; }
      last = now;
      this.frame = requestAnimationFrame(tick);
    };
    this.frame = requestAnimationFrame(tick);
  }
  stop() { this.running = false; cancelAnimationFrame(this.frame); }
  destroy() {
    this.stop();
    this.observer?.disconnect();
    if (this.contextLost) this.canvas.removeEventListener('webglcontextlost', this.contextLost);
    this.renderer?.release();
    this.textures.forEach(texture => this.gl.deleteTexture(texture));
    this.textures = [];
    this.physics?.release();
    if (this.model) this.moc.deleteModel(this.model);
    this.moc?.release();
    this.model = null; this.moc = null;
    this.callbacks.clear();
    this.clearExpressions();
  }
}
