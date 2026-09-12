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

/**
 * 需要转发给 Spring Boot 的路径（REST 接口 + 后端托管的媒体与上传）。
 *
 * 注意这里**不再包含 /static 与 /index**：老设计资源已从 frontend/static 迁到
 * web/public/ 下，由 Vite(dev) 与 Nginx(prod) 直接托管，不再经过后端。
 * （Vite 的 server.proxy 优先级高于 publicDir，若仍列出这两个前缀，
 *   请求会被错误地反代到后端而拿不到 public 下的文件。）
 */
const BACKEND_PREFIXES = ['/api', '/upload', '/showFmImg', '/showimage', '/file']

/**
 * 老设计资源前缀。仅用于 dev 中间件判断「这不是一个页面请求，不要改写成入口 HTML」，
 * 不参与反代。URL 与迁移前完全一致：
 *   /index/**            ← web/public/index/**            （前台设计资源）
 *   /static/admin/**     ← web/public/static/admin/**     （后台设计资源）
 *   /static/auth/**      ← web/public/static/auth/**      （认证页设计资源）
 */
const LEGACY_ASSET_PREFIXES = ['/index', '/static']

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
        // 页面里的 /index/… /static/… 引用由 publicDir（dev）与 Nginx（prod）托管，
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
          // 后端接口、老设计资源、以及任何带扩展名的路径都不是「页面请求」，不做入口改写
          const isAsset = /\.[a-z0-9]+$/i.test(path)
            || BACKEND_PREFIXES.some(p => path.startsWith(p))
            || LEGACY_ASSET_PREFIXES.some(p => path === p || path.startsWith(p + '/'))
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
