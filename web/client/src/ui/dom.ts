/** Enough of a view layer for one page. No framework: this app is a desk, not a document. */

type Props = Record<string, unknown>;
type Child = Node | string | number | null | undefined | false;

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Props = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = String(value);
    else if (key === 'text') node.textContent = String(value);
    else if (key === 'html') node.innerHTML = String(value);
    else if (key === 'style' && typeof value === 'object') Object.assign(node.style, value);
    else if (key.startsWith('on') && typeof value === 'function') {
      node.addEventListener(key.slice(2).toLowerCase(), value as EventListener);
    } else if (key === 'dataset' && typeof value === 'object') {
      Object.assign(node.dataset, value as Record<string, string>);
    } else if (value === true) node.setAttribute(key, '');
    else node.setAttribute(key, String(value));
  }
  append(node, children);
  return node;
}

export function append(parent: Node, children: Child[]): void {
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    parent.appendChild(typeof child === 'object' ? child : document.createTextNode(String(child)));
  }
}

export function clear(node: Node): void {
  while (node.firstChild) node.removeChild(node.firstChild);
}

export function replace(node: Element, ...children: Child[]): void {
  clear(node);
  append(node, children);
}

/** Icons drawn rather than depended on — a filled square for *stop* is the honest shape for radio. */
export function icon(name: 'stop' | 'play' | 'record' | 'mute' | 'solo' | 'plus' | 'cross' | 'retry' | 'chevron' | 'save' | 'edit' | 'share' | 'download'): SVGSVGElement {
  const paths: Record<string, string> = {
    stop: '<rect x="5" y="5" width="14" height="14" rx="1.5"/>',
    play: '<path d="M7 4.5 19 12 7 19.5z"/>',
    record: '<circle cx="12" cy="12" r="7"/>',
    mute: '<path d="M4 9h4l5-4v14l-5-4H4z"/><path d="M16 9l5 6M21 9l-5 6" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"/>',
    solo: '<path d="M12 3 14.6 9.2 21 9.8l-4.8 4.3L17.6 21 12 17.6 6.4 21l1.4-6.9L3 9.8l6.4-.6z"/>',
    plus: '<path d="M11 4h2v7h7v2h-7v7h-2v-7H4v-2h7z"/>',
    cross: '<path d="M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z"/>',
    retry: '<path d="M12 5V2L8 6l4 4V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7z"/>',
    chevron: '<path d="M12 15.4 5.6 9l1.4-1.4 5 5 5-5L18.4 9z"/>',
    save: '<path d="M5 3h11l3 3v15H5zM8 3v6h7V3M8 14h8v7H8z"/>',
    edit: '<path d="M3 17.2V21h3.8L18 9.8 14.2 6zM20.7 7.3a1 1 0 0 0 0-1.4l-2.6-2.6a1 1 0 0 0-1.4 0l-1.8 1.8L18.9 9z"/>',
    share: '<path d="M18 16.1a3 3 0 0 0-2 .8l-7.1-4.1a3 3 0 0 0 0-1.6L16 7.1A3 3 0 1 0 15 5c0 .3 0 .5.1.8L8 9.9a3 3 0 1 0 0 4.2l7.1 4.1c0 .3-.1.5-.1.8A3 3 0 1 0 18 16z"/>',
    download: '<path d="M11 3h2v9h4l-5 5.5L7 12h4zM4 19h16v2H4z"/>',
  };
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  svg.innerHTML = paths[name];
  return svg;
}

export function button(
  label: string,
  props: Props & { iconName?: Parameters<typeof icon>[0] } = {},
): HTMLButtonElement {
  const { iconName, ...rest } = props;
  const node = el('button', { type: 'button', ...rest });
  if (iconName) node.appendChild(icon(iconName));
  if (label) node.appendChild(el('span', { text: label }));
  if (!label && iconName) node.setAttribute('aria-label', String(rest['title'] ?? ''));
  return node;
}

let toastTimer: number | undefined;

/** One line of feedback, bottom of the screen. Never a modal for something that isn't a question. */
export function toast(message: string): void {
  let node = document.querySelector<HTMLElement>('.toast');
  if (!node) {
    node = el('div', { class: 'toast', role: 'status', 'aria-live': 'polite' });
    document.body.appendChild(node);
  }
  node.textContent = message;
  node.dataset.show = 'true';
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    if (node) node.dataset.show = 'false';
  }, 3200);
}

export function relativeTime(at: number): string {
  const seconds = Math.round((Date.now() - at) / 1000);
  if (seconds < 90) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 36) return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  const days = Math.round(hours / 24);
  if (days < 14) return `${days} days ago`;
  return `${Math.round(days / 7)} weeks ago`;
}
