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
          // 用函数形式按「包路径」分组，而不是对象形式列出包名：
          // 对象形式只能捕获包的入口与其静态依赖，而代码高亮的语言语法是
          // 按需 import() 的独立模块，会各自切出一个 0.1KB 的碎片 chunk（实测 25 个）。
          manualChunks(id) {
            if (!id.includes('node_modules')) {
              return undefined;
            }
            // 语法高亮全家桶：prism-light + 各语言语法 + 底层 refractor/prismjs
            if (
              /node_modules[\\/](?:react-syntax-highlighter|refractor|prismjs|highlight\.js|lowlight|highlightjs-vue)[\\/]/.test(
                id
              )
            ) {
              return 'syntax-highlighter';
            }
            if (
              /node_modules[\\/](?:react|react-dom|react-router|react-router-dom|scheduler)[\\/]/.test(
                id
              )
            ) {
              return 'react-vendor';
            }
            if (/node_modules[\\/](?:framer-motion|lucide-react)[\\/]/.test(id)) {
              return 'ui-vendor';
            }
            return undefined;
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
        // Java 内部接口（如简历 Preview PDF 渲染），非 /api 前缀，同样转发到业务后端
        '/internal': {
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
