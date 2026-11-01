import { defineConfig } from "vite";

export default defineConfig({
  root: "tizen",
  base: "./",
  define: { __MUGEN_VERSION__: JSON.stringify(process.env.npm_package_version || "0.16.0") },
  build: { outDir: "build", emptyOutDir: true, target: "chrome69", sourcemap: false },
});
