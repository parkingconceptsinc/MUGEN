import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { chromium } from 'playwright';
import { zipSync, strToU8 } from 'fflate';
import { createMediaFixtures } from './browser/media-fixtures.mjs';

const directory = process.env.MUGEN_ARTIFACT_DIR || '/tmp/mugen-browser';
await mkdir(directory, { recursive: true });
const media = await createMediaFixtures(join(directory, 'unified-fixtures'));
const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR4nGO8Y6Pxn4GBgYGJAQoAItwCQ64eCDwAAAAASUVORK5CYII=', 'base64');
const epub = Buffer.from(zipSync({
  mimetype: strToU8('application/epub+zip'),
  'META-INF/container.xml': strToU8('<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'),
  'book.opf': strToU8('<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">mixed-book</dc:identifier><dc:title>Mixed book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>'),
  'chapter.xhtml': strToU8('<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body><p>Unified import fixture.</p></body></html>'),
}));
const files = [
  { name: 'Track.WAV', mimeType: 'audio/wav', buffer: await readFile(media.audio) },
  { name: 'Movie.MP4', mimeType: 'video/mp4', buffer: await readFile(media.video) },
  { name: 'Book.EPUB', mimeType: 'application/epub+zip', buffer: epub },
  { name: 'Manual.PDF', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n%%EOF') },
  { name: 'Notes.md', mimeType: 'text/plain', buffer: Buffer.from('Unified notes') },
  { name: 'Manga.CBZ', mimeType: 'application/zip', buffer: Buffer.from(zipSync({ 'page1.png': image })) },
  { name: 'Photo.PNG', mimeType: 'image/png', buffer: image },
  { name: 'download.bin', mimeType: 'application/epub+zip', buffer: epub },
  { name: 'app.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('unsupported fixture') },
];
const browser = await chromium.launch({ headless: true, executablePath: process.env.MUGEN_CHROMIUM_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
async function pickFiles() {
  await page.evaluate(files => {
    const transfer = new DataTransfer();
    files.forEach(entry => transfer.items.add(new File([new Uint8Array(entry.bytes)], entry.name, { type: entry.mimeType, lastModified: 1700000000000 })));
    const input = document.querySelector('input[aria-label="Add files to MUGEN"]');
    input.files = transfer.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, files.map(file => ({ name: file.name, mimeType: file.mimeType, bytes: Array.from(file.buffer) })));
}
async function saved() {
  return page.evaluate(() => new Promise((resolve, reject) => {
    const open = indexedDB.open('mortimer-player');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction(['library', 'comics', 'photos']);
      const media = tx.objectStore('library').getAll();
      const comics = tx.objectStore('comics').getAll();
      const photos = tx.objectStore('photos').getAll();
      tx.oncomplete = () => { resolve({ media: media.result.map(x => [x.name, x.kind]).sort(), books: comics.result.map(x => [x.name, x.shelf, x.format]).sort(), photos: photos.result.map(x => x.name).sort() }); db.close(); };
      tx.onerror = () => reject(tx.error);
    };
  }));
}
try {
  await page.goto(process.env.MUGEN_TEST_URL || 'http://127.0.0.1:4173/MUGEN/');
  await page.getByText('Loading your library…', { exact: true }).waitFor({ state: 'hidden' });
  await page.locator('.tabbar').getByRole('button', { name: 'Books', exact: true }).click();
  await page.getByLabel('Add files to MUGEN', { exact: true }).waitFor({ state: 'attached' });
  await pickFiles();
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('1 unsupported file skipped'));
  const first = await saved();
  assert.deepEqual(first.media.sort(), [['Movie.MP4', 'video'], ['Track.WAV', 'audio']]);
  assert.deepEqual(first.books.sort(), [['Book.EPUB', 'books', 'epub'], ['Manga.CBZ', 'comics', 'cbz'], ['Manual.PDF', 'books', 'pdf'], ['Notes.md', 'books', 'md'], ['download.bin', 'books', 'epub']]);
  assert.deepEqual(first.photos, ['Photo.PNG']);
  for (const section of ['Music', 'Videos', 'Comics', 'Photos']) {
    await page.locator('.tabbar').getByRole('button', { name: section, exact: true }).click();
    await page.locator('.toast').waitFor({ state: 'hidden' });
    await pickFiles();
    await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('1 unsupported file skipped'));
    assert.deepEqual(await saved(), first, `reimport from ${section} must preserve counts and routing`);
  }
  // Exercise the directory fallback with the same bytes and nested paths.
  await page.locator('.toast').waitFor({ state: 'hidden' });
  await page.evaluate(files => {
    const transfer = new DataTransfer();
    for (const entry of files) {
      const file = new File([new Uint8Array(entry.bytes)], entry.name, { type: entry.mimeType });
      Object.defineProperty(file, 'webkitRelativePath', { value: `Mixed/${entry.name}` });
      transfer.items.add(file);
    }
    const input = document.querySelector('input[webkitdirectory]');
    input.files = transfer.files;
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, files.filter(file => file.name !== 'Photo.PNG').map(file => ({ name: file.name, mimeType: file.mimeType, bytes: Array.from(file.buffer) })));
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('1 unsupported file skipped'));
  assert.deepEqual(await saved(), first, 'folder paths do not duplicate mixed media or documents');
  await page.reload();
  await page.getByText('Loading your library…', { exact: true }).waitFor({ state: 'hidden' });
  assert.deepEqual(await saved(), first, 'all routed items persist across reload');
  assert.deepEqual(errors, []);
  await writeFile(join(directory, 'unified-import.json'), JSON.stringify({ result: 'passed', saved: first, pageErrors: errors }, null, 2));
  console.log('PASS unified import: mixed formats from five sections, MIME fallback, unsupported skip, folder reimport and reload');
} finally { await context.close(); await browser.close(); }
