// Caption bubble and "how to answer" hints. Hints pair a glyph with words and show a countdown bar.
import { GESTURE_HINTS } from './presets.js';

export class Ui {
  constructor(doc = document) {
    this.caption = doc.querySelector('#caption');
    this.options = doc.querySelector('#options');
    this.doc = doc;
    this.captionTimer = 0;
  }

  say(text, holdMs) {
    clearTimeout(this.captionTimer);
    if (!text) { this.caption.classList.remove('shown'); return; }
    this.caption.textContent = text;
    this.caption.classList.add('shown');
    this.captionTimer = setTimeout(() => this.caption.classList.remove('shown'), holdMs);
  }

  showOptions(options, timeoutSec) {
    this.options.replaceChildren();
    if (!options?.length) return;
    for (const o of options) {
      const hint = GESTURE_HINTS[o.gesture] ?? { glyph: '•', verb: o.gesture };
      const chip = this.doc.createElement('div');
      chip.className = 'chip';
      const glyph = this.doc.createElement('span');
      glyph.className = 'glyph';
      glyph.textContent = o.knockCount ? `${hint.glyph}×${o.knockCount}` : hint.glyph;
      const label = this.doc.createElement('span');
      label.className = 'label';
      label.textContent = o.label || hint.verb;
      const meaning = this.doc.createElement('span');
      meaning.className = 'meaning';
      meaning.textContent = o.meaning ? `＝${o.meaning}` : '';
      chip.append(glyph, label, meaning);
      this.options.append(chip);
    }
    const bar = this.doc.createElement('div');
    bar.className = 'timer';
    bar.style.animationDuration = `${timeoutSec}s`;
    this.options.append(bar);
  }

  clearOptions() { this.options.replaceChildren(); }

  thinking(on) { this.doc.body.classList.toggle('thinking', !!on); }

  setCaptionScale(scale) { this.doc.documentElement.style.setProperty('--caption-scale', String(scale)); }
}
