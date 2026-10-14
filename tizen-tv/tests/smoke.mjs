import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const html = read("index.html");
const css = read("styles.css");
const js = read("app.js");
const media = read("media.js");
const manifest = read("config.xml");
const readme = read("README.md");

assert.match(html, /id="home-screen"/, "home screen should exist");
assert.match(html, /id="detail-screen"/, "detail screen host should exist");
assert.match(html, /id="player-overlay"/, "TV playback overlay should exist");
assert.match(html, /id="avplay-surface"/, "Samsung AVPlay surface should exist");
assert.match(html, /styles\.css/, "stylesheet must be linked");
assert.match(html, /app\.js/, "application script must be linked");
assert.match(html, /media\.js/, "TV media adapter must be linked");
assert.match(html, /\$WEBAPIS\/webapis\/webapis\.js/, "Samsung WebAPI library must be declared");
assert.match(html, /aria-label="Main navigation"/, "navigation needs an accessible label");
assert.match(html, /role="status"/, "a status region should exist");

for (const screen of ["home", "music", "videos", "books", "comics", "photos", "now-playing", "queue", "equalizer", "sources", "settings"]) {
  const declared = js.includes(screen + ":") || js.includes("'" + screen + "'") || js.includes('"' + screen + '"');
  assert.ok(declared, `screen route missing: ${screen}`);
}
for (const action of ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Escape", "Backspace"]) {
  assert.ok(js.includes(action), `remote/keyboard key handling missing: ${action}`);
}
assert.match(js, /registerKeyBatch/, "Tizen media-key registration should be guarded");
assert.match(media, /listStorages/, "storage discovery API should be wired");
assert.match(media, /listDirectory/, "TV folder browsing should be wired");
assert.match(media, /toURI/, "local media paths must become file URIs");
assert.match(media, /prepareAsync/, "AVPlay async preparation should be used");
assert.match(media, /setDisplayRect/, "video output placement should be handled");
assert.match(media, /onScreen: function/, "media adapter should respond to screen navigation");
assert.match(css, /:focus-visible/, "focus-visible styling is required");
assert.match(css, /prefers-reduced-motion/, "reduced-motion support is required");
assert.match(css, /--accent:\s*#ff7849/i, "TV accent should match Mortimer PWA");
assert.match(manifest, /tizen:profile name="tv-samsung"/, "Samsung TV profile must be declared");
assert.match(manifest, /privilege name="http:\/\/tizen\.org\/privilege\/filesystem\.read"/, "filesystem read privilege is required");
assert.match(manifest, /privilege name="http:\/\/tizen\.org\/privilege\/externalstorage"/, "external storage access is required");
assert.match(readme, /physical TV|physical television|not tested/i, "physical device limitations must be documented");
assert.ok(existsSync(new URL("../DESIGN.md", import.meta.url)), "design contract must exist");
assert.ok(existsSync(new URL("../TV-TEST-CHECKLIST.md", import.meta.url)), "device validation checklist must exist");

console.log("Tizen TV static smoke checks passed. Hardware playback and USB enumeration still require TV/emulator validation.");
