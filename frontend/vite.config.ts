import {defineConfig, loadEnv} from 'vite'
import react from '@vitejs/plugin-react'
import wasm from 'vite-plugin-wasm'
import topLevelAwait from 'vite-plugin-top-level-await'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'
  // Python Agent Service（SSE 流式聊天），可在 .env 中覆盖
  const agentProxyTarget = env.VITE_AGENT_PROXY_TARGET || 'http://localhost:8000'

  return {
    plugins: [
      wasm(),
      topLevelAwait(),
      react(),
    ],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            'react-vendor': ['react', 'react-dom', 'react-router-dom'],
            'ui-vendor': ['framer-motion', 'lucide-react'],
            'syntax-highlighter': ['react-syntax-highlighter'],
          },
        },
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        // Agent 流式聊天转发到 Python Agent Service，须在 /api 之前匹配
        '/api/chat': {
          target: agentProxyTarget,
          changeOrigin: true,
        },
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
      },
      // 忽略 @ricky0123/vad-web 的 sourcemap 警告
      sourcemapIgnoreList: (relativeSourcePath) => {
        return relativeSourcePath.includes('node_modules/.pnpm/@ricky0123+vad-web');
      },
    },
    optimizeDeps: {
      // No need to optimize vad-web since we load it via script tag
    },
  }
});
