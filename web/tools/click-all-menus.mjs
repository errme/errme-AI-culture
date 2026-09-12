/**
 * 后台「全部菜单项」真实鼠标点击验证
 *
 * 逐项：展开所属分组 → 真实点击菜单项 → 断言页面确实发生变化
 * （路由变化 / 预期弹窗打开），用于发现「点了没反应」这类问题。
 *
 * 用法：node web/tools/click-all-menus.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9999)
const sleep = ms => new Promise(r => setTimeout(r, ms))

const ITEMS = [
  { group: '系统管理', name: '用户管理', path: '/admin/user' },
  { group: '系统管理', name: '邮件设置', path: '/admin/mail' },
  { group: '系统管理', name: '操作日志', path: '/admin/oplog' },
  { group: '内容管理', name: '内容列表', path: '/admin/culture' },
  { group: '内容管理', name: '发布内容', path: '/admin/culture', modal: '#cultureAddModal' },
  { group: '内容管理', name: '分类管理', path: '/admin/category' },
  { group: '好词佳句', name: '句子管理', path: '/admin/sentence' },
  { group: '公告管理', name: '公告列表', path: '/admin/announcement' },
  { group: '互动治理', name: '评论审核', path: '/admin/comment' },
  { group: '互动治理', name: '标签管理', path: '/admin/tag' }
]

const results = []
async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-menus-'))
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
    try {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
      if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
      return r.result.value
    } catch (e) { return '调用失败: ' + e.message }
  }

  /** 真实鼠标点击一个元素（元素由页面内表达式定位） */
  async function realClick(findExpr) {
    const box = await evaluate(`(() => {
      const el = ${findExpr}
      if (!el) return null
      el.scrollIntoView({ block: 'center' })
      const r = el.getBoundingClientRect()
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height) }
    })()`)
    if (!box || !box.w || !box.h) return false
    await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: box.x, y: box.y })
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
    return true
  }

  await send('Page.enable'); await send('Runtime.enable')
  await send('Page.navigate', { url: BASE + '/auth/login' })
  await sleep(1500)
  const login = await fetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json())
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(login.data.token)}); 'ok'`)

  for (const item of ITEMS) {
    logs.length = 0
    // 每次都从后台首页开始，避免「目标路由与当前一致」造成的假阴性
    await send('Page.navigate', { url: BASE + '/admin' })
    await sleep(3200)

    // 1) 展开所属分组
    const groupExpr = `[...document.querySelectorAll('.nav-item-has-subnav')].find(li => li.textContent.includes(${JSON.stringify(item.group)}))`
    const shown = await evaluate(`(() => { const li=${groupExpr}; return li ? getComputedStyle(li.querySelector('.nav-subnav')).display !== 'none' : false })()`)
    if (!shown) {
      await realClick(`${groupExpr}.querySelector('a')`)
      await sleep(1100)
    }
    // 2) 点击菜单项
    const clicked = await realClick(`[...${groupExpr}.querySelectorAll('.nav-subnav a')].find(a => a.textContent.includes(${JSON.stringify(item.name)}))`)
    await sleep(2600)

    const state = await evaluate(`JSON.stringify({
      path: location.pathname,
      modal: ${item.modal ? `!!document.querySelector('${item.modal}') && getComputedStyle(document.querySelector('${item.modal}')).display !== 'none'` : 'null'},
      card: (document.querySelector('.card-title') || {}).textContent || ''
    })`)
    const st = JSON.parse(state)
    const okPath = st.path === item.path
    const okModal = item.modal ? st.modal === true : true
    const ok = clicked && okPath && okModal && logs.length === 0
    results.push({ name: item.group + ' → ' + item.name, ok })
    console.log(`${ok ? 'PASS' : 'FAIL'} ${item.group} → ${item.name}  path=${st.path}${item.modal ? ' 弹窗=' + st.modal : ''}${logs.length ? '  错误:' + logs[0].slice(0, 120) : ''}`)
  }

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 菜单逐项点击：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) { failed.forEach(f => console.log('  FAIL ' + f.name)); process.exitCode = 1 }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
