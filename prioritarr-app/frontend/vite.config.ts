import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8001', changeOrigin: true },
      '/health': { target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8001', changeOrigin: true },
      '/ready': { target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8001', changeOrigin: true },
      '/openapi.json': { target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8001', changeOrigin: true },
    },
  },
})
