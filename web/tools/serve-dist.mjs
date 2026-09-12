/**
 * 本机「等价 Nginx」预览服务（纯 Node 内置模块，无第三方依赖）
 *
 * 用途：本机没有 Nginx 时，用它验证 web/dist 的生产形态：
 *   1) 静态托管 Vue 构建产物（history 路由 try_files 回退到对应入口 HTML）
 *   2) 旧 .html / 旧路径 301 到干净 URL
 *   3) 把 /api、/static、/index、/upload、/showFmImg、/showimage、/file 反向代理到 Spring Boot(8081)
 *
 * 用法：
 *   npm --prefix web run build
 *   node web/tools/serve-dist.mjs            # http://localhost:8080
 *   PORT=9000 node web/tools/serve-dist.mjs
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(fileURLToPath(new URL('../dist', import.meta.url)))
const BACKEND = process.env.BACKEND_ORIGIN || 'http://127.0.0.1:8081'
const PORT = Number(process.env.PORT || 8080)

/** 需要反代给后端的路径前缀 */
const BACKEND_PREFIXES = ['/api', '/static', '/index', '/upload', '/showFmImg', '/showimage', '/file', '/sitemap.xml', '/robots.txt', '/rss.xml', '/sitemap-static.xml', '/sitemap-culture-']

/** 旧地址 -> 新干净地址（301） */
const LEGACY_REDIRECTS = {
  '/static/auth/login.html': '/auth/login',
  '/static/auth/login': '/auth/login',
  '/static/auth/register.html': '/auth/register',
  '/static/auth/register': '/auth/register',
  '/static/auth/forgot.html': '/auth/forgot',
  '/static/auth/forgot': '/auth/forgot',
  '/static/auth/admin-login.html': '/admin/login',
  '/static/auth/admin-login': '/admin/login',
  '/static/auth/blank.html': '/auth/login',
  '/index.html': '/',
  '/login': '/auth/login',
  '/signup': '/auth/register',
  '/toLogin': '/admin/login',
  '/index': '/'
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.eot': 'application/vnd.ms-fontobject',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8'
}

/** 可压缩的响应类型（等价 Nginx 的 gzip_types） */
const COMPRESSIBLE = /^(text\/|application\/(javascript|json|xml|xhtml\+xml|rss\+xml)|image\/svg\+xml)/

/** 基础安全响应头（等价 Nginx 的 add_header，HSTS 需 HTTPS，交给生产 Nginx） */
const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'X-Frame-Options': 'SAMEORIGIN'
}

const gzipCache = new Map()
/**
 * 带缓存的 gzip（避免每次请求都重新压缩）。
 * ⚠️ key 必须带 mtime：只按「路径+长度」缓存会踩坑 —— 重新构建后 HTML 只换了资源 hash，
 *    文件长度完全一样，旧 key 命中就会返回**过期 HTML**，浏览器进而请求已被删除的 chunk
 *    （表现为页面静默挂不起来、预渲染全部失败，非常难排查）。
 */
function gzipBuffer(filePath, buf, mtimeMs) {
  const key = filePath + ':' + mtimeMs + ':' + buf.length
  let gz = gzipCache.get(key)
  if (!gz) {
    gz = zlib.gzipSync(buf, { level: 6 })
    if (gzipCache.size > 200) gzipCache.clear()
    gzipCache.set(key, gz)
  }
  return gz
}

function acceptsGzip(req) {
  // 用 includes 而不是正则，避免转义陷阱
  return String(req.headers['accept-encoding'] || '').indexOf('gzip') > -1
}

/** 按路径选择 SPA 入口（等价 Nginx 的多 location try_files） */
function entryFor(pathname) {
  if (pathname === '/admin/login') return '/admin-login.html'
  if (pathname === '/admin' || pathname.startsWith('/admin/')) return '/admin.html'
  if (/^\/(auth|login|signup|forgot)(\/|$)/.test(pathname)) return '/auth.html'
  return '/front.html'
}

function sendFile(req, res, filePath) {
  fs.readFile(filePath, (err, buf) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      res.end('404 Not Found: ' + path.basename(filePath))
      return
    }
    const ext = path.extname(filePath).toLowerCase()
    const type = MIME[ext] || 'application/octet-stream'
    const headers = { 'Content-Type': type, ...SECURITY_HEADERS }
    // HTML 一律不缓存：否则浏览器会拿旧 HTML 继续引用旧的 hash 资源，
    // 表现为「页面看着是新的、功能却是旧的」（例如后台菜单点了没反应）。
    if (ext === '.html') headers['Cache-Control'] = 'no-cache, must-revalidate'
    // 带 hash 的构建产物可长缓存（内容变了文件名必变）
    else if (filePath.includes('assets')) headers['Cache-Control'] = 'public, max-age=31536000, immutable'
    // 其余静态资源（图片/字体等）缓存 1 天并允许协商缓存
    else headers['Cache-Control'] = 'public, max-age=86400'

    // ETag / 304：避免重复传输未变化的资源
    let mtime = 0
    try { mtime = fs.statSync(filePath).mtimeMs } catch (e) { /* ignore */ }
    const etag = '"' + buf.length.toString(16) + '-' + Math.round(mtime).toString(16) + '"'
    headers['ETag'] = etag
    if (req.headers['if-none-match'] === etag) {
      res.writeHead(304, headers)
      res.end()
      return
    }

    // gzip 压缩（等价 Nginx gzip on + gzip_types）
    if (buf.length > 1024 && COMPRESSIBLE.test(type) && acceptsGzip(req)) {
      const gz = gzipBuffer(filePath, buf, mtime)
      headers['Content-Encoding'] = 'gzip'
      headers['Vary'] = 'Accept-Encoding'
      headers['Content-Length'] = gz.length
      res.writeHead(200, headers)
      res.end(gz)
      return
    }
    headers['Vary'] = 'Accept-Encoding'
    headers['Content-Length'] = buf.length
    res.writeHead(200, headers)
    res.end(buf)
  })
}

