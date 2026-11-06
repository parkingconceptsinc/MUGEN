import type { StoredLibraryItem } from "./library";
import { kindOf, stripExt } from "./util";
import type { Tags } from "./metadataReader";

export const META_VERSION = 3;
export type Incoming = { file: File; path: string };
export type { Tags } from "./metadataReader";

// Parsing runs off the UI thread, with a bounded lifetime for malformed media.
export function readTags(file: Blob, name: string): Promise<Tags> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./metadataWorker.ts", import.meta.url), { type: "module" });
    const finish = () => { clearTimeout(timer); worker.terminate(); };
    const timer = setTimeout(() => { finish(); reject(new Error("Metadata read timed out")); }, 15000);
    worker.onmessage = (event: MessageEvent<{ tags?: Tags; error?: string }>) => {
      finish();
      if (event.data.tags) resolve(event.data.tags);
      else reject(new Error(event.data.error || "Could not read metadata"));
    };
    worker.onerror = () => { finish(); reject(new Error("Metadata reader unavailable")); };
    worker.postMessage({ file, name });
  });
}

export async function readItem({ file, path }: Incoming, addedAt: number): Promise<StoredLibraryItem> {
  const kind = kindOf(file.name);
  const item: StoredLibraryItem = {
    id: crypto.randomUUID(),
    name: file.name,
    kind,
    path,
    size: file.size,
    lastModified: file.lastModified,
    // File already implements Blob. Keep it directly instead of constructing
    // new Blob([file]), which can force an unnecessary full-file copy during import.
    file,
    title: stripExt(file.name),
    addedAt,
    // Audio metadata is enriched after the file is persisted. Keep it stale
    // until that pass completes so an interrupted import is retried next time.
    metaVersion: kind === "audio" ? 0 : META_VERSION,
  };
  if (kind !== "audio") return item;

  // Do not parse tags during import. music-metadata can be slow or stall on
  // unusual files; importing the file itself must never depend on tag parsing.
  // Metadata is enriched asynchronously by the player after the item is saved.
  return item;
}
