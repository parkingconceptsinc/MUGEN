import type { StoredLibraryItem } from "./library";
import { stripExt } from "./util";
type ParseBlob = typeof import("music-metadata").parseBlob;

let parser: Promise<ParseBlob | null> | null = null;
const loadParser = () => (parser ??= import("music-metadata").then((m) => m.parseBlob).catch(() => null));

export type Tags = Pick<StoredLibraryItem, "title" | "artist" | "album" | "albumArtist" | "genre" | "year" | "trackNo" | "duration" | "cover">;

export async function parseTags(file: Blob, name: string): Promise<Tags> {
  const tags: Tags = { title: stripExt(name) };
  const parseBlob = await loadParser();
  if (!parseBlob) throw new Error("Metadata parser unavailable");
  const { common, format } = await parseBlob(file);
    tags.title = common.title || tags.title;
    tags.artist = common.artist || common.artists?.[0] || "";
    tags.album = common.album || "";
    tags.albumArtist = common.albumartist || "";
    tags.genre = common.genre?.[0] || "";
    tags.year = common.year || undefined;
    tags.trackNo = common.track?.no ?? undefined;
    tags.duration = format.duration && Number.isFinite(format.duration) ? format.duration : undefined;
  const picture = common.picture?.find((candidate) => {
    const type = (candidate as { type?: unknown }).type;
    return type === 3 || /front/i.test(String(type));
  }) ?? common.picture?.find((candidate) => /cover/i.test(String((candidate as { type?: unknown }).type ?? ""))) ?? common.picture?.[0];
  if (picture) tags.cover = new Blob([new Uint8Array(picture.data)], { type: picture.format });
  return tags;
}

