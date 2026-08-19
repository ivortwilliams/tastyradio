import * as api from '../api.js';
import * as store from '../data/store.js';
import type { Station } from '../data/types.js';
import { button, el, toast } from './dom.js';

/** Modals, kept to the few places where the app genuinely has a question to ask. */

function shell(title: string, body: HTMLElement, actions: HTMLElement[]): HTMLDialogElement {
  const dialog = el(
    'dialog',
    { class: 'dialog' },
    el(
      'form',
      { method: 'dialog' },
      el('h2', { text: title }),
      body,
      el('menu', { class: 'dialog-actions' }, ...actions),
    ),
  ) as HTMLDialogElement;

  document.body.appendChild(dialog);
  dialog.addEventListener('close', () => dialog.remove());
  dialog.showModal();
  return dialog;
}

export function confirmDialog(title: string, message: string, confirmLabel = 'Delete'): Promise<boolean> {
  return new Promise((resolve) => {
    let answer = false;
    const dialog = shell(title, el('p', { class: 'dialog-text', text: message }), [
      button('Cancel', { class: 'ghost', value: 'cancel' }),
      button(confirmLabel, {
        class: 'danger-button',
        onClick: () => {
          answer = true;
        },
      }),
    ]);
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
      [
        button('Cancel', { class: 'ghost', value: 'cancel' }),
        button('Save', {
          class: 'primary',
          onClick: () => {
            answer = input.value.trim() || null;
          },
        }),
      ],
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

  const file = el('input', { type: 'file', accept: '.m3u,.m3u8,.pls,audio/x-mpegurl,audio/x-scpls', class: 'file-input' }) as HTMLInputElement;

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

  const add = button('Add', {
    class: 'primary',
    onClick: async (event: Event) => {
      event.preventDefault();
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
          status.textContent = `That is a playlist of ${resolved.playlist.length} stations — importing all of them.`;
          const added = store.importStations(resolved.playlist.map((entry) => ({ name: null, url: entry })));
          toast(`Imported ${added} station${added === 1 ? '' : 's'}.`);
          onAdded();
          dialog.close();
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
        dialog.close();
      } catch (error) {
        status.textContent = (error as Error).message;
        add.disabled = false;
      }
    },
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
      el(
        'label',
        { class: 'dialog-field' },
        el('span', { text: 'Or import an M3U / PLS playlist' }),
        file,
      ),
      el('p', {
        class: 'dialog-note',
        text: "Transistor's own Export M3U works here — that is how this list arrived in the first place.",
      }),
    ),
    [button('Cancel', { class: 'ghost', value: 'cancel' }), add],
  );

  setTimeout(() => url.focus(), 30);
}

/** Long-press equivalent: change what a station is called, what it looks like, and where it plays from. */
export function editStationDialog(station: Station, onSaved: () => void): void {
  const name = el('input', { class: 'field', type: 'text', value: station.name, required: true }) as HTMLInputElement;
  const image = el('input', {
    class: 'field',
    type: 'url',
    value: station.imageUrl ?? '',
    placeholder: 'https://…/logo.png',
  }) as HTMLInputElement;
  const stream = el('input', { class: 'field', type: 'url', value: station.streamUrl, required: true }) as HTMLInputElement;

  const dialog = shell(
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
    [
      button('Cancel', { class: 'ghost', value: 'cancel' }),
      button('Save', {
        class: 'primary',
        onClick: () => {
          if (name.value.trim() === '' || stream.value.trim() === '') return;
          store.updateStation(station.id, {
            name: name.value.trim(),
            imageUrl: image.value.trim() || undefined,
            streamUrl: stream.value.trim(),
          });
          toast('Saved.');
          onSaved();
        },
      }),
    ],
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
  }) as HTMLInputElement;
  const status = el('p', { class: 'dialog-note' });

  const dialog = el(
    'dialog',
    { class: 'dialog gate' },
    el(
      'form',
      { method: 'dialog' },
      el('img', { class: 'gate-logo', src: '/ophelia.png', alt: '' }),
      el('h2', { text: 'Tasty Radio' }),
      el('p', {
        class: 'dialog-text',
        text: 'Several stations at once, with a fader on each, and a record button. There is one code for everybody.',
      }),
      el('label', { class: 'dialog-field' }, el('span', { text: 'Access code' }), code),
      status,
      el(
        'menu',
        { class: 'dialog-actions' },
        button('Come in', {
          class: 'primary',
          onClick: async (event: Event) => {
            event.preventDefault();
            status.textContent = 'Checking…';
            if (await api.submitCode(code.value)) {
              dialog.close();
              dialog.remove();
              onPassed();
            } else {
              status.textContent = 'That is not the code.';
              code.select();
            }
          },
        }),
      ),
    ),
  ) as HTMLDialogElement;

  // No cancel: there is nothing behind this until the code is right.
  dialog.addEventListener('cancel', (event) => event.preventDefault());
  document.body.appendChild(dialog);
  dialog.showModal();
  setTimeout(() => code.focus(), 30);
}
