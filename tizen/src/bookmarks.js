/**
 * TV playback position journal. Failures (full localStorage, revoked storage,
 * malformed JSON) never erase a user's USB library or block media playback.
 * Positions are hints, not a media source of truth.
 */
export function createPlaybackBookmarks(storage, limit = 250) {
  const key = "mugen.tv.resume.v1";
  const maximum = Math.max(1, Math.min(500, Math.trunc(limit) || 250));
  function read() {
    try {
      const document = JSON.parse(storage?.getItem(key) || "null");
      if (!document || document.schema !== 1 || !Array.isArray(document.entries)) return [];
      return document.entries.filter(entry =>
        entry && typeof entry.path === "string" && entry.path.length > 0 && entry.path.length <= 2048 &&
        Number.isSafeInteger(entry.position) && entry.position >= 1000 && entry.position <= 604800000 &&
        Number.isSafeInteger(entry.duration) && entry.duration > 0 && entry.position < entry.duration - 3000 &&
        Number.isSafeInteger(entry.updated) && entry.updated > 0
      ).slice(-500);
    } catch { return []; }
  }
  function write(entries) {
    try {
      storage?.setItem(key, JSON.stringify({ schema: 1, entries }));
      return !!storage;
    } catch { return false; }
  }
  function get(path) {
    if (typeof path !== "string") return 0;
    return read().find(entry => entry.path === path)?.position || 0;
  }
  function clear(path) {
    return write(read().filter(entry => entry.path !== path));
  }
  function set(path, position, duration) {
    if (typeof path !== "string" || !path || path.length > 2048 ||
      !Number.isFinite(position) || !Number.isFinite(duration) || duration <= 0) return false;
    const at = Math.floor(position);
    const length = Math.floor(duration);
    if (at < 1000 || at >= length - 3000) return clear(path);
    if (at > 604800000 || !Number.isSafeInteger(at) || !Number.isSafeInteger(length)) return false;
    const items = read().filter(entry => entry.path !== path);
    items.push({ path, position: at, duration: length, updated: Date.now() });
    items.sort((a, b) => a.updated - b.updated);
    return write(items.slice(-maximum));
  }
  return { get, set, clear };
}
