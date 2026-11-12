import { createPlaybackController } from "./playback.js";
import { collectUsbMedia } from "./scan.js";
import { nextFocus } from "./focus.js";

const VERSION = __MUGEN_VERSION__;
const VIDEO_EXTENSIONS = /\.(mp4|m4v|mkv|mov|webm|avi)$/i;
const STORE_KEY = "mugen.tv.library.v1";
const PAGE_SIZE = 12;
const app = document.querySelector("#app");

const state = {
  active: 0,
  view: "home",
  storage: "",
  items: loadItems(),
  filtered: [],
  selected: 0,
  page: 0,
  current: null,
  playing: false,
  playerStatus: "idle",
  returnView: "library",
  returnFocus: 0,
  scanBusy: false,
  scanCancelled: false,
  scanCount: 0,
  message: "Connect a USB drive, then choose Scan USB.",
};

const playback = createPlaybackController(window.webapis && window.webapis.avplay, (update) => {
  state.playerStatus = update.status;
  state.playing = update.status === "playing";
  state.message = update.message;
  paintPlayerStatus();
});

function loadItems() {
  try {
    const value = JSON.parse(localStorage.getItem(STORE_KEY) || "[]");
    return Array.isArray(value) ? value.filter((item) => item && ["id", "path", "name", "storageLabel"].every((key) => typeof item[key] === "string")).map((item) => ({ ...item, video: isVideo(item.name) })) : [];
  } catch {
    return [];
  }
}

function saveItems() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(state.items));
    return true;
  } catch {
    state.message = "TV storage is full. MUGEN could not save this library.";
    return false;
  }
}

