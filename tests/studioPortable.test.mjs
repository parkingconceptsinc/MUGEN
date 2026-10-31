import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { exportPortableStudio, importPortableStudio, makePortableManifest, parsePortableManifest, projectFromPortable, studioCrc32 } from '../src/studioPortable.ts';
const root = new URL('../shared/fixtures/', import.meta.url);
const referenceBytes = () => readFile(new URL('studio-reference.mugenstudio', root));
const fixtureManifest = async () => JSON.parse(await readFile(new URL('studio-portable/manifest.json', root), 'utf8'));
const mutate = (bytes, offset, value, type = 'uint16') => { const next = new Uint8Array(bytes); const view = new DataView(next.buffer); view[type === 'uint16' ? 'setUint16' : 'setUint32'](offset, value, true); return new Blob([next]); };
function centralOffsets(bytes) { const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength); const end = bytes.length - 22; let cursor = view.getUint32(end + 16, true); const count = view.getUint16(end + 10, true); const result = []; for (let i=0;i<count;i++) { result.push(cursor); cursor += 46 + view.getUint16(cursor+28,true) + view.getUint16(cursor+30,true) + view.getUint16(cursor+32,true); } return result; }

test('shared Android/PWA fixture imports and re-exports every edit and original byte exactly', async () => {
  const reference = await fixtureManifest();
  const decoded = await importPortableStudio(new Blob([await referenceBytes()]));
  assert.deepEqual(makePortableManifest(decoded.project), reference);
  const original = await readFile(new URL('studio-portable/media/source-000.bin', root));
  const source = reference.media[0];
  assert.deepEqual(new Uint8Array(await decoded.media.get(source.id).arrayBuffer()), new Uint8Array(original));
  const encoded = await exportPortableStudio(decoded.project, decoded.media);
  const roundtrip = await importPortableStudio(encoded);
  assert.deepEqual(makePortableManifest(roundtrip.project), reference);
  assert.deepEqual(new Uint8Array(await roundtrip.media.get(source.id).arrayBuffer()), new Uint8Array(original));
});
test('canonical schema rejects unknown transforms, unsupported versions, bad identifiers and references', async () => {
  const fixture = await fixtureManifest();
  const change = mutate => { const copy = structuredClone(fixture); mutate(copy); return copy; };
  for (const invalid of [change(p=>p.version=2),change(p=>p.project.crop={x:0}),change(p=>p.project.clips[0].captions=[]),change(p=>p.project.id='invalid'),change(p=>p.media[0].path='../movie.mp4'),change(p=>p.project.clips[0].mediaId='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'),change(p=>p.project.clips[0].contrast=3),change(p=>p.project.clips[0].volume=NaN),change(p=>p.project.clips[0].out=.6),change(p=>p.media[0].width=320.1),change(p=>p.project.height=0)]) assert.throws(()=>parsePortableManifest(invalid));
});
test('ZIP rejects compression, encryption, descriptors, CRC mismatch, trailing data and truncated archives', async () => {
  const bytes = new Uint8Array(await referenceBytes()); const offsets = centralOffsets(bytes);
  for (const archive of [mutate(bytes,offsets[0]+10,8),mutate(bytes,offsets[0]+8,1),mutate(bytes,offsets[0]+8,8),mutate(bytes,offsets[0]+16,0,'uint32'),new Blob([bytes,new Uint8Array([1])]),new Blob([bytes.subarray(0,bytes.length-4)])]) await assert.rejects(importPortableStudio(archive));
  const body = new Uint8Array(bytes); body[30 + 'manifest.json'.length + 10] ^= 1;
  await assert.rejects(importPortableStudio(new Blob([body])), /checksum/);
});
test('ZIP rejects unsafe paths, duplicate entries, ZIP64, overlap and unsupported hidden payloads', async () => {
  const bytes = new Uint8Array(await referenceBytes()); const offsets = centralOffsets(bytes);
  const badName = new Uint8Array(bytes); badName.set(new TextEncoder().encode('../nifest.json'), offsets[0]+46);
  await assert.rejects(importPortableStudio(new Blob([badName])), /unsafe path|unsupported file/);
  for (const archive of [mutate(bytes,offsets[1]+42,0,'uint32'),mutate(bytes,offsets[1]+24,0xffffffff,'uint32'),mutate(bytes,offsets[1]+38,0xa0000000,'uint32')]) await assert.rejects(importPortableStudio(archive));
});
test('source bytes are checked against directory CRC and manifest size', async () => {
  const bytes = new Uint8Array(await referenceBytes()); const offset = centralOffsets(bytes)[1]; const view = new DataView(bytes.buffer); const local = view.getUint32(offset+42,true); const start = local+30+view.getUint16(local+26,true)+view.getUint16(local+28,true); bytes[start+5]^=1;
  await assert.rejects(importPortableStudio(new Blob([bytes])), /checksum/);
  const fixture = await fixtureManifest(); const project = projectFromPortable(fixture);
  await assert.rejects(exportPortableStudio(project,new Map()), /missing or incomplete/);
});
test('export only includes referenced source media and cancellation leaves no partial result', async () => {
  const imported = await importPortableStudio(new Blob([await referenceBytes()]));
  const extra = { ...imported.project.sources[0], id:'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' };
  const project = {...imported.project,sources:[...imported.project.sources,extra]};
  assert.equal(makePortableManifest(project).media.length,1);
  const controller = new AbortController();controller.abort();
  await assert.rejects(exportPortableStudio(project,imported.media,{signal:controller.signal}),{name:'AbortError'});
  await assert.rejects(importPortableStudio(new Blob([await referenceBytes()]),{signal:controller.signal}),{name:'AbortError'});
});
test('CRC32 matches the standard ZIP check vector',()=>assert.equal(studioCrc32(new TextEncoder().encode('123456789')),0xcbf43926));
