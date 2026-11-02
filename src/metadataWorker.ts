import { parseTags } from "./metadataReader";

self.onmessage = async (event: MessageEvent<{ file: Blob; name: string }>) => {
  try { self.postMessage({ tags: await parseTags(event.data.file, event.data.name) }); }
  catch (error) { self.postMessage({ error: (error as Error).message }); }
};
