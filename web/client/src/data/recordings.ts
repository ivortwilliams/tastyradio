/**
 * Where recordings live: IndexedDB, in the browser.
 *
 * The blob never touches the server. That keeps hosting free of storage costs, keeps a personal
 * tape off somebody else's disk, and means a recording is available the instant it stops — which is
 * the moment you want to send it to a friend, not later.
 *
 * The cost, stated plainly: clearing site data loses them. Every recording gets a download button
 * for exactly that reason, and the file it saves is the same one the phone would have made.
 */

export interface Recording {
  id: string;
  fileName: string;
  mime: string;
  durationMs: number;
  createdAt: number;
  sizeBytes: number;
  blob: Blob;
}

const DB_NAME = 'tastyradio-recordings';
const STORE = 'recordings';

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: 'id' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function withStore<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  return new Promise<T>((resolve, reject) => {
    const transaction = db.transaction(STORE, mode);
    const request = work(transaction.objectStore(STORE));
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    transaction.oncomplete = () => db.close();
  });
}

export async function saveRecording(recording: Recording): Promise<void> {
  await withStore('readwrite', (store) => store.put(recording));
}

export async function listRecordings(): Promise<Recording[]> {
  const all = await withStore<Recording[]>('readonly', (store) => store.getAll() as IDBRequest<Recording[]>);
  return all.sort((a, b) => b.createdAt - a.createdAt);
}

export async function deleteRecording(id: string): Promise<void> {
  await withStore('readwrite', (store) => store.delete(id));
}

/** Hands the file to the browser's own save flow. */
export function download(recording: Recording): void {
  const url = URL.createObjectURL(recording.blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = recording.fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Revoking immediately can cancel the download in some browsers; a beat later is safe.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/**
 * The share sheet, where there is one. This is the whole point of the recording feature — the file
 * exists to be sent to somebody — so on a phone it should be one tap, not a download and a hunt
 * through the filesystem.
 */
/** Whether to offer a share button at all. Desktop Firefox has no share sheet; a phone does. */
export function sharingAvailable(): boolean {
  return typeof navigator.share === 'function' && typeof navigator.canShare === 'function';
}

export async function share(recording: Recording): Promise<boolean> {
  const file = new File([recording.blob], recording.fileName, { type: recording.mime });
  if (!navigator.canShare?.({ files: [file] })) return false;
  try {
    await navigator.share({ files: [file], title: recording.fileName });
    return true;
  } catch {
    return false; // the user dismissed the sheet, which is not a failure worth reporting
  }
}

export function formatDuration(ms: number): string {
  const total = Math.round(ms / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

export function formatSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}
