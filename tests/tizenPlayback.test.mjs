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

test('AVPlay seek is bounded, rejects non-ready states, and ignores stale callbacks', () => {
  const h = harness();
  let time = 27000, done;
  h.controller.close();
  assert.equal(h.controller.seekBy(10000), false);
  const player = h.controller;
  // A standalone controller with the additional AVPlay timeline methods.
  const moves = [], changes = [];
  const av = { stop() {}, close() {}, open() {}, setListener() {},
    setDisplayRect() {}, setDisplayMethod() {}, prepareAsync(callback) { this.ready = callback; },
    play() {}, pause() {}, getDuration() { return 60000; }, getCurrentTime() { return time; },
    seekTo(target, ok) { moves.push(target); done = ok; } };
  const seek = createPlaybackController(av, value => changes.push(value));
  seek.open('/usb/movie.mkv', { x: 0, y: 0, width: 800, height: 600 });
  assert.equal(seek.seekBy(10000), false);
  av.ready();
  assert.deepEqual(seek.getTimeline(), { position: 27000, duration: 60000 });
  assert.equal(seek.seekBy(90000), true);
  assert.equal(seek.seekBy(-40000), true);
  assert.deepEqual(moves, [60000, 0]);
  seek.close();
  const count = changes.length;
  done();
  assert.equal(changes.length, count);
});

test('resume attempts to seek after playback preparation and ignores late callbacks', () => {
  const callbacks = [];
  const seekCalls = [];
  const events = [];
  const av = {
    stop() {}, close() {}, open() {}, setListener() {}, setDisplayRect() {},
    setDisplayMethod() {}, play() {}, pause() {},
    prepareAsync(ready) { this.ready = ready; }, getDuration() { return 80000; },
    seekTo(ms, onSuccess, onFailure) { seekCalls.push(ms); callbacks.push({ onSuccess, onFailure }); },
  };
  const playback = createPlaybackController(av, value => events.push(value));
  playback.open('/usb/movie.mp4', { x: 0, y: 0, width: 800, height: 500 }, 40000);
  assert.equal(seekCalls.length, 0);
  av.ready();
  assert.deepEqual(seekCalls, [40000]);
  assert.equal(playback.getStatus(), 'playing');
  playback.close();
  const before = events.length;
  callbacks[0].onSuccess();
  callbacks[0].onFailure(Error('late'));
  assert.equal(events.length, before);
  playback.open('/usb/new.mp4', { x: 0, y: 0, width: 800, height: 500 }, 79900);
  av.ready();
  assert.deepEqual(seekCalls, [40000], 'near-end resume must restart instead of seeking');
});
