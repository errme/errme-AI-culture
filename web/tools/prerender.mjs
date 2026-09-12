/**
 * 前台内容页预渲染（SEO）
 *
 * 思路：用无头 Chrome 打开「已构建好的 SPA」，等页面把数据渲染出来后，
 * 把当时的 DOM 快照写成静态 HTML（每路由一个 index.html）。
 * 这样爬虫（尤其百度，对 JS 渲染支持弱）无需执行 JS 就能拿到正文。
 *
 * 为什么不用 vite-ssg / Nuxt：
 *   - 需要把 6 个视图的数据获取改成 SSR 生命周期、并让 router 守卫在 Node 下不碰 document；
 *   - 还要改构建与部署链路（多入口 → SSG 单入口 + 产物合并）。
 *   本方案对现有代码**零侵入**，产物结构（每路由一个 index.html）与 vite-ssg 一致，
 *   将来若切到 Nuxt/vite-ssg，Nginx 规则与重建脚本都不用改。
 *
 * 用法：
 *   node tools/serve-dist.mjs &            # 先让构建产物可访问（或换成 Nginx）
 *   node tools/prerender.mjs               # 默认 http://localhost:8080
 *   BASE_URL=http://localhost:8080 node tools/prerender.mjs
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.env.BASE_URL || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const OUT = path.resolve(fileURLToPath(new URL('../dist', import.meta.url)))
const PORT = Number(process.env.CDP_PORT || 9777)
const sleep = ms => new Promise(r => setTimeout(r, ms))

/** 每个路由的「渲染完成」判定选择器（纯 CSS 选择器，避免非法写法） */
const CONTENT_MARKERS = {
  '/': ['.topbar', '.post'],
  '/culture': ['.proerty-item'],
  '/sentence': ['.item.box'],
  '/about': ['.aboutcontent'],
  '/culture/': ['.title', '.proerty-th']
}

async function fetchRoutes() {
  // 复用 sitemap 作为「该被收录的 URL 全集」。
  // 注意：SEO 端点在内容量大时会输出 sitemapindex（分片），此时必须先跟进各分片再取 loc，
  // 否则只会拿到几个分片地址、导致 /culture/{id} 全部不再预渲染。
  const xml = await fetch(API + '/sitemap.xml').then(r => r.text())
  let locs = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map(m => m[1])
  if (/<sitemapindex[\s>]/.test(xml)) {
    const shardLocs = locs.slice()
    locs = []
    for (const shard of shardLocs) {
      try {
        // 分片 loc 是绝对地址（指向站点域名），本地要换回后端 origin
        const u = new URL(shard)
        const shardXml = await fetch(API + u.pathname + u.search).then(r => r.text())
        locs.push(...[...shardXml.matchAll(/<loc>([^<]+)<\/loc>/g)].map(m => m[1]))
      } catch (e) {
        console.warn('[prerender] 读取 sitemap 分片失败：' + shard + ' -> ' + e.message)
      }
    }
  }
  const paths = locs
    .map(u => {
      try { return new URL(u).pathname } catch (e) { return null }
    })
    .filter(Boolean)
    .filter(p => !/^\/(admin|auth|center|search|tag)/.test(p))
  // 去重并保证基础页在前
  const uniq = [...new Set(paths)]
  const base = ['/', '/culture', '/sentence', '/about']
  return [...base.filter(p => uniq.includes(p) || base.includes(p)), ...uniq.filter(p => !base.includes(p))]
}

async function startChrome() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-prerender-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  for (let i = 0; i < 40; i++) {
    await sleep(300)
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (res.ok) return { child, profile }
    } catch (e) { /* 等启动 */ }
  }
  throw new Error('Chrome 调试端口未就绪')
}

/**
 * 删除上一次的预渲染产物（只删 HTML，保留 assets）。
 * 必须做：托管层会优先返回已存在的 <route>/index.html，
 * 若不清掉，本轮访问 / 时拿到的就是「上一次的快照」，快照套快照会逐轮丢内容
 * （曾因此丢掉 head 里的 jQuery，导致首页轮播与后半部分区块消失）。
 */
function cleanPreviousSnapshots() {
  let removed = 0
  const walk = dir => {
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name)
      if (name === 'assets') continue
      const st = fs.statSync(full)
      if (st.isDirectory()) walk(full)
      else if (name === 'index.html') { fs.unlinkSync(full); removed++ }
    }
  }
  if (fs.existsSync(OUT)) walk(OUT)
  // 顺手清掉空目录
  const prune = dir => {
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name)
      if (name === 'assets' || !fs.statSync(full).isDirectory()) continue
      prune(full)
      if (fs.readdirSync(full).length === 0) fs.rmdirSync(full)
    }
  }
  try { prune(OUT) } catch (e) { /* ignore */ }
  if (removed) console.log(`[prerender] 已清理上一次的 ${removed} 个快照文件`)
}

/**
 * 构建自检：入口 HTML 引用的 assets/* 必须真实存在。
 * 存在的意义：只要「构建产物」与「HTML 引用」不同步（例如只跑了 vite build 没跑 prerender，
 * 或者中途被别的构建覆盖），页面就会静默挂不起来（模块脚本 404），非常难排查。
 */