function escapeHtml(value = "") {
  const entities = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  return String(value).replace(/[&<>"']/g, (char) => entities[char]);
}

function isVideo(path) {
  return VIDEO_EXTENSIONS.test(path);
}

function listUsbStorages() {
  return new Promise((resolve, reject) => {
    if (!window.tizen || !tizen.filesystem || !tizen.filesystem.listStorages) {
      reject(new Error("Samsung TV storage APIs are unavailable. USB scanning runs on a Tizen TV."));
      return;
    }
    tizen.filesystem.listStorages(resolve, reject);
  });
}

function resolveStorage(label) {
  return new Promise((resolve, reject) => tizen.filesystem.resolve(label, resolve, reject, "r"));
}

async function scanUsb() {
  if (state.scanBusy) return;
  state.scanBusy = true;
  state.scanCancelled = false;
  state.scanCount = 0;
  state.message = "Looking for connected USB drives…";
  render();
  try {
    const storages = await listUsbStorages();
    const mounted = storages.filter((storage) => storage.label && storage.label.indexOf("removable_") === 0 && String(storage.state).toLowerCase() === "mounted");
    if (!mounted.length) {
      state.message = "No mounted USB drive found. Check the connection and try again.";
      return;
    }
    state.message = "Scanning " + mounted.length + " USB drive" + (mounted.length === 1 ? "…" : "s…");
    render();
    const found = [];
    let unreadable = 0;
    let limited = false;
    for (const storage of mounted) {
      if (state.scanCancelled) break;
      if (found.length >= 15000) { limited = true; break; }
      try {
        const root = await resolveStorage(storage.label);
        const result = await collectUsbMedia(root, storage.label, {
          maxFiles: 15000 - found.length,
          shouldContinue: () => !state.scanCancelled,
          onProgress: (count) => { state.scanCount = found.length + count; updateScanStatus(); },
        });
        found.push(...result.items);
        unreadable += result.unreadable;
        limited = limited || result.limited;
      } catch { unreadable++; }
    }
    const existing = new Map(state.items.map((item) => [item.id, item]));
    for (const item of found) existing.set(item.id, Object.assign({}, existing.get(item.id), item));
    state.items = Array.from(existing.values()).sort((a, b) => a.name.localeCompare(b.name));
    state.storage = mounted.map((item) => item.label).join(", ");
    const saved = saveItems();
    if (saved) {
      const outcome = state.scanCancelled ? "Scan cancelled" : unreadable || limited ? "Partial scan" : "Scan complete";
      state.message = outcome + ": " + found.length + " media files found. Previous library entries were preserved.";
      if (unreadable) state.message += " " + unreadable + " folders or entries could not be read; reconnect the USB and retry.";
      if (limited) state.message += " A scan limit was reached; scan a smaller library.";
    }
    if (["library", "music", "movies"].indexOf(state.view) >= 0) applyFilter();
  } catch (error) {
    state.message = "USB scan stopped: " + (error && error.message ? error.message : "TV storage access failed.");
  } finally {
    state.scanBusy = false;
    render();
  }
}

function applyFilter() {
  const category = state.view;
  state.filtered = state.items.filter((item) => category === "library" || (category === "music" ? !item.video : item.video));
  state.selected = Math.min(state.selected, Math.max(0, state.filtered.length - 1));
  state.page = Math.floor(state.selected / PAGE_SIZE);
}

function focusables() {
  return Array.prototype.slice.call(app.querySelectorAll("[data-focusable='true']:not(:disabled)"));
}

function paintFocus() {
  const nodes = focusables();
  state.active = Math.max(0, Math.min(state.active, nodes.length - 1));
  nodes.forEach((node, index) => node.classList.toggle("focused", index === state.active));
  if (nodes[state.active]) nodes[state.active].focus({ preventScroll: true });
  if (nodes[state.active] && nodes[state.active].scrollIntoView) nodes[state.active].scrollIntoView({ block: "nearest", inline: "nearest" });
}

function navigate(view) {
  state.view = view;
  state.active = Math.max(0, ["library", "music", "movies"].indexOf(view));
  if (["library", "music", "movies"].indexOf(view) >= 0) applyFilter();
  render();
}

function play(item) {
  if (!item) return;
  if (state.view !== "player") {
    state.returnView = state.view;
    state.returnFocus = state.active;
  }
  state.current = item;
  state.message = "Opening " + item.name + "…";
  state.playerStatus = "loading";
  state.playing = false;
  state.view = "player";
  state.active = 0;
  render();
  // AVPlay uses a fixed 1920-pixel coordinate system, regardless of CSS viewport.
  const rect = document.querySelector(".player-stage").getBoundingClientRect();
  const ratio = 1920 / document.documentElement.clientWidth;
  playback.open(item.path, { x: Math.round(rect.left * ratio), y: Math.round(rect.top * ratio),
    width: Math.round(rect.width * ratio), height: Math.round(rect.height * ratio) });
}

function closePlayer() {
  playback.close();
  state.playing = false;
  state.playerStatus = "idle";
  state.view = state.returnView;
  state.active = state.returnFocus;
  state.message = "Playback stopped. Choose a file to play.";
  applyFilter();
  render();
}

function prettyTime(milliseconds) {
  const seconds = Math.floor(Math.max(0, milliseconds) / 1000);
  return [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map((value, index) => index === 0 ? String(value) : String(value).padStart(2, "0")).join(":");
}

function paintPlayerStatus() {
  const status = document.querySelector("#player-status");
  if (status) status.textContent = state.playerStatus.toUpperCase();
  const message = document.querySelector(".playback-message");
  if (message) message.textContent = state.message;
  const toggle = app.querySelector("[data-action='toggle']");
  if (toggle) toggle.textContent = state.playing ? "Pause" : "Play";
  const timeline = app.querySelector("#playback-progress");
  if (timeline) { const position = playback.getTimeline(); timeline.textContent = prettyTime(position.position) + " / " + prettyTime(position.duration); }
}

function updateScanStatus() {
  const status = document.querySelector("#scan-status");
  if (status) status.textContent = state.scanBusy ? "Scanning USB media… " + state.scanCount + " files found" : state.message;
}

function itemCard(item, index) {
  return `<button class="media-row ${index === state.selected ? "selected" : ""}" data-focusable="true" data-item="${index}"><span class="media-icon">${item.video ? "▶" : "♫"}</span><span class="media-copy"><strong>${escapeHtml(item.name)}</strong><small>${escapeHtml(item.storageLabel)} · ${item.video ? "VIDEO" : "AUDIO"}</small></span><span class="media-open">OPEN</span></button>`;
}

function render() {
  if (state.view === "player") {
    app.innerHTML = `<main class="player-view"><div class="player-stage">${state.current && state.current.video ? `<object class="av-surface" type="application/avplayer"></object>` : ""}<div class="equalizer" ${state.current && state.current.video ? `hidden` : ""}>${Array.from({ length: 24 }, (_, i) => `<i style="height:${12 + (i % 7) * 9}%"></i>`).join("")}</div><span id="player-status" class="pill">${state.playerStatus.toUpperCase()}</span></div><section class="player-meta"><p class="eyebrow">NOW PLAYING</p><h1>${escapeHtml(state.current ? state.current.name : "MUGEN")}</h1><p>${escapeHtml(state.current ? state.current.storageLabel : "USB DRIVE")}</p><div class="player-actions"><button data-focusable="true" data-action="seek" data-ms="-10000">−10s</button><button data-focusable="true" data-action="toggle">${state.playing ? "Pause" : "Resume"}</button><button data-focusable="true" data-action="seek" data-ms="10000">+10s</button><button data-focusable="true" data-action="back">Back to library</button></div><p id="playback-progress" class="hint">0:00:00 / 0:00:00</p><p class="playback-message">${escapeHtml(state.message)}</p><p class="hint">Press BACK to return to your library.</p></section></main>`;
    paintFocus();
    return;
  }

  const sections = [["library", "All media"], ["music", "Music"], ["movies", "Movies"]];
  const isList = ["library", "music", "movies"].indexOf(state.view) >= 0;
  if (isList) applyFilter();
  const pageStart = state.page * PAGE_SIZE;
  const rows = isList ? state.filtered.slice(pageStart, pageStart + PAGE_SIZE).map((item, index) => itemCard(item, pageStart + index)).join("") : "";
  const title = isList ? sections.find(([view]) => view === state.view)[1] : "Your sound. Your screen.";
  app.innerHTML = `<main class="tv-shell"><aside class="rail"><div class="brand"><span class="brand-mark">M</span><span>MUGEN<small>MEDIA CENTER</small></span></div><nav>${sections.map(([view, label]) => `<button class="nav-item ${state.view === view ? "current" : ""}" data-focusable="true" data-view="${view}"><span>${view === "library" ? "▦" : view === "music" ? "♫" : "▶"}</span>${label}</button>`).join("")}<button class="nav-item" data-focusable="true" data-action="scan"><span>＋</span>Scan USB</button></nav><div class="rail-bottom"><span class="online-dot"></span>LOCAL LIBRARY<br/><small>VERSION ${VERSION}</small></div></aside><section class="content"><header class="topline"><div><p class="eyebrow">YOUR MEDIA · ${escapeHtml(state.storage || "READY FOR USB")}</p><h1>${title}</h1></div><button class="scan-button" data-focusable="true" data-action="scan">${state.scanBusy ? "Cancel scan" : "Scan USB"}</button></header>${!isList ? `<section class="hero"><div><span class="hero-kicker">MUGEN FOR SAMSUNG TV</span><h2>Your library,<br/>ready for the room.</h2><p>Connect a USB drive to browse and play your media on the big screen.</p><button class="hero-cta" data-focusable="true" data-action="scan">Scan USB drive <b>→</b></button></div><div class="disc"><div class="disc-center">M</div></div></section><section class="quick-stats"><article><span>LIBRARY</span><strong>${state.items.length}</strong><small>Saved media files</small></article><article><span>CONNECTED</span><strong>${state.storage ? state.storage.split(", ").length : "—"}</strong><small>${escapeHtml(state.storage || "USB drive")}</small></article><article><span>VERSION</span><strong>${VERSION}</strong><small>TV preview build</small></article></section>` : `<section class="library-head"><span>${state.filtered.length} ITEMS</span><span>Page ${state.page + 1} / ${Math.max(1, Math.ceil(state.filtered.length / PAGE_SIZE))} · Use arrows and Enter</span></section><div class="media-list">${rows || `<div class="empty-state"><span>♫</span><h2>No media here yet</h2><p>Connect a USB drive and scan to add supported audio or video files.</p><button data-focusable="true" data-action="scan">Scan USB</button></div>`}</div>${state.filtered.length > PAGE_SIZE ? `<div class="page-actions"><button data-focusable="true" data-action="page" data-dir="-1" ${state.page === 0 ? "disabled" : ""}>Previous page</button><button data-focusable="true" data-action="page" data-dir="1" ${state.page >= Math.ceil(state.filtered.length / PAGE_SIZE) - 1 ? "disabled" : ""}>Next page</button></div>` : ""}` }<footer class="status-line"><span class="status-dot"></span><span id="scan-status">${escapeHtml(state.message)}</span></footer></section></main>`;
  paintFocus();
}

function activate(node) {
  if (!node) return;
  if (node.dataset.view) navigate(node.dataset.view);
  else if (node.dataset.action === "scan") {
    if (state.scanBusy) { state.scanCancelled = true; state.message = "Cancelling USB scan…"; updateScanStatus(); }
    else scanUsb();
  }
  else if (node.dataset.action === "back") closePlayer();
  else if (node.dataset.action === "toggle") togglePlayback();
  else if (node.dataset.action === "seek") playback.seekBy(Number(node.dataset.ms));
  else if (node.dataset.action === "page") {
    const lastPage = Math.max(0, Math.ceil(state.filtered.length / PAGE_SIZE) - 1);
    state.page = Math.max(0, Math.min(lastPage, state.page + Number(node.dataset.dir)));
    state.selected = state.page * PAGE_SIZE;
    state.active = 0;
    render();
  }
  else if (node.dataset.item !== undefined) {
    state.selected = Number(node.dataset.item);
    play(state.filtered[state.selected]);
  }
}

function togglePlayback() {
  if (["ended", "error", "idle"].indexOf(playback.getStatus()) >= 0) play(state.current);
  else playback.toggle();
}

function onKeyDown(event) {
  const keys = { 37: "LEFT", 38: "UP", 39: "RIGHT", 40: "DOWN", 13: "ENTER", 10009: "BACK", 8: "BACK", 415: "PLAY", 19: "PAUSE", 413: "STOP", 412: "REWIND", 417: "FAST_FORWARD", 10252: "PLAYPAUSE" };
  const key = keys[event.keyCode] || ({ ArrowLeft: "LEFT", ArrowRight: "RIGHT", ArrowUp: "UP", ArrowDown: "DOWN" })[event.key] || String(event.key).toUpperCase();
  if (["LEFT", "RIGHT", "UP", "DOWN", "ENTER", "BACK", "ESCAPE", "PLAY", "PAUSE", "STOP", "PLAYPAUSE", "REWIND", "FAST_FORWARD"].indexOf(key) >= 0) event.preventDefault();
  if (state.view === "player") {
    if (["BACK", "ESCAPE", "STOP"].indexOf(key) >= 0) { closePlayer(); return; }
    if (key === "PLAY") {
      if (["ended", "error", "idle"].indexOf(playback.getStatus()) >= 0) play(state.current);
      else playback.setPlaying(true);
      return;
    }
    if (key === "PAUSE") { playback.setPlaying(false); return; }
    if (key === "PLAYPAUSE") { togglePlayback(); return; }
    if (key === "REWIND") { playback.seekBy(-10000); return; }
    if (key === "FAST_FORWARD") { playback.seekBy(10000); return; }
  }
  const nodes = focusables();
  if (!nodes.length) return;
  if (["LEFT", "RIGHT", "UP", "DOWN"].indexOf(key) >= 0) state.active = nextFocus(nodes.map(node => node.getBoundingClientRect()), state.active, key);
  else if (key === "ENTER") activate(nodes[state.active]);
  else if (["BACK", "ESCAPE"].indexOf(key) >= 0 && state.view !== "home") navigate("home");
  paintFocus();
}

app.addEventListener("click", (event) => {
  const node = event.target.closest("[data-focusable='true']:not(:disabled)");
  if (!node) return;
  state.active = focusables().indexOf(node);
  activate(node);
});
app.addEventListener("focusin", (event) => {
  const index = focusables().indexOf(event.target);
  if (index >= 0) {
    state.active = index;
    focusables().forEach((node, position) => node.classList.toggle("focused", position === index));
  }
});
document.addEventListener("keydown", onKeyDown);
// Update the timeline without rebuilding the remote-focus DOM on every tick.
setInterval(() => { if (state.view === "player" && !document.hidden) paintPlayerStatus(); }, 500);

function stopHiddenPlayback() {
  state.scanCancelled = true;
  playback.close();
  if (state.view === "player") {
    state.playing = false;
    state.playerStatus = "idle";
    state.message = "Playback stopped while MUGEN was hidden. Choose Play to reopen the file.";
    paintPlayerStatus();
  }
}
window.addEventListener("pagehide", stopHiddenPlayback);
document.addEventListener("visibilitychange", () => { if (document.hidden) stopHiddenPlayback(); });

if (window.tizen && tizen.tvinputdevice) {
  ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaRewind", "MediaFastForward"].forEach((key) => {
    try { tizen.tvinputdevice.registerKey(key); } catch {}
  });
}

render();
