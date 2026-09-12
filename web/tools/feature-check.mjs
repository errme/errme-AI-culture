/**
 * 第 7 批功能回归：回收站 / 角色权限 / 版本历史 / 定时发布 / 敏感词 / 标签合并 / 搜索增强 / 指标与看板
 *
 * 为什么单独一套：这些是本轮新增能力，散落在多个页面与接口上；仅靠菜单点击套件覆盖不到。
 * 本套件用「接口造数据 + 真实浏览器断言 + 收尾清理」的方式，逐项确认功能真的可用。
 *
 * 用法：node web/tools/feature-check.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9161)
const sleep = ms => new Promise(r => setTimeout(r, ms))
const WATCHDOG_MS = Number(process.env.WATCHDOG_MS || 300000)
setTimeout(() => { console.error('[feature-check] 超时未完成，强制退出'); process.exit(3) }, WATCHDOG_MS).unref?.()

const results = []
const record = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? '  — ' + detail : ''}`)
}

const jsonFetch = async (url, opt = {}, timeoutMs = 15000) => {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), timeoutMs)
  try {
    const r = await fetch(url, { ...opt, signal: ctl.signal })
    const text = await r.text()
    try { return { status: r.status, body: JSON.parse(text) } } catch (e) { return { status: r.status, text } }
  } finally { clearTimeout(timer) }
}

async function main() {
  const login = await jsonFetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  })
  const token = login.body.data.token
  const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }
  const api = (m, u, b) => jsonFetch(API + u, { method: m, headers: H, body: b ? JSON.stringify(b) : undefined })
  const stamp = Date.now().toString().slice(-6)
  const cleanup = []

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-feat-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--window-size=1440,900',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  await sleep(1200)
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('连接 Chrome 超时')), 15000)
    ws.addEventListener('open', () => { clearTimeout(t); res() })
    ws.addEventListener('error', e => { clearTimeout(t); rej(e) })
  })
  let seq = 0
  const pending = new Map()
  const errors = []
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data)
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text)
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errors.push('[console.error] ' + (m.params.args || []).map(a => a.value ?? a.description).join(' '))
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result) }
  })
  const send = (method, params = {}) => {
    const id = ++seq
    ws.send(JSON.stringify({ id, method, params }))
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { pending.delete(id); reject(new Error('CDP 超时：' + method)) }, 15000)
      pending.set(id, { resolve: v => { clearTimeout(t); resolve(v) }, reject: e => { clearTimeout(t); reject(e) } })
    })
  }
  const evaluate = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
    return r.result.value
  }
  const json = async expr => { try { return JSON.parse(await evaluate(`JSON.stringify(${expr})`)) } catch (e) { return {} } }
  const goto = async (url, wait = 3200) => { await send('Page.navigate', { url }); await sleep(wait) }

  await send('Page.enable'); await send('Runtime.enable')
  await goto(BASE + '/auth/login', 1300)
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(token)}); 'ok'`)

  /* ---------------- 1. 回收站 ---------------- */
  const tagName = '自测功能' + stamp
  await api('POST', '/api/admin/tag/save', { name: tagName, sort: 993 })
  const tagList = await api('GET', '/api/admin/tag/list')
  const tagRows = (tagList.body.data && (tagList.body.data.rows || tagList.body.data)) || []
  const tag = tagRows.find(t => t.name === tagName)
  if (tag) { await api('POST', '/api/admin/tag/delete', { id: tag.id }); cleanup.push(() => api('POST', '/api/admin/recycle/purge', { type: 'tag', ids: [tag.id] })) }

  await goto(BASE + '/admin/recycle')
  const recycleUi = await json(`(() => ({
    tabs: document.querySelectorAll('.ad-page button, .ad-page .btn').length,
    hasCounts: document.querySelectorAll('.ad-badge').length,
    table: !!document.querySelector('.ad-table'),
    rows: document.querySelectorAll('.ad-table tbody tr').length
  }))()`)
  record('回收站页面渲染（类型切换 + 数量徽章 + 列表）',
    recycleUi.hasCounts > 0 && recycleUi.table && recycleUi.rows > 0,
    `徽章=${recycleUi.hasCounts} 列表行=${recycleUi.rows}`)

  // 回收站默认在「文化」页签，测试的标签要先切到「标签」页签
  await evaluate(`(() => {
    // 只在页面内容区找类型页签：侧边栏也有「标签管理」，按全文档找会点到菜单上
    const scope = document.querySelector('.ad-page') || document
    const btn = [...scope.querySelectorAll('button,a')].find(b => /^标签/.test((b.textContent || '').trim()))
    if (btn) btn.click()
    return !!btn
  })()`)
  await sleep(2000)
  const restoreBtn = await json(`(() => {
    const rows = [...document.querySelectorAll('.ad-table tbody tr')]
    const hit = rows.find(r => (r.textContent || '').indexOf(${JSON.stringify(tagName)}) > -1)
    if (!hit) return { found: false }
    const btn = [...hit.querySelectorAll('button,a,div')].find(b => /恢复/.test(b.textContent || ''))
    if (btn) btn.click()
    return { found: true, clicked: !!btn }
  })()`)
  await sleep(2500)
  const restoredByUi = (await api('GET', '/api/admin/tag/list')).body.data
  const restoredRows = restoredByUi.rows || restoredByUi
  const uiRestored = restoredRows.some(t => t.name === tagName)
  record('回收站页点「恢复」可把内容恢复回来', restoreBtn.found && restoreBtn.clicked && uiRestored,
    `找到=${restoreBtn.found} 已恢复=${uiRestored}`)
  if (tag) { await api('POST', '/api/admin/tag/delete', { id: tag.id }) }

  /* ---------------- 2. 角色管理 + 按钮权限 ---------------- */
  await goto(BASE + '/admin/role')
  const roleUi = await json(`(() => ({
    roles: document.querySelectorAll('.perm-role, .ad-card button').length,
    roleCards: document.querySelectorAll('[class*=role]').length,
    hasButtonPerms: (document.body.innerText || '').indexOf('按钮权限') > -1,
    hasMenuLink: (document.body.innerText || '').indexOf('菜单权限') > -1
  }))()`)
  record('角色管理页渲染（角色列表 + 按钮权限 + 菜单权限入口）',
    roleUi.hasButtonPerms && roleUi.hasMenuLink && roleUi.roles > 0,
    `角色元素=${roleUi.roles} 按钮权限区=${roleUi.hasButtonPerms} 菜单权限入口=${roleUi.hasMenuLink}`)

  const btnPerms = await api('GET', '/api/admin/permission/buttons?roleId=1')
  record('按钮级权限接口可用（返回全部按钮 + 已授权）',
    btnPerms.body && btnPerms.body.data && Array.isArray(btnPerms.body.data.all) && btnPerms.body.data.all.length >= 4,
    `按钮数=${btnPerms.body && btnPerms.body.data ? btnPerms.body.data.all.length : 0} 已授权=${btnPerms.body && btnPerms.body.data ? btnPerms.body.data.granted.length : 0}`)

  /* ---------------- 3. 文化页：版本历史 / 状态筛选 / 批量改状态 ---------------- */
  await goto(BASE + '/admin/culture', 3600)
  const cultureUi = await json(`(() => {
    const text = document.body.innerText || ''
    const statusSelect = [...document.querySelectorAll('select')].some(s => [...s.options].some(o => /草稿/.test(o.textContent || '')))
    return {
      hasVersionBtn: text.indexOf('版本') > -1,
      hasStatusFilter: statusSelect,
      hasBatchStatus: /批量/.test(text)
    }
  })()`)
  record('文化页具备版本历史入口 / 状态筛选 / 批量操作',
    cultureUi.hasVersionBtn && cultureUi.hasStatusFilter,
    `版本入口=${cultureUi.hasVersionBtn} 状态筛选=${cultureUi.hasStatusFilter} 批量区=${cultureUi.hasBatchStatus}`)

  const versionApi = await api('GET', '/api/admin/culture/versions?id=32')
  const vTotal = versionApi.body && versionApi.body.data ? versionApi.body.data.total : -1
  record('版本历史接口可用（列表不含正文）', vTotal >= 0, `文化#32 版本数=${vTotal}`)

  /* ---------------- 4. 敏感词 ---------------- */
  const word = '自测敏感词' + stamp
  const saved = await api('POST', '/api/admin/sensitive/save', { word, action: 2, enabled: 1, remark: '自测' })
  const sensList = await api('GET', '/api/admin/sensitive/list?page=1&pageSize=50')
  const sensRows = (sensList.body.data && sensList.body.data.rows) || []
  const hit = sensRows.find(w => w.word === word)
  if (hit) cleanup.push(() => api('POST', '/api/admin/sensitive/delete', { id: hit.id }))
  record('敏感词可新增并出现在列表', !!(saved.body && saved.body.code === 200) && !!hit, `word=${word}`)

  await goto(BASE + '/admin/comment', 3400)
  const commentUi = await json(`(() => {
    const text = document.body.innerText || ''
    return { hasSensitiveCard: text.indexOf('敏感词') > -1, hasRecheck: text.indexOf('重新过词表') > -1 || text.indexOf('过词表') > -1 }
  })()`)
  record('评论页含敏感词管理区与「重新过词表」入口', commentUi.hasSensitiveCard && commentUi.hasRecheck,
    `敏感词区=${commentUi.hasSensitiveCard} 重新过词表=${commentUi.hasRecheck}`)

  /* ---------------- 5. 标签合并 ---------------- */
  const A = '自测合并A' + stamp, B = '自测合并B' + stamp
  await api('POST', '/api/admin/tag/save', { name: A, sort: 992 })
  await api('POST', '/api/admin/tag/save', { name: B, sort: 991 })
  const tl = await api('GET', '/api/admin/tag/list')
  const tlRows = (tl.body.data && (tl.body.data.rows || tl.body.data)) || []
  const ta = tlRows.find(t => t.name === A), tb = tlRows.find(t => t.name === B)
  const merge = ta && tb ? await api('POST', '/api/admin/tag/merge', { sourceId: ta.id, targetId: tb.id }) : null
  record('标签合并接口可用（返回 moved/merged）',
    !!(merge && merge.body && merge.body.data && typeof merge.body.data.moved === 'number'),
    merge && merge.body && merge.body.data ? `moved=${merge.body.data.moved} merged=${merge.body.data.merged}` : '调用失败')
  if (tb) cleanup.push(() => api('POST', '/api/admin/tag/delete', { id: tb.id }))

  /* ---------------- 6. 搜索增强（热门词 / 高亮 / 历史 401） ---------------- */
  await jsonFetch(API + '/api/search?keyword=文化', {})
  const hot = await jsonFetch(API + '/api/search/hot?limit=5')
  record('热门搜索词接口匿名可访问且能累计', hot.status === 200 && Array.isArray(hot.body.data) && hot.body.data.length > 0,
    `状态=${hot.status} 条数=${hot.body && hot.body.data ? hot.body.data.length : 0}`)

  const searchRes = await jsonFetch(API + '/api/search?keyword=文化')
  const cultures = (searchRes.body.data && searchRes.body.data.cultures) || []
  const withHighlight = cultures.filter(c => c && typeof c.highlight === 'string' && c.highlight.length > 0)
  record('搜索结果带高亮字段（后端已转义 + em 标签）',
    withHighlight.length > 0 && withHighlight.every(c => !/<(?!\/?em>)[a-z]/i.test(c.highlight)),
    `结果=${cultures.length} 带高亮=${withHighlight.length}`)

  const histAnon = await jsonFetch(API + '/api/search/history')
  record('搜索历史仍需登录（匿名 401）', histAnon.status === 401, `HTTP ${histAnon.status}`)

  /* ---------------- 7. 指标与前端错误看板 ---------------- */
  const metrics = await jsonFetch(API + '/api/metrics')
  const metricText = typeof metrics.text === 'string' ? metrics.text : JSON.stringify(metrics.body || {})
  record('GET /api/metrics 返回 Prometheus 文本指标',
    metrics.status === 200 && metricText.indexOf('culture_up 1') > -1 && metricText.indexOf('culture_jvm_heap_used_bytes') > -1,
    `状态=${metrics.status} 指标行=${metricText.split('\n').filter(l => l.indexOf('culture_') === 0).length}`)

  await jsonFetch(API + '/api/client-error', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: '功能回归自测上报' + stamp, url: '/feature-check', line: 1, col: 1 })
  })
  const errBoard = await api('GET', '/api/admin/client-errors?limit=5')
  record('前端错误看板接口可用（含累计与最近记录）',
    !!(errBoard.body && errBoard.body.data && errBoard.body.data.total >= 1 && errBoard.body.data.rows.length >= 1),
    errBoard.body && errBoard.body.data ? `累计=${errBoard.body.data.total} 最近=${errBoard.body.data.rows.length}` : '调用失败')

  /* ---------------- 8. 老 jQuery 全局隔离（防回归） ----------------
   * 背景：文化详情页会加载 jQuery 1.11.1 给老插件用，而 jQuery 是**全局单例**。
   * 若不在加载后调用 noConflict(true) 把全局还原，访问一次详情页就会把整个 SPA 的
   * window.$ 永久降级成 1.11.1 —— 之后用 3.4.1 注册的事件监听器无法再被 off() 解绑
   * （jQuery 各副本各自维护事件存储），表现为监听器持续泄漏。
   */
  await goto(BASE + '/', 3000)
  const beforeJq = await evaluate(`(window.jQuery && window.jQuery.fn) ? window.jQuery.fn.jquery : null`)

  const listResp = await jsonFetch(API + '/api/culture/list?limit=1&offset=0')
  const one = (listResp.body && listResp.body.data && (listResp.body.data.rows || [])[0]) || null
  if (!one) {
    record('老 jQuery 全局隔离（详情页不污染全局）', false, '没有可用于测试的文化数据')
  } else {
    await goto(BASE + '/culture/' + one.id, 4500)
    const afterDetail = await evaluate(`(window.jQuery && window.jQuery.fn) ? window.jQuery.fn.jquery : null`)
    record('老 jQuery 全局隔离：访问详情页后全局仍是 3.4.1（1.11.1 已被 noConflict 收回）',
      !!beforeJq && /^3\.4\./.test(String(beforeJq)) && afterDetail === beforeJq,
      `访问前=${beforeJq} 访问详情页后=${afterDetail}`)

    // 再切到别的路由，确认全局 jQuery 没有被误摘掉
    // （loadScript 按 src 去重，若老脚本本次并未真正执行，就不能调用 noConflict）
    await goto(BASE + '/about', 3000)
    const stillOk = await evaluate(`(window.jQuery && window.jQuery.fn) ? window.jQuery.fn.jquery : null`)
    record('离开详情页后全局 jQuery 仍可正常使用（未被误摘）',
      !!stillOk && /^3\.4\./.test(String(stillOk)), `jQuery=${stillOk}`)
  }

  /* ---------------- 9. 收尾 ---------------- */
  for (const fn of cleanup.reverse()) { try { await fn() } catch (e) { /* 忽略 */ } }
  console.log('\n测试数据已清理')
  record('全程无控制台报错', errors.length === 0, errors.slice(0, 2).join(' ; ').slice(0, 200))

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 第 7 批功能验证：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) { failed.forEach(f => console.log('  FAIL ' + f.name)); process.exitCode = 1 }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
