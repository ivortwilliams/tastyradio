import { defineConfig } from 'vite';

export default defineConfig({
  build: {
    // Straight into the server package, which serves it as static files. One container, one process.
    outDir: '../server/public',
    emptyOutDir: true,
    target: 'es2020',
  },
  server: {
    port: 5173,
    // In development the client runs on Vite and the API on the server process next door.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
      '/healthz': 'http://localhost:8080',
    },
  },
});
