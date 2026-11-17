import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { indexedDB } from 'fake-indexeddb';
import { createServer } from 'vite';
import { classifyLibraryFile } from '../src/mediaFormats.ts';

let server, library, readItem;
before(async () => {
  globalThis.indexedDB = indexedDB;
  server = await createServer({ configFile: false, server: { middlewareMode: true, hmr: false } });
  library = await server.ssrLoadModule('/src/library.ts');
  ({ readItem } = await server.ssrLoadModule('/src/metadata.ts'));
});
after(async () => { await server?.close(); });

test('mixed file routing persists exact formats and reimport preserves shelves and IDs', async () => {
  const files = [
    new File(['audio bytes'], 'Track.FLAC'), new File(['video bytes'], 'Movie.MKV'),
    new File(['EPUB bytes'], 'Book.EPUB'), new File(['PDF bytes'], 'Manual.PDF'),
    new File(['archive bytes'], 'Manga.CBZ'), new File(['text bytes'], 'Notes.md'),
    new File(['download bytes'], 'download.bin', { type: 'application/epub+zip' }),
    new File(['unrecognized'], 'app.exe'),
  ];
  async function importFiles(prefix) {
    const media = [], documents = [];
    for (const file of files) {
      const route = classifyLibraryFile(file.name, file.type);
      if (!route) continue;
      const path = `${prefix}/${file.name}`;
      if (route.kind === 'audio' || route.kind === 'video') media.push(await readItem({ file, path }, 100));
      else documents.push({ id: crypto.randomUUID(), name: file.name, path, size: file.size, lastModified: file.lastModified, addedAt: 100, format: route.format, shelf: route.kind, file });
    }
    return { media: await library.saveLibraryFast(media), documents: await library.saveComicsFast(documents) };
  }
  const first = await importFiles('First');
  assert.equal(first.media.length, 2);
  assert.equal(first.documents.length, 5);
  assert.deepEqual(first.media.map(item => item.kind), ['audio', 'video']);
  assert.deepEqual(first.documents.map(item => [item.shelf, item.format]), [['books', 'epub'], ['books', 'pdf'], ['comics', 'cbz'], ['books', 'md'], ['books', 'epub']]);
  const id = first.documents.find(item => item.format === 'pdf').id;
  await library.patchComic(id, { shelf: 'comics', title: 'My illustrated manual', pages: 42 });
  assert.deepEqual(await importFiles('Another folder'), { media: [], documents: [] });
  const stored = await library.loadComics();
  assert.equal(stored.length, 5);
  assert.deepEqual(stored.map(item => item.id).sort(), first.documents.map(item => item.id).sort());
  const manual = stored.find(item => item.id === id);
  assert.equal(manual.shelf, 'comics');
  assert.equal(manual.title, 'My illustrated manual');
  assert.equal(manual.pages, 42);
  assert.equal((await library.loadLibrary()).length, 2);
  await assert.rejects(readItem({ file: files.at(-1), path: 'app.exe' }, 100), /Unsupported media file/);
});
