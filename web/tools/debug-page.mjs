/**
 * 定点调试：打开指定页面，打印控制台错误 + 异常完整堆栈 + #app 渲染情况
 * 用法：node web/tools/debug-page.mjs <url> [admin|front]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const URL_TO_OPEN = process.argv[2] || 'http://localhost:8080/admin'
const MODE = process.argv[3] || 'admin'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9444)
const ORIGIN = new URL(URL_TO_OPEN).origin
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-dbg-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
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
    if (m.method === 'Runtime.consoleAPICalled') {
      logs.push('[' + m.params.type + '] ' + (m.params.args || []).map(a => a.value ?? a.description ?? a.type).join(' '))
    }
    if (m.method === 'Runtime.exceptionThrown') {
      logs.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text))
    }
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result) }
  })
  const send = (method, params = {}) => { const id = ++seq; ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => pending.set(id, { resolve, reject })) }
  const evaluate = async expr => {
    try {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
      if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
      return r.result.value
    } catch (e) { return '调用失败(可能已跳转): ' + e.message }
  }

  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable')
  await send('Page.navigate', { url: ORIGIN + '/auth/login' })
  await sleep(2000)

  if (MODE === 'admin') {
    const login = await fetch(API + '/api/auth/admin/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
    }).then(r => r.json())
    const token = login.data.token
    const front = await fetch(API + '/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
    }).then(r => r.json())
    await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(token)});
                    localStorage.setItem('culture_front_token', ${JSON.stringify(front.data.token)}); 'ok'`)
  }

  logs.length = 0
  await send('Page.navigate', { url: URL_TO_OPEN })
  await sleep(6000)

  const dom = await evaluate(`(() => {
    const app = document.querySelector('#app')
    return JSON.stringify({
      title: document.title,
      url: location.href,
      appChildren: app ? app.children.length : -1,
      appHtml: app ? app.innerHTML.slice(0, 600) : '',
      hasSidebar: !!document.querySelector('.coder-layout-sidebar'),
      hasContent: !!document.querySelector('.coder-layout-content'),
      tableRows: document.querySelectorAll('table tbody tr').length
    }, null, 2)
  })()`)

  const routerState = await evaluate(`(() => {
    const app = document.querySelector('#app').__vue_app__
    if (!app) return 'no app'
    const r = app.config.globalProperties.$router
    const cur = r.currentRoute.value
    const root = app._instance && app._instance.subTree
    return JSON.stringify({
      fullPath: cur.fullPath,
      matched: cur.matched.map(m => m.path + (m.components ? '[' + Object.keys(m.components).join(',') + ']' : '')),
      rootType: root && (root.type && (root.type.name || root.type.__name) || String(root.type)),
      rootShape: root && root.shapeFlag,
      rootComponentNull: root && root.component === null
    }, null, 2)
  })()`)
  console.log('===== 路由/根 vnode 状态 =====')
  console.log(routerState)

  console.log('===== DOM 状态 =====')
  console.log(dom)
  console.log('\n===== 控制台/异常（完整） =====')
  logs.slice(0, 12).forEach((l, i) => console.log(`--- ${i + 1} ---\n${l}`))

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
}

main().catch(e => { console.error(e); process.exitCode = 1 })
