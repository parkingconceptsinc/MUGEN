const MEDIA = /\.(mp4|m4v|mkv|mov|webm|avi|mp3|m4a|aac|flac|wav|ogg|wma)$/i;
const VIDEO = /\.(mp4|m4v|mkv|mov|webm|avi)$/i;

// Continue past an unreadable child, but never report a truncated scan as complete.
export async function collectUsbMedia(root, storageLabel, {
  maxFiles = 15000, maxDepth = 64, shouldContinue = () => true, onProgress = () => {},
} = {}) {
  const stack = [{ entry: root, depth: 0 }];
  const visited = new Set();
  const items = [];
  let unreadable = 0, limited = false, checked = 0;
  while (stack.length && shouldContinue()) {
    if (items.length >= maxFiles) { limited = true; break; }
    const { entry, depth } = stack.pop();
    let path;
    try { path = entry.fullPath || (entry.toURI && entry.toURI()) || entry.path; }
    catch { unreadable++; continue; }
    if (!path || visited.has(path)) continue;
    visited.add(path);
    if (entry.isDirectory) {
      if (depth > maxDepth) { limited = true; continue; }
      if (depth && String(entry.name).startsWith(".")) continue;
      try {
        const children = await new Promise((resolve, reject) => entry.listFiles(resolve, reject));
        for (let i = children.length - 1; i >= 0; i--) stack.push({ entry: children[i], depth: depth + 1 });
      } catch { unreadable++; }
    } else if (MEDIA.test(entry.name)) {
      items.push({ id: storageLabel + ":" + path, path, name: entry.name, storageLabel, video: VIDEO.test(entry.name) });
    }
    if (++checked % 50 === 0) {
      onProgress(items.length);
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
  }
  onProgress(items.length);
  return { items, unreadable, limited, cancelled: !shouldContinue() };
}
