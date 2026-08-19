import * as api from '../api.js';
import * as store from '../data/store.js';
import type { Station } from '../data/types.js';
import { button, el, toast } from './dom.js';

/** Modals, kept to the few places where the app genuinely has a question to ask. */

/**
 * A dialog with exactly one way out.
 *
 * Two things about `<dialog>` bit hard enough to be worth writing down, because both produce the
 * same symptom — a control that visibly does nothing:
 *
 * 1. **A `method="dialog"` form closes the dialog on submit without running any of your code**, and
 *    pressing Enter in a text field submits the form. A dialog whose only handler is on the button's
 *    `click` therefore just vanishes when you type a value and hit Enter. That is exactly how the
 *    access-code box behaved on the day it shipped: right password, blank page.
 * 2. **The `close` event cannot be relied on to fire at all.** Measured in a Chromium-based browser:
 *    zero `close` events, even for a plain `close()` on an element still in the document. Anything
 *    that resolved a promise or cleaned up from that event hung forever.
 *
 * So nothing here is event-driven. Every route out — Enter, the primary button, Cancel, Escape —
 * goes through [Dialog.dismiss], which is idempotent and does the tidying itself.
 */
export interface Dialog {
  node: HTMLDialogElement;
  /** Close, remove from the page, and run the dismiss callback. Safe to call more than once. */
  dismiss(): void;
}

interface ShellOptions {
  title?: string;
  body: HTMLElement;
  /** Built with the handle, so a Cancel button can dismiss without reaching for the DOM. */
  actions: (dialog: Dialog) => HTMLElement[];
  onCommit?: (dialog: Dialog) => void | Promise<void>;
  /** Runs once, however the dialog goes away — including after a commit. */
  onDismiss?: () => void;
  /** False for the gate, which has nothing behind it to go back to. */
  dismissible?: boolean;
  className?: string;
}

function shell(options: ShellOptions): Dialog {
  const node = el('dialog', { class: `dialog ${options.className ?? ''}`.trim() }) as HTMLDialogElement;

  let gone = false;
  const handle: Dialog = {
    node,
    dismiss() {
      if (gone) return;
      gone = true;
      if (node.open) node.close();
      node.remove();
      options.onDismiss?.();
    },
  };

  const form = el(
    'form',
    { method: 'dialog' },
    options.title ? el('h2', { text: options.title }) : null,
    options.body,
    el('menu', { class: 'dialog-actions' }, ...options.actions(handle)),
  );
  node.appendChild(form);

  form.addEventListener('submit', (event) => {
    // Without this the dialog closes natively and none of the code below ever runs.
    event.preventDefault();
    if (options.onCommit) void options.onCommit(handle);
    else handle.dismiss();
  });

  if (options.dismissible === false) {
    node.addEventListener('cancel', (event) => event.preventDefault());
  } else {
    // Escape. The `cancel` event does fire; `close` is the unreliable one.
    node.addEventListener('cancel', () => handle.dismiss());
  }

  document.body.appendChild(node);
  node.showModal();
  return handle;
}

function cancelButton(dialog: Dialog, label = 'Cancel'): HTMLButtonElement {
  return button(label, { class: 'ghost', onClick: () => dialog.dismiss() });
}

/** Settles a dialog's promise exactly once, whichever way out the person took. */
function settler<T>(resolve: (value: T) => void): (value: T) => void {
  let settled = false;
  return (value: T) => {
    if (settled) return;
    settled = true;
    resolve(value);
  };
}

export function confirmDialog(title: string, message: string, confirmLabel = 'Delete'): Promise<boolean> {
  return new Promise((resolve) => {
    const done = settler(resolve);
    shell({
      title,
      body: el('p', { class: 'dialog-text', text: message }),
      actions: (dialog) => [
        cancelButton(dialog),
        button(confirmLabel, { class: 'danger-button', type: 'submit' }),
      ],
      onCommit: (dialog) => {
        done(true);
        dialog.dismiss();
      },
      // Reached by Cancel or Escape; a no-op after a commit, because `done` only fires once.
      onDismiss: () => done(false),
    });
  });
}

