import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 遇你 · Vue3 前端构建配置（前后端分离）
 *
 * 采用「多入口」而非单一 SPA 入口：前台站点 / 前台认证 / 后台登录 / 后台管理
 * 各自加载原本那套 CSS 与 JS（bootstrap、jQuery 插件、orb 动画等），
 * 从根上避免样式互相污染 —— 保证「样式不变」。
 */
const BACKEND = process.env.BACKEND_ORIGIN || 'http://localhost:8081'

/** 需要转发给 Spring Boot 的路径（REST 接口 + 后端托管的静态资源与媒体） */
const BACKEND_PREFIXES = ['/api', '/static', '/index', '/upload', '/showFmImg', '/showimage', '/file']

/** dev 模式：把整洁 URL 重写到对应入口 HTML（等价于生产环境 Nginx 的 try_files） */
const ENTRIES = [
  { to: '/front.html', test: p => p === '/' || /^\/(culture|sentence|about|center)(\/|$)/.test(p) },
  { to: '/auth.html', test: p => /^\/(auth|login|signup|forgot)(\/|$)/.test(p) },
  { to: '/admin-login.html', test: p => p === '/admin/login' },
  { to: '/admin.html', test: p => p === '/admin' || p.startsWith('/admin/') }
]

const entry = name => fileURLToPath(new URL(`./${name}`, import.meta.url))

export default defineConfig({
  plugins: [
    vue({
      template: {
        // 页面里的 /index/… /static/… 图片与样式由后端（Nginx 反代）托管，
        // 不参与打包，否则 Rollup 会因找不到文件而构建失败。
        transformAssetUrls: false
      }
    }),
    {
      name: 'culture-clean-url-dev',
      configureServer(server) {
        server.middlewares.use((req, _res, next) => {
          const path = (req.url || '/').split('?')[0]
          const wantsHtml = (req.headers.accept || '').includes('text/html')
          const isAsset = /\.[a-z0-9]+$/i.test(path) || BACKEND_PREFIXES.some(p => path.startsWith(p))
          if (wantsHtml && !isAsset) {
            const hit = ENTRIES.find(e => e.test(path))
            if (hit) req.url = hit.to
          }
          next()
        })
      }
    }
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    proxy: Object.fromEntries(
      BACKEND_PREFIXES.map(p => [p, { target: BACKEND, changeOrigin: true }])
    )
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    // 兼容到 ES2018（后台仍有 jQuery 老插件）
    target: 'es2018',
    rollupOptions: {
      input: {
        front: entry('front.html'),
        auth: entry('auth.html'),
        adminLogin: entry('admin-login.html'),
        admin: entry('admin.html')
      }
    }
  }
})
