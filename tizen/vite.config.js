import { defineConfig } from "vite";
import { readFileSync } from "node:fs";
const release = JSON.parse(readFileSync(new URL("../shared/release.json", import.meta.url), "utf8"));

export default defineConfig({
  root: "tizen",
  base: "./",
  define: { __MUGEN_VERSION__: JSON.stringify(release.version) },
  build: { outDir: "build", emptyOutDir: true, target: "chrome69", sourcemap: false },
});
