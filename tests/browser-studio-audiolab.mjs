import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createMediaFixtures, probeMedia, readPcmWav } from './browser/media-fixtures.mjs';

let playwright;
try { playwright = await import('playwright'); }
catch { playwright = await import('/opt/codex/cua_node/lib/node_modules/playwright/index.mjs'); }
const baseURL = process.env.MUGEN_TEST_URL || 'http://127.0.0.1:4173/MUGEN/';
const artifacts = process.env.MUGEN_ARTIFACT_DIR || '/tmp/mugen-browser';
await mkdir(artifacts, { recursive: true });
let serverReady = false;
for (let attempt = 0; attempt < 60; attempt++) {
  try { const response = await fetch(baseURL, { signal: AbortSignal.timeout(1000) }); if (response.ok) { serverReady = true; break; } } catch {}
  await delay(500);
}
assert.ok(serverReady, `Preview server did not become ready: ${baseURL}`);
const fixtures = await createMediaFixtures(join(artifacts, 'fixtures'));
const browser = await playwright.chromium.launch({ headless: true, args: ['--autoplay-policy=no-user-gesture-required'] });
const context = await browser.newContext({ acceptDownloads: true, viewport: { width: 1365, height: 900 } });
const page = await context.newPage();
page.setDefaultTimeout(20_000);
page.setDefaultNavigationTimeout(90_000);
const pageErrors = [];
const results = [];
page.on('pageerror', (error) => pageErrors.push(error.stack || error.message));
page.on('dialog', (dialog) => void dialog.accept());
const screenshot = (name) => page.screenshot({ path: join(artifacts, name + '.png'), fullPage: true });
async function run(name, action) {
  const started = Date.now();
  try { await action(); results.push({ name, status: 'passed', elapsedMs: Date.now() - started }); }
  catch (error) {
    results.push({ name, status: 'failed', elapsedMs: Date.now() - started, error: error.stack || String(error) });
    await screenshot('failure-' + results.length).catch(() => {});
    throw error;
  }
}
async function navigate(name) {
  await page.getByRole('button', { name, exact: true }).first().click();
}
async function fillNumber(label, value) {
  const input = page.getByLabel(label, { exact: true });
  await input.fill(String(value));
  await input.blur();
}
async function assertNumber(label, expected) {
  // React may commit a parent edit before its controlled input has painted.
  // Poll the exact value; timeout still fails instead of accepting stale state.
  await page.waitForFunction(({ label, expected }) => {
    const input = Array.from(document.querySelectorAll('input')).find((element) => element.getAttribute('aria-label') === label);
    return input && Math.abs(Number(input.value) - expected) < 0.0001;
  }, { label, expected }, { timeout: 5000 });
  const actual = Number(await page.getByLabel(label, { exact: true }).inputValue());
  assert.ok(Math.abs(actual - expected) < 0.0001, `${label}: expected ${expected}, got ${actual}`);
}
async function saveDownload(download, filename) {
  const error = await download.failure();
  assert.equal(error, null, `Download failed: ${error}`);
  const target = join(artifacts, filename);
  await download.saveAs(target);
  return target;
}

