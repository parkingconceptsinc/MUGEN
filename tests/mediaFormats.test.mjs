import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyLibraryFile, mayImportLibraryFile } from '../src/mediaFormats.ts';

test('a mixed selection routes every format once, independently of a screen or picker', () => {
  const files = ['TRACK.FLAC', 'Movie.MKV', 'Book.EPUB', 'Manual.PDF', 'Notes.md', 'Manga.CBZ', 'Archive.CBR', 'Photo.JPEG', 'data.exe'];
  assert.deepEqual(files.map(name => classifyLibraryFile(name)?.kind ?? null), ['audio', 'video', 'books', 'books', 'books', 'comics', 'comics', 'photos', null]);
  assert.deepEqual(classifyLibraryFile('sound.mp3', 'video/mp4'), { kind: 'audio', format: 'audio' });
  assert.deepEqual(classifyLibraryFile('clip.rm'), { kind: 'video', format: 'video' });
});

test('extensionless downloads recover exact format from MIME without promoting unsupported archives', () => {
  assert.deepEqual(classifyLibraryFile('download', 'application/pdf; charset=binary'), { kind: 'books', format: 'pdf' });
  assert.deepEqual(classifyLibraryFile('download.bin', 'Application/Epub+Zip'), { kind: 'books', format: 'epub' });
  assert.deepEqual(classifyLibraryFile('download', 'image/png'), { kind: 'photos', format: 'photo' });
  assert.deepEqual(classifyLibraryFile('download', 'audio/mpeg'), { kind: 'audio', format: 'audio' });
  assert.equal(classifyLibraryFile('backup.zip', 'application/epub+zip'), null);
  assert.equal(classifyLibraryFile('comic.cb7', 'application/vnd.comicbook+zip'), null);
  assert.equal(classifyLibraryFile('unknown', 'application/octet-stream'), null);
  assert.equal(mayImportLibraryFile('download'), true);
  assert.equal(mayImportLibraryFile('download.bin'), true);
  assert.equal(mayImportLibraryFile('app.exe'), false);
});
