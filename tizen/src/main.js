const VERSION = __MUGEN_VERSION__;
const MEDIA_EXTENSIONS = /\.(mp4|m4v|mkv|mov|webm|avi|mp3|m4a|aac|flac|wav|ogg|wma)$/i;
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
  scanBusy: false,
  scanCount: 0,
  message: "Connect a USB drive, then choose Scan USB.",
};

function loadItems() {
  try {
    const value = JSON.parse(localStorage.getItem(STORE_KEY) || "[]");
    return Array.isArray(value) ? value : [];
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

function listDirectory(directory) {
  return new Promise((resolve, reject) => {
    if (typeof directory.listFiles !== "function") {
      reject(new Error("This TV firmware does not expose the expected folder listing API."));
      return;
    }
    directory.listFiles(resolve, reject);
  });
}

async function scanDirectory(directory, storageLabel, depth, results) {
  if (depth > 12 || results.length >= 15000) return;
  const entries = await listDirectory(directory);
  for (const entry of entries) {
    if (entry.isDirectory) {
      if (!entry.name.startsWith(".")) await scanDirectory(entry, storageLabel, depth + 1, results);
    } else if (MEDIA_EXTENSIONS.test(entry.name)) {
      const path = entry.fullPath || (entry.toURI && entry.toURI()) || entry.path;
      if (path) {
        results.push({ id: storageLabel + ":" + path, path, name: entry.name, storageLabel, video: isVideo(entry.name) });
        state.scanCount++;
        if (state.scanCount % 50 === 0) {
          updateScanStatus();
          await new Promise((resolve) => window.setTimeout(resolve, 0));
        }
      }
      if (results.length >= 15000) break;
    }
  }
}

async function scanUsb() {
  if (state.scanBusy) return;
  state.scanBusy = true;
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
    for (const storage of mounted) {
      const root = await resolveStorage(storage.label);
      await scanDirectory(root, storage.label, 0, found);
    }
    const existing = new Map(state.items.map((item) => [item.id, item]));
    for (const item of found) existing.set(item.id, Object.assign({}, existing.get(item.id), item));
    state.items = Array.from(existing.values()).sort((a, b) => a.name.localeCompare(b.name));
    state.storage = mounted.map((item) => item.label).join(", ");
    const saved = saveItems();
    if (saved) state.message = "Scan complete: " + found.length + " media files found across " + mounted.length + " USB drive" + (mounted.length === 1 ? "." : "s.");
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
  return Array.prototype.slice.call(app.querySelectorAll("[data-focusable='true']"));
}

function paintFocus() {
  const nodes = focusables();
  nodes.forEach((node, index) => node.classList.toggle("focused", index === state.active));
  if (nodes[state.active] && nodes[state.active].scrollIntoView) nodes[state.active].scrollIntoView({ block: "nearest", inline: "nearest" });
}

function navigate(view) {
  state.view = view;
  state.active = 0;
  if (["library", "music", "movies"].indexOf(view) >= 0) applyFilter();
  render();
}

function play(item) {
  if (!item) return;
  state.current = item;
  state.message = "Opening " + item.name + "…";
  state.view = "player";
  state.active = 0;
  render();
  if (window.webapis && webapis.avplay) {
    try { webapis.avplay.stop(); } catch {}
    try {
      webapis.avplay.open(item.path);
      webapis.avplay.setDisplayRect(90, 70, 1080, 850);
      webapis.avplay.setDisplayMethod("PLAYER_DISPLAY_MODE_FULL_SCREEN");
      webapis.avplay.prepareAsync(() => {
        try {
          webapis.avplay.play();
          state.playing = true;
          state.message = "Playback started. Press BACK to return to your library.";
          paintPlayerStatus();
        } catch (error) {
          state.message = "Playback could not start: " + (error.message || error);
          render();
        }
      }, (error) => {
        state.message = "This TV could not open this file: " + (error.message || error);
        render();
      });
    } catch (error) {
      state.message = "This TV could not open this file: " + (error.message || error);
      render();
    }
  } else {
    state.message = "AVPlay is available on the TV. Browser preview cannot play USB paths.";
    paintPlayerStatus();
  }
}

function closePlayer() {
  if (window.webapis && webapis.avplay) {
    try { webapis.avplay.stop(); } catch {}
    try { webapis.avplay.close(); } catch {}
  }
  state.playing = false;
  state.view = "library";
  applyFilter();
  render();
}

function paintPlayerStatus() {
  const status = document.querySelector("#player-status");
  if (status) status.textContent = state.playing ? "PLAYING" : "READY";
  const message = document.querySelector(".playback-message");
  if (message) message.textContent = state.message;
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
    app.innerHTML = `<main class="player-view"><div class="player-stage"><div class="equalizer">${Array.from({ length: 24 }, (_, i) => `<i style="height:${12 + (i % 7) * 9}%"></i>`).join("")}</div><span id="player-status" class="pill">${state.playing ? "PLAYING" : "READY"}</span></div><section class="player-meta"><p class="eyebrow">NOW PLAYING</p><h1>${escapeHtml(state.current ? state.current.name : "MUGEN")}</h1><p>${escapeHtml(state.current ? state.current.storageLabel : "USB DRIVE")}</p><div class="player-actions"><button data-focusable="true" data-action="toggle">${state.playing ? "Pause" : "Resume"}</button><button data-focusable="true" data-action="back">Back to library</button></div><p class="playback-message">${escapeHtml(state.message)}</p><p class="hint">Press BACK to return to your library.</p></section></main>`;
    paintFocus();
    return;
  }

  const sections = [["library", "All media"], ["music", "Music"], ["movies", "Movies"]];
  const isList = ["library", "music", "movies"].indexOf(state.view) >= 0;
  if (isList) applyFilter();
  const pageStart = state.page * PAGE_SIZE;
  const rows = isList ? state.filtered.slice(pageStart, pageStart + PAGE_SIZE).map((item, index) => itemCard(item, pageStart + index)).join("") : "";
  const title = isList ? sections.find(([view]) => view === state.view)[1] : "Your sound. Your screen.";
  app.innerHTML = `<main class="tv-shell"><aside class="rail"><div class="brand"><span class="brand-mark">M</span><span>MUGEN<small>MEDIA CENTER</small></span></div><nav>${sections.map(([view, label]) => `<button class="nav-item ${state.view === view ? "current" : ""}" data-focusable="true" data-view="${view}"><span>${view === "library" ? "▦" : view === "music" ? "♫" : "▶"}</span>${label}</button>`).join("")}<button class="nav-item" data-focusable="true" data-action="scan"><span>＋</span>Scan USB</button></nav><div class="rail-bottom"><span class="online-dot"></span>LOCAL LIBRARY<br/><small>VERSION ${VERSION}</small></div></aside><section class="content"><header class="topline"><div><p class="eyebrow">YOUR MEDIA · ${escapeHtml(state.storage || "READY FOR USB")}</p><h1>${title}</h1></div><button class="scan-button" data-focusable="true" data-action="scan">${state.scanBusy ? "Scanning…" : "Scan USB"}</button></header>${!isList ? `<section class="hero"><div><span class="hero-kicker">MUGEN FOR SAMSUNG TV</span><h2>Your library,<br/>ready for the room.</h2><p>Connect a USB drive to browse and play your media on the big screen.</p><button class="hero-cta" data-focusable="true" data-action="scan">Scan USB drive <b>→</b></button></div><div class="disc"><div class="disc-center">M</div></div></section><section class="quick-stats"><article><span>LIBRARY</span><strong>${state.items.length}</strong><small>Saved media files</small></article><article><span>CONNECTED</span><strong>${state.storage ? state.storage.split(", ").length : "—"}</strong><small>${escapeHtml(state.storage || "USB drive")}</small></article><article><span>VERSION</span><strong>${VERSION}</strong><small>TV preview build</small></article></section>` : `<section class="library-head"><span>${state.filtered.length} ITEMS</span><span>Page ${state.page + 1} / ${Math.max(1, Math.ceil(state.filtered.length / PAGE_SIZE))} · Use arrows and Enter</span></section><div class="media-list">${rows || `<div class="empty-state"><span>♫</span><h2>No media here yet</h2><p>Connect a USB drive and scan to add supported audio or video files.</p><button data-focusable="true" data-action="scan">Scan USB</button></div>`}</div>${state.filtered.length > PAGE_SIZE ? `<div class="page-actions"><button data-focusable="true" data-action="page" data-dir="-1" ${state.page === 0 ? "disabled" : ""}>Previous page</button><button data-focusable="true" data-action="page" data-dir="1" ${state.page >= Math.ceil(state.filtered.length / PAGE_SIZE) - 1 ? "disabled" : ""}>Next page</button></div>` : ""}` }<footer class="status-line"><span class="status-dot"></span><span id="scan-status">${escapeHtml(state.message)}</span></footer></section></main>`;
  paintFocus();
}

function activate(node) {
  if (!node) return;
  if (node.dataset.view) navigate(node.dataset.view);
  else if (node.dataset.action === "scan") scanUsb();
  else if (node.dataset.action === "back") closePlayer();
  else if (node.dataset.action === "toggle") togglePlayback();
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
  if (!window.webapis || !webapis.avplay) return;
  try {
    if (state.playing) webapis.avplay.pause();
    else webapis.avplay.play();
    state.playing = !state.playing;
    render();
  } catch (error) {
    state.message = "Playback control failed: " + (error.message || error);
    render();
  }
}

function onKeyDown(event) {
  const keys = { 37: "LEFT", 38: "UP", 39: "RIGHT", 40: "DOWN", 13: "ENTER", 10009: "BACK", 8: "BACK", 415: "PLAY", 19: "PAUSE", 10252: "PLAYPAUSE" };
  const key = keys[event.keyCode] || String(event.key).toUpperCase();
  if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Enter", " ", "Backspace"].indexOf(event.key) >= 0 || keys[event.keyCode]) event.preventDefault();
  if (state.view === "player") {
    if (["BACK", "ESCAPE"].indexOf(key) >= 0) closePlayer();
    else if (["PLAY", "PAUSE", "PLAYPAUSE", "ENTER"].indexOf(key) >= 0) togglePlayback();
    return;
  }
  const nodes = focusables();
  if (!nodes.length) return;
  if (key === "LEFT" || key === "UP") state.active = (state.active + nodes.length - 1) % nodes.length;
  else if (key === "RIGHT" || key === "DOWN") state.active = (state.active + 1) % nodes.length;
  else if (key === "ENTER") activate(nodes[state.active]);
  else if (["BACK", "ESCAPE"].indexOf(key) >= 0 && state.view !== "home") navigate("home");
  paintFocus();
}

app.addEventListener("click", (event) => {
  const node = event.target.closest("[data-focusable='true']");
  if (!node) return;
  state.active = focusables().indexOf(node);
  activate(node);
});
document.addEventListener("keydown", onKeyDown);
window.addEventListener("pagehide", () => {
  if (window.webapis && webapis.avplay && state.playing) {
    try { webapis.avplay.pause(); } catch {}
  }
});

try {
  if (window.tizen && tizen.tvinputdevice) tizen.tvinputdevice.registerKey("MediaPlayPause");
} catch {}

render();
