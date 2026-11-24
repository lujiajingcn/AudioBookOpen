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
    port: 3000,
    proxy: {
      '/api': {
        // 默认仍指向本机 8080（本地跑后端时的常规姿势）。
        // 需要联调**远端**后端时用环境变量覆盖即可，不必改文件：
        //   VITE_API_TARGET=http://api.example.com npm run dev
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        // 去掉 Origin 头：dev 代理会把浏览器的 Origin 原样转给后端，而后端的
        // CORS 白名单在生产是空的（application-prod.yml: CORS_ALLOWED_ORIGINS 未配），
        // 于是 Spring 判为跨域并回 403 "Invalid CORS request"，登录直接失败。
        // 请求不带 Origin ⇒ Spring 不判跨域 ⇒ 放行；对同源 dev 场景无副作用。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        },
      },
    },
  },
})
