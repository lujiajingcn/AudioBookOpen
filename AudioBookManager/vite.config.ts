import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // @audiobook/shared 通过 npm 的 file: 依赖（file:../AudioBookShared）以符号链接形式
    // 装进 node_modules/@audiobook/shared，位置在项目树之外。Vite 默认会把该符号链接解析到
    // 真实路径 AudioBookShared/，再从该目录向上查找 node_modules，从而找不到 axios/pinia/vue/vue-router。
    // preserveSymlinks 保留符号链接路径，让 Rollup 从 node_modules/@audiobook/shared 向上解析 peer 依赖。
    preserveSymlinks: true,
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 3001,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
