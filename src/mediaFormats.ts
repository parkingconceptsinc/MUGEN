/** The library's single routing table. Pickers and folders never choose a shelf. */
export const audioExt = /\.(mp3|wav|flac|m4a|aac|ogg|oga|opus|weba|aiff|aif|alac|wma|mka|mp2|mpa|ac3|eac3|dts|amr|ape|tak|tta|mpc|wv|shn|caf|au|snd|ra|rm|rma)$/i;
export const videoExt = /\.(mp4|webm|ogv|mov|m4v|mkv|avi|3gp|3g2|ts|m2ts|mts|m2v|mpg|mpeg|mpeg2|vob|wmv|asf|flv|f4v|rmvb|rm|dv|tod|mod|vro|nut|ogm|mxf|divx)$/i;
export const TEXT_BOOK_EXT = /\.(txt|md|markdown|log|nfo|csv|tsv|json|xml|yaml|yml|toml|ini|cfg|conf|srt|vtt|ass|ssa|sub|html|htm|rtf|docx|odt)$/i;
export const PHOTO_EXT = /\.(jpe?g|jfif|png|gif|webp|avif|bmp|heic|heif)$/i;

export type LibraryFileKind = "audio" | "video" | "books" | "comics" | "photos";
export type LibraryFileRoute = { kind: LibraryFileKind; format: string };

const documentMimes: Record<string, string> = {
  "application/pdf": "pdf", "application/epub+zip": "epub",
  "application/vnd.comicbook+zip": "cbz", "application/x-cbz": "cbz",
  "application/vnd.comicbook-rar": "cbr", "application/x-cbr": "cbr",
  "text/plain": "txt", "text/markdown": "md", "text/csv": "csv",
  "text/html": "html", "application/rtf": "rtf", "text/rtf": "rtf",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document": "docx",
  "application/vnd.oasis.opendocument.text": "odt",
};
const photoMimes = new Set(["image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp", "image/heic", "image/heif"]);

export function classifyLibraryFile(name: string, mime = ""): LibraryFileRoute | null {
  // Extensions win over unreliable provider MIME types; overlapping containers
  // retain the existing video-first rule. Format detection is not a codec guarantee.
  if (videoExt.test(name)) return { kind: "video", format: "video" };
  if (audioExt.test(name)) return { kind: "audio", format: "audio" };
  const ext = name.split("/").pop()?.match(/\.([^.]+)$/)?.[1].toLowerCase() ?? "";
  if (ext === "cbz" || ext === "cbr") return { kind: "comics", format: ext };
  if (ext === "epub" || ext === "pdf" || TEXT_BOOK_EXT.test(name)) return { kind: "books", format: ext };
  if (PHOTO_EXT.test(name)) return { kind: "photos", format: "photo" };
  // A MIME hint can recover an extensionless download, but must not turn an
  // explicitly unsupported extension (e.g. ZIP/CB7) into a supported format.
  if (ext && ext !== "bin") return null;
  const type = mime.toLowerCase().split(";", 1)[0].trim();
  if (type.startsWith("audio/")) return { kind: "audio", format: "audio" };
  if (type.startsWith("video/")) return { kind: "video", format: "video" };
  if (photoMimes.has(type)) return { kind: "photos", format: "photo" };
  const format = documentMimes[type];
  return format ? { kind: format === "cbz" || format === "cbr" ? "comics" : "books", format } : null;
}

/** Folder handles expose names before File MIME metadata is available. */
export const mayImportLibraryFile = (name: string) => classifyLibraryFile(name) !== null || !/\.[^./]+$/.test(name) || /\.bin$/i.test(name);
