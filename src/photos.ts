import { useSyncExternalStore } from "react";
import { deletePhotos, loadPhotos, savePhotos, type PhotoExif, type StoredPhoto } from "./library";
import { readPref, writePref } from "./prefs";
import { folderOf } from "./util";

export type Photo = {
  id: string;
  name: string;
  path: string;
  folder: string;
  size: number;
  lastModified: number;
  addedAt: number;
  takenAt: number;
  width: number;
  height: number;
  mime: string;
  display: Blob;
  thumb: Blob;
  exif?: PhotoExif;
};

export type PhotoAlbum = { id: string; name: string; photoIds: string[]; createdAt: number; coverId?: string };

type PhotoState = {
  loaded: boolean;
  photos: Photo[];
  favorites: Set<string>;
  albums: PhotoAlbum[];
  rotation: Record<string, number>;
};

const PHOTO_EXT = /\.(jpe?g|jfif|png|gif|webp|avif|bmp|heic|heif)$/i;
const DECODABLE = /\.(jpe?g|jfif|png|gif|webp|avif|bmp)$/i;
const KEEP_AS_IS = new Set(["image/jpeg", "image/png", "image/webp", "image/gif", "image/avif"]);
const DISPLAY_MAX = 2560;
const THUMB_SHORT = 320;
const THUMB_LONG = 640;

export const isPhotoFile = (name: string) => PHOTO_EXT.test(name);

let state: PhotoState = {
  loaded: false,
  photos: [],
  favorites: new Set(readPref<string[]>("photoFavorites", [])),
  albums: readPref<PhotoAlbum[]>("photoAlbums", []),
  rotation: readPref<Record<string, number>>("photoRotation", {}),
};
const listeners = new Set<() => void>();
let loading: Promise<void> | null = null;
const urls = new Map<string, { thumb?: string; display?: string }>();

function emit(next: Partial<PhotoState>) {
  state = { ...state, ...next };
  if (next.favorites) writePref("photoFavorites", [...next.favorites]);
  if (next.albums) writePref("photoAlbums", next.albums);
  if (next.rotation) writePref("photoRotation", next.rotation);
  for (const listener of listeners) listener();
}

function toPhoto(x: StoredPhoto): Photo {
  return { ...x, folder: folderOf(x.path) };
}

export function ensurePhotosLoaded() {
  if (state.loaded || loading) return;
  loading = loadPhotos()
    .then((items) => emit({ loaded: true, photos: items.map(toPhoto) }))
    .catch(() => emit({ loaded: true }))
    .finally(() => { loading = null; });
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  ensurePhotosLoaded();
  return () => { listeners.delete(listener); };
}

export function usePhotos(): PhotoState {
  return useSyncExternalStore(subscribe, () => state);
}

export function thumbUrl(photo: Photo) {
  const entry = urls.get(photo.id) ?? {};
  if (!entry.thumb) entry.thumb = URL.createObjectURL(photo.thumb);
  urls.set(photo.id, entry);
  return entry.thumb;
}

export function displayUrl(photo: Photo) {
  const entry = urls.get(photo.id) ?? {};
  if (!entry.display) entry.display = URL.createObjectURL(photo.display);
  urls.set(photo.id, entry);
  return entry.display;
}

function revoke(id: string) {
  const entry = urls.get(id);
  if (entry?.thumb) URL.revokeObjectURL(entry.thumb);
  if (entry?.display) URL.revokeObjectURL(entry.display);
  urls.delete(id);
}

// ---------- Library actions ----------

export function toggleFavorite(ids: string[], on?: boolean) {
  const favorites = new Set(state.favorites);
  const value = on ?? !ids.every((id) => favorites.has(id));
  for (const id of ids) value ? favorites.add(id) : favorites.delete(id);
  emit({ favorites });
}

export function rotatePhoto(id: string, by = 90) {
  const rotation = { ...state.rotation, [id]: (((state.rotation[id] ?? 0) + by) % 360 + 360) % 360 };
  if (!rotation[id]) delete rotation[id];
  emit({ rotation });
}

