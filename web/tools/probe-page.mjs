/**
 * 通用页面探针：打开指定 URL，执行一段 JS 表达式并打印结果 + 控制台错误。
 *
 * 用法：
 *   node web/tools/probe-page.mjs <url> "<js表达式>" [admin|front|none]
 * 例：
 *   node web/tools/probe-page.mjs http://localhost:8080/culture/33 "document.querySelectorAll('.article-toc__item').length" none
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const URL_TO_OPEN = process.argv[2]
const EXPR = process.argv[3] || 'document.title'
const MODE = process.argv[4] || 'none'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9666)
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function main() {
  if (!URL_TO_OPEN) throw new Error('缺少 URL 参数')
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-probe-'))
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
      const t = '[' + m.params.type + '] ' + (m.params.args || []).map(a => a.value ?? a.description).join(' ')
      if (['error', 'warning'].includes(m.params.type)) logs.push(t)
    }
    if (m.method === 'Runtime.exceptionThrown') logs.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text))
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

  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable')
  const origin = new URL(URL_TO_OPEN).origin
  await send('Page.navigate', { url: origin + '/auth/login' })
  await sleep(1500)
  if (MODE === 'admin' || MODE === 'front') {
    const login = await fetch(API + '/api/auth/admin/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
    }).then(r => r.json())
    await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(login.data.token)}); 'ok'`)
  }
  if (MODE === 'front' || MODE === 'admin') {
    const fl = await fetch(API + '/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
    }).then(r => r.json())
    await evaluate(`localStorage.setItem('culture_front_token', ${JSON.stringify(fl.data.token)}); 'ok'`)
  }

  logs.length = 0
  await send('Page.navigate', { url: URL_TO_OPEN })
  await sleep(Number(process.env.PROBE_WAIT || 4000))

  console.log('=== 探针结果 ===')
  console.log(await evaluate(EXPR))
  console.log('=== 控制台错误 ===')
  console.log(logs.slice(0, 5).join('\n') || '（无）')

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
}

main().catch(e => { console.error(e); process.exitCode = 1 })
