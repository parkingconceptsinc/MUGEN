import { resolveAlias } from "./fileIdentity.ts";

export type PlaybackQueue = { version: 1; ids: string[]; index: number };

/** Preserve positions, including repeated IDs. If the selected file is gone,
 * select the next surviving position, or the last one when no later file exists. */
export function restoreQueue(
  ids: readonly unknown[], index: number, aliases: Record<string, string> = {}, available?: ReadonlySet<string>,
): PlaybackQueue {
  const selected = Number.isFinite(index) ? Math.max(0, Math.min(ids.length - 1, Math.floor(index))) : 0;
  const kept: string[] = [];
  let before = 0;
  ids.forEach((id, position) => {
    if (typeof id !== "string" || !id) return;
    const canonical = resolveAlias(id, aliases);
    if (available && !available.has(canonical)) return;
    if (position < selected) before++;
    kept.push(canonical);
  });
  return { version: 1, ids: kept, index: Math.min(before, Math.max(0, kept.length - 1)) };
}

export function readQueueSnapshot(value: unknown, legacyIds: unknown[], legacyIndex: number): PlaybackQueue {
  const snapshot = value as Partial<PlaybackQueue> | null;
  if (snapshot?.version === 1 && Array.isArray(snapshot.ids) && typeof snapshot.index === "number") {
    return restoreQueue(snapshot.ids, snapshot.index);
  }
  return restoreQueue(legacyIds, legacyIndex);
}
