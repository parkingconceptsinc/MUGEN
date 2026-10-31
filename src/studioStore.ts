import { validateProject, type StudioProject } from "./studioModel";
const DB_NAME = "mugen-studio-v1";
let opening: Promise<IDBDatabase> | null = null;
let writes: Promise<unknown> = Promise.resolve();
function openStore(): Promise<IDBDatabase> {
  if (opening) return opening;
  opening = new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => { request.result.createObjectStore("state"); request.result.createObjectStore("media"); };
    request.onerror = () => reject(request.error || new Error("Studio storage is unavailable."));
    request.onblocked = () => reject(new Error("Close other MUGEN tabs and retry opening Studio."));
    request.onsuccess = () => { const db = request.result; db.onversionchange = () => { db.close(); opening = null; }; resolve(db); };
  }).catch(error => { opening = null; throw error; });
  return opening;
}
const requestValue = <T>(request: IDBRequest<T>): Promise<T> => new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
const transactionDone = (transaction: IDBTransaction): Promise<void> => new Promise((resolve, reject) => {
  transaction.oncomplete = () => resolve(); transaction.onerror = () => reject(transaction.error || new Error("Studio could not save the project.")); transaction.onabort = () => reject(transaction.error || new Error("Studio save was interrupted. The last saved project is preserved."));
});
export type StudioRestore = { project: StudioProject | null; media: Map<string, Blob>; recovered: boolean };
export async function restoreStudio(): Promise<StudioRestore> {
  const db = await openStore();
  const tx = db.transaction(["state", "media"], "readonly");
  const done = transactionDone(tx);
  const [current, backup, keys, blobs] = await Promise.all([requestValue(tx.objectStore("state").get("current")), requestValue(tx.objectStore("state").get("backup")), requestValue(tx.objectStore("media").getAllKeys()), requestValue(tx.objectStore("media").getAll()), done]);
  if (current == null && backup == null) return { project: null, media: new Map(), recovered: false };
  const media = new Map<string, Blob>();
  keys.forEach((key, index) => { if (typeof key === "string" && blobs[index] instanceof Blob) media.set(key, blobs[index]); });
  for (const [index, raw] of [current, backup].entries()) {
    try {
      const project = validateProject(raw);
      if (project.sources.some(s => !media.has(s.id) || media.get(s.id)!.size !== s.size)) throw new Error("Some project media is missing.");
      return { project, media, recovered: index === 1 };
    } catch { /* Try the previous complete project snapshot. */ }
  }
  throw new Error("The saved project and its backup could not be opened. Storage has been left untouched. Retry, or explicitly start a new project.");
}
export function saveStudio(project: StudioProject, media: Map<string, Blob>): Promise<void> {
  const snapshot = validateProject(project);
  const files = new Map(media);
  const work = writes.then(async () => {
    const db = await openStore();
    const tx = db.transaction(["state", "media"], "readwrite");
    const done = transactionDone(tx);
    const state = tx.objectStore("state");
    const previous = state.get("current");
    previous.onsuccess = () => {
      // A recovered backup must remain intact if current was corrupt or had
      // missing media. Never rotate the failed checkpoint over the healthy one.
      if (previous.result != null) {
        try {
          const prior = validateProject(previous.result);
          if (prior.sources.every(source => {
            const file = files.get(source.id);
            return file instanceof Blob && file.size === source.size;
          })) state.put(prior, "backup");
        } catch { /* Keep the existing backup when current cannot be restored. */ }
      }
      state.put(snapshot, "current");
    };
    for (const source of snapshot.sources) {
      const blob = files.get(source.id);
      if (!blob || blob.size !== source.size) { tx.abort(); await done; throw new Error(`Media for ${source.name} is missing. The previous project was preserved.`); }
      const store = tx.objectStore("media");
      const exists = store.get(source.id);
      exists.onsuccess = () => {
        // IDs identify immutable sources; repair incomplete persisted copies
        // without repeatedly rewriting healthy media on every edit.
        if (!(exists.result instanceof Blob) || exists.result.size !== blob.size) store.put(blob, source.id);
      };
    }
    await done;
  });
  writes = work.catch(() => {});
  return work;
}
export function resetStudio(): Promise<void> {
  const work = writes.then(async () => {
    const db = await openStore();
    const tx = db.transaction(["state", "media"], "readwrite");
    const done = transactionDone(tx);
    tx.objectStore("state").clear(); tx.objectStore("media").clear();
    await done;
  });
  writes = work.catch(() => {});
  return work;
}

/** Replace the entire workspace atomically; a failed import retains both old snapshots and media. */
export function replaceStudio(project: StudioProject, media: Map<string, Blob>): Promise<void> {
  const snapshot = validateProject(project); const files = new Map(media);
  for (const source of snapshot.sources) {
    const blob = files.get(source.id);
    if (!blob || blob.size !== source.size) return Promise.reject(new Error('Imported project media is incomplete. Your previous workspace is unchanged.'));
  }
  const work = writes.then(async () => {
    const db = await openStore(); const tx = db.transaction(['state', 'media'], 'readwrite'); const done = transactionDone(tx);
    try {
      tx.objectStore('state').clear(); tx.objectStore('media').clear();
      tx.objectStore('state').put(snapshot, 'current'); tx.objectStore('state').put(snapshot, 'backup');
      for (const source of snapshot.sources) tx.objectStore('media').put(files.get(source.id)!, source.id);
      await done;
    } catch (error) { try { tx.abort(); } catch {} await done.catch(() => {}); throw error; }
  });
  writes = work.catch(() => {}); return work;
}