function proxy(req, res) {
  const target = new URL(req.url, BACKEND)
  const proxyReq = http.request(
    {
      hostname: target.hostname,
      port: target.port || 80,
      path: target.pathname + target.search,
      method: req.method,
      headers: { ...req.headers, host: target.host }
    },
    proxyRes => {
      const headers = { ...proxyRes.headers }
      const pathname = target.pathname
      // 老静态资源（无 hash）：后端目前返回 no-store，这里统一改成 1 天缓存 + ETag 协商。
      // 只覆盖静态前缀，/api/** 的动态响应不动。
      const STATIC_PREFIXES = ['/static/', '/index/', '/upload/', '/showFmImg/', '/showimage/', '/file/']
      const isLegacyStatic = STATIC_PREFIXES.some(pre => pathname.indexOf(pre) === 0)
      const cc = String(headers['cache-control'] || '')
      if (isLegacyStatic && (!cc || cc.indexOf('no-store') > -1 || cc.indexOf('no-cache') > -1)) {
        headers['cache-control'] = 'public, max-age=86400'
      }
      const type = String(headers['content-type'] || '')
      if (!headers['content-encoding'] && COMPRESSIBLE.test(type) && acceptsGzip(req)) {
        headers['content-encoding'] = 'gzip'
        headers['vary'] = headers['vary'] ? headers['vary'] + ', Accept-Encoding' : 'Accept-Encoding'
        delete headers['content-length']
        res.writeHead(proxyRes.statusCode || 502, headers)
        proxyRes.pipe(zlib.createGzip({ level: 5 })).pipe(res)
        return
      }
      if (!headers['vary']) headers['vary'] = 'Accept-Encoding'
      res.writeHead(proxyRes.statusCode || 502, headers)
      proxyRes.pipe(res)
    }
  )
  proxyReq.on('error', err => {
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify({ code: 502, message: '后端未启动或不可达：' + err.message }))
  })
  req.pipe(proxyReq)
}

const server = http.createServer((req, res) => {
  const pathname = decodeURIComponent((req.url || '/').split('?')[0])

  // 1) 旧地址 301
  if (LEGACY_REDIRECTS[pathname]) {
    res.writeHead(301, { Location: LEGACY_REDIRECTS[pathname] })
    res.end()
    return
  }

  // 2) 后端接口 / 静态资源 / 媒体
  if (BACKEND_PREFIXES.some(p => pathname === p || pathname.startsWith(p + '/'))) {
    proxy(req, res)
    return
  }

  // 3) 构建产物静态文件；若是目录则优先返回其中的 index.html（预渲染产物）
  const filePath = path.join(ROOT, path.normalize(pathname))
  if (filePath.startsWith(ROOT) && fs.existsSync(filePath)) {
    const st = fs.statSync(filePath)
    if (st.isFile()) { sendFile(req, res, filePath); return }
    if (st.isDirectory()) {
      const indexFile = path.join(filePath, 'index.html')
      if (fs.existsSync(indexFile)) { sendFile(req, res, indexFile); return }
    }
  }

  // 4) 构建产物缺失时不要回退成 HTML：否则浏览器会把 HTML 当模块脚本解析，
  //    报「Expected a JavaScript-or-Wasm module script but MIME type is text/html」，
  //    真因只是「构建产物与预渲染快照 hash 不一致」。直接 404 更容易定位。
  if (pathname.indexOf('/assets/') === 0) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', ...SECURITY_HEADERS })
    res.end('404 构建产物不存在（请重新执行 npm run build:all）：' + pathname)
    return
  }

  // 5) history 路由回退到对应入口 HTML（未预渲染的路由走 SPA）
  sendFile(req, res, path.join(ROOT, entryFor(pathname)))
})

server.listen(PORT, () => {
  console.log(`[serve-dist] 前端预览  http://localhost:${PORT}`)
  console.log(`[serve-dist] 静态目录  ${ROOT}`)
  console.log(`[serve-dist] 后端代理  ${BACKEND}`)
})
