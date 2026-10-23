export function bindTouches(canvas, avatar, send) {
  const active = new Set();
  const handlers = new Map();
  for (const [type, phase] of [['pointerdown', 'down'], ['pointermove', 'move'], ['pointerup', 'up'], ['pointercancel', 'cancel']]) {
    const handler = event => {
      if (phase === 'down') {
        active.add(event.pointerId);
        canvas.setPointerCapture(event.pointerId);
      } else if (!active.has(event.pointerId)) return;
      const rect = canvas.getBoundingClientRect();
      const x = event.clientX - rect.left, y = event.clientY - rect.top;
      send({ type: 'touch', phase, pointerId: event.pointerId, x, y,
        timeMs: event.timeStamp, ageMs: Math.max(0, performance.now() - event.timeStamp),
        inAvatar: avatar.hitTest(x, y), bounds: avatar.getBounds() });
      if (phase === 'up' || phase === 'cancel') active.delete(event.pointerId);
      event.preventDefault();
    };
    handlers.set(type, handler);
    canvas.addEventListener(type, handler);
  }
  const lost = event => {
    if (!active.delete(event.pointerId)) return;
    send({ type: 'touch', phase: 'cancel', pointerId: event.pointerId,
      x: 0, y: 0, timeMs: event.timeStamp, ageMs: Math.max(0, performance.now() - event.timeStamp),
      inAvatar: false, bounds: avatar.getBounds() });
  };
  canvas.addEventListener('lostpointercapture', lost);
  return () => {
    handlers.forEach((handler, type) => canvas.removeEventListener(type, handler));
    canvas.removeEventListener('lostpointercapture', lost);
    active.clear();
  };
}
