import { Avatar, fetchAsset, loadCore } from './avatar/avatar.js';
import { bindTouches } from './avatar/touch.js';

const status = document.querySelector('#status');
const canvas = document.querySelector('#avatar-canvas');
const bridge = window.vtubephone;
export const send = message => bridge?.postMessage(JSON.stringify(message));
let calibration = null, samples = 0;
export const timing = () => ({ sentMs: performance.now(), clockOffsetMs: calibration?.clockOffsetMs,
  uncertaintyMs: calibration?.uncertaintyMs });
function sync() { send({ type: 'clockSync', sentMs: performance.now() }); }
if (bridge) {
  bridge.onmessage = ({ data }) => {
    try {
      const receivedMs = performance.now(), message = JSON.parse(data);
      if (message.type === 'clockSync') {
        const roundTripMs = Math.max(0, receivedMs - message.jsSentMs - (message.nativeSentMs - message.nativeReceivedMs));
        const clockOffsetMs = ((message.nativeReceivedMs - message.jsSentMs) + (message.nativeSentMs - receivedMs)) / 2;
        if (!calibration || roundTripMs < calibration.roundTripMs) calibration = { roundTripMs, clockOffsetMs, uncertaintyMs: roundTripMs / 2 };
        if (++samples < 5) sync();
      }
      // PM subscribes here; this layer does not select expressions or reactions.
      window.dispatchEvent(new CustomEvent(`vtubephone:${message.type}`, { detail: message }));
    } catch (error) { console.warn('Invalid native message', error); }
  };
  send({ type: 'ready' });
  sync();
}

export async function initializeAvatar() {
  let avatar;
  try {
    const manifest = await fetchAsset(new URL('live2d-assets.json', import.meta.url), 'json');
    if (!manifest.model) throw new Error(manifest.modelError ?? 'モデル未設定');
    if (!manifest.core) throw new Error(manifest.coreError ?? 'Cubism Core を利用できません');
    await loadCore(new URL(manifest.core, import.meta.url));
    avatar = await new Avatar(canvas).load(new URL(manifest.model, import.meta.url));
    window.avatar = avatar;
    const unbind = bindTouches(canvas, avatar, send);
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) avatar.stop();
      else { avatar.start(); calibration = null; samples = 0; sync(); }
    });
    window.addEventListener('pagehide', () => { unbind(); avatar.destroy(); }, { once: true });
    canvas.addEventListener('avatarerror', () => { status.textContent = '描画を停止しました。アプリを開き直してください'; });
    status.textContent = '';
    if (!document.hidden) avatar.start();
    window.dispatchEvent(new CustomEvent('vtubephone:avatarready', { detail: avatar }));
    return avatar;
  } catch (error) {
    avatar?.destroy();
    console.error(error);
    status.textContent = error.message || 'モデルを読み込めませんでした';
    return null;
  }
}
void initializeAvatar();