export async function removePhotos(ids: string[]) {
  const gone = new Set(ids);
  await deletePhotos(ids).catch(() => {});
  for (const id of ids) revoke(id);
  const favorites = new Set([...state.favorites].filter((id) => !gone.has(id)));
  const albums = state.albums.map((a) => ({ ...a, photoIds: a.photoIds.filter((id) => !gone.has(id)), coverId: a.coverId && gone.has(a.coverId) ? undefined : a.coverId }));
  const rotation = Object.fromEntries(Object.entries(state.rotation).filter(([id]) => !gone.has(id)));
  emit({ photos: state.photos.filter((p) => !gone.has(p.id)), favorites, albums, rotation });
}

export function resetPhotos() {
  for (const id of urls.keys()) revoke(id);
  emit({ photos: [], favorites: new Set(), albums: [], rotation: {} });
}

export function createAlbum(name: string, photoIds: string[] = []): PhotoAlbum {
  const album: PhotoAlbum = { id: crypto.randomUUID(), name: name.trim() || "Untitled album", photoIds: [...new Set(photoIds)], createdAt: Date.now() };
  emit({ albums: [...state.albums, album] });
  return album;
}

export function renameAlbum(id: string, name: string) {
  emit({ albums: state.albums.map((a) => (a.id === id ? { ...a, name: name.trim() || a.name } : a)) });
}

export function deleteAlbum(id: string) {
  emit({ albums: state.albums.filter((a) => a.id !== id) });
}

export function addToAlbum(id: string, photoIds: string[]) {
  emit({ albums: state.albums.map((a) => (a.id === id ? { ...a, photoIds: [...new Set([...a.photoIds, ...photoIds])] } : a)) });
}

export function removeFromAlbum(id: string, photoIds: string[]) {
  const gone = new Set(photoIds);
  emit({ albums: state.albums.map((a) => (a.id === id ? { ...a, photoIds: a.photoIds.filter((p) => !gone.has(p)), coverId: a.coverId && gone.has(a.coverId) ? undefined : a.coverId } : a)) });
}

export function setAlbumCover(id: string, photoId: string) {
  emit({ albums: state.albums.map((a) => (a.id === id ? { ...a, coverId: photoId } : a)) });
}

// ---------- Sharing and downloads ----------

const fileOf = (photo: Photo) => new File([photo.display], photo.name, { type: photo.display.type || photo.mime });

export async function sharePhotos(photos: Photo[]): Promise<"shared" | "unsupported" | "cancelled"> {
  const files = photos.map(fileOf);
  const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean };
  if (!nav.share || !nav.canShare?.({ files })) return "unsupported";
  try {
    await nav.share({ files });
    return "shared";
  } catch {
    return "cancelled";
  }
}

export function downloadPhotos(photos: Photo[]) {
  photos.forEach((photo, i) => {
    window.setTimeout(() => {
      const a = document.createElement("a");
      a.href = displayUrl(photo);
      a.download = photo.name;
      a.click();
    }, i * 250);
  });
}

// ---------- Import ----------

async function readExif(file: Blob): Promise<{ takenAt?: number; exif?: PhotoExif }> {
  try {
    const exifr = (await import("exifr")).default;
    const tags = await exifr.parse(file, {
      gps: true,
      pick: ["DateTimeOriginal", "CreateDate", "Make", "Model", "LensModel", "FocalLength", "FNumber", "ExposureTime", "ISO", "latitude", "longitude"],
    });
    if (!tags) return {};
    const date: unknown = tags.DateTimeOriginal ?? tags.CreateDate;
    const exif: PhotoExif = {
      make: typeof tags.Make === "string" ? tags.Make.trim() : undefined,
      model: typeof tags.Model === "string" ? tags.Model.trim() : undefined,
      lens: typeof tags.LensModel === "string" ? tags.LensModel.trim() : undefined,
      focalLength: typeof tags.FocalLength === "number" ? tags.FocalLength : undefined,
      fNumber: typeof tags.FNumber === "number" ? tags.FNumber : undefined,
      exposureTime: typeof tags.ExposureTime === "number" ? tags.ExposureTime : undefined,
      iso: typeof tags.ISO === "number" ? tags.ISO : undefined,
      lat: typeof tags.latitude === "number" ? tags.latitude : undefined,
      lon: typeof tags.longitude === "number" ? tags.longitude : undefined,
    };
    const clean = Object.fromEntries(Object.entries(exif).filter(([, v]) => v !== undefined)) as PhotoExif;
    return {
      takenAt: date instanceof Date && !Number.isNaN(date.getTime()) ? date.getTime() : undefined,
      exif: Object.keys(clean).length ? clean : undefined,
    };
  } catch {
    return {};
  }
}

