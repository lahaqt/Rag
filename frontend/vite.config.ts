import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api/chat': {
        target: 'http://127.0.0.1:28083',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://127.0.0.1:28081',
        changeOrigin: true,
      },
    },
  },
})