export function promptDialog(title: string, label: string, initial = ''): Promise<string | null> {
  return new Promise((resolve) => {
    const done = settler(resolve);
    const input = el('input', { class: 'field', type: 'text', value: initial, required: true }) as HTMLInputElement;
    shell({
      title,
      body: el('label', { class: 'dialog-field' }, el('span', { text: label }), input),
      actions: (dialog) => [cancelButton(dialog), button('Save', { class: 'primary', type: 'submit' })],
      onCommit: (dialog) => {
        done(input.value.trim() || null);
        dialog.dismiss();
      },
      onDismiss: () => done(null),
    });
    setTimeout(() => input.select(), 30);
  });
}

/**
 * Add a station by URL, or import a playlist.
 *
 * The URL is resolved through the server before it's kept, so a `.pls` file, a redirect chain or a
 * dead host is discovered now rather than the first time you try to play it. Streaming radio is
 * messy in practice; this is the place to absorb some of it.
 */
export function addStationDialog(onAdded: () => void): void {
  const url = el('input', {
    class: 'field',
    type: 'url',
    placeholder: 'https://example.com/stream.mp3',
    required: true,
  }) as HTMLInputElement;
  const name = el('input', { class: 'field', type: 'text', placeholder: 'Optional' }) as HTMLInputElement;
  const status = el('p', { class: 'dialog-note' });
  const add = button('Add', { class: 'primary', type: 'submit' });

  const picker = el('input', {
    type: 'file',
    accept: '.m3u,.m3u8,.pls,audio/x-mpegurl,audio/x-scpls',
    class: 'file-input',
  }) as HTMLInputElement;

  const dialog = shell({
    title: 'Add a station',
    body: el(
      'div',
      {},
      el('label', { class: 'dialog-field' }, el('span', { text: 'Stream URL' }), url),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Name' }), name),
      status,
      el('hr', { class: 'dialog-rule' }),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Or import an M3U / PLS playlist' }), picker),
      el('p', {
        class: 'dialog-note',
        text: "Transistor's own Export M3U works here — that is how this list arrived in the first place.",
      }),
    ),
    actions: (self) => [cancelButton(self), add],
    onCommit: async (self) => {
      const target = url.value.trim();
      if (target === '') return;

      status.textContent = 'Checking that URL…';
      add.disabled = true;
      try {
        const resolved = await api.resolve(target);
        if (!resolved.ok) {
          status.textContent = resolved.reason ?? 'That URL did not answer.';
          add.disabled = false;
          return;
        }
        if (resolved.playlist && resolved.playlist.length > 1) {
          const added = store.importStations(resolved.playlist.map((entry) => ({ name: null, url: entry })));
          toast(`Imported ${added} station${added === 1 ? '' : 's'}.`);
          onAdded();
          self.dismiss();
          return;
        }

        const known = resolved.known;
        store.addStation({
          name: name.value.trim() || resolved.name || known?.name || store.hostOf(resolved.url ?? target),
          // The resolved URL, not what was typed: a redirect chain resolved once is one fewer thing
          // to go wrong every time the station is played.
          streamUrl: resolved.url ?? target,
          imageUrl: known?.favicon || undefined,
          sourceUuid: known?.uuid || undefined,
          source: known ? 'radio-browser' : 'manual',
          tags: known?.tags || undefined,
          codec: known?.codec || undefined,
          bitrate: known?.bitrate || undefined,
          country: known?.country || undefined,
          language: known?.language || undefined,
        });
        toast('Station added.');
        onAdded();
        self.dismiss();
      } catch (error) {
        status.textContent = (error as Error).message;
        add.disabled = false;
      }
    },
  });

  picker.addEventListener('change', async () => {
    const chosen = picker.files?.[0];
    if (!chosen) return;
    status.textContent = 'Reading playlist…';
    try {
      const entries = await api.parsePlaylist(await chosen.text());
      const added = store.importStations(entries);
      toast(added === 0 ? 'Nothing new in that playlist.' : `Imported ${added} station${added === 1 ? '' : 's'}.`);
      onAdded();
      dialog.dismiss();
    } catch (error) {
      status.textContent = `Could not read that file: ${(error as Error).message}`;
    }
  });

  setTimeout(() => url.focus(), 30);
}