try {
  await page.goto(baseURL, { waitUntil: 'networkidle' });
  await run('Studio imports media, trims source range and preserves undo/redo', async () => {
    await navigate('Studio');
    await page.getByRole('heading', { name: 'MUGEN Studio', exact: true }).waitFor();
    await page.getByText('Saved on this device', { exact: true }).waitFor();
    await page.getByLabel('Import Studio videos', { exact: true }).setInputFiles(fixtures.video);
    await page.getByRole('button', { name: /Select clip 1:.*mugen-fixture/ }).click();
    await page.getByLabel('Studio preview', { exact: true }).waitFor();
    await fillNumber('Out point (seconds)', 1.5);
    await fillNumber('In point (seconds)', 0.25);
    await assertNumber('In point (seconds)', 0.25);
    await page.getByRole('button', { name: /Select clip 1:.*mugen-fixture/ }).getByText('00:01.25 · 1×', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Undo', exact: true }).click();
    await assertNumber('In point (seconds)', 0);
    await page.getByRole('button', { name: 'Redo', exact: true }).click();
    await assertNumber('In point (seconds)', 0.25);
    await assertNumber('Out point (seconds)', 1.5);
    await page.getByLabel('Project name', { exact: true }).fill('Browser QA project');
    await page.getByLabel('Project name', { exact: true }).blur();
    await page.getByRole('button', { name: 'Save project', exact: true }).click();
    await page.getByText('Saved on this device', { exact: true }).waitFor();
    await screenshot('studio-desktop');
  });
  await run('Studio restores project and copied media after a reload', async () => {
    await page.reload({ waitUntil: 'networkidle' });
    await navigate('Studio');
    await page.getByRole('button', { name: /Select clip 1:.*mugen-fixture/ }).click();
    assert.equal(await page.getByLabel('Project name', { exact: true }).inputValue(), 'Browser QA project');
    await assertNumber('In point (seconds)', 0.25);
    await assertNumber('Out point (seconds)', 1.5);
    const preview = page.getByLabel('Studio preview', { exact: true });
    await preview.evaluate((video) => new Promise((resolve, reject) => {
      if (video.readyState >= 1) return resolve();
      const timeout = setTimeout(() => reject(Error('Restored media metadata timed out')), 15000);
      video.addEventListener('loadedmetadata', () => { clearTimeout(timeout); resolve(); }, { once: true });
      video.addEventListener('error', () => { clearTimeout(timeout); reject(Error('Restored media cannot be decoded')); }, { once: true });
    }));
    const duration = await preview.evaluate((video) => video.duration);
    assert.ok(duration >= 1.9 && duration <= 2.1, `Restored source duration: ${duration}`);
  });
  // Export assertions are filled against the module's public controls below.
  await run('Studio assembles a playable two-clip MP4 with trim and speed changes', async () => {
    await page.getByRole('button', { name: 'Duplicate clip', exact: true }).click();
    await page.getByRole('button', { name: /Select clip 2:.*mugen-fixture/ }).click();
    await fillNumber('Speed', 2);
    await page.getByLabel('Rotation', { exact: true }).selectOption('90');
    await page.getByLabel('Timeline position', { exact: true }).evaluate((input) => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, '0.5');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.waitForFunction(() => {
      const video = document.querySelector('video[aria-label="Studio preview"]');
      return video && video.readyState >= 1 && Math.abs(video.currentTime - 0.75) < 0.15;
    });
    await screenshot('studio-desktop');
    await page.getByRole('button', { name: 'Export MP4', exact: true }).click();
    await page.getByRole('link', { name: 'Download MP4', exact: true }).waitFor({ timeout: 180_000 });
    const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
    await page.getByRole('link', { name: 'Download MP4', exact: true }).click();
    const download = await downloadPromise;
    const path = await saveDownload(download, 'studio-trimmed.mp4');
    const media = probeMedia(path);
    const video = media.streams.find((s) => s.codec_type === 'video');
    assert.ok(video && video.width > 0 && video.height > 0, 'Export must contain a decodable video stream');
    assert.ok(media.streams.some((s) => s.codec_type === 'audio'), 'Export must retain the synthetic audio');
    const duration = Number(media.format.duration);
    assert.ok(duration >= 1.7 && duration <= 2.15, `Expected edited duration ~1.875s, got ${duration}`);
    await fillNumber('Volume', 0.7);
    await page.getByRole('link', { name: 'Download MP4', exact: true }).waitFor({ state: 'detached' });
    assert.equal(await page.getByRole('link', { name: 'Download MP4', exact: true }).count(), 0, 'An edited project must not offer a stale export');
    await page.getByRole('button', { name: 'Undo', exact: true }).click();
    await page.getByRole('link', { name: 'Download MP4', exact: true }).waitFor();
  });
  await run('Studio fits a phone viewport', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await screenshot('studio-mobile');
    const size = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
    assert.ok(size.width <= size.viewport + 1, `Studio page overflows: ${size.width} > ${size.viewport}`);
    await page.setViewportSize({ width: 1365, height: 900 });
  });
  await run('Studio recovery preserves the last valid backup when saving after corruption', async () => {
    const save = async () => {
      await page.getByRole('button', { name: 'Save project', exact: true }).click();
      await page.getByText('Saved on this device', { exact: true }).waitFor();
    };
    await save();
    await save();
    const corruptCurrent = () => page.evaluate(() => new Promise((resolve, reject) => {
      const request = indexedDB.open('mugen-studio-v1', 1);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        const db = request.result;
        const tx = db.transaction('state', 'readwrite');
        tx.objectStore('state').put({ version: 999, name: 'deliberately corrupt test fixture' }, 'current');
        tx.oncomplete = () => { db.close(); resolve(); };
        tx.onerror = () => { db.close(); reject(tx.error); };
      };
    }));
    for (let pass = 0; pass < 2; pass++) {
      await corruptCurrent();
      await page.reload({ waitUntil: 'networkidle' });
      await navigate('Studio');
      await page.getByText('Recovered the previous complete project from the backup. Review it and save when ready.', { exact: true }).waitFor();
      await page.getByRole('button', { name: /Select clip 1:.*mugen-fixture/ }).waitFor();
      await save();
    }
  });
  await run('Audio Lab renders actual processed PCM WAV and preserves the source', async () => {
    await navigate('Audio Lab');
    await page.getByLabel('Audio Lab audio file', { exact: true }).setInputFiles(fixtures.audio);
    const processing = page.getByRole('switch', { name: 'Audio Lab processing', exact: true });
    if (await processing.getAttribute('aria-checked') !== 'true') await processing.click();
    const highPass = page.getByRole('switch', { name: 'High-pass filter', exact: true });
    if (await highPass.getAttribute('aria-checked') !== 'true') await highPass.click();
    const frequency = page.getByRole('slider', { name: 'High-pass frequency', exact: true });
    await frequency.evaluate((input) => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, '1200');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.getByRole('button', { name: 'Render processed WAV', exact: true }).click();
    await page.getByLabel('Rendered audio preview', { exact: true }).waitFor({ timeout: 90_000 });
    const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
    await page.getByRole('button', { name: 'Download processed WAV', exact: true }).click();
    const path = await saveDownload(await downloadPromise, 'audiolab-processed.wav');
    const source = readPcmWav(fixtures.originalWav);
    const output = readPcmWav(await readFile(path));
    assert.ok(output.duration >= source.duration && output.duration <= source.duration + 8, `Unexpected rendered duration ${output.duration}`);
    assert.equal(output.rate, 48_000, 'Processed WAV uses the declared sample rate');
    assert.ok(output.channels === 1 || output.channels === 2, 'Processed WAV is mono/stereo');
    assert.ok(output.rms > 0.01, 'Processed signal must not be silent');
    assert.ok(output.rms < source.rms * 0.9, `High-pass filter must alter the signal: input RMS ${source.rms}, output RMS ${output.rms}`);
    assert.ok(output.peak <= 1, '16-bit output must remain bounded');
    assert.deepEqual(await readFile(fixtures.audio), fixtures.originalWav, 'Rendering must preserve the original source');
    await screenshot('audiolab-desktop');
  });
  await run('Audio Lab settings persist after reload and fit a phone viewport', async () => {
    await page.reload({ waitUntil: 'networkidle' });
    await navigate('Audio Lab');
    assert.equal(await page.getByRole('switch', { name: 'High-pass filter', exact: true }).getAttribute('aria-checked'), 'true');
    assert.equal(Number(await page.getByRole('slider', { name: 'High-pass frequency', exact: true }).inputValue()), 1200);
    await page.setViewportSize({ width: 390, height: 844 });
    await screenshot('audiolab-mobile');
    const size = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
    assert.ok(size.width <= size.viewport + 1, `Audio Lab page overflows: ${size.width} > ${size.viewport}`);
  });
  assert.deepEqual(pageErrors, [], 'Browser must not produce uncaught exceptions');
} finally {
  await writeFile(join(artifacts, 'results.json'), JSON.stringify({ baseURL, results, pageErrors }, null, 2));
  await context.close();
  await browser.close();
}
