import { createHash } from "node:crypto";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve, relative, sep } from "node:path";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

const release = JSON.parse(readFileSync(new URL("./shared/release.json", import.meta.url), "utf8"));

function offlinePrecache(): Plugin {
  return {
    name: "mortimer-offline-precache",
    apply: "build",
    closeBundle() {
      const outDir = resolve(process.cwd(), "dist");
      const swPath = resolve(outDir, "sw.js");
      const files = (directory: string): string[] =>
        readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
          const path = resolve(directory, entry.name);
          return entry.isDirectory() ? files(path) : [path];
        });
      const assets = files(outDir)
        .map((path) => relative(outDir, path).split(sep).join("/"))
        .filter((path) => path !== "sw.js" && path !== "index.html")
        .sort();
      const source = readFileSync(swPath, "utf8");
      const marker = "const BUILD_ASSETS = [];";
      if (!source.includes(marker)) throw new Error("Service worker precache marker is missing");
      const hash = createHash("sha256").update(source);
      for (const asset of ["index.html", ...assets]) {
        hash.update(asset).update("\0").update(readFileSync(resolve(outDir, asset)));
      }
      const cacheName = `mugen-shell-${release.version}-${hash.digest("hex").slice(0, 12)}`;
      writeFileSync(swPath, source.replace(marker, `const BUILD_ASSETS = ${JSON.stringify(assets)};`)
        .replace("mugen-shell-dev", cacheName));
    },
  };
}

export default defineConfig({
  base: "/MUGEN/",
  define: { __APP_VERSION__: JSON.stringify(release.version) },
  plugins: [react(), offlinePrecache()],
  build: { manifest: true },
});
