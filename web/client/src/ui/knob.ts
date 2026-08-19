import { el } from './dom.js';

/**
 * A rotary control, because this is a mixing desk.
 *
 * A slider would have been less work, but three horizontal sliders stacked in a channel strip is
 * how you make a desk look like a settings page. Knobs also survive the vertical budget: a strip
 * has room for three of them side by side and no room at all for three sliders.
 *
 * Dragging is vertical (up is more, as on hardware), the wheel works, arrow keys work, and
 * double-clicking returns the control to its default — which on a centre-detented knob is the
 * position where the band is untouched.
 */

export interface KnobOptions {
  label: string;
  min: number;
  max: number;
  value: number;
  /** Where a double-click returns to. */
  defaultValue?: number;
  /** Draws the arc outward from twelve o'clock rather than from the left stop. */
  centred?: boolean;
  format: (value: number) => string;
  onChange: (value: number) => void;
  /** Units of travel per pixel dragged. */
  sensitivity?: number;
}

export interface Knob {
  node: HTMLElement;
  set(value: number): void;
}

const SWEEP = 270; // degrees of travel, -135° to +135°, the hardware convention

export function knob(options: KnobOptions): Knob {
  const { label, min, max, format, onChange } = options;
  const defaultValue = options.defaultValue ?? (options.centred ? 0 : min);
  const sensitivity = options.sensitivity ?? (max - min) / 160;

  let value = options.value;

  const arcValue = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  arcValue.setAttribute('class', 'knob-value');

  const arcTrack = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  arcTrack.setAttribute('class', 'knob-track');
  arcTrack.setAttribute('d', arc(-135, 135));

  const pointer = document.createElementNS('http://www.w3.org/2000/svg', 'line');
  pointer.setAttribute('class', 'knob-pointer');
  pointer.setAttribute('x1', '20');
  pointer.setAttribute('y1', '20');

  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 40 40');
  svg.append(arcTrack, arcValue, pointer);

  const readout = el('span', { class: 'knob-readout' });
  const dial = el('div', { class: 'knob-dial' });
  dial.appendChild(svg);

  const node = el(
    'div',
    {
      class: 'knob',
      role: 'slider',
      tabindex: '0',
      'aria-label': label,
      'aria-valuemin': String(min),
      'aria-valuemax': String(max),
    },
    dial,
    el('span', { class: 'knob-label', text: label }),
    readout,
  );

  function draw(): void {
    const fraction = (value - min) / (max - min);
    const angle = -135 + fraction * SWEEP;
    const from = options.centred ? 0 : -135;
    arcValue.setAttribute('d', arc(Math.min(from, angle), Math.max(from, angle)));

    const radians = ((angle - 90) * Math.PI) / 180;
    pointer.setAttribute('x2', String(20 + Math.cos(radians) * 13));
    pointer.setAttribute('y2', String(20 + Math.sin(radians) * 13));

    readout.textContent = format(value);
    node.setAttribute('aria-valuenow', value.toFixed(2));
    node.setAttribute('aria-valuetext', format(value));
    node.dataset.active = String(Math.abs(value - defaultValue) > 1e-6);
  }

  function set(next: number, notify = true): void {
    const clamped = Math.min(max, Math.max(min, next));
    if (clamped === value) return;
    value = clamped;
    draw();
    if (notify) onChange(value);
  }

  let dragging = false;
  let lastY = 0;

  dial.addEventListener('pointerdown', (event: PointerEvent) => {
    dragging = true;
    lastY = event.clientY;
    dial.setPointerCapture(event.pointerId);
    node.dataset.dragging = 'true';
    event.preventDefault();
  });
  dial.addEventListener('pointermove', (event: PointerEvent) => {
    if (!dragging) return;
    const delta = lastY - event.clientY;
    lastY = event.clientY;
    // Fine adjustment while shift is held, which is what anybody who has used a plug-in expects.
    set(value + delta * sensitivity * (event.shiftKey ? 0.25 : 1));
  });
  const release = (event: PointerEvent) => {
    if (!dragging) return;
    dragging = false;
    node.dataset.dragging = 'false';
    if (dial.hasPointerCapture(event.pointerId)) dial.releasePointerCapture(event.pointerId);
  };
  dial.addEventListener('pointerup', release);
  dial.addEventListener('pointercancel', release);

  dial.addEventListener('dblclick', () => set(defaultValue));

  node.addEventListener(
    'wheel',
    (event: WheelEvent) => {
      event.preventDefault();
      set(value - Math.sign(event.deltaY) * sensitivity * (event.shiftKey ? 2 : 8));
    },
    { passive: false },
  );

  node.addEventListener('keydown', (event: KeyboardEvent) => {
    const step = (max - min) / (event.shiftKey ? 100 : 20);
    if (event.key === 'ArrowUp' || event.key === 'ArrowRight') set(value + step);
    else if (event.key === 'ArrowDown' || event.key === 'ArrowLeft') set(value - step);
    else if (event.key === 'Home') set(min);
    else if (event.key === 'End') set(max);
    else if (event.key === 'Backspace' || event.key === 'Delete') set(defaultValue);
    else return;
    event.preventDefault();
  });

  draw();
  return {
    node,
    set(next: number) {
      if (dragging) return; // never fight the hand that's holding it
      set(next, false);
    },
  };
}

/** An SVG arc across the dial face, in the same -135°…135° space the pointer uses. */
function arc(fromDegrees: number, toDegrees: number): string {
  const radius = 16;
  const point = (degrees: number) => {
    const radians = ((degrees - 90) * Math.PI) / 180;
    return [20 + Math.cos(radians) * radius, 20 + Math.sin(radians) * radius];
  };
  const [x1, y1] = point(fromDegrees);
  const [x2, y2] = point(toDegrees);
  const large = Math.abs(toDegrees - fromDegrees) > 180 ? 1 : 0;
  if (Math.abs(toDegrees - fromDegrees) < 0.01) return `M ${x1} ${y1}`;
  return `M ${x1} ${y1} A ${radius} ${radius} 0 ${large} 1 ${x2} ${y2}`;
}
