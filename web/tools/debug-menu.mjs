/**
 * 后台菜单交互验证：真实点击父/子菜单，检查展开状态、手风琴互斥、路由跳转与页面错误
 * 用法：node web/tools/debug-menu.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9555)
const sleep = ms => new Promise(r => setTimeout(r, ms))

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok })
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? '  — ' + detail : ''))
}

const GROUP_STATE = `JSON.stringify([...document.querySelectorAll('.nav-item-has-subnav')].map(li => ({
  name: li.querySelector('a span') ? li.querySelector('a span').textContent : '',
  open: li.classList.contains('open'),
  display: getComputedStyle(li.querySelector('.nav-subnav')).display
})))`

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-menu-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  await sleep(1200)
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej) })

  let seq = 0
  const pending = new Map()
  let logs = []
  ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data)
    if (m.method === 'Runtime.consoleAPICalled') {
      const t = '[' + m.params.type + '] ' + (m.params.args || []).map(a => a.value ?? a.description).join(' ')
      if (m.params.type === 'error' || m.params.type === 'warning') logs.push(t)
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
  const clickGroup = name => evaluate(`(() => {
    const li = [...document.querySelectorAll('.nav-item-has-subnav')].find(x => x.textContent.includes('${name}'))
    if (!li) return false
    li.querySelector('a').click(); return true
  })()`)

  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable')
  await send('Page.navigate', { url: BASE + '/auth/login' })
  await sleep(1800)
  const login = await fetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json())
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(login.data.token)}); 'ok'`)

  await send('Page.navigate', { url: BASE + '/admin' })
  await sleep(4000)
  logs = []

  const initial = JSON.parse(await evaluate(GROUP_STATE))
  check('初始状态：所有分组收起', initial.every(g => !g.open && g.display === 'none'),
    initial.map(g => g.name + ':' + g.display).join(' '))

  await clickGroup('内容管理')
  await sleep(1200)
  const afterExpand = JSON.parse(await evaluate(GROUP_STATE))
  const culture = afterExpand.find(g => g.name === '内容管理')
  check('点击父菜单「内容管理」可展开', !!culture && culture.open && culture.display === 'block', JSON.stringify(culture))
  check('展开过程无 JS 报错', logs.length === 0, logs.slice(0, 2).join(' | '))

  logs = []
  await evaluate(`(() => {
    const a = [...document.querySelectorAll('.nav-subnav a')].find(x => x.textContent.includes('内容列表'))
    a.click(); return true
  })()`)
  await sleep(2800)
  const nav = JSON.parse(await evaluate(`JSON.stringify({ url: location.pathname, rows: document.querySelectorAll('table tbody tr').length })`))
  check('点击子菜单「内容列表」跳转并加载数据', nav.url === '/admin/culture' && nav.rows > 0, JSON.stringify(nav))
  check('子菜单跳转无 JS 报错', logs.length === 0, logs.slice(0, 2).join(' | '))

  await clickGroup('公告管理')
  await sleep(1300)
  const accordion = JSON.parse(await evaluate(GROUP_STATE))
  const notice = accordion.find(g => g.name === '公告管理')
  const culture2 = accordion.find(g => g.name === '内容管理')
  check('手风琴互斥：展开「公告管理」时「内容管理」收起',
    notice.open && notice.display === 'block' && !culture2.open && culture2.display === 'none',
    '公告管理=' + notice.display + ' 内容管理=' + culture2.display)

  await send('Page.navigate', { url: BASE + '/admin/culture' })
  await sleep(3500)
  const direct = JSON.parse(await evaluate(GROUP_STATE))
  const culture3 = direct.find(g => g.name === '内容管理')
  check('直接打开 /admin/culture 时所属分组自动展开', culture3.open && culture3.display === 'block', JSON.stringify(culture3))

  await evaluate(`document.querySelector('.coder-aside-toggler').click()`)
  await sleep(700)
  const collapse = JSON.parse(await evaluate(`JSON.stringify({
    asideOpen: !!document.querySelector('.coder-layout-sidebar.coder-aside-open'),
    bodyClose: document.body.classList.contains('coder-layout-sidebar-close'),
    mask: !!document.querySelector('.coder-mask-modal')
  })`))
  check('侧边栏折叠按钮生效', collapse.asideOpen && collapse.bodyClose && collapse.mask, JSON.stringify(collapse))

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }

  const failed = results.filter(r => !r.ok)
  console.log('\n=== 菜单验证：' + (results.length - failed.length) + '/' + results.length + ' 通过 ===')
  if (failed.length) process.exitCode = 1
}

main().catch(e => { console.error(e); process.exitCode = 2 })
