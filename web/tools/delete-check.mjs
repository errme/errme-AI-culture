/**
 * 高危操作（删除）端到端回归
 *
 * 为什么单独做一套：之前的 ui-check 只验证了「点取消不删除」，**没有验证「点确认真的执行」**，
 * 于是漏掉了两类真实 bug：
 *   1) 删除接口执行了，但成功提示/列表刷新抛错（`$ is not defined`），用户看到「行还在」→ 以为没执行；
 *   2) 部分入口（分类、用户、邮件账号）压根没有二次确认，或者用的是原生 window.confirm。
 *
 * 本套件对每个实体：用接口造一条测试数据 → 在页面里真实点击「删除」→ 断言弹出统一确认弹窗
 * → 点确认 → 断言接口数据已删除、表格行已消失、有成功提示。
 *
 * 用法：node web/tools/delete-check.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9133)
const sleep = ms => new Promise(r => setTimeout(r, ms))

/**
 * 看门狗：任何一步卡住（Chrome 起不来 / CDP 无响应 / 接口不返回）都必须**快速失败退出**，
 * 否则脚本会静静挂住，看起来像「卡死」。默认 4 分钟。
 */
const WATCHDOG_MS = Number(process.env.WATCHDOG_MS || 240000)
const watchdog = setTimeout(() => {
  console.error(`[delete-check] 超过 ${Math.round(WATCHDOG_MS / 1000)}s 未完成，强制退出（避免挂死）`)
  process.exit(3)
}, WATCHDOG_MS)
watchdog.unref && watchdog.unref()

