import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  base: "/",
  server: {
    proxy: {
      "/api": {
        target: process.env.PACKMATE_API_ORIGIN ?? "http://127.0.0.1:65000",
        changeOrigin: true,
        ws: true,
      },
    },
  },
  test: {
    environment: "happy-dom",
    setupFiles: ["./src/test/setup.ts"],
    restoreMocks: true,
  },
});
