import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const runtimeEnv = (globalThis as { process?: { env?: Record<string, string> } }).process?.env ?? {};
const relixTarget = runtimeEnv.VITE_RELIX_URL ?? "http://localhost:8084";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api/v1/entities": {
        target: relixTarget,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, "").split("?")[0],
      },
      "/api/v1/documents": {
        target: relixTarget,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, "").split("?")[0],
      },
      "/synquest": {
        target: runtimeEnv.VITE_SYNQUEST_URL ?? "http://localhost:8083",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/synquest/, ""),
      },
      "/synflux": {
        target: runtimeEnv.VITE_SYNFLUX_URL ?? "http://localhost:8090",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/synflux/, ""),
      },
      "/api": "http://localhost:8080",
      "/mcp": "http://localhost:8091",
      "/auth": "http://localhost:8081",
      "/topology": "http://localhost:8082",
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