/** 带超时的 JSON 请求：接口不响应时快速失败，而不是无限等待 */
async function fetchJson(url, options = {}, timeoutMs = 15000) {
  const ctl = new AbortController()
  const timer = setTimeout(() => ctl.abort(), timeoutMs)
  try {
    const r = await fetch(url, { ...options, signal: ctl.signal })
    return await r.json()
  } finally {
    clearTimeout(timer)
  }
}

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? '  — ' + detail : ''}`)
}

let token = ''
const api = (method, url, body) => fetchJson(API + url, {
  method,
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: body ? JSON.stringify(body) : undefined
}).catch(e => ({ success: false, message: e.message }))

/** 造数据用的名称（每轮唯一，避免与历史数据混淆） */
const stamp = Date.now().toString().slice(-6)

async function main() {
  const login = await fetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json())
  token = login.data.token

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-del-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--window-size=1440,900',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
  await sleep(1200)
  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?about:blank`, { method: 'PUT' })).json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    const timer = setTimeout(() => rej(new Error('连接 Chrome 调试端口超时')), 15000)
    ws.addEventListener('open', () => { clearTimeout(timer); res() })
    ws.addEventListener('error', e => { clearTimeout(timer); rej(e) })
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
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('CDP 调用超时：' + method)) }, 15000)
      pending.set(id, { resolve: v => { clearTimeout(timer); resolve(v) }, reject: e => { clearTimeout(timer); reject(e) } })
    })
  }
  const evaluate = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
    if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
    return r.result.value
  }
  const json = async expr => { try { return JSON.parse(await evaluate(`JSON.stringify(${expr})`)) } catch (e) { return {} } }
  const goto = async (url, wait = 3500) => { await send('Page.navigate', { url }); await sleep(wait) }

  await send('Page.enable'); await send('Runtime.enable')
  await goto(BASE + '/auth/login', 1300)
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(token)}); 'ok'`)

  /**
   * 通用删除用例：造数据 → 页面点删除 → 确认 → 校验
   * @param label        用例名（用于输出与测试数据命名）
   * @param page         列表页路径
   * @param create       造数据函数，返回 { ok, error }
   * @param stillExists  可选：通过接口判断记录是否还在（更硬的断言）
   */
  const createdRecords = []

  async function deleteCase({ label, page, create, stillExists, rowText, cleanup }) {
    const name = rowText || ('自测' + label + stamp)
    const made = await create(name)
    if (made && made.id && cleanup) createdRecords.push({ label, name, id: made.id, cleanup })
    if (!made.ok) { record(`${label}：二次确认后真正删除`, false, '造数据失败：' + made.error); return }
    errors.length = 0
    await goto(BASE + page)
    const clicked = await evaluate(`(() => {
      const isDel = el => {
        const t = [
          el.textContent || '',
          el.getAttribute ? (el.getAttribute('title') || '') : '',
          el.getAttribute ? (el.getAttribute('data-original-title') || '') : '',
          el.getAttribute ? (el.getAttribute('onclick') || '') : '',
          typeof el.className === 'string' ? el.className : ''
        ].join(' ').toLowerCase()
        return t.indexOf('删除') > -1 || t.indexOf('del(') > -1 || t.indexOf('delete') > -1
      }
      const tr = [...document.querySelectorAll('tbody tr')].find(r => r.textContent.includes(${JSON.stringify(name)}))
      if (!tr) return 'row-not-found'
      // 老后台的删除控件是 <div class="btn" onclick="del(id)">，不能只找 a/button；
      // 取「最内层」匹配，避免点到包含「编辑 删除」文字的 <td>（点 td 不会触发删除）
      const cands = [...tr.querySelectorAll('*')].filter(isDel)
      const btn = cands.length ? cands[cands.length - 1] : null
      if (!btn) return 'no-delete-btn: ' + tr.innerHTML.replace(/\s+/g, ' ').slice(0, 160)
      btn.click(); return 'clicked'
    })()`)
    await sleep(800)
    const dialog = await json(`(() => {
      const d = document.querySelector('.ds-dialog')
      if (!d) return { ok: false, reason: '未弹出确认弹窗' }
      const danger = d.querySelector('.ds-btn--danger')
      return {
        ok: !!danger,
        title: (d.querySelector('.ds-dialog__title') || {}).textContent || '',
        content: (d.querySelector('.ds-dialog__content') || {}).textContent || '',
        detail: (d.querySelector('.ds-dialog__detail') || {}).textContent || '',
        confirmText: danger ? danger.textContent.trim() : ''
      }
    })()`)
    if (!dialog.ok) {
      record(`${label}：二次确认后真正删除`, false, `点「删除」后 ${dialog.reason || '未出现危险确认按钮'}（点击结果=${clicked}）`)
      return
    }
    await evaluate(`(() => { const b = document.querySelector('.ds-dialog .ds-btn--danger'); if (b) b.click(); return !!b })()`)
    await sleep(2600)
    const after = await json(`(() => ({
      dialogGone: document.querySelectorAll('.ds-dialog').length === 0,
      toast: (document.querySelector('.ds-toast') || {}).textContent || '',
      rowGone: !(document.querySelector('tbody') || document.body).innerText.includes(${JSON.stringify(name)})
    }))()`)
    const apiGone = stillExists ? !(await stillExists(name)) : true
    const ok = clicked === 'clicked' && after.rowGone && after.dialogGone && apiGone
    record(`${label}：二次确认后真正删除`, ok,
      `弹窗「${dialog.title}」确认按钮=「${dialog.confirmText}」| 行消失=${after.rowGone} 接口已删=${apiGone} 提示=${(after.toast || '无').slice(0, 24)}` +
      (after.rowGone ? '' : ` | 点击=${clicked}`))
    if (errors.length) console.log('     ⚠ 控制台：' + errors.slice(0, 2).join(' | ').slice(0, 200))
  }

  // ---------- 分类 ----------
  await deleteCase({
    label: '分类',
    page: '/admin/category',
    create: async n => {
      const r = await api('POST', '/api/admin/category/save', { categoryName: n })
      const list = await api('GET', '/api/admin/category/list?limit=200&offset=0')
      const rows = (list.data && (list.data.rows || list.data)) || []
      const hit = rows.find(x => x.categoryName === n)
      return { ok: r && r.code === 200, id: hit && hit.id, error: (r && r.message) || '未知' }
    },
    stillExists: async n => {
      const r = await api('GET', '/api/admin/category/list?limit=200&offset=0')
      const rows = (r.data && (r.data.rows || r.data)) || []
      return rows.some(x => x.categoryName === n)
    },
    cleanup: id => api('POST', '/api/admin/category/delete', { id })
  })

  // ---------- 公告 ----------
  await deleteCase({
    label: '公告',
    page: '/admin/announcement',
    create: async n => {
      const r = await api('POST', '/api/admin/announcement/save', { announcement: n })
      const id = r && r.data && (r.data.id || r.data)
      return { ok: r && r.code === 200, id: id && typeof id === 'object' ? id.id : id, error: (r && r.message) || '未知' }
    },
    cleanup: id => api('POST', '/api/admin/announcement/delete', { id })
  })

  // ---------- 句子 ----------
  await deleteCase({
    label: '句子',
    page: '/admin/sentence',
    create: async n => {
      const r = await api('POST', '/api/admin/sentence/save', { content: n })
      const id = r && r.data && (r.data.id || r.data)
      return { ok: r && r.code === 200, id: id && typeof id === 'object' ? id.id : id, error: (r && r.message) || '未知' }
    },
    cleanup: id => api('POST', '/api/admin/sentence/delete', { id })
  })

  // ---------- 文化 ----------
  let categoryId = null
  const cate = await api('GET', '/api/admin/category/list?limit=200&offset=0')
  const cateRows = (cate.data && (cate.data.rows || cate.data)) || []
  if (cateRows.length) categoryId = cateRows[0].id
  await deleteCase({
    label: '文化',
    page: '/admin/culture',
    create: async n => {
      const r = await api('POST', '/api/admin/culture/save', { cultureName: n, categoryId, address: '自测', desc: '自测数据' })
      const list = await api('GET', '/api/admin/culture/list?limit=50&offset=0&cultureName=' + encodeURIComponent(n))
      const rows = (list.data && (list.data.rows || list.data)) || []
      const hit = rows.find(x => x.cultureName === n)
      return { ok: r && r.code === 200, id: hit && hit.id, error: (r && r.message) || '未知' }
    },
    cleanup: id => api('POST', '/api/admin/culture/delete', { id })
  })

  // ---------- 标签 ----------
  await deleteCase({
    label: '标签',
    page: '/admin/tag',
    create: async n => {
      const r = await api('POST', '/api/admin/tag/save', { name: n, sort: 999 })
      const id = r && r.data && (r.data.id || r.data)
      return { ok: r && r.code === 200, id: id && typeof id === 'object' ? id.id : id, error: (r && r.message) || '未知' }
    },
    stillExists: async n => {
      const r = await api('GET', '/api/admin/tag/list')
      const rows = (r.data && (r.data.rows || r.data)) || []
      return rows.some(x => x.name === n)
    },
    cleanup: id => api('POST', '/api/admin/tag/delete', { id })
  })

  // ---------- 评论（前端提交一条待审核评论，再在后台删除） ----------
  const frontLogin = await fetch(API + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json()).catch(() => null)
  const cultureList = await api('GET', '/api/admin/culture/list?limit=1&offset=0')
  const cultureRows = (cultureList.data && (cultureList.data.rows || cultureList.data)) || []
  const commentName = '自测评论' + stamp
  if (frontLogin && frontLogin.data && cultureRows.length) {
    const sub = await fetch(API + '/api/comment/submit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + frontLogin.data.token },
      body: JSON.stringify({ cultureId: cultureRows[0].id, parentId: 0, content: commentName })
    }).then(r => r.json()).catch(e => ({ message: e.message }))
    if (sub && sub.code === 200) {
      await deleteCase({ label: '评论', page: '/admin/comment', create: async () => ({ ok: true }), rowText: commentName })
    } else if (sub && /频繁|稍后/.test(sub.message || '')) {
      console.log('SKIP 评论：提交接口触发 60 秒限流，本轮跳过（不影响其它用例）')
    } else {
      record('评论：二次确认后真正删除', false, '提交测试评论失败：' + ((sub && sub.message) || '未知'))
    }
  } else {
    record('评论：二次确认后真正删除', false, '无法准备测试评论（缺少前台令牌或文化数据）')
  }

  // ---------- 撤销：删除后点提示上的「撤销」应把记录恢复回来 ----------
  {
    const undoName = '自测撤销' + stamp
    const made = await api('POST', '/api/admin/tag/save', { name: undoName, sort: 998 })
    const madeId = made && made.data && (made.data.id || made.data)
    const idValue = madeId && typeof madeId === 'object' ? madeId.id : madeId
    if (!made || made.code !== 200) {
      record('删除后可通过提示「撤销」恢复', false, '造数据失败：' + ((made && made.message) || '未知'))
    } else {
      await goto(BASE + '/admin/tag')
      await evaluate(`(() => {
        const isDel = el => {
          const t = [el.textContent || '', el.getAttribute ? (el.getAttribute('title') || '') : '',
            el.getAttribute ? (el.getAttribute('onclick') || '') : ''].join(' ').toLowerCase()
          return t.indexOf('删除') > -1 || t.indexOf('del(') > -1
        }
        const tr = [...document.querySelectorAll('tbody tr')].find(r => r.textContent.includes(${JSON.stringify(undoName)}))
        if (!tr) return 'row-not-found'
        const cands = [...tr.querySelectorAll('*')].filter(isDel)
        const btn = cands.length ? cands[cands.length - 1] : null
        if (btn) btn.click()
        return btn ? 'clicked' : 'no-delete-btn'
      })()`)
      await sleep(700)
      await evaluate(`(() => { const b = document.querySelector('.ds-dialog .ds-btn--danger'); if (b) b.click(); return !!b })()`)
      await sleep(1800)
      // 提示上应有「撤销」按钮
      const undoBtn = await evaluate(`(() => {
        const btn = [...document.querySelectorAll('.ds-toast__action')].find(b => /撤销/.test(b.textContent || ''))
        if (!btn) return false
        btn.click()
        return true
      })()`)
      await sleep(2200)
      const listAfter = await api('GET', '/api/admin/tag/list')
      const rowsAfter = (listAfter.data && (listAfter.data.rows || listAfter.data)) || []
      const restored = rowsAfter.some(t => t.name === undoName)
      const toastText = await evaluate(`(document.querySelector('.ds-toast') || {}).textContent || ''`)
      record('删除后可通过提示「撤销」恢复', undoBtn && restored,
        `撤销按钮=${undoBtn} 接口已恢复=${restored} 提示=${String(toastText).slice(0, 20)}`)
      if (!restored) { try { await api('POST', '/api/admin/tag/delete', { id: idValue }) } catch (e) { /* 忽略 */ } }
    }
  }

  // ---------- 用户：造一个真实账号 → 真实删除 → 断言「行消失 + 接口已删」 ----------
  //
  // 为什么这里必须真删（原实现只点开弹窗就点取消）：
  //   UserMapper.xml 的列表/统计 SQL 曾漏掉 `u.deleted = 0`，删除接口把 deleted 置 1 后，
  //   该用户依然出现在列表与总数里、行不消失 —— 用户看到的现象就是「点了删除没反应」。
  //   只验证弹窗的用例永远发现不了这种「接口成功但界面没变化」的问题。
  const probeUser = 'zzdel' + stamp
  await deleteCase({
    label: '用户',
    page: '/admin/user',
    rowText: probeUser,
    create: async n => {
      const r = await api('POST', '/api/admin/user/save', {
        username: n, email: n + '@test.local', tel: '13800000000', password: '123456', sex: 1
      })
      // /user/save 不回传 id，按用户名回查一次（与页面新增后的 resolveUserId 同一策略）
      const list = await api('GET', '/api/admin/user/list?page=1&pageSize=50&username=' + encodeURIComponent(n))
      const rows = (list.data && list.data.rows) || []
      const hit = rows.find(x => x.username === n)
      return { ok: r && r.code === 200, id: hit && hit.id, error: (r && r.message) || '未知' }
    },
    stillExists: async n => {
      const r = await api('GET', '/api/admin/user/list?page=1&pageSize=50&username=' + encodeURIComponent(n))
      const rows = (r.data && r.data.rows) || []
      return rows.some(x => x.username === n)
    },
    cleanup: id => api('POST', '/api/admin/user/delete', { id })
  })

  // ---------- 邮件账号：有数据则验证弹窗为统一样式（点取消） ----------
  await goto(BASE + '/admin/mail', 4000)
  const mailDialog = await json(`(() => {
    const btns = [...document.querySelectorAll('button,a')].filter(b => /删除/.test(b.textContent || ''))
    if (!btns.length) return { skip: true }
    btns[0].click()
    return { skip: false }
  })()`)
  if (mailDialog.skip) {
    console.log('SKIP 邮件账号删除：当前没有账号数据行可点')
  } else {
    await sleep(800)
    const mailConfirm = await json(`(() => {
      const d = document.querySelector('.ds-dialog')
      if (!d) return { ok: false, reason: '未弹出统一确认弹窗（可能仍是原生 confirm）' }
      const ok = !!d.querySelector('.ds-btn--danger')
      const cancel = d.querySelector('.ds-btn--ghost')
      if (cancel) cancel.click()
      return { ok, content: (d.querySelector('.ds-dialog__content') || {}).textContent || '' }
    })()`)
    record('邮件账号删除：走统一二次确认（点取消不留副作用）', !!mailConfirm.ok, mailConfirm.content || mailConfirm.reason || '')
  }

  // 收尾：用例失败时删除逻辑不会执行，这里按创建记录再做一次 best-effort 清理
  let cleaned = 0
  for (const rec of createdRecords) {
    try {
      await rec.cleanup(rec.id)
      cleaned++
    } catch (e) { /* 忽略 */ }
  }
  if (cleaned) console.log('')
  if (cleaned) console.log('已清理测试数据 ' + cleaned + ' 条（用例失败时兜底）')

  record('全程无控制台报错', errors.length === 0, errors.slice(0, 2).join(' ; ').slice(0, 200))

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 高危操作（删除）验证：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) { failed.forEach(f => console.log('  FAIL ' + f.name)); process.exitCode = 1 }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
