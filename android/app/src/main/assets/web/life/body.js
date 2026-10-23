// The avatar's body inside the "phone box": she slides toward whichever edge is down, leans to stay upright
// in the real world, and bumps into the screen edges. Pure (no DOM) so it can be unit-tested in Node.

const G = 9.80665;

export class BoxBody {
  constructor({ pxPerMps2 = 300, spring = 10, damping = 2.6, deadZone = 0.12, restitution = 0.35,
    minImpactSpeed = 80, fullImpactSpeed = 700, bumpRefractoryMs = 120, maxLean = 0.6 } = {}) {
    Object.assign(this, { pxPerMps2, spring, damping, deadZone, restitution, minImpactSpeed, fullImpactSpeed, bumpRefractoryMs, maxLean });
    this.x = 0; this.vx = 0;
    this.rot = 0; this.vrot = 0;
    this.wall = 100;
    this.lastBumpMs = -Infinity;
    this.up = [0, 1, 0];
    this.gravity = { x: 0, y: -1 };
  }

  /** Half the horizontal travel in CSS px (her centre may move ±wall). */
  setWall(px) { this.wall = Math.max(0, px); this.x = Math.max(-this.wall, Math.min(this.wall, this.x)); }

  /** Kick from a gesture (e.g. a shake): velocities in px/s and rad/s. */
  impulse(vx, vrot = 0) { this.vx += vx; this.vrot += vrot; }

  /**
   * Advances by dt seconds with the device up vector (Android device coordinates: x right, y up, z out of
   * the screen). Returns `{ bump }` with impact 0..1 when she hits an edge hard enough.
   */
  step(dt, up, nowMs) {
    if (up && up.every(Number.isFinite)) this.up = up;
    const [ux, uy] = this.up;
    const inPlane = Math.hypot(ux, uy);
    const weight = smoothstep(0.25, 0.6, inPlane); // lying flat: no "down" on the screen
    let result = { bump: 0 };
    if (dt <= 0) return result;
    // Screen-plane gravity toward the lower edge (CSS x to the right). Small tilts are absorbed by "friction".
    let gx = -ux * G * weight;
    const dead = this.deadZone * G;
    gx = Math.abs(gx) <= dead ? 0 : gx - Math.sign(gx) * dead;
    const ax = gx * this.pxPerMps2 - this.spring * this.x - this.damping * this.vx;
    this.vx += ax * dt;
    this.x += this.vx * dt;
    if (Math.abs(this.x) > this.wall) {
      const speed = Math.abs(this.vx);
      this.x = Math.sign(this.x) * this.wall;
      if (Math.sign(this.vx) === Math.sign(this.x)) {
        this.vx = -this.vx * this.restitution;
        if (speed >= this.minImpactSpeed && nowMs - this.lastBumpMs >= this.bumpRefractoryMs) {
          this.lastBumpMs = nowMs;
          const impact = Math.min(1, speed / this.fullImpactSpeed);
          this.vrot += -Math.sign(this.x) * 3 * impact; // the head keeps going a little
          result = { bump: impact, side: Math.sign(this.x) };
        }
      }
    }
    // Lean: stay partly upright relative to real gravity, and swing back when accelerating sideways.
    const target = clamp(Math.atan2(ux, uy) * 0.5 * weight, -this.maxLean, this.maxLean);
    const arot = 60 * (target - this.rot) - 8 * this.vrot - ax * 0.0015;
    this.vrot += arot * dt;
    this.rot += this.vrot * dt;
    // Gravity for Cubism hair physics, in the rotated model frame (y up).
    const sx = -ux, sy = -uy, c = Math.cos(this.rot), s = Math.sin(this.rot);
    const mx = sx * c - sy * s, my = sx * s + sy * c, len = Math.hypot(mx, my);
    const g = len > 1e-3 ? { x: mx / len, y: my / len } : { x: 0, y: -1 };
    this.gravity = { x: g.x * weight, y: g.y * weight - (1 - weight) };
    return result;
  }
}

export const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
export function smoothstep(a, b, v) { const t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
