// Rendering coordinates only; body physics and collision responses belong to the PM.
export function placement(view, bounds, transform) {
  const fit = Math.min(view.width * .9 / bounds.width, view.height * .9 / bounds.height);
  return { cx: view.width / 2 + transform.x, cy: view.height / 2 + transform.y,
    unit: fit * transform.scale, rotation: transform.rotation,
    ox: bounds.x + bounds.width / 2, oy: bounds.y + bounds.height / 2 };
}
export function toScreen(x, y, p) {
  const dx = (x - p.ox) * p.unit, dy = -(y - p.oy) * p.unit;
  const c = Math.cos(p.rotation), s = Math.sin(p.rotation);
  return { x: p.cx + c * dx - s * dy, y: p.cy + s * dx + c * dy };
}
export function toModel(x, y, p) {
  const dx = x - p.cx, dy = y - p.cy;
  const c = Math.cos(p.rotation), s = Math.sin(p.rotation);
  return { x: p.ox + (c * dx + s * dy) / p.unit, y: p.oy - (-s * dx + c * dy) / p.unit };
}
export function screenBounds(bounds, p) {
  const corners = [[bounds.x, bounds.y], [bounds.x + bounds.width, bounds.y],
    [bounds.x, bounds.y + bounds.height], [bounds.x + bounds.width, bounds.y + bounds.height]]
    .map(([x, y]) => toScreen(x, y, p));
  const x = Math.min(...corners.map(p => p.x)), y = Math.min(...corners.map(p => p.y));
  return { x, y, width: Math.max(...corners.map(p => p.x)) - x, height: Math.max(...corners.map(p => p.y)) - y };
}
export function assetUrl(name, base) {
  if (typeof name !== 'string' || name.startsWith('/') || name.includes('\\') || name.split('/').includes('..')) {
    throw new Error('Invalid model asset path');
  }
  return new URL(name.split('/').map(encodeURIComponent).join('/'), base).href;
}
