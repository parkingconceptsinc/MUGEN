import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";

const root = new URL("../", import.meta.url);
const read = (path) => readFileSync(new URL(path, root), "utf8");
const html = read("index.html");
const css = read("styles.css");
const js = read("app.js");
const manifest = read("config.xml");
const readme = read("README.md");

assert.match(html, /id="home-screen"/, "home screen should exist");
assert.match(html, /id="detail-screen"/, "detail screen host should exist");
assert.match(html, /styles\.css/, "stylesheet must be linked");
assert.match(html, /app\.js/, "application script must be linked");
assert.match(html, /aria-label="Main navigation"/, "navigation needs an accessible label");
assert.match(html, /role="status"/, "a status/toast region should exist");

for (const screen of ["home", "music", "videos", "books", "comics", "photos", "now-playing", "queue", "equalizer", "sources", "settings"]) {
  const declared = js.includes(screen + ":") || js.includes("'" + screen + "'") || js.includes('"' + screen + '"');
  assert.ok(declared, `screen route missing: ${screen}`);
}
for (const action of ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Escape", "Backspace"]) {
  assert.ok(js.includes(action), `remote/keyboard key handling missing: ${action}`);
}
assert.match(js, /registerKeyBatch/, "Tizen media-key registration should be guarded");
assert.match(css, /:focus-visible/, "focus-visible styling is required");
assert.match(css, /prefers-reduced-motion/, "reduced-motion support is required");
assert.match(css, /--accent:\s*#ff7849/i, "TV accent should match Mortimer PWA");
assert.match(manifest, /tizen:profile name="tv"/, "Tizen TV profile must be declared");
assert.match(readme, /not yet|not connected|pending/i, "known integration gaps must be documented");
assert.ok(existsSync(new URL("../DESIGN.md", import.meta.url)), "design contract must exist");

console.log("Tizen TV static smoke checks passed. Physical TV compatibility is not covered by this test.");
