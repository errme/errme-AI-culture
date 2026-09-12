/**
 * 后台菜单「连续点击」真实鼠标验证
 *
 * 覆盖前一个测试的盲区：click-all-menus 每项都重新加载页面，
 * 而用户的实际操作是「在一个页面里连续点多个菜单」。
 *
 * 用法：node web/tools/click-seq.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9111)
const sleep = ms => new Promise(r => setTimeout(r, ms))

// 连续点击序列（group, item, 期望路径）
const SEQUENCE = [
  ['内容管理', '内容列表', '/admin/culture'],
  ['系统管理', '用户管理', '/admin/user'],
  ['互动治理', '评论审核', '/admin/comment'],
  ['互动治理', '标签管理', '/admin/tag'],
  ['公告管理', '公告列表', '/admin/announcement'],
  ['好词佳句', '句子管理', '/admin/sentence'],
  ['内容管理', '分类管理', '/admin/category'],
  ['系统管理', '操作日志', '/admin/oplog'],
  ['系统管理', '邮件设置', '/admin/mail'],
  ['内容管理', '发布内容', '/admin/culture']   // 期望同时打开新增弹窗
]

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-seq-'))
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

  // 只加载一次页面，之后全部在同一页面内连续点击
  await send('Page.navigate', { url: BASE + '/admin' })
  await sleep(4000)

  const results = []
  for (const [group, item, expectPath] of SEQUENCE) {
    logs.length = 0
    // 展开分组（真实点击）
    const groupExpr = `[...document.querySelectorAll('.nav-item-has-subnav')].find(li => li.textContent.includes(${JSON.stringify(group)}))`
    const shown = await evaluate(`(() => { const li=${groupExpr}; return li ? getComputedStyle(li.querySelector('.nav-subnav')).display !== 'none' : false })()`)
    if (!shown) { await realClick(`${groupExpr}.querySelector('a')`); await sleep(1000) }

    const clicked = await realClick(`[...${groupExpr}.querySelectorAll('.nav-subnav a')].find(a => a.textContent.includes(${JSON.stringify(item)}))`)
    await sleep(2400)

    const st = JSON.parse(await evaluate(`JSON.stringify({
      path: location.pathname,
      modal: !!document.querySelector('#cultureAddModal') && getComputedStyle(document.querySelector('#cultureAddModal')).display !== 'none',
      title: document.title
    })`))
    const ok = clicked && st.path === expectPath && logs.length === 0
    results.push({ name: group + ' → ' + item, ok })
    console.log(`${ok ? 'PASS' : 'FAIL'} ${group} → ${item}  期望=${expectPath} 实际=${st.path}${item === '发布内容' ? ' 弹窗=' + st.modal : ''}${logs.length ? '  错误: ' + String(logs[0]).split('\n').slice(0, 8).join(' | ') : ''}`)
  }

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 连续点击：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) { failed.forEach(f => console.log('  FAIL ' + f.name)); process.exitCode = 1 }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
