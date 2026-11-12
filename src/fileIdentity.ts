/** Compare complete contents, in bounded chunks. Paths and dates vary by picker;
 * names/sizes only select candidates and are never proof that files are equal. */
export async function sameFileBytes(a: Blob, b: Blob, shouldContinue = () => true): Promise<boolean> {
  if (!(a instanceof Blob) || !(b instanceof Blob) || a.size !== b.size) return false;
  const chunkSize = 1024 * 1024;
  for (let offset = 0; offset < a.size; offset += chunkSize) {
    if (!shouldContinue()) throw new Error("File comparison cancelled");
    const [left, right] = await Promise.all([
      a.slice(offset, offset + chunkSize).arrayBuffer(), b.slice(offset, offset + chunkSize).arrayBuffer(),
    ]);
    const x = new Uint8Array(left), y = new Uint8Array(right);
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return false;
  }
  return true;
}

export type FileRecord = { id: string; name: string; size: number; file: Blob; addedAt?: number };
export const candidateKey = (item: Pick<FileRecord, "name" | "size">) => JSON.stringify([item.name, item.size]);

export async function groupIdenticalFiles<T extends FileRecord>(items: T[], shouldContinue = () => true): Promise<T[][]> {
  const candidates = new Map<string, T[][]>();
  for (const item of [...items].sort((a, b) => (a.addedAt ?? 0) - (b.addedAt ?? 0) || a.id.localeCompare(b.id))) {
    const key = candidateKey(item);
    const groups = candidates.get(key) ?? [];
    let match: T[] | undefined;
    for (const group of groups) {
      if (await sameFileBytes(group[0].file, item.file, shouldContinue)) { match = group; break; }
    }
    if (match) match.push(item);
    else groups.push([item]);
    candidates.set(key, groups);
  }
  return [...candidates.values()].flat();
}

export function mergeFileRecords<T extends FileRecord>(items: T[]): T {
  const merged = { ...items[0] };
  for (const item of items.slice(1)) {
    for (const key of Object.keys(item) as Array<keyof T>) {
      if (merged[key] == null || merged[key] === "") merged[key] = item[key];
    }
  }
  return merged;
}

export function resolveAlias(id: string, aliases: Record<string, string>): string {
  const seen = new Set<string>();
  while (aliases[id] && !seen.has(id)) { seen.add(id); id = aliases[id]; }
  return id;
}

export function remapIds(ids: string[], aliases: Record<string, string>): string[] {
  // Playlists and queues are sequences: two positions can intentionally point
  // to the same file, including after aliases converge on a canonical record.
  return ids.map(id => resolveAlias(id, aliases));
}

export function remapUniqueIds(ids: string[], aliases: Record<string, string>): string[] {
  return [...new Set(remapIds(ids, aliases))];
}

export function remapNumbers(values: Record<string, number>, aliases: Record<string, string>, sum = false): Record<string, number> {
  const result: Record<string, number> = {};
  for (const [id, value] of Object.entries(values)) {
    const key = resolveAlias(id, aliases);
    result[key] = sum ? (result[key] ?? 0) + value : Math.max(result[key] ?? 0, value);
  }
  return result;
}
