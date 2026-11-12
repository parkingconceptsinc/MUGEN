import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const playwrightPath = process.env.PLAYWRIGHT_PACKAGE || "/tmp/mugen-virtual-tv/node_modules/playwright";
const { chromium } = require(playwrightPath);
const browser = await chromium.launch({ headless: true, args: ["--no-sandbox"] });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(process.env.VIRTUAL_TV_URL || "http://127.0.0.1:4173/virtual-tv.html", { waitUntil: "load" });
  await page.waitForFunction(() => {
    const results = document.querySelectorAll("#results .result.pass, #results .result.fail");
    return results.length >= 6;
  }, { timeout: 45000 });
  const results = await page.locator("#results .result").allTextContents();
  const failures = results.filter(text => text.startsWith("FAIL"));
  console.log("Virtual TV functional results:");
  results.forEach(text => console.log(" - " + text));
  assert.equal(failures.length, 0, failures.join("\n"));
  assert.deepEqual(errors, [], "Virtual TV emitted browser runtime errors: " + errors.join("; "));
  for (const size of ["1920x1080", "1280x720", "960x540"]) {
    await page.locator('[data-size="' + size + '"]').click();
    const frame = await page.locator("#tv").boundingBox();
    assert.ok(frame && frame.width > 0 && frame.height > 0, "TV viewport failed for " + size);
  }
  console.log("PASS · all Virtual TV functional tests and responsive viewport controls");
} finally {
  await browser.close();
}
