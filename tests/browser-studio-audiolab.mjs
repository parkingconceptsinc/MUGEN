import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createHash } from 'node:crypto';
import { createMediaFixtures, probeMedia, readPcmWav, estimateTone, segmentRms } from './browser/media-fixtures.mjs';

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
const soundMetrics = {};
const consoleMessages = [];
const failedRequests = [];
const failedResponses = [];
page.on('console', (message) => {
  if (consoleMessages.length < 300) consoleMessages.push({ type: message.type(), text: message.text().slice(0, 4000) });
});
page.on('requestfailed', (request) => {
  if (failedRequests.length < 100) failedRequests.push({ url: request.url(), error: request.failure()?.errorText });
});
page.on('response', (response) => {
  if (response.status() >= 400 && failedResponses.length < 100) failedResponses.push({ url: response.url(), status: response.status() });
});
page.on('pageerror', (error) => pageErrors.push(error.stack || error.message));
page.on('dialog', (dialog) => void dialog.accept());
const screenshot = (name) => page.screenshot({ path: join(artifacts, name + '.png'), fullPage: true });
async function run(name, action) {
  const started = Date.now();
  try { await action(); results.push({ name, status: 'passed', elapsedMs: Date.now() - started }); return true; }
  catch (error) {
    results.push({ name, status: 'failed', elapsedMs: Date.now() - started, error: error.stack || String(error) });
    await screenshot('failure-' + results.length).catch(() => {});
    await writeFile(join(artifacts, 'failure-' + results.length + '-body.txt'), await page.locator('body').innerText().catch(() => '<body unavailable>'));
    await page.setViewportSize({ width: 1365, height: 900 }).catch(() => {});
    const cancel = page.getByRole('button', { name: 'Cancel export', exact: true });
    if (await cancel.isVisible().catch(() => false)) {
      await cancel.click().catch(() => {});
      await cancel.waitFor({ state: 'detached', timeout: 10000 }).catch(() => {});
    }
    // Keep independent Audio Lab coverage even if Studio delivery fails.
    return false;
  }
}
async function navigate(name) {
  await page.getByRole('button', { name, exact: true }).first().click();
}
async function fillNumber(label, value) {
  const input = page.getByRole('spinbutton', { name: label, exact: true });
  await input.fill(String(value));
  await input.blur();
}
async function assertNumber(label, expected) {
  // React may commit a parent edit before its controlled input has painted.
  // Poll the exact value; timeout still fails instead of accepting stale state.
  await page.waitForFunction(({ label, expected }) => {
    const input = Array.from(document.querySelectorAll('input[type=number]')).find((element) => element.getAttribute('aria-label') === label);
    return input && Math.abs(Number(input.value) - expected) < 0.0001;
  }, { label, expected }, { timeout: 5000 });
  const actual = Number(await page.getByRole('spinbutton', { name: label, exact: true }).inputValue());
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
    const completion = await page.waitForFunction(() => {
      if (document.querySelector('.studioExportPanel a[download]')) return { kind: 'ready' };
      const button = Array.from(document.querySelectorAll('.studioExportPanel button')).find((element) => element.textContent?.trim() === 'Export MP4');
      const notice = document.querySelector('.studioNotice')?.textContent?.trim();
      if (button && !button.disabled && notice) return { kind: 'error', message: notice };
      return false;
    }, undefined, { timeout: 180_000 });
    const exportState = await completion.jsonValue(); await completion.dispose();
    assert.equal(exportState.kind, 'ready', `Studio export failed: ${exportState.message || 'unknown error'}`);
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
  await run('Portable Studio packages restore editing state and original media after replacing the workspace', async () => {
    await navigate('Studio');
    const originalName=await page.getByLabel('Project name',{exact:true}).inputValue();
    const downloads=page.waitForEvent('download',{timeout:30_000});
    await page.getByRole('button',{name:'Export project package',exact:true}).click();
    const portable=await saveDownload(await downloads,'roundtrip.mugenstudio');
    await page.getByRole('button',{name:'New project',exact:true}).click();
    await page.getByRole('button',{name:/Select clip 1:/}).waitFor({state:'detached'});
    await page.getByLabel('Import Studio project package',{exact:true}).setInputFiles(portable);
    await page.getByText('Portable project imported and saved on this device. Originals are preserved.',{exact:true}).waitFor();
    assert.equal(await page.getByLabel('Project name',{exact:true}).inputValue(),originalName);
    await page.getByRole('button',{name:/Select clip 2:/}).waitFor();
    const preview=page.getByLabel('Studio preview',{exact:true});
    await preview.evaluate(video=>new Promise((resolve,reject)=>{if(video.readyState>=1)return resolve();video.addEventListener('loadedmetadata',resolve,{once:true});video.addEventListener('error',()=>reject(Error('Portable video failed')),{once:true});}));
    assert.ok((await preview.evaluate(v=>v.duration))>1.9);
    // Import the same IDs over themselves: the preview must reload, not retain a revoked URL.
    const previousUrl=await preview.evaluate(v=>v.currentSrc);
    await page.getByLabel('Import Studio project package',{exact:true}).setInputFiles(portable);
    await page.waitForFunction(previous=>{const v=document.querySelector('video[aria-label="Studio preview"]');return v&&v.currentSrc!==previous&&v.readyState>=1&&v.videoWidth>0;},previousUrl);
    await page.reload({waitUntil:'networkidle'});await navigate('Studio');
    await page.getByRole('button',{name:/Select clip 2:/}).waitFor();
    assert.equal(await page.getByLabel('Project name',{exact:true}).inputValue(),originalName);
    await screenshot('studio-portable-roundtrip');
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
    const completion = await page.waitForFunction(() => {
      if (document.querySelector('audio[aria-label="Rendered audio preview"]')) return { kind: 'ready' };
      const button = Array.from(document.querySelectorAll('.audioLabWorkstation button')).find((element) => element.textContent?.trim() === 'Render processed WAV');
      const notice = document.querySelector('.labNotice')?.textContent?.trim();
      if (button && !button.disabled && notice && !notice.startsWith('Rendering ')) return { kind: 'error', message: notice };
      return false;
    }, undefined, { timeout: 90_000 });
    const renderState = await completion.jsonValue(); await completion.dispose();
    assert.equal(renderState.kind, 'ready', `Audio Lab rendering failed: ${renderState.message || 'unknown error'}`);
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
    soundMetrics.processing = { inputRms: source.rms, outputRms: output.rms, outputPeak: output.peak, inputDuration: source.duration, outputDuration: output.duration, sampleRate: output.rate };
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
  await page.setViewportSize({ width: 1365, height: 900 });
  await run('Synth Studio renders a pitched sine sequence with rests into an actual WAV', async () => {
    await navigate('Audio Lab');
    await page.getByRole('tab', { name: 'Synth', exact: true }).click();
    const synth = page.locator('[aria-label="Synth Studio"]');
    await synth.waitFor();
    const reference = JSON.parse(await readFile(new URL('../shared/fixtures/synth-patch.json', import.meta.url), 'utf8'));
    const patch = { ...reference, settings: { ...reference.settings,
      wave: 0, octave: 4, attack: 0.002, decay: 0.01, sustain: 1, release: 0.01,
      cutoff: 20000, resonance: 0.707, detuneCents: 0, blend: 0, vibratoHz: 5, vibratoDepth: 0,
      volume: 0.35, bpm: 120, sequence: false, steps: [69, ...Array(15).fill(-1)],
    } };
    await synth.getByLabel('Synth patch file', { exact: true }).setInputFiles({ name: 'qa-sine.mugensynth.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(patch)) });
    await page.waitForFunction(() => document.querySelector('[aria-label="Step 1 note"]')?.value === '69');
    await synth.getByLabel('Render bars', { exact: true }).selectOption('1');
    await synth.getByRole('button', { name: 'Render synth WAV', exact: true }).click();
    await synth.getByLabel('Rendered synth preview', { exact: true }).waitFor({ timeout: 90_000 });
    const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
    await synth.getByRole('button', { name: 'Download synth WAV', exact: true }).click();
    const path = await saveDownload(await downloadPromise, 'synth-sine-a4.wav');
    const output = readPcmWav(await readFile(path));
    const tone = estimateTone(output, 440, 0.02, 0.07);
    const restRms = segmentRms(output, 0.25, 1.8);
    soundMetrics.synth = { ...tone, targetHz: 440, duration: output.duration, rms: output.rms, peak: output.peak, restRms, sampleRate: output.rate };
    assert.equal(output.rate, 48000);
    assert.ok(Math.abs(output.duration - 2.03) <= 0.01, `Expected one bar and release tail ~2.03s, got ${output.duration}`);
    assert.ok(output.peak > 0.01 && output.peak <= 0.99, `Synth output must be audible and bounded: peak ${output.peak}`);
    assert.ok(Math.abs(tone.centsFromTarget) <= 10, `Synth A4 is off pitch by ${tone.centsFromTarget} cents`);
    assert.ok(tone.confidence > 0.95, `Sine segment is not stable: ${tone.confidence}`);
    assert.ok(restRms < 0.0001, `Rest steps must stay silent after the release: RMS ${restRms}`);
    await screenshot('synth-desktop');
    await page.setViewportSize({ width: 390, height: 844 });
    await screenshot('synth-mobile');
    const size = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
    assert.ok(size.width <= size.viewport + 1, `Synth page overflows: ${size.width} > ${size.viewport}`);
    await page.setViewportSize({ width: 1365, height: 900 });
  });
  await run('Voice tuning moves a detuned synthetic voice to its target without changing duration', async () => {
    await navigate('Audio Lab');
    await page.getByRole('tab', { name: 'Voice', exact: true }).click();
    const voice = page.locator('[aria-label="Voice tuning"]');
    await voice.waitFor();
    await voice.getByLabel('Voice tuning audio file', { exact: true }).setInputFiles(fixtures.voice);
    const tuning = voice.getByRole('switch', { name: 'Correct voice pitch', exact: true });
    if (await tuning.getAttribute('aria-checked') !== 'true') await tuning.click();
    await voice.getByLabel('Voice key', { exact: true }).selectOption('9');
    await voice.getByLabel('Voice scale', { exact: true }).selectOption('0');
    for (const [label, value] of [['Correction amount', 1], ['Retune speed', 30], ['Transpose voice', 0]]) {
      await voice.getByRole('slider', { name: label, exact: true }).evaluate((input, value) => {
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, String(value));
        input.dispatchEvent(new Event('input', { bubbles: true })); input.dispatchEvent(new Event('change', { bubbles: true }));
      }, value);
    }
    await voice.getByRole('button', { name: 'Render tuned WAV', exact: true }).click();
    const completion = await page.waitForFunction(() => {
      const region = document.querySelector('[aria-label="Voice tuning"]');
      if (region?.querySelector('audio[aria-label="Tuned voice preview"]')) return { kind: 'ready' };
      const button = Array.from(region?.querySelectorAll('button') || []).find((element) => element.textContent?.trim() === 'Render tuned WAV');
      const notice = region?.querySelector('.voiceNotice')?.textContent?.trim();
      if (button && !button.disabled && notice && !notice.startsWith('Rendering ')) return { kind: 'error', message: notice };
      return false;
    }, undefined, { timeout: 90_000 });
    const state = await completion.jsonValue(); await completion.dispose();
    assert.equal(state.kind, 'ready', `Voice tuning failed: ${state.message || 'unknown error'}`);
    const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
    await voice.getByRole('button', { name: 'Download tuned WAV', exact: true }).click();
    const path = await saveDownload(await downloadPromise, 'voice-tuned-a4.wav');
    const input = readPcmWav(fixtures.voiceWav);
    const output = readPcmWav(await readFile(path));
    const sourceTone = estimateTone(input, 440);
    const tunedTone = estimateTone(output, 440);
    soundMetrics.voiceTuning = { targetHz: 440, source: sourceTone, tuned: tunedTone, sourceDuration: input.duration, outputDuration: output.duration, outputRms: output.rms, outputPeak: output.peak, sampleRate: output.rate };
    assert.ok(Math.abs(output.duration - input.duration) <= 0.01, `Tuning must preserve duration: ${input.duration} → ${output.duration}`);
    assert.ok(output.rms > 0.01 && output.peak <= 1, 'Tuned signal must remain audible and bounded');
    assert.ok(Math.abs(tunedTone.centsFromTarget) <= 20, `Expected A4 after correction, off by ${tunedTone.centsFromTarget} cents`);
    assert.ok(Math.abs(tunedTone.centsFromTarget) < Math.abs(sourceTone.centsFromTarget) * 0.7, 'Correction must materially improve the detuned source');
    assert.deepEqual(await readFile(fixtures.voice), fixtures.voiceWav, 'Tuning must preserve the original file');
    await screenshot('voice-tuning-desktop');
    await page.setViewportSize({ width: 390, height: 844 });
    await screenshot('voice-tuning-mobile');
    const size = await page.evaluate(() => ({ width: document.documentElement.scrollWidth, viewport: window.innerWidth }));
    assert.ok(size.width <= size.viewport + 1, `Voice tuning page overflows: ${size.width} > ${size.viewport}`);
    await page.setViewportSize({ width: 1365, height: 900 });
  });
  if (pageErrors.length) results.push({ name: 'No uncaught browser exceptions', status: 'failed', error: pageErrors.join('\n') });
  const failures = results.filter((result) => result.status === 'failed');
  assert.equal(failures.length, 0, failures.map((failure) => `${failure.name}: ${failure.error}`).join('\n\n'));
} finally {
  await writeFile(join(artifacts, 'results.json'), JSON.stringify({ baseURL, results, soundMetrics, pageErrors, consoleMessages, failedRequests, failedResponses }, null, 2));
  await writeFile(join(artifacts, 'sound-metrics.json'), JSON.stringify(soundMetrics, null, 2));
  await writeFile(join(artifacts, 'final-body.txt'), await page.locator('body').innerText().catch(() => '<body unavailable>'));
  await context.close();
  await browser.close();
}
