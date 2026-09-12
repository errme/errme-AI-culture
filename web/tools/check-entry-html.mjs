/**
 * 入口 HTML 静态依赖检查（CI 用）
 *
 * 背景（一次真实事故）：bootstrap-table 内置 core-js，会在加载时用 defineProperty 覆盖原生
 * Symbol；它被放在 admin.html 的 <head> 里同步执行，而入口是 type="module"（延后执行），
 * 于是 Vue 初始化时的 Symbol.for('v-txt')（文本节点类型）变成「假 Symbol 对象」，
 * createVNode 把文本节点当成组件节点，路由切换卸载旧页面时崩溃，之后所有菜单点击失效。
 *
 * 因此本检查把规则固化下来：
 *   1) 入口 HTML 的 <head> 里**不允许**静态引入任何内置 core-js（或覆盖 Symbol）的库 —— 硬失败
 *   2) head 里的单个脚本超过 150KB 时给出提示（建议改为按需加载）
 *
 * 用法：node web/tools/check-entry-html.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const WEB = path.resolve(fileURLToPath(new URL('..', import.meta.url)))
const REPO = path.resolve(WEB, '..')
const STATIC_ROOT = path.join(REPO, 'frontend', 'static')
const SIZE_WARN = 150 * 1024

/** core-js / Symbol polyfill 特征串 */
const POLYFILL_MARKERS = ['__core-js_shared__', 'core-js/modules', 'Symbol.for("v-txt")', 'es6-symbol']

/** 把 URL 映射到本地文件：/static/** -> frontend/static/**，/index/** -> frontend/static/index/** */
function localFileFor(url) {
  if (url.startsWith('/static/')) return path.join(STATIC_ROOT, url.slice('/static/'.length))
  if (url.startsWith('/index/')) return path.join(STATIC_ROOT, 'index', url.slice('/index/'.length))
  return null
}

function scan(file) {
  const src = fs.readFileSync(file, 'utf8')
  const head = src.slice(0, src.search(/<\/head>/i) === -1 ? src.length : src.search(/<\/head>/i))
  const scripts = [...head.matchAll(/<script[^>]*\ssrc="([^"]+)"[^>]*>/gi)].map(m => m[1])
  const styles = [...head.matchAll(/<link[^>]*\shref="([^"]+)"[^>]*>/gi)].map(m => m[1]).filter(h => /\.css(\?|$)/.test(h))
  return { scripts, styles, preload: [...head.matchAll(/<script>([\s\S]*?)<\/script>/gi)].length }
}

const errors = []
const warns = []
const rows = []

for (const name of fs.readdirSync(WEB).filter(f => f.endsWith('.html'))) {
  const file = path.join(WEB, name)
  const { scripts, styles } = scan(file)
  for (const url of scripts) {
    const local = localFileFor(url)
    const size = local && fs.existsSync(local) ? fs.statSync(local).size : 0
    let polyfill = false
    if (local && fs.existsSync(local)) {
      const content = fs.readFileSync(local, 'utf8')
      polyfill = POLYFILL_MARKERS.some(mk => content.includes(mk))
    }
    rows.push({ page: name, url, size, polyfill })
    if (polyfill) {
      errors.push(`${name} 的 <head> 静态引入了内置 core-js 的脚本：${url}\n` +
        `    → 必须在 Vue 初始化之后再加载（参考 src/entries/admin.js 里 bootstrap-table 的做法），` +
        `否则会覆盖原生 Symbol 并导致路由切换崩溃。`)
    }
    if (size > SIZE_WARN) {
      warns.push(`${name} 的 <head> 脚本较大（${Math.round(size / 1024)} KB）：${url} → 建议改为按需加载`)
    }
  }
  console.log(`\n[${name}] head 静态脚本 ${scripts.length} 个，样式 ${styles.length} 个`)
  for (const r of rows.filter(r => r.page === name)) {
    console.log(`   ${r.polyfill ? '✗' : '·'} ${String(Math.round(r.size / 1024)).padStart(5)} KB  ${r.url}`)
  }
}

if (warns.length) {
  console.log('\n提示：')
  warns.forEach(w => console.log('  ⚠ ' + w))
}

if (errors.length) {
  console.error('\n入口 HTML 检查未通过：')
  errors.forEach(e => console.error('  ✗ ' + e))
  process.exitCode = 1
} else {
  console.log('\n入口 HTML 检查通过：head 里没有内置 core-js 的库。')
}
