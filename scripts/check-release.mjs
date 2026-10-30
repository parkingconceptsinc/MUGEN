import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
const read = (p) => JSON.parse(readFileSync(new URL('../' + p, import.meta.url), 'utf8'));
const release = read('shared/release.json');
assert.match(release.version, /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/);
const [major, minor, patch] = release.version.split('.').map(Number);
assert.ok(major <= 20 && minor <= 99 && patch <= 99);
assert.equal(release.androidVersionCode, major * 10000 + minor * 100 + patch);
assert.equal(read('package.json').version, release.version, 'PWA version must match shared release');
assert.equal(read('package-lock.json').version, release.version);
assert.equal(read('package-lock.json').packages[''].version, release.version);
if (process.env.GITHUB_REF_TYPE === 'tag') {
  assert.equal(process.env.GITHUB_REF_NAME, 'android-v' + release.version, 'Release tag must match both apps');
}
console.log('Shared release verified: ' + release.version);
