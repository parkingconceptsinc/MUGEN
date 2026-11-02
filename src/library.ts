import { candidateKey, groupIdenticalFiles, mergeFileRecords, sameFileBytes, type FileRecord } from "./fileIdentity";

export type StoredLibraryItem = {
  id: string;
  name: string;
  kind: "audio" | "video";
  path: string;
  size: number;
  lastModified: number;
  file: Blob;
  title?: string;
  artist?: string;
  album?: string;
  albumArtist?: string;
  genre?: string;
  year?: number;
  trackNo?: number;
  duration?: number;
  addedAt?: number;
  metaVersion?: number;
  cover?: Blob;
};

export type PhotoExif = {
  make?: string;
  model?: string;
  lens?: string;
  focalLength?: number;
  fNumber?: number;
  exposureTime?: number;
  iso?: number;
  lat?: number;
  lon?: number;
};

export type StoredPhoto = {
  id: string;
  name: string;
  path: string;
  size: number;
  lastModified: number;
  addedAt: number;
  takenAt: number;
  width: number;
  height: number;
  mime: string;
  // A screen-sized copy (the original when it is already small enough) and a grid thumbnail.
  display: Blob;
  thumb: Blob;
  exif?: PhotoExif;
};

export type StoredComic = {
  id: string;
  name: string;
  path: string;
  size: number;
  lastModified: number;
  addedAt: number;
  format:
    | "cbz" | "cbr" | "pdf" | "epub"
    | "txt" | "md" | "markdown" | "log" | "nfo"
    | "csv" | "tsv" | "json" | "xml" | "yaml" | "yml" | "toml" | "ini" | "cfg" | "conf"
    | "srt" | "vtt" | "ass" | "ssa" | "sub"
    | "html" | "htm" | "rtf" | "docx" | "odt";
  shelf?: "books" | "comics";
  title?: string;
  author?: string;
  pages?: number;
  file: Blob;
  cover?: Blob;
  // Serialized epub.js locations, so percentages don't need recomputing on every open.
  locations?: string;
};

const DB_NAME = "mortimer-player";
const STORE = "library";
const COMICS = "comics";
const PHOTOS = "photos";
const VERSION = 6;
const CANDIDATE_INDEX = "nameSize";
const ALIASES = "aliases";
const IDENTITY_INDEX = "identity";

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, VERSION);
    let settled = false;
    request.onupgradeneeded = () => {
      const db = request.result;
      const tx = request.transaction!;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: "id" });
      if (!db.objectStoreNames.contains(COMICS)) db.createObjectStore(COMICS, { keyPath: "id" });
      if (!db.objectStoreNames.contains(PHOTOS)) db.createObjectStore(PHOTOS, { keyPath: "id" });
      if (!db.objectStoreNames.contains(ALIASES)) db.createObjectStore(ALIASES, { keyPath: "id" });
      // A compound index lets imports check file identity without getAll() and
      // without rewriting existing records/large video Blobs during migration.
      for (const name of [STORE, COMICS, PHOTOS]) {
        const store = tx.objectStore(name);
        if (!store.indexNames.contains(IDENTITY_INDEX)) store.createIndex(IDENTITY_INDEX, ["path", "size", "lastModified"], { unique: false });
        if (!store.indexNames.contains(CANDIDATE_INDEX)) store.createIndex(CANDIDATE_INDEX, ["name", "size"], { unique: false });
      }
    };
    request.onsuccess = () => {
      const db = request.result;
      // A blocked open may eventually succeed after another tab closes. Don't
      // leak that late connection after reporting the blocked request to callers.
      if (settled) {
        db.close();
        return;
      }
      settled = true;
      // Lets a newer tab upgrade the schema instead of being blocked by this connection.
      db.onversionchange = () => db.close();
      resolve(db);
    };
    request.onerror = () => {
      if (settled) return;
      settled = true;
      reject(request.error ?? new Error("Couldn't open local library storage"));
    };
    request.onblocked = () => {
      if (settled) return;
      settled = true;
      reject(new Error("Local library storage is blocked by another open tab. Close other Mortimer tabs and retry."));
    };
  });
}

