import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig({
  base: "/modern/",
  plugins: [react()],
  server: {
    fs: {
      // Vite checks both the path and ?raw ID; allow only public browser scripts.
      allow: [fileURLToPath(new URL(".", import.meta.url)), fileURLToPath(new URL("../resources/public/js", import.meta.url))],
    },
  },
  build: {
    outDir: "../resources/public/modern",
    emptyOutDir: true,
  },
  test: {
    exclude: [...configDefaults.exclude, "e2e/**"],
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
  },
});
