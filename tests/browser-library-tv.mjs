import assert from 'node:assert/strict';
import { preview } from 'vite';
import { chromium } from 'playwright';
import { setTimeout as delay } from 'node:timers/promises';

const pwaURL = process.env.MUGEN_TEST_URL || 'http://127.0.0.1:4173/MUGEN/';
const tvServer = await preview({ configFile: 'tizen/vite.config.js', preview: { host: '127.0.0.1', port: 4174, strictPort: true } });
let browser;
let activePage;
try {
  let ready = false;
  for (let attempt = 0; attempt < 40; attempt++) {
    try { if ((await fetch(pwaURL)).ok) { ready = true; break; } } catch {}
    await delay(250);
  }
  assert.ok(ready, 'PWA preview did not start');
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();
  activePage = page;
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(pwaURL);
  await page.locator('input[type=file][multiple]:not([webkitdirectory])').waitFor({ state: 'attached' });
  await page.getByText('Loading your library…', { exact: true }).waitFor({ state: 'hidden' });
  // Use the same File objects on retry so identity metadata remains unchanged.
  // Abort the second actual IndexedDB batch, rather than mocking the save result.
  await page.evaluate(() => {
    window.__importFiles = Array.from({ length: 305 }, (_, i) => new File([new Uint8Array(64)], `Song ${i}.mp3`, { type: 'audio/mpeg', lastModified: 1700000000000 }));
    window.__mediaWrites = 0;
    window.__failWrites = true;
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (...args) {
      if (this.name === 'library' && window.__failWrites && ++window.__mediaWrites > 100) {
        this.transaction.abort();
        return;
      }
      return put.apply(this, args);
    };
    window.__pickImportFiles = () => {
      const transfer = new DataTransfer();
      window.__importFiles.forEach(file => transfer.items.add(file));
      const input = document.querySelector('input[type=file][multiple]:not([webkitdirectory])');
      input.files = transfer.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    window.__pickImportFiles();
  });
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('Storage could not save all selected files'));
  assert.match(await page.locator('.toast').innerText(), /100 songs\/videos added/);
  async function countSaved() {
    return page.evaluate(() => new Promise((resolve, reject) => {
      const request = indexedDB.open('mortimer-player');
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction('library');
        const count = tx.objectStore('library').count();
        tx.oncomplete = () => { resolve(count.result); db.close(); };
      };
    }));
  }
  assert.equal(await countSaved(), 100);
  await page.evaluate(() => { window.__failWrites = false; window.__pickImportFiles(); });
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('205 songs/videos added'));
  assert.equal(await countSaved(), 305);
  await page.evaluate(() => window.__pickImportFiles());
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('already in your library'));
  assert.equal(await countSaved(), 305);
  await page.reload();
  await page.locator('input[type=file][multiple]:not([webkitdirectory])').waitFor({ state: 'attached' });
  assert.equal(await countSaved(), 305);
  assert.deepEqual(errors, []);
  console.log('PASS PWA: quota abort saves 100, retry adds 205, repeat/reload preserves 305 without duplicates');
  await page.getByText('Loading your library…', { exact: true }).waitFor({ state: 'hidden' });
  const addFolder = page.locator('.library').getByRole('button', { name: 'Add folder', exact: true });
  await page.evaluate(() => {
    window.showDirectoryPicker = undefined;
    document.querySelector('input[webkitdirectory]').click = () => {};
  });
  await addFolder.click();
  await page.getByText('Preparing the selected folder…').waitFor();
  await page.locator('input[webkitdirectory]').dispatchEvent('cancel');
  await page.getByText('Preparing the selected folder…').waitFor({ state: 'hidden' });
  await page.evaluate(() => {
    const file = (name, unreadable = false) => ({ kind: 'file', name, async getFile() {
      if (unreadable) throw new DOMException('File removed', 'NotFoundError');
      return new File([new Uint8Array(64)], name, { type: 'audio/mpeg' });
    } });
    window.showDirectoryPicker = async () => ({ name: 'Collection', async *values() {
      for (let i = 0; i < 16; i++) yield file(`Early folder track ${i}.mp3`);
      await new Promise(resolve => { window.__continueFolderScan = resolve; });
      yield { kind: 'directory', name: 'Disconnected', async *values() { throw new DOMException('Access lost', 'NotReadableError'); } };
      yield file('Unreadable track.mp3', true);
      yield file('Track from remaining folder.mp3');
    } });
  });
  await addFolder.click();
  await page.waitForFunction(() => typeof window.__continueFolderScan === 'function');
  assert.equal(await countSaved(), 321, 'first 16 files are persisted before folder enumeration finishes');
  await page.evaluate(() => window.__continueFolderScan());
  await page.waitForFunction(() => document.querySelector('.toast')?.textContent.includes('1 unreadable folder, 1 file read error'));
  assert.equal(await countSaved(), 322, 'readable files after an inaccessible folder and file are still saved');
  await page.reload();
  await page.getByText('Loading your library…', { exact: true }).waitFor({ state: 'hidden' });
  assert.equal(await countSaved(), 322);
  assert.deepEqual(errors, []);
  console.log('PASS PWA: first folder batch saves early and unreadable entries do not stop later files');
  await context.close();

  const tv = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const screen = await tv.newPage();
  activePage = screen;
  await screen.route('**/webapis/webapis.js', route => route.fulfill({ contentType: 'text/javascript', body: '/* Samsung API supplied by the test harness. */' }));
  const tvErrors = [];
  screen.on('pageerror', error => tvErrors.push(error.message));
  await screen.addInitScript(() => {
    localStorage.setItem('mugen.tv.library.v1', JSON.stringify(Array.from({ length: 25 }, (_, i) => ({ id: 'item' + i, path: '/usb/Movie' + i + '.mp4', name: 'Movie ' + i + '.mp4', storageLabel: 'removable_sda', video: true }))));
    window.__avCalls = [];
    window.__preparations = [];
    window.webapis = { avplay: Object.fromEntries(['open', 'stop', 'close', 'play', 'pause', 'setDisplayRect', 'setDisplayMethod', 'setListener'].map(name => [name, (...args) => window.__avCalls.push([name, ...args])])) };
    webapis.avplay.prepareAsync = (ready, failed) => window.__preparations.push({ ready, failed });
    window.tizen = { tvinputdevice: { registerKey() {} } };
    window.__mediaKey = keyCode => document.dispatchEvent(new KeyboardEvent('keydown', { keyCode, bubbles: true }));
  });
  await screen.goto('http://127.0.0.1:4174/');
  await screen.locator('[data-view=movies]').click();
  await screen.keyboard.press('ArrowUp');
  assert.equal(await screen.evaluate(() => document.activeElement.dataset.view),'music','Up follows the rail');
  await screen.keyboard.press('ArrowRight');
  assert.ok(await screen.evaluate(() => document.activeElement.matches('.media-row')),'Right enters the media list');
  const focusedRow = await screen.evaluate(() => Number(document.activeElement.dataset.item));
  await screen.keyboard.press('ArrowDown');
  assert.equal(await screen.evaluate(() => Number(document.activeElement.dataset.item)),focusedRow+1,'Down follows the next visible row');
  await screen.locator('[data-action=page][data-dir="1"]').click();
  assert.match(await screen.locator('.library-head').innerText(), /Page 2 \/ 3/);
  await screen.locator('.media-row').first().click();
  assert.equal(await screen.locator('object[type="application/avplayer"]').count(), 1);
  await screen.keyboard.press('Escape');
  await screen.evaluate(() => window.__preparations[0].ready());
  assert.equal(await screen.evaluate(() => window.__avCalls.filter(([name]) => name === 'play').length), 0);
  assert.match(await screen.locator('.topline h1').innerText(), /Movies/);
  await screen.locator('.media-row').first().click();
  await screen.evaluate(() => window.__preparations[1].ready());
  assert.equal(await screen.locator('#player-status').innerText(), 'PLAYING');
  await screen.evaluate(() => { window.__mediaKey(415); window.__mediaKey(415); window.__mediaKey(19); window.__mediaKey(19); });
  assert.equal(await screen.locator('#player-status').innerText(), 'PAUSED');
  assert.equal(await screen.evaluate(() => window.__avCalls.filter(([name]) => name === 'pause').length), 1);
  // Enter activates the focused Back button instead of always toggling playback.
  await screen.keyboard.press('ArrowRight');
  await screen.keyboard.press('Enter');
  await screen.locator('.media-list').waitFor();
  assert.match(await screen.locator('.library-head').innerText(), /Page 2 \/ 3/);
  assert.deepEqual(tvErrors, []);
  console.log('PASS Tizen browser simulation: spatial D-pad navigation, paging, video surface, late preparation, distinct media keys and D-pad Back');
  await tv.close();
} catch (error) {
  console.error('Browser test state:', await activePage?.evaluate(() => ({
    url: location.href, toast: document.querySelector('.toast')?.textContent,
    progress: document.querySelector('.importBar')?.textContent,
    mediaWrites: window.__mediaWrites, body: document.body.innerText.slice(0, 4000),
  })).catch(() => null));
  throw error;
} finally {
  if (browser) await browser.close();
  await new Promise(resolve => tvServer.httpServer.close(resolve));
}
