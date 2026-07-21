import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { createHmac } from 'node:crypto'
import type { ProxyOptions } from 'vite'

const identitySigningSecret = process.env.RAG_IDENTITY_SIGNING_SECRET
const developmentUserId = process.env.RAG_DEV_USER_ID

function signedProxy(target: string): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    configure(proxy) {
      proxy.on('proxyReq', (request) => {
        if (!identitySigningSecret || !developmentUserId) return
        const signature = createHmac('sha256', identitySigningSecret).update(developmentUserId).digest('base64url')
        request.setHeader('X-Rag-User-Id', developmentUserId)
        request.setHeader('X-Rag-Identity-Signature', signature)
      })
    },
  }
}

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
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api/mcp': {
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api/conversations': {
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api/approvals': {
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api/memories': {
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api/feedback': {
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api/traces': {
        ...signedProxy('http://127.0.0.1:28083'),
      },
      '/api': {
        ...signedProxy('http://127.0.0.1:28081'),
      },
    },
  },
})
