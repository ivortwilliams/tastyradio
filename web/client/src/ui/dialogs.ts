import * as api from '../api.js';
import * as store from '../data/store.js';
import type { Station } from '../data/types.js';
import { button, el, toast } from './dom.js';

/** Modals, kept to the few places where the app genuinely has a question to ask. */

/**
 * One dialog shell, and one route out of it.
 *
 * ⚠️ **A `method="dialog"` form closes the dialog on submit without running any of your code**, and
 * pressing Enter in a text field submits the form. So a dialog whose only handler is on the button's
 * `click` does nothing at all when you type a value and hit Enter — it just vanishes, which reads as
 * "the site is broken". That is exactly how the access-code dialog behaved on the day it shipped.
 *
 * Everything commits through [onCommit] instead: the form's submit event, which both Enter and the
 * primary button (`type="submit"`) go through. Cancel buttons close the dialog directly.
 */
function shell(
  title: string,
  body: HTMLElement,
  actions: HTMLElement[],
  onCommit?: (dialog: HTMLDialogElement) => void | Promise<void>,
  options: { dismissible?: boolean; className?: string } = {},
): HTMLDialogElement {
  const form = el(
    'form',
    { method: 'dialog' },
    title ? el('h2', { text: title }) : null,
    body,
    el('menu', { class: 'dialog-actions' }, ...actions),
  );

  const dialog = el('dialog', { class: `dialog ${options.className ?? ''}`.trim() }, form) as HTMLDialogElement;

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (onCommit) void onCommit(dialog);
    else dialog.close();
  });

  if (options.dismissible === false) {
    // Escape has nothing to go back to on the gate.
    dialog.addEventListener('cancel', (event) => event.preventDefault());
  }

  document.body.appendChild(dialog);
  dialog.addEventListener('close', () => dialog.remove());
  dialog.showModal();
  return dialog;
}

function cancelButton(label = 'Cancel'): HTMLButtonElement {
  const node = button(label, { class: 'ghost' });
  node.addEventListener('click', () => node.closest('dialog')?.close());
  return node;
}

export function confirmDialog(title: string, message: string, confirmLabel = 'Delete'): Promise<boolean> {
  return new Promise((resolve) => {
    let answer = false;
    const dialog = shell(
      title,
      el('p', { class: 'dialog-text', text: message }),
      [cancelButton(), button(confirmLabel, { class: 'danger-button', type: 'submit' })],
      (self) => {
        answer = true;
        self.close();
      },
    );
    dialog.addEventListener('close', () => resolve(answer));
  });
}

export function promptDialog(title: string, label: string, initial = ''): Promise<string | null> {
  return new Promise((resolve) => {
    const input = el('input', { class: 'field', type: 'text', value: initial, required: true }) as HTMLInputElement;
    let answer: string | null = null;
    const dialog = shell(
      title,
      el('label', { class: 'dialog-field' }, el('span', { text: label }), input),
      [cancelButton(), button('Save', { class: 'primary', type: 'submit' })],
      (self) => {
        answer = input.value.trim() || null;
        self.close();
      },
    );
    dialog.addEventListener('close', () => resolve(answer));
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

  const file = el('input', {
    type: 'file',
    accept: '.m3u,.m3u8,.pls,audio/x-mpegurl,audio/x-scpls',
    class: 'file-input',
  }) as HTMLInputElement;

  file.addEventListener('change', async () => {
    const chosen = file.files?.[0];
    if (!chosen) return;
    status.textContent = 'Reading playlist…';
    try {
      const entries = await api.parsePlaylist(await chosen.text());
      const added = store.importStations(entries);
      toast(added === 0 ? 'Nothing new in that playlist.' : `Imported ${added} station${added === 1 ? '' : 's'}.`);
      onAdded();
      dialog.close();
    } catch (error) {
      status.textContent = `Could not read that file: ${(error as Error).message}`;
    }
  });

  const dialog = shell(
    'Add a station',
    el(
      'div',
      {},
      el('label', { class: 'dialog-field' }, el('span', { text: 'Stream URL' }), url),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Name' }), name),
      status,
      el('hr', { class: 'dialog-rule' }),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Or import an M3U / PLS playlist' }), file),
      el('p', {
        class: 'dialog-note',
        text: "Transistor's own Export M3U works here — that is how this list arrived in the first place.",
      }),
    ),
    [cancelButton(), add],
    async (self) => {
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
          self.close();
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
        self.close();
      } catch (error) {
        status.textContent = (error as Error).message;
        add.disabled = false;
      }
    },
  );

  setTimeout(() => url.focus(), 30);
}

/** Change what a station is called, what it looks like, and where it plays from. */
export function editStationDialog(station: Station, onSaved: () => void): void {
  const name = el('input', { class: 'field', type: 'text', value: station.name, required: true }) as HTMLInputElement;
  const image = el('input', {
    class: 'field',
    type: 'url',
    value: station.imageUrl ?? '',
    placeholder: 'https://…/logo.png',
  }) as HTMLInputElement;
  const stream = el('input', {
    class: 'field',
    type: 'url',
    value: station.streamUrl,
    required: true,
  }) as HTMLInputElement;

  shell(
    'Edit station',
    el(
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
    [cancelButton(), button('Save', { class: 'primary', type: 'submit' })],
    (self) => {
      if (name.value.trim() === '' || stream.value.trim() === '') return;
      store.updateStation(station.id, {
        name: name.value.trim(),
        imageUrl: image.value.trim() || undefined,
        streamUrl: stream.value.trim(),
      });
      toast('Saved.');
      onSaved();
      self.close();
    },
  );
  setTimeout(() => name.select(), 30);
}

/** One shared password, entered once. Not an account. */
export function gateDialog(onPassed: () => void): void {
  const code = el('input', {
    class: 'field',
    type: 'password',
    required: true,
    autocomplete: 'current-password',
    placeholder: 'Access code',
    // The code is case-sensitive, and a phone keyboard helpfully capitalising the first letter or
    // autocorrecting it is not a failure the person typing it can see.
    autocapitalize: 'none',
    autocorrect: 'off',
    spellcheck: 'false',
  }) as HTMLInputElement;
  const status = el('p', { class: 'dialog-note' });
  const enter = button('Come in', { class: 'primary', type: 'submit' });

  shell(
    '',
    el(
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
    [enter],
    async (self) => {
      status.textContent = 'Checking…';
      enter.disabled = true;
      try {
        if (await api.submitCode(code.value)) {
          self.close();
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
    { dismissible: false, className: 'gate' },
  );

  setTimeout(() => code.focus(), 30);
}
