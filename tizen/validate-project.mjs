import assert from 'node:assert/strict';

export function validateTvProject(config, html) {
  const application = /<tizen:application\b([^>]+)\/>/.exec(config)?.[1] || '';
  const packageId = /\bpackage="([^"]+)"/.exec(application)?.[1] || '';
  const applicationId = /\bid="([^"]+)"/.exec(application)?.[1] || '';
  assert.match(packageId, /^[A-Za-z0-9]{10}$/, 'Tizen web package ID must contain exactly 10 letters/digits');
  assert.ok(applicationId.startsWith(packageId + '.') && applicationId.length > packageId.length + 1, 'Tizen application ID must start with its package ID');
  assert.match(html, /<script\b[^>]*\bsrc=["']\$WEBAPIS\/webapis\/webapis\.js["'][^>]*>/, 'Samsung Product API loader is required for AVPlay');
  assert.match(config, /name="http:\/\/tizen.org\/privilege\/tv.inputdevice"/, 'Remote media keys need the TV input-device privilege');
}
