import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextFocus } from '../tizen/src/focus.js';
const rect = (left, top, width = 100, height = 50) => ({ left, top, right:left+width, bottom:top+height });

test('TV arrows follow the screen grid even when DOM order differs', () => {
  const controls = [rect(0,0),rect(200,100),rect(200,0),rect(0,100)];
  assert.equal(nextFocus(controls,0,'RIGHT'),2);
  assert.equal(nextFocus(controls,0,'DOWN'),3);
  assert.equal(nextFocus(controls,1,'LEFT'),3);
  assert.equal(nextFocus(controls,1,'UP'),2);
});
test('TV navigation stays at edges instead of wrapping to a distant control', () => {
  const controls = [rect(0,0),rect(0,100),rect(200,100)];
  assert.equal(nextFocus(controls,0,'UP'),0);
  assert.equal(nextFocus(controls,0,'LEFT'),0);
  assert.equal(nextFocus(controls,2,'RIGHT'),2);
  assert.equal(nextFocus(controls,2,'DOWN'),2);
});
test('TV focus prefers the same row/column and ignores hidden zero-size controls', () => {
  const controls = [rect(0,0),rect(110,55),rect(300,0),rect(101,0,0,0)];
  assert.equal(nextFocus(controls,0,'RIGHT'),2);
  assert.equal(nextFocus([rect(0,0),rect(100,100)],0,'DOWN'),1,'diagonal fallback crosses regions when no column exists');
  assert.equal(nextFocus(controls,0,'ENTER'),0);
  assert.equal(nextFocus([],0,'RIGHT'),0);
});