function draw(bitmap: ImageBitmap, width: number, height: number, type: string, quality: number): Promise<Blob | null> {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  if (!ctx) return Promise.resolve(null);
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(bitmap, 0, 0, width, height);
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

async function processPhoto(file: File, path: string, addedAt: number): Promise<StoredPhoto> {
  if (!DECODABLE.test(file.name)) throw new Error("unsupported");
  // createImageBitmap applies the EXIF orientation, so sizes and pixels come out upright.
  const [bitmap, meta] = await Promise.all([createImageBitmap(file), readExif(file)]);
  try {
    const { width, height } = bitmap;
    const mime = file.type || "image/jpeg";
    const long = Math.max(width, height);
    let display: Blob = file;
    if (long > DISPLAY_MAX || !KEEP_AS_IS.has(mime)) {
      const scale = Math.min(1, DISPLAY_MAX / long);
      display = (await draw(bitmap, Math.round(width * scale), Math.round(height * scale), "image/jpeg", 0.9)) ?? file;
    }
    const thumbScale = Math.min(1, THUMB_SHORT / Math.min(width, height), THUMB_LONG / long);
    const thumb = (await draw(bitmap, Math.max(1, Math.round(width * thumbScale)), Math.max(1, Math.round(height * thumbScale)), "image/jpeg", 0.8)) ?? display;
    return {
      id: crypto.randomUUID(), name: file.name, path, size: file.size, lastModified: file.lastModified, addedAt,
      takenAt: meta.takenAt ?? file.lastModified, width, height, mime, display, thumb, exif: meta.exif,
    };
  } finally {
    bitmap.close();
  }
}

export type PhotoImportResult = { added: number; failed: number; duplicates: number; unsupported: number; storageFailed: boolean };

export async function importPhotos(entries: Array<{ file: File; path: string }>, onProgress?: (done: number, total: number) => void): Promise<PhotoImportResult> {
  await new Promise<void>((resolve) => {
    if (state.loaded) return resolve();
    ensurePhotosLoaded();
    const unsubscribe = subscribe(() => { if (state.loaded) { unsubscribe(); resolve(); } });
  });
  const seen = new Set(state.photos.map((p) => `${p.path}|${p.size}|${p.lastModified}`));
  const fresh = entries.filter(({ file, path }) => {
    const key = `${path}|${file.size}|${file.lastModified}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const result: PhotoImportResult = { added: 0, failed: 0, duplicates: entries.length - fresh.length, unsupported: 0, storageFailed: false };
  const addedAt = Date.now();
  const batchSize = 12;
  for (let start = 0; start < fresh.length; start += batchSize) {
    const slice = fresh.slice(start, start + batchSize);
    const items: StoredPhoto[] = [];
    // Decode a few at a time: full-size photos are large in memory once decoded.
    for (let i = 0; i < slice.length; i += 3) {
      const done = await Promise.allSettled(slice.slice(i, i + 3).map(({ file, path }, k) => processPhoto(file, path, addedAt + start + i + k)));
      done.forEach((r, k) => {
        if (r.status === "fulfilled") items.push(r.value);
        else if (!DECODABLE.test(slice[i + k].file.name)) result.unsupported++;
        else result.failed++;
      });
    }
    const saved = await savePhotos(items).catch(() => { result.storageFailed = true; return []; });
    result.added += saved.length;
    if (saved.length) emit({ photos: [...state.photos, ...saved.map(toPhoto)] });
    onProgress?.(Math.min(start + slice.length, fresh.length), fresh.length);
  }
  return result;
}

export function describeImport(r: PhotoImportResult) {
  const parts = [r.added ? `${r.added} photo${r.added === 1 ? "" : "s"} added` : "No new photos added"];
  if (r.unsupported) parts.push(`${r.unsupported} HEIC/HEIF file${r.unsupported === 1 ? "" : "s"} skipped (convert to JPEG)`);
  if (r.failed) parts.push(`${r.failed} couldn't be read`);
  if (r.storageFailed) parts.push("Some photos could not be saved to device storage");
  return parts.join(" · ");
}

// ---------- Formatting ----------

export function exposureLabel(seconds?: number) {
  if (!seconds) return undefined;
  return seconds >= 1 ? `${seconds}s` : `1/${Math.round(1 / seconds)}s`;
}

export const dayKey = (t: number) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
};
