import { artworkUrl } from '../api.js';
import { el } from './dom.js';

/**
 * Station artwork, with the monogram as a genuine fallback rather than a backdrop.
 *
 * Two things learned on the phone and true again here:
 *
 * - **Directory favicons are often transparent PNGs drawn for a light page.** They need an opaque
 *   light backdrop or they're invisible on a dark one.
 * - **The monogram must be replaced, not covered.** Leaving it behind a transparent logo makes
 *   every station look dirty; it is removed the moment a real image loads.
 */

const PALETTE = ['#5b6cff', '#c9772f', '#3f8f6d', '#a8478c', '#4f7ab8', '#93692a', '#7a5bbf', '#417f8f'];

function initials(name: string): string {
  const words = name
    .replace(/[^\p{L}\p{N} ]/gu, ' ')
    .split(/\s+/)
    .filter(Boolean);
  if (words.length === 0) return '·';
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase();
  return (words[0][0] + words[1][0]).toUpperCase();
}

function colourFor(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
  return PALETTE[hash % PALETTE.length];
}

export function artwork(station: { name: string; imageUrl?: string }, size = 48): HTMLElement {
  const node = el('div', {
    class: 'art',
    style: { width: `${size}px`, height: `${size}px`, background: colourFor(station.name) },
  });

  const monogram = el('span', {
    class: 'art-monogram',
    text: initials(station.name),
    style: { fontSize: `${Math.round(size * 0.36)}px` },
  });
  node.appendChild(monogram);

  if (station.imageUrl) {
    const image = new Image();
    image.decoding = 'async';
    image.loading = 'lazy';
    image.alt = '';
    image.className = 'art-image';
    image.addEventListener('load', () => {
      monogram.remove();
      node.appendChild(image);
      node.dataset.hasImage = 'true';
    });
    // A missing favicon is normal, not an error — the monogram simply stays.
    image.src = artworkUrl(station.imageUrl);
  }

  return node;
}