function assertEntryAssets() {
  const missing = []
  for (const name of fs.readdirSync(OUT)) {
    if (!name.endsWith('.html')) continue
    const file = path.join(OUT, name)
    const html = fs.readFileSync(file, 'utf8')
    for (const m of html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)) {
      const abs = path.join(OUT, m[1].replace(/^\//, ''))
      if (!fs.existsSync(abs)) missing.push(`${name} -> ${m[1]}`)
    }
  }
  if (missing.length) {
    console.error('[prerender] 构建产物与 HTML 引用不一致（缺少 ' + missing.length + ' 个资源）：')
    missing.slice(0, 5).forEach(x => console.error('   ✗ ' + x))
    console.error('   → 请重新执行 npm run build:all（只跑 vite build 会让预渲染快照引用失效）')
    process.exit(1)
  }
}

async function main() {
  assertEntryAssets()
  cleanPreviousSnapshots()
  const routes = await fetchRoutes()
  console.log(`[prerender] 待预渲染路由 ${routes.length} 个，输出目录 ${OUT}`)
  const { child, profile } = await startChrome()
  let ok = 0
  const failed = []

  try {
    const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
    const ws = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })

    let seq = 0
    const pending = new Map()
    ws.addEventListener('message', ev => {
      const m = JSON.parse(ev.data)
      if (m.id && pending.has(m.id)) {
        const p = pending.get(m.id)
        pending.delete(m.id)
        m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result)
      }
    })
    const send = (method, params = {}) => {
      const id = ++seq
      ws.send(JSON.stringify({ id, method, params }))
      return new Promise((resolve, reject) => pending.set(id, { resolve, reject }))
    }
    const evaluate = async expr => {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
      return r.result.value
    }
    await send('Page.enable'); await send('Runtime.enable')

    for (const route of routes) {
      const url = BASE + route
      try {
        await send('Page.navigate', { url })
        // 等页面把主要内容渲染出来（最多 12 秒）
        const markers = route.startsWith('/culture/') ? CONTENT_MARKERS['/culture/'] : CONTENT_MARKERS[route]
        const deadline = Date.now() + 20000
        let ready = false
        while (Date.now() < deadline) {
          await sleep(250)
          const hit = await evaluate(`(${JSON.stringify(markers || [])}).every(sel => document.querySelector(sel) !== null)`)
          if (hit) { ready = true; break }
        }
        // 再等一拍，让字体/图片/目录等收尾
        await sleep(600)

        // 关键断言：入口脚本会把挂载时刻写入 window.__appMountedAt。
        // 若没有该值，说明拿到的不是 SPA 页面（例如误抓了旧的预渲染产物），
        // 此时必须报错而不是写出一份残缺快照。
        const mounted = await evaluate('window.__appMountedAt || 0')
        if (!mounted) throw new Error('页面未挂载 SPA（可能抓到了旧的预渲染产物），已跳过')

        const html = await evaluate(`(() => {
          // 注意：**不要**剔除 <script>！
          // 快照里的脚本既包含入口 HTML 自带的基础库（jQuery / underscore / OwlCarousel …），
          // 也包含视图在运行时注入的老插件；一律保留。客户端挂载时 loadScript 发现同 src 已存在会跳过注入，
          // 因此不会重复执行。曾经把所有 script 都删掉，导致 index.js 报 “$ is not defined”，
          // 首页轮播图与「好词佳句」之后的区块全部消失。
          document.querySelectorAll('.ce-busy').forEach(el => el.classList.remove('ce-busy'))
          return '<!DOCTYPE html>\\n' + document.documentElement.outerHTML
        })()`)

        const dir = route === '/' ? OUT : path.join(OUT, route.replace(/^\//, ''))
        fs.mkdirSync(dir, { recursive: true })
        const file = path.join(dir, 'index.html')
        fs.writeFileSync(file, html, 'utf8')
        const bytes = fs.statSync(file).size
        // 落盘校验：类名可能与其他 class 并列（如 class="box-two proerty-item"），
        // 因此只做「类名出现」的宽松校验；真实渲染判定由上面的选择器轮询负责。
        const hasMarker = (markers || []).every(sel => html.includes(sel.replace(/^\./, '').replace(/\./g, ' ')))
        if (hasMarker) {
          ok++
          console.log(`  ✅ ${route}  (${(bytes / 1024).toFixed(0)} KB)${ready ? '' : '  [等待超时但内容已就绪]'}`)
        } else {
          failed.push(route)
          console.log(`  ⚠️  ${route}  正文标记缺失，仍写出 ${(bytes / 1024).toFixed(0)} KB`)
        }
      } catch (e) {
        failed.push(route)
        console.log(`  ❌ ${route}  ${e.message}`)
      }
    }
  } finally {
    child.kill()
    try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  }

  console.log(`\n[prerender] 完成：${ok}/${routes.length} 成功${failed.length ? '，失败：' + failed.slice(0, 5).join(', ') : ''}`)
  if (failed.length) process.exitCode = 1
}

main().catch(e => { console.error('[prerender] 异常：', e); process.exitCode = 2 })
