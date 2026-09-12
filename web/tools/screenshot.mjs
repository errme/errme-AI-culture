/**
 * 页面截图工具（headless Chrome + CDP，无第三方依赖）
 *
 * 用法：
 *   node web/tools/screenshot.mjs <url> [输出文件] [full|viewport]
 * 例：
 *   node web/tools/screenshot.mjs http://localhost:8080/ tools/_shot-home.png full
 *
 * 说明：默认整页截图；截图前会等待页面挂载完成（后台等 window.__appMountedAt）。
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const URL_TO_OPEN = process.argv[2]
const OUT = process.argv[3] || 'tools/_shot.png'
const FULL = (process.argv[4] || 'full') !== 'viewport'
const PORT = Number(process.env.CDP_PORT || 9677)
const sleep = ms => new Promise(r => setTimeout(r, ms))

async function main() {
  if (!URL_TO_OPEN) throw new Error('缺少 URL 参数')
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-shot-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--hide-scrollbars',
    '--window-size=1440,900', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  await sleep(1200)
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })

  let seq = 0
  const pending = new Map()
  const errors = []
  ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data)
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text)
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errors.push('[console.error] ' + (m.params.args || []).map(a => a.value ?? a.description).join(' '))
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result) }
  })
  const send = (method, params = {}) => { const id = ++seq; ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => pending.set(id, { resolve, reject })) }

  await send('Page.enable'); await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  await send('Page.navigate', { url: URL_TO_OPEN })
  await sleep(4500)
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: FULL })
  fs.writeFileSync(OUT, Buffer.from(shot.data, 'base64'))
  const size = fs.statSync(OUT).size
  console.log(`截图已保存 ${OUT}（${(size / 1024).toFixed(0)} KB，${FULL ? '整页' : '视口'}）`)
  if (errors.length) { console.log('控制台错误：'); errors.slice(0, 5).forEach(e => console.log('  ' + String(e).split('\n')[0])) }
  else console.log('控制台错误：无')

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
