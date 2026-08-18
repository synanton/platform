import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
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
