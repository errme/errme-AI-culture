/**
 * 后台菜单「真实鼠标点击」验证
 *
 * 为什么需要它：element.click() 绕过浏览器命中检测，
 * 如果侧边栏上盖了遮罩（如主题的 .coder-mask-modal、perfect-scrollbar 的 ps__rail 等），
 * 程序化点击仍然成功、真实点击却会被拦住 —— 本脚本用 CDP Input.dispatchMouseEvent
 * 在元素坐标上派发真实鼠标事件，能复现用户的“点了没反应”。
 *
 * 用法：node web/tools/click-check.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9888)
const sleep = ms => new Promise(r => setTimeout(r, ms))

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? '  — ' + detail : ''}`)
}

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-click-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
    '--window-size=1440,900',   // 窗口太小会让下方菜单落在视口外，真实点击自然落空
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  await sleep(1200)
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })

  let seq = 0
  const pending = new Map()
  ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data)
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result) }
  })
  const send = (method, params = {}) => { const id = ++seq; ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => pending.set(id, { resolve, reject })) }
  const evaluate = async expr => {
    try {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
      if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
      return r.result.value
    } catch (e) { return '调用失败: ' + e.message }
  }

  /** 真实鼠标点击：先取元素中心坐标，再派发 mousePressed/Released */
  async function realClick(selectorOrExpr, isExpr = false) {
    const findExpr = isExpr ? selectorOrExpr : `document.querySelector(${JSON.stringify(selectorOrExpr)})`
    const box = await evaluate(`(() => {
      const el = ${findExpr}
      if (!el) return null
      el.scrollIntoView({ block: 'center' })
      const r = el.getBoundingClientRect()
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height) }
    })()`)
    if (!box || !box.w) return { ok: false, reason: '元素不可见/未找到', box }
    // 命中检测：该坐标上最顶层的元素是谁？
    const topEl = await evaluate(`(() => {
      const el = document.elementFromPoint(${box.x}, ${box.y})
      return el ? (el.tagName + '.' + String(el.className || '').split(' ').slice(0, 2).join('.')) : 'none'
    })()`)
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y })
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    return { ok: true, box, topEl }
  }

  await send('Page.enable'); await send('Runtime.enable')
  await send('Page.navigate', { url: BASE + '/auth/login' })
  await sleep(1500)
  const login = await fetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json())
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(login.data.token)}); 'ok'`)
  await send('Page.navigate', { url: BASE + '/admin' })
  await sleep(4000)

  // 1) 真实点击父分组「互动治理」
  const before = await evaluate(`(() => { const li=[...document.querySelectorAll('.nav-item-has-subnav')].find(x=>x.textContent.includes('互动治理')); return li ? getComputedStyle(li.querySelector('.nav-subnav')).display : 'missing' })()`)
  const clickGroup = await realClick(
    `[...document.querySelectorAll('.nav-item-has-subnav')].find(li => li.textContent.includes('互动治理')).querySelector('a')`, true)
  await sleep(1200)
  const after = await evaluate(`(() => { const li=[...document.querySelectorAll('.nav-item-has-subnav')].find(x=>x.textContent.includes('互动治理')); return li ? getComputedStyle(li.querySelector('.nav-subnav')).display : 'missing' })()`)
  check('真实点击父分组可展开', before === 'none' && after === 'block', `点击落点最上层元素=${clickGroup.topEl}；display ${before} → ${after}`)

  // 2) 真实点击子项「评论审核」
  const clickChild = await realClick(
    `[...document.querySelectorAll('.nav-subnav a')].find(a => a.textContent.includes('评论审核'))`, true)
  await sleep(2500)
  const url1 = await evaluate('location.pathname')
  check('真实点击子项可跳转', /^\/admin\//.test(url1), `点击落点最上层元素=${clickChild.topEl}；url=${url1}`)

  // 3) 检查是否有遮罩拦截
  const overlay = await evaluate(`JSON.stringify({
    mask: !!document.querySelector('.coder-mask-modal'),
    bodyClose: document.body.classList.contains('coder-layout-sidebar-close'),
    psRail: !!document.querySelector('.ps__rail-y')
  })`)
  check('侧边栏无遮罩拦截', !JSON.parse(overlay).mask && !JSON.parse(overlay).bodyClose, overlay)

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 真实点击验证：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) process.exitCode = 1
}

main().catch(e => { console.error(e); process.exitCode = 2 })
