/**
 * 诊断工具：抓出「谁把 Vue 管理的节点从文档里摘掉了」
 *
 * 背景：后台连续点击菜单时，第二次导航报
 *   TypeError: Cannot read properties of null (reading 'parentNode')
 *   位置：componentUpdateFn -> hostParentNode(prevTree.el)
 * 说明某个组件的根节点（或 Fragment 锚点）在组件仍存活时被外部代码移出了文档。
 * 本工具在文档创建时注入 DOM 破坏性操作探针，记录 removeChild / remove / replaceWith /
 * innerHTML= / textContent= 以及「跨父节点移动」的调用栈，然后完成两次菜单点击并导出报告。
 *
 * 用法：node web/tools/diag-detach.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8082'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9117)
const sleep = ms => new Promise(r => setTimeout(r, ms))

const PROBE = `(() => {
  if (window.__detachInstalled) return
  window.__detachInstalled = true
  const groups = new Map()
  window.__detach = () => [...groups.values()].sort((a, b) => b.n - a.n).slice(0, 60)

  function callerKey() {
    const st = (new Error().stack || '').split('\\n').slice(2).map(s => s.trim()).filter(Boolean)
    // 丢掉探针自身与匿名注入帧
    const frames = st.filter(s => !/__detach|detach-probe/.test(s))
    return frames.slice(0, 4).join(' <- ').slice(0, 420)
  }
  function describe(n) {
    if (!n) return String(n)
    if (n.nodeType === 3) return 'TEXT(' + JSON.stringify(String(n.data).slice(0, 24)) + ')'
    if (n.nodeType === 1) {
      const cls = typeof n.className === 'string' ? n.className.trim().split(/\\s+/).slice(0, 3).join('.') : ''
      return n.tagName + (n.id ? '#' + n.id : '') + (cls ? '.' + cls : '')
    }
    return 'node' + n.nodeType
  }
  function insideApp(n) {
    const root = document.getElementById('app')
    if (!root || !n) return false
    let p = n
    while (p) { if (p === root) return true; p = p.parentNode }
    return false
  }
  function rec(op, node, ctx) {
    if (!node) return
    if (!insideApp(node) && !insideApp(ctx)) return
    const stack = callerKey()
    const key = op + '|' + stack
    let g = groups.get(key)
    if (!g) { g = { op, stack, n: 0, samples: [] }; groups.set(key, g) }
    g.n++
    if (g.samples.length < 4) g.samples.push(describe(node) + (ctx ? '  @' + describe(ctx) : ''))
    if (groups.size > 4000) { groups.clear() }
  }

  const rc = Node.prototype.removeChild
  Node.prototype.removeChild = function (child) { rec('removeChild', child, this); return rc.call(this, child) }
  const ib = Node.prototype.insertBefore
  Node.prototype.insertBefore = function (n, ref) {
    if (n && n.parentNode && n.parentNode !== this) rec('MOVE insertBefore', n, this)
    return ib.call(this, n, ref)
  }
  const ac = Node.prototype.appendChild
  Node.prototype.appendChild = function (n) {
    if (n && n.parentNode && n.parentNode !== this) rec('MOVE appendChild', n, this)
    return ac.call(this, n)
  }
  const rp = Node.prototype.replaceChild
  Node.prototype.replaceChild = function (n, old) { rec('replaceChild', old, this); return rp.call(this, n, old) }
  const er = Element.prototype.remove
  Element.prototype.remove = function () { rec('el.remove', this, this.parentNode); return er.call(this) }
  const rw = Element.prototype.replaceWith
  Element.prototype.replaceWith = function () { rec('replaceWith', this, this.parentNode); return rw.apply(this, arguments) }
  const ih = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML')
  Object.defineProperty(Element.prototype, 'innerHTML', {
    configurable: true,
    get: ih.get,
    set(v) {
      if (this.firstElementChild || this.firstChild) rec('innerHTML=' + JSON.stringify(String(v).slice(0, 16)), this, this)
      return ih.set.call(this, v)
    }
  })
  const tc = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent')
  Object.defineProperty(Node.prototype, 'textContent', {
    configurable: true,
    get: tc.get,
    set(v) {
      if (this.firstElementChild || this.firstChild) rec('textContent=' + JSON.stringify(String(v).slice(0, 16)), this, this)
      return tc.set.call(this, v)
    }
  })
})()`

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-diag-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--window-size=1440,900',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  await sleep(1200)
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })

  let seq = 0
  const pending = new Map()
  const logs = []
  ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data)
    if (m.method === 'Runtime.exceptionThrown') logs.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text)
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') logs.push('[console.error] ' + (m.params.args || []).map(a => a.value ?? a.description).join(' '))
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result) }
  })
  const send = (method, params = {}) => { const id = ++seq; ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => pending.set(id, { resolve, reject })) }
  const evaluate = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
    return r.result.value
  }
  async function realClick(findExpr) {
    const box = await evaluate(`(() => {
      const el = ${findExpr}
      if (!el) return null
      el.scrollIntoView({ block: 'center' })
      const r = el.getBoundingClientRect()
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height) }
    })()`)
    if (!box || !box.w) return false
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y })
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    return true
  }

  await send('Page.enable'); await send('Runtime.enable')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: PROBE })

  await send('Page.navigate', { url: BASE + '/auth/login' })
  await sleep(1500)
  const login = await fetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json())
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(login.data.token)}); 'ok'`)

  await send('Page.navigate', { url: BASE + '/admin/comment' })
  await sleep(4000)
  console.log('第一页就绪，探针安装=', await evaluate('!!window.__detachInstalled'))
  const sym = await evaluate(`JSON.stringify({
    symType: typeof window.Symbol,
    hasFor: typeof (window.Symbol && window.Symbol.for),
    txtType: (function(){ try { return typeof window.Symbol.for('v-txt') } catch (e) { return 'ERR:' + e.message } })(),
    txtStr: (function(){ try { return String(window.Symbol.for('v-txt')) } catch (e) { return 'ERR' } })(),
    desc: Object.getOwnPropertyDescriptor(window, 'Symbol') ? Object.keys(Object.getOwnPropertyDescriptor(window, 'Symbol')) : null,
    err: typeof window.__guardNativeSymbol
  })`)
  console.log('Symbol 状态=', sym)

  // 点击「互动治理 → 标签管理」，触发第一次组件卸载
  logs.length = 0
  const groupExpr = `[...document.querySelectorAll('.nav-item-has-subnav')].find(li => li.textContent.includes('互动治理'))`
  const shown = await evaluate(`(() => { const li=${groupExpr}; return li ? getComputedStyle(li.querySelector('.nav-subnav')).display !== 'none' : false })()`)
  if (!shown) { await realClick(`${groupExpr}.querySelector('a')`); await sleep(1000) }
  await realClick(`[...${groupExpr}.querySelectorAll('.nav-subnav a')].find(a => a.textContent.includes('标签管理'))`)
  await sleep(2500)
  const st = await evaluate('location.pathname')
  console.log('点击后路径=', st, '错误=', logs.length ? String(logs[0]).split('\n').slice(0, 4).join(' | ') : '无')

  const report = await evaluate('JSON.stringify(window.__detach ? window.__detach() : [])')
  const rows = JSON.parse(report)
  console.log(`\n=== 破坏性 DOM 操作分组（共 ${rows.length} 组，按次数降序，仅列非 Vue 运行时栈）===`)
  const vueRuntime = /vue-router-|@vue|chunk-|createElementBlock|componentUpdateFn/
  let shownCount = 0
  for (const r of rows) {
    if (vueRuntime.test(r.stack)) continue
    console.log(`\n[${r.n}次] ${r.op}  样本: ${r.samples.join(' ; ')}`)
    console.log('    ' + r.stack.split(' <- ').slice(0, 3).join('\n    '))
    if (++shownCount >= 18) break
  }
  if (!shownCount) console.log('（没有非 Vue 栈的记录，说明破坏动作来自 Vue 自身或未走这些 API）')

  fs.writeFileSync(path.join(process.cwd(), 'tools', '_detach-report.json'), JSON.stringify(rows, null, 2))
  console.log('\n完整报告已写入 tools/_detach-report.json')

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
