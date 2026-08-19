import {
  deleteRecording,
  download,
  formatDuration,
  formatSize,
  listRecordings,
  share,
  sharingAvailable,
  type Recording,
} from '../data/recordings.js';
import { confirmDialog } from './dialogs.js';
import { button, el, relativeTime, replace, toast } from './dom.js';

/**
 * Your recordings.
 *
 * They live in this browser and nowhere else — the blob never reaches the server. That keeps a
 * personal tape off somebody else's disk and keeps hosting free of storage, at the honest cost that
 * clearing site data loses them. Hence a download button on every one.
 */
export function recordingsPage(): HTMLElement {
  const list = el('div', { class: 'rows' });
  const page = el(
    'div',
    { class: 'page' },
    el(
      'header',
      { class: 'page-head' },
      el(
        'div',
        {},
        el('h1', { text: 'Recordings' }),
        el('p', {
          class: 'page-sub',
          text: 'Kept in this browser. Download the ones you want to keep for good.',
        }),
      ),
    ),
    list,
  );

  async function refresh(): Promise<void> {
    const recordings = await listRecordings();
    replace(list);
    if (recordings.length === 0) {
      list.appendChild(
        el('p', {
          class: 'empty',
          text: 'Nothing recorded yet. Start a couple of stations, balance them, and press Record on the desk.',
        }),
      );
      return;
    }
    for (const recording of recordings) {
      list.appendChild(recordingRow(recording, refresh));
    }
  }

  void refresh();
  return page;
}

function recordingRow(recording: Recording, refresh: () => Promise<void>): HTMLElement {
  const audio = el('audio', { controls: true, class: 'rec-player', preload: 'none' }) as HTMLAudioElement;
  audio.src = URL.createObjectURL(recording.blob);

  return el(
    'article',
    { class: 'row row-recording' },
    el(
      'div',
      { class: 'row-body' },
      el('div', { class: 'row-name', text: recording.fileName }),
      el('div', {
        class: 'row-meta',
        text: `${formatDuration(recording.durationMs)} · ${formatSize(recording.sizeBytes)} · ${relativeTime(recording.createdAt)}`,
      }),
      audio,
    ),
    el(
      'div',
      { class: 'row-actions' },
      // The moment you want to send it to a friend is the moment it stops, not later — so share
      // comes first where the browser has a share sheet at all.
      sharingAvailable()
        ? button('', {
            class: 'chip-button',
            iconName: 'share',
            title: 'Share',
            'aria-label': `Share ${recording.fileName}`,
            onClick: async () => {
              if (!(await share(recording))) toast('Sharing is not available here — download it instead.');
            },
          })
        : null,
      button('', {
        class: 'chip-button',
        iconName: 'download',
        title: 'Download',
        'aria-label': `Download ${recording.fileName}`,
        onClick: () => download(recording),
      }),
      button('', {
        class: 'chip-button danger',
        iconName: 'cross',
        title: 'Delete',
        'aria-label': `Delete ${recording.fileName}`,
        onClick: async () => {
          if (await confirmDialog('Delete recording', `Delete "${recording.fileName}"? This cannot be undone.`)) {
            await deleteRecording(recording.id);
            await refresh();
          }
        },
      }),
    ),
  );
}
