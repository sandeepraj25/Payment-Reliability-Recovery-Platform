import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev and preview proxy API calls to the Spring Boot backend, so the browser sees ONE origin
// (no CORS needed) - the same arrangement nginx provides in docker-compose.
const backend = process.env.PAYGUARD_BACKEND_URL ?? 'http://localhost:8080';
const proxy = {
  '/api': { target: backend, changeOrigin: true },
  '/actuator': { target: backend, changeOrigin: true },
};

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy },
  preview: { port: 5173, proxy },
  build: {
    rollupOptions: {
      output: { manualChunks: { charts: ['recharts'], react: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query'] } },
    },
  },
});