async function withStore<T>(mode: IDBTransactionMode, run: (store: IDBObjectStore) => IDBRequest<T> | void, storeName = STORE): Promise<T | undefined> {
  const db = await openDB();
  try {
    return await new Promise<T | undefined>((resolve, reject) => {
      const tx = db.transaction(storeName, mode);
      const request = run(tx.objectStore(storeName));
      tx.oncomplete = () => resolve(request ? request.result : undefined);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

async function saveUniqueItems<T extends { id: string; path: string; size: number; lastModified: number }>(
  storeName: string,
  items: T[],
  keyOf: (item: T) => string,
  shouldSave: () => boolean = () => true,
): Promise<T[]> {
  if (!items.length) return [];
  const db = await openDB();
  try {
    return await new Promise<T[]>((resolve, reject) => {
      const tx = db.transaction(storeName, "readwrite");
      const store = tx.objectStore(storeName);
      const identityIndex = store.index(IDENTITY_INDEX);
      const unique: T[] = [];
      const seen = new Set<string>();
      for (const item of items) {
        const identity = keyOf(item);
        if (seen.has(identity)) continue;
        seen.add(identity);
        const lookup = identityIndex.getKey([item.path, item.size, item.lastModified]);
        lookup.onsuccess = () => {
          // Check inside the IndexedDB request callback, immediately before the
          // write, so a cancelled import cannot repopulate a just-cleared store.
          if (!shouldSave() || lookup.result !== undefined) return;
          store.put(item);
          unique.push(item);
        };
        lookup.onerror = () => { try { tx.abort(); } catch {} };
      }
      tx.oncomplete = () => resolve(unique);
      tx.onerror = () => reject(tx.error ?? new Error("Library transaction failed"));
      tx.onabort = () => reject(tx.error ?? new Error("Library transaction aborted"));
    });
  } finally {
    db.close();
  }
}


// File comparisons cannot await inside a live IDB transaction. Read candidates,
// compare outside the transaction, then verify their IDs in the serialized write
// transaction. A concurrent tab/import forces a fresh comparison before any put.
async function contentCandidates<T extends FileRecord>(storeName: string, items: T[]): Promise<Map<string, T[]>> {
  const db = await openDB();
  try {
    return await new Promise((resolve, reject) => {
      const result = new Map<string, T[]>();
      const tx = db.transaction(storeName, "readonly");
      const index = tx.objectStore(storeName).index(CANDIDATE_INDEX);
      for (const item of items) {
        const key = candidateKey(item);
        if (result.has(key)) continue;
        result.set(key, []);
        const request = index.getAll([item.name, item.size]);
        request.onsuccess = () => result.set(key, request.result);
      }
      tx.oncomplete = () => resolve(result);
      tx.onabort = tx.onerror = () => reject(tx.error ?? new Error("Could not read file identities"));
    });
  } finally { db.close(); }
}

async function saveContentUniqueItems<T extends FileRecord>(storeName: string, items: T[], shouldSave: () => boolean): Promise<T[]> {
  if (!items.length || !shouldSave()) return [];
  for (let attempt = 0; attempt < 8; attempt++) {
    const snapshot = await contentCandidates(storeName, items);
    const known = new Map([...snapshot].map(([key, values]) => [key, [...values]]));
    const fresh: T[] = [];
    for (const item of items) {
      if (!shouldSave()) return [];
      const key = candidateKey(item);
      const candidates = known.get(key) ?? [];
      let exists = false;
      for (const other of candidates) {
        if (await sameFileBytes(item.file, other.file, shouldSave)) { exists = true; break; }
      }
      if (!exists) { fresh.push(item); candidates.push(item); known.set(key, candidates); }
    }
    if (!shouldSave()) return [];
    const db = await openDB();
    try {
      const saved = await new Promise<T[] | null>((resolve, reject) => {
        const tx = db.transaction(storeName, "readwrite");
        const store = tx.objectStore(storeName);
        let remaining = snapshot.size;
        let changed = false;
        let wrote = false;
        const commit = () => {
          if (--remaining > 0) return;
          if (!changed && shouldSave()) {
            try {
              for (const item of fresh) store.put(item);
              wrote = true;
            } catch {
              // Quota/cancellation can abort midway through this request callback.
              // Report the transaction failure without an uncaught callback error.
              try { tx.abort(); } catch {}
            }
          }
        };
        for (const item of new Map(items.map(item => [candidateKey(item), item])).values()) {
          const request = store.index(CANDIDATE_INDEX).getAllKeys([item.name, item.size]);
          request.onsuccess = () => {
            const before = (snapshot.get(candidateKey(item)) ?? []).map(item => item.id).sort();
            changed ||= JSON.stringify(before) !== JSON.stringify(request.result.map(String).sort());
            commit();
          };
        }
        tx.oncomplete = () => resolve(changed ? null : wrote ? fresh : []);
        tx.onabort = tx.onerror = () => reject(tx.error ?? new Error("Library transaction aborted"));
      });
      if (saved !== null) return saved;
    } finally { db.close(); }
  }
  throw new Error("The library is changing in another tab. Close that tab and retry.");
}

// Alias records and cleanup commit together. If the app closes before preferences
// are remapped, the next launch can still recover every old ID from this journal.
async function repairContentDuplicates<T extends FileRecord>(storeName: string): Promise<T[]> {
  const snapshot = (await withStore<T[]>("readonly", store => store.getAll(), storeName)) ?? [];
  const groups = (await groupIdenticalFiles(snapshot)).filter(group => group.length > 1);
  if (!groups.length) return snapshot;
  const db = await openDB();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction([storeName, ALIASES], "readwrite");
      const store = tx.objectStore(storeName);
      for (const group of groups) {
        const live: T[] = [];
        let remaining = group.length;
        for (const item of group) {
          const request = store.get(item.id);
          request.onsuccess = () => {
            if (request.result) live.push(request.result);
            if (--remaining || !live.length) return;
            // Use the planned canonical ID, retaining metadata patched since the read.
            live.sort((a, b) => group.findIndex(item => item.id === a.id) - group.findIndex(item => item.id === b.id));
            try {
              const keep = mergeFileRecords(live);
              store.put(keep);
              for (const drop of live.slice(1)) {
                tx.objectStore(ALIASES).put({ id: drop.id, canonicalId: keep.id, storeName });
                store.delete(drop.id);
              }
            } catch { try { tx.abort(); } catch {} }
          };
        }
      }
      tx.oncomplete = () => resolve();
      tx.onabort = tx.onerror = () => reject(tx.error ?? new Error("Duplicate cleanup aborted"));
    });
  } finally { db.close(); }
  return (await withStore<T[]>("readonly", store => store.getAll(), storeName)) ?? [];
}

export async function loadFileAliases(): Promise<Record<string, string>> {
  const rows = (await withStore<Array<{ id: string; canonicalId: string }>>("readonly", store => store.getAll(), ALIASES)) ?? [];
  return Object.fromEntries(rows.map(row => [row.id, row.canonicalId]));
}


export async function saveLibraryFast(items: StoredLibraryItem[], shouldSave: () => boolean = () => true): Promise<StoredLibraryItem[]> {
  if (!items.length) return [];
  return saveContentUniqueItems(STORE, items, shouldSave);
}

export async function saveComicsFast(items: StoredComic[], shouldSave: () => boolean = () => true): Promise<StoredComic[]> {
  if (!items.length) return [];
  return saveContentUniqueItems(COMICS, items, shouldSave);
}

export async function saveLibrary(items: StoredLibraryItem[]): Promise<StoredLibraryItem[]> {
  if (!items.length) return [];
  return saveContentUniqueItems(STORE, items, () => true);
}

export async function loadLibrary(): Promise<StoredLibraryItem[]> {
  return repairContentDuplicates<StoredLibraryItem>(STORE);
}

export async function deleteTracks(ids: string[]) {
  if (!ids.length) return;
  await withStore("readwrite", (store) => { for (const id of ids) store.delete(id); });
}

export async function patchTrack(id: string, patch: Partial<Omit<StoredLibraryItem, "id" | "file">>) {
  const db = await openDB();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite");
      const store = tx.objectStore(STORE);
      const get = store.get(id);
      get.onsuccess = () => { if (get.result) store.put({ ...get.result, ...patch }); };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

export async function clearLibrary() {
  const db = await openDB();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction([STORE, COMICS, PHOTOS, ALIASES], "readwrite");
      tx.objectStore(STORE).clear();
      tx.objectStore(COMICS).clear();
      tx.objectStore(PHOTOS).clear();
      tx.objectStore(ALIASES).clear();
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error ?? new Error("IndexedDB transaction aborted"));
    });
  } finally {
    db.close();
  }
}