/** Change what a station is called, what it looks like, and where it plays from. */
export function editStationDialog(station: Station, onSaved: () => void): void {
  const name = el('input', { class: 'field', type: 'text', value: station.name, required: true }) as HTMLInputElement;
  const image = el('input', {
    class: 'field',
    // Text, not url: the seeded Tasty Radio station carries the bundled `/ophelia.png`, and a
    // `type="url"` field rejects a relative path — which fails validation on submit and leaves you
    // pressing Save on a dialog that refuses to close.
    type: 'text',
    inputmode: 'url',
    value: station.imageUrl ?? '',
    placeholder: 'https://…/logo.png',
  }) as HTMLInputElement;
  const stream = el('input', {
    class: 'field',
    type: 'url',
    value: station.streamUrl,
    required: true,
  }) as HTMLInputElement;

  shell({
    title: 'Edit station',
    body: el(
      'div',
      {},
      el('label', { class: 'dialog-field' }, el('span', { text: 'Name' }), name),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Artwork URL' }), image),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Stream URL' }), stream),
      el('p', {
        class: 'dialog-note',
        text: 'The stream URL is yours to edit. Stations move, and a station you can fix is better than one you have to replace.',
      }),
    ),
    actions: (self) => [cancelButton(self), button('Save', { class: 'primary', type: 'submit' })],
    onCommit: (self) => {
      if (name.value.trim() === '' || stream.value.trim() === '') return;
      store.updateStation(station.id, {
        name: name.value.trim(),
        imageUrl: image.value.trim() || undefined,
        streamUrl: stream.value.trim(),
      });
      toast('Saved.');
      onSaved();
      self.dismiss();
    },
  });
  setTimeout(() => name.select(), 30);
}

/**
 * One shared password, entered once.
 *
 * Only shown when the server is configured with an access code; the site runs open by default, in
 * which case this is never constructed.
 */
export function gateDialog(onPassed: () => void): void {
  const code = el('input', {
    class: 'field',
    type: 'password',
    required: true,
    autocomplete: 'current-password',
    placeholder: 'Access code',
    // The server matches case-insensitively and trims, but a phone keyboard silently capitalising
    // the first letter is still a confusing thing to look at.
    autocapitalize: 'none',
    autocorrect: 'off',
    spellcheck: 'false',
  }) as HTMLInputElement;
  const status = el('p', { class: 'dialog-note' });
  const enter = button('Come in', { class: 'primary', type: 'submit' });

  shell({
    body: el(
      'div',
      {},
      el('img', { class: 'gate-logo', src: '/ophelia.png', alt: '' }),
      el('h2', { text: 'Tasty Radio' }),
      el('p', {
        class: 'dialog-text',
        text: 'Several stations at once, with a fader on each, and a record button. There is one code for everybody.',
      }),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Access code' }), code),
      status,
    ),
    actions: () => [enter],
    dismissible: false,
    className: 'gate',
    onCommit: async (self) => {
      status.textContent = 'Checking…';
      enter.disabled = true;
      try {
        if (await api.submitCode(code.value)) {
          self.dismiss();
          onPassed();
          return;
        }
        status.textContent = 'That is not the code.';
      } catch {
        status.textContent = 'Could not reach the server — try again.';
      }
      enter.disabled = false;
      code.select();
    },
  });

  setTimeout(() => code.focus(), 30);
}
