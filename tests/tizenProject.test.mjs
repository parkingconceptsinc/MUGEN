import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { validateTvProject } from '../tizen/validate-project.mjs';
const config = readFileSync(new URL('../tizen/config.xml', import.meta.url), 'utf8');
const html = readFileSync(new URL('../tizen/index.html', import.meta.url), 'utf8');

test('TV project declares a valid package and loads the actual Samsung Product API', () => {
  assert.doesNotThrow(() => validateTvProject(config, html));
});
test('TV packaging rejects the original dotted package identifier and a mismatched application ID', () => {
  assert.throws(() => validateTvProject(config.replace('package="MugenTV001"', 'package="org.mugen.tv"'), html), /10 letters/);
  assert.throws(() => validateTvProject(config.replace('id="MugenTV001.MUGEN"', 'id="Wrong.MUGEN"'), html), /start with its package/);
});
test('TV packaging fails without the platform loader or remote-key privilege', () => {
  assert.throws(() => validateTvProject(config, html.replace('$WEBAPIS/webapis/webapis.js', 'missing.js')), /loader is required/);
  assert.throws(() => validateTvProject(config.replace('http://tizen.org/privilege/tv.inputdevice', 'missing'), html), /input-device privilege/);
});
