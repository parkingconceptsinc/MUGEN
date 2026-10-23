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
const VERSION = 4;
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
      // A compound index lets imports check file identity without getAll() and
      // without rewriting existing records/large video Blobs during migration.
      for (const name of [STORE, COMICS, PHOTOS]) {
        const store = tx.objectStore(name);
        if (!store.indexNames.contains(IDENTITY_INDEX)) {
          store.createIndex(IDENTITY_INDEX, ["path", "size", "lastModified"], { unique: false });
        }
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
          if (lookup.result !== undefined) return;
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


// Fast path for imports that have already been de-duplicated in the active library state.
// Avoid getAll() on every batch: for a large collection that turns repeated imports
// into an O(existing library × batch count) scan and creates avoidable UI stalls.
async function putItemsFast<T extends { id: string }>(storeName: string, items: T[]): Promise<T[]> {
  if (!items.length) return [];
  const db = await openDB();
  try {
    return await new Promise<T[]>((resolve, reject) => {
      const tx = db.transaction(storeName, "readwrite");
      const store = tx.objectStore(storeName);
      for (const item of items) store.put(item);
      tx.oncomplete = () => resolve(items);
      tx.onerror = () => reject(tx.error ?? new Error("Library write failed"));
      tx.onabort = () => reject(tx.error ?? new Error("Library write aborted"));
    });
  } finally {
    db.close();
  }
}

// Import batches can overlap React state updates. Enforce uniqueness inside the
// IndexedDB transaction so repeated batches/selections cannot create duplicate rows.
export async function saveLibraryFast(items: StoredLibraryItem[]): Promise<StoredLibraryItem[]> {
  if (!items.length) return [];
  return saveUniqueItems(STORE, items, (item) => JSON.stringify([item.path, item.size, item.lastModified]));
}

export async function saveComicsFast(items: StoredComic[]): Promise<StoredComic[]> {
  if (!items.length) return [];
  return saveUniqueItems(COMICS, items, (item) => JSON.stringify([item.path, item.size, item.lastModified]));
}

export async function saveLibrary(items: StoredLibraryItem[]): Promise<StoredLibraryItem[]> {
  if (!items.length) return [];
  return await saveUniqueItems(STORE, items, (item) => JSON.stringify([item.path, item.size, item.lastModified]));
}

export async function loadLibrary(): Promise<StoredLibraryItem[]> {
  return (await withStore<StoredLibraryItem[]>("readonly", (store) => store.getAll())) ?? [];
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
      const tx = db.transaction([STORE, COMICS, PHOTOS], "readwrite");
      tx.objectStore(STORE).clear();
      tx.objectStore(COMICS).clear();
      tx.objectStore(PHOTOS).clear();
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
  return await saveUniqueItems(COMICS, items, (item) => JSON.stringify([item.path, item.size, item.lastModified]));
}

export async function loadComics(): Promise<StoredComic[]> {
  return (await withStore<StoredComic[]>("readonly", (store) => store.getAll(), COMICS)) ?? [];
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

export async function savePhotos(items: StoredPhoto[]): Promise<StoredPhoto[]> {
  if (!items.length) return [];
  return await saveUniqueItems(PHOTOS, items, (item) => JSON.stringify([item.path, item.size, item.lastModified]));
}

export async function loadPhotos(): Promise<StoredPhoto[]> {
  return (await withStore<StoredPhoto[]>("readonly", (store) => store.getAll(), PHOTOS)) ?? [];
}

export async function deletePhotos(ids: string[]) {
  if (!ids.length) return;
  await withStore("readwrite", (store) => { for (const id of ids) store.delete(id); }, PHOTOS);
}