export async function saveComics(items: StoredComic[]): Promise<StoredComic[]> {
  if (!items.length) return [];
  return saveContentUniqueItems(COMICS, items, () => true);
}

export async function loadComics(): Promise<StoredComic[]> {
  return repairContentDuplicates<StoredComic>(COMICS);
}

export async function deleteComics(ids: string[]) {
  if (!ids.length) return;
  await withStore("readwrite", (store) => { for (const id of ids) store.delete(id); }, COMICS);
}

export async function patchComic(id: string, patch: Partial<Omit<StoredComic, "id" | "file">>) {
  const db = await openDB();
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(COMICS, "readwrite");
      const store = tx.objectStore(COMICS);
      const get = store.get(id);
      get.onsuccess = () => { if (get.result) store.put({ ...get.result, ...patch }); };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } finally {
    db.close();
  }
}

export async function loadComicLocations(id: string): Promise<string | undefined> {
  const item = await withStore<StoredComic | undefined>("readonly", (store) => store.get(id), COMICS);
  return item?.locations;
}

export async function savePhotos(items: StoredPhoto[], shouldSave: () => boolean = () => true): Promise<StoredPhoto[]> {
  if (!items.length || !shouldSave()) return [];
  return await saveUniqueItems(PHOTOS, items, (item) => JSON.stringify([item.path, item.size, item.lastModified]), shouldSave);
}

export async function loadPhotos(): Promise<StoredPhoto[]> {
  return (await withStore<StoredPhoto[]>("readonly", (store) => store.getAll(), PHOTOS)) ?? [];
}

export async function deletePhotos(ids: string[]) {
  if (!ids.length) return;
  await withStore("readwrite", (store) => { for (const id of ids) store.delete(id); }, PHOTOS);
}
