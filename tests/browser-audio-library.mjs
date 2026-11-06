import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { syntheticPcmWav } from './browser/media-fixtures.mjs';

const url = process.env.MUGEN_TEST_URL || 'http://127.0.0.1:4173/MUGEN/';
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const wav = syntheticPcmWav({ duration: 20 });
try {
  await page.addInitScript(() => {
    window.__metadataWorkers = 0;
    const WorkerBase = window.Worker;
    window.Worker = class extends WorkerBase {
      constructor(url, ...args) { super(url, ...args); if (String(url).includes('metadataWorker')) window.__metadataWorkers++; }
    };
    if (sessionStorage.getItem('failQueueSave')) {
      const setItem = Storage.prototype.setItem;
      Storage.prototype.setItem = function(key, value) {
        if (this === localStorage && key === 'mortimer:playbackQueue') throw new DOMException('Interrupted queue save', 'QuotaExceededError');
        return setItem.call(this, key, value);
      };
    }
  });
  await page.goto(url);
  await page.getByText('Loading your library…', { exact: true }).waitFor({ state: 'hidden' });
  await page.evaluate(async bytes => {
    const file = new Blob([new Uint8Array(bytes)], { type: 'audio/wav' });
    const other = new Uint8Array(bytes); other[other.length-1] ^= 1;
    await new Promise((resolve, reject) => {
      const request = indexedDB.open('mortimer-player');
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result, tx = db.transaction('library', 'readwrite'), store = tx.objectStore('library');
        for (const [id, path, addedAt] of [['old','Music/Song.wav',1], ['copy','Song.wav',2], ['copy2','Disk/Music/Song.wav',3]]) {
          store.put({ id, name: 'Song.wav', title: 'Song', kind: 'audio', path, size: file.size, lastModified: addedAt, addedAt, file, metaVersion: 3 });
        }
        store.put({ id: 'other', name: 'Song.wav', title: 'Other recording', kind: 'audio', path: 'Music/Song.wav', size: file.size, lastModified: 1, addedAt: 4, file: new Blob([other],{type:'audio/wav'}), metaVersion:3 });
        tx.oncomplete = () => { db.close(); resolve(); };
      };
    });
    localStorage.removeItem('mortimer:playbackQueue');
    sessionStorage.setItem('failQueueSave','1');
    for (const [key,value] of Object.entries({ queue:['copy','other','copy2','other'], qIndex:2, originalQueue:['copy','other','copy2','other'], favorites:['copy2','old'], playlists:[{id:'list',name:'Saved',createdAt:1,trackIds:['copy','other','old']}], plays:{old:2,copy:3}, resume:{id:'copy2',time:2} })) localStorage.setItem('mortimer:'+key,JSON.stringify(value));
  }, Array.from(wav));
  await page.reload();
  await page.waitForFunction(() => document.querySelector('.library .screenHeader')?.textContent.includes('2 songs') || document.querySelector('.library')?.textContent.includes('2 songs'));
  assert.equal(await page.evaluate(() => localStorage.getItem('mortimer:playbackQueue')),null,'failed snapshot writes retain the legacy queue for recovery');
  await page.evaluate(() => sessionStorage.removeItem('failQueueSave'));
  await page.reload();
  await page.getByText('Loading your library…',{exact:true}).waitFor({state:'hidden'});
  const prefs = await page.evaluate(() => Object.fromEntries(['playbackQueue','originalQueue','favorites','playlists','plays','resume'].map(key => [key,JSON.parse(localStorage.getItem('mortimer:'+key))])));
  assert.deepEqual(prefs.playbackQueue,{version:1,ids:['old','other','old','other'],index:2});
  assert.deepEqual(prefs.originalQueue,['old','other','old','other']); assert.deepEqual(prefs.favorites,['old']);
  assert.deepEqual(prefs.playlists[0].trackIds,['old','other','old']); assert.equal(prefs.plays.old,5); assert.equal(prefs.resume.id,'old');
  await page.reload();
  await page.getByText('Loading your library…',{exact:true}).waitFor({state:'hidden'});
  assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('mortimer:playbackQueue'))),prefs.playbackQueue,'a second startup keeps the selected repeated occurrence');
  const input = page.locator('input[type=file][multiple]:not([webkitdirectory])');
  await input.setInputFiles({ name:'Song.wav',mimeType:'audio/wav',buffer:wav });
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('already in your library'));

  // Reproduce a delayed rejection from an older play request while the new
  // track is already running. It must not change the current playing state.
  await page.evaluate(() => {
    const audio = document.querySelector('audio');
    const play = audio.play.bind(audio); let first=true;
    audio.play = () => {
      if(first) { first=false; return new Promise((_,reject) => setTimeout(() => reject(new DOMException('old request','NotAllowedError')),1500)); }
      return play();
    };
  });
  await page.locator('.miniPlayer').getByRole('button',{name:'Play',exact:true}).click();
  await page.locator('.miniPlayer').getByRole('button',{name:'Next',exact:true}).click();
  await page.waitForFunction(() => {const a=document.querySelector('audio');return !a.paused && a.currentTime>.3;});
  assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('mortimer:playbackQueue'))),{version:1,ids:['old','other','old','other'],index:3},'queue actions save order and index together');
  const before = await page.evaluate(() => ({ workers:window.__metadataWorkers,time:document.querySelector('audio').currentTime,src:document.querySelector('audio').src }));
  await input.setInputFiles(Array.from({length:8},(_,i)=>({name:`Added ${i}.wav`,mimeType:'audio/wav',buffer:wav})));
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('8 songs/videos added'));
  await page.locator('.tabbar').getByRole('button',{name:'Videos',exact:true}).click();
  await page.locator('.tabbar').getByRole('button',{name:'Music',exact:true}).click();
  await page.waitForFunction(time => document.querySelector('audio').currentTime>time+3,before.time);
  const during = await page.evaluate(() => ({ workers:window.__metadataWorkers,paused:document.querySelector('audio').paused,src:document.querySelector('audio').src }));
  assert.equal(during.workers,before.workers,'tag workers must wait while music plays');
  assert.equal(during.paused,false); assert.equal(during.src,before.src,'imports must not reload the audio resource');
  await page.locator('.miniPlayer').getByRole('button',{name:'Pause',exact:true}).waitFor();
  await page.locator('.miniPlayer').getByRole('button',{name:'Pause',exact:true}).click();
  await page.waitForFunction(count => window.__metadataWorkers>count,before.workers);
  await page.waitForFunction(() => new Promise(resolve => {
    const open=indexedDB.open('mortimer-player');open.onsuccess=()=>{const db=open.result,tx=db.transaction('library'),r=tx.objectStore('library').getAll();tx.oncomplete=()=>{db.close();resolve(r.result.length===10 && r.result.filter(x=>x.name.startsWith('Added')).every(x=>x.metaVersion===3));};};
  }),{},{timeout:30000});
  await page.reload();
  await page.getByText('Loading your library…',{exact:true}).waitFor({state:'hidden'});
  await page.waitForFunction(() => document.querySelector('.library')?.textContent.includes('10 songs'));
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('mortimer:playbackQueue')).index),3,'track selection survives another restart');
  assert.deepEqual(errors,[]);
  console.log('PASS PWA audio/library: legacy copies collapse, playlist repeats/selected queue occurrence survive interrupted saves and restarts, homonyms survive, reimport stays unique, stale play errors are ignored, music advances during import/navigation, metadata waits and resumes');
} catch(error) {
  console.error(await page.evaluate(() => ({body:document.body.innerText.slice(0,2500),workers:window.__metadataWorkers,audio:{paused:document.querySelector('audio')?.paused,time:document.querySelector('audio')?.currentTime,error:document.querySelector('audio')?.error?.message}})).catch(()=>null));
  throw error;
} finally { await context.close(); await browser.close(); }
