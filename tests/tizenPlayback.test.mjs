import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPlaybackController } from '../tizen/src/playback.js';

function harness() {
  const calls = [], preparations = [], listeners = [], changes = [];
  const av = Object.fromEntries(['stop', 'close', 'open', 'setDisplayRect', 'setDisplayMethod', 'play', 'pause'].map(name => [name, (...args) => calls.push([name, ...args])]));
  av.setListener = listener => listeners.push(listener);
  av.prepareAsync = (ready, failed) => preparations.push({ ready, failed });
  const controller = createPlaybackController(av, value => changes.push(value));
  const open = path => controller.open(path, { x: 100, y: 100, width: 1000, height: 700 });
  return { calls, preparations, listeners, changes, controller, open };
}

test('leaving during AVPlay preparation prevents late playback and error changes', () => {
  const h = harness(); h.open('/usb/movie.mp4'); h.controller.close();
  const count = h.changes.length;
  h.preparations[0].ready(); h.preparations[0].failed(Error('late'));
  assert.equal(h.calls.filter(([name]) => name === 'play').length, 0);
  assert.equal(h.changes.length, count);
  assert.equal(h.controller.getStatus(), 'idle');
});
test('a previous opening cannot start or release the newly opened file', () => {
  const h = harness(); h.open('/usb/one.mp4'); h.open('/usb/two.mp4');
  const count = h.calls.length;
  h.preparations[0].ready(); h.listeners[0].onerror(Error('removed'));
  assert.equal(h.calls.length, count);
  h.preparations[1].ready(); assert.equal(h.controller.getStatus(), 'playing');
});
test('Play and Pause are idempotent and pause during preparation defers autoplay', () => {
  const h = harness(); h.open('/usb/song.mp3'); h.controller.setPlaying(false);
  h.preparations[0].ready(); assert.equal(h.controller.getStatus(), 'ready');
  h.controller.setPlaying(true); h.controller.setPlaying(true);
  h.controller.setPlaying(false); h.controller.setPlaying(false);
  assert.equal(h.calls.filter(([name]) => name === 'play').length, 1);
  assert.equal(h.calls.filter(([name]) => name === 'pause').length, 1);
});
test('stream end and decoder errors release resources and invalidate callbacks', () => {
  for (const event of ['onstreamcompleted', 'onerror']) {
    const h = harness(); h.open('/usb/movie.mp4'); h.preparations[0].ready();
    h.listeners[0][event](Error('unsupported'));
    const count = h.calls.length; h.preparations[0].ready();
    assert.equal(h.calls.length, count);
    assert.equal(h.calls.at(-1)[0], 'close');
    assert.equal(h.controller.getStatus(), event === 'onerror' ? 'error' : 'ended');
  }
});
