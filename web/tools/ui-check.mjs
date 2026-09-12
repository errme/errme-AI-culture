/**
 * 全局提示 / 二次确认弹窗 端到端验证
 *
 * 覆盖：
 *   1) 顶部居中轻提示（类似 platform.deepseek.com）：位置、自动消失、可堆叠
 *   2) 老代码 `$.confirm` 兼容层：单按钮（告知）→ 顶部提示；双按钮 → 新确认弹窗
 *   3) 破坏性确认重设计：红色主按钮、写明影响、默认聚焦「取消」、Esc 取消
 *   4) 真实删除流程点「取消」不产生副作用
 *
 * 用法：node web/tools/ui-check.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = process.argv[2] || 'http://localhost:8080'
const API = process.env.API_URL || 'http://localhost:8081'
const PORT = Number(process.env.CDP_PORT || 9121)
const sleep = ms => new Promise(r => setTimeout(r, ms))

const results = []
function record(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? '  — ' + detail : ''}`)
}

async function main() {
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'culture-ui-'))
  const child = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--window-size=1440,900',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' })
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
  const evaluate = async expr => {
    try {
      const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
      if (r.exceptionDetails) return 'JS异常: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)
      return r.result.value
    } catch (e) { return '调用失败: ' + e.message }
  }
  const json = async expr => {
    const v = await evaluate(`JSON.stringify(${expr})`)
    try { return JSON.parse(v) } catch (e) { return { parseError: String(v) } }
  }

  await send('Page.enable'); await send('Runtime.enable')
  await send('Page.navigate', { url: BASE + '/auth/login' })
  await sleep(1500)
  const login = await fetch(API + '/api/auth/admin/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@qq.com', password: '123456' })
  }).then(r => r.json())
  await evaluate(`localStorage.setItem('culture_admin_token', ${JSON.stringify(login.data.token)}); 'ok'`)

  // 用标签管理页（有真实删除按钮）做验证
  await send('Page.navigate', { url: BASE + '/admin/tag' })
  await sleep(4000)

  // ---------- 1. 顶部提示：位置居中、在最上方 ----------
  await evaluate(`window.__dshNotify.toast.success('保存成功')`)
  await sleep(400)
  const t1 = await json(`(() => {
    const el = document.querySelector('.ds-toast')
    if (!el) return null
    const r = el.getBoundingClientRect()
    return { text: el.textContent.trim(), top: Math.round(r.top), centerX: Math.round(r.left + r.width / 2), viewportCenter: Math.round(window.innerWidth / 2), z: getComputedStyle(el.parentElement).zIndex }
  })()`)
  record('顶部提示出现在页面顶部居中', !!t1 && t1.top < 60 && Math.abs(t1.centerX - t1.viewportCenter) <= 2,
    t1 ? `top=${t1.top} center=${t1.centerX}/${t1.viewportCenter} 文案=${t1.text}` : '未出现提示')

  // ---------- 2. 自动消失 ----------
  await sleep(3200)
  const gone = await evaluate(`document.querySelectorAll('.ds-toast').length`)
  record('成功提示会自动消失（不打断操作）', gone === 0, `剩余提示=${gone}`)

  // ---------- 3. 老 $.confirm 单按钮 → 顶部提示（而不是模态） ----------
  await evaluate(`window.jQuery.confirm({ title: '温馨提示', content: '操作成功', type: 'green', buttons: { omg: { text: '谢谢', btnClass: 'btn-green' } } })`)
  await sleep(400)
  const legacy = await json(`(() => ({
    toast: document.querySelectorAll('.ds-toast').length,
    dialog: document.querySelectorAll('.ds-dialog').length,
    text: (document.querySelector('.ds-toast') || {}).textContent || ''
  }))()`)
  record('老代码单按钮 $.confirm 自动变成顶部提示（不再弹模态）',
    legacy.toast === 1 && legacy.dialog === 0, `toast=${legacy.toast} dialog=${legacy.dialog} 文案=${(legacy.text || '').trim()}`)
  await evaluate(`window.__dshNotify.toast.clear()`)

  // ---------- 4. 老 $.confirm 双按钮 → 新确认弹窗 ----------
  await evaluate(`void window.__dshNotify.confirmDialog({ title: '确认操作', content: '确定要执行吗？', confirmText: '确定' }).then(v => { window.__uiResult = v }); true`)
  await sleep(400)
  const dlg = await json(`(() => {
    const d = document.querySelector('.ds-dialog')
    return { exists: !!d, title: d ? d.querySelector('.ds-dialog__title').textContent.trim() : '', buttons: d ? [...d.querySelectorAll('.ds-btn')].map(b => b.textContent.trim()) : [] }
  })()`)
  record('双按钮确认走新的确认弹窗', dlg.exists && dlg.buttons.length === 2, `${dlg.title} 按钮=[${dlg.buttons.join('/')}]`)
  // Esc 关闭 → Promise 解析为 false
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 })
  await sleep(400)
  const escRes = await json(`(() => ({ closed: !document.querySelector('.ds-dialog'), result: window.__uiResult }))()`)
  record('Esc 可取消确认，且返回 false', escRes.closed && escRes.result === false, `closed=${escRes.closed} result=${escRes.result}`)

  // ---------- 5. 删除标签：红色主按钮 + 影响说明 + 默认聚焦取消 ----------
  const rowCount = `document.querySelectorAll('table tbody tr').length`
  const before = await evaluate(rowCount)
  const clicked = await evaluate(`(() => {
    const btn = [...document.querySelectorAll('table tbody tr button, table tbody tr a')].find(el => /删除/.test(el.textContent || ''))
    if (!btn) return false
    btn.click()
    return true
  })()`)
  await sleep(600)
  const danger = await json(`(() => {
    const d = document.querySelector('.ds-dialog')
    if (!d) return { exists: false }
    const confirmBtn = d.querySelector('.ds-btn--danger')
    return {
      exists: true,
      danger: !!confirmBtn,
      confirmText: confirmBtn ? confirmBtn.textContent.trim() : '',
      content: d.querySelector('.ds-dialog__content').textContent.trim(),
      detail: d.querySelector('.ds-dialog__detail') ? d.querySelector('.ds-dialog__detail').textContent.trim() : '',
      focused: document.activeElement ? document.activeElement.textContent.trim() : '',
      cancelFirst: document.activeElement === d.querySelector('.ds-btn--ghost')
    }
  })()`)
  record('删除确认已重设计（红色主按钮 + 影响说明 + 默认聚焦取消）',
    clicked && danger.exists && danger.danger && danger.cancelFirst && !!danger.detail,
    danger.exists ? `主按钮=${danger.confirmText} 聚焦=${danger.focused} 说明=${(danger.detail || '').slice(0, 30)}` : '未弹出确认框')

  // ---------- 6. 取消不产生副作用 ----------
  await evaluate(`document.querySelector('.ds-dialog .ds-btn--ghost').click()`)
  await sleep(600)
  const after = await evaluate(rowCount)
  const stillOpen = await evaluate(`document.querySelectorAll('.ds-dialog').length`)
  record('点「取消」关闭弹窗且不删除数据', stillOpen === 0 && before === after, `弹窗=${stillOpen} 元素数 ${before}→${after}`)

  // ---------- 7. 批量操作 UI（标签页：全选 → 按钮可用 → 取消 → 禁用） ----------
  await send('Page.navigate', { url: BASE + '/admin/tag' })
  await sleep(3500)
  const batchUi = await json(`(() => {
    const head = document.querySelector('thead input[type=checkbox]')
    const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /批量删除/.test(b.textContent || ''))
    const before = btn ? btn.disabled : null
    if (head) { head.checked = true; head.dispatchEvent(new Event('change', { bubbles: true })) }
    return { hasHeadCheckbox: !!head, hasBatchBtn: !!btn, disabledBefore: before }
  })()`)
  await sleep(300)
  const afterCheck = await json(`(() => {
    const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /批量删除/.test(b.textContent || ''))
    const tip = [...document.querySelectorAll('.card-toolbar span')].map(x => x.textContent.trim()).join('|')
    const rowChecks = document.querySelectorAll('tbody input[type=checkbox]:checked').length
    return { disabledAfter: btn ? btn.disabled : null, tip, rowChecks }
  })()`)
  record('批量操作 UI：勾选列存在且全选后按钮可用',
    batchUi.hasHeadCheckbox && batchUi.hasBatchBtn && batchUi.disabledBefore === true &&
    afterCheck.disabledAfter === false && afterCheck.rowChecks > 0,
    `全选前禁用=${batchUi.disabledBefore} 全选后禁用=${afterCheck.disabledAfter} 已勾选行=${afterCheck.rowChecks} ${afterCheck.tip}`)

  await evaluate(`(() => { const h = document.querySelector('thead input[type=checkbox]'); if (h) { h.checked = false; h.dispatchEvent(new Event('change', { bubbles: true })) } return true })()`)
  await sleep(300)
  const afterUncheck = await json(`(() => {
    const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /批量删除/.test(b.textContent || ''))
    return { disabled: btn ? btn.disabled : null, checked: document.querySelectorAll('tbody input[type=checkbox]:checked').length }
  })()`)
  record('批量操作 UI：取消全选后按钮回到禁用（防误操作）',
    afterUncheck.disabled === true && afterUncheck.checked === 0,
    `禁用=${afterUncheck.disabled} 勾选=${afterUncheck.checked}`)

  // ---------- 8. CSV 导出（文化页工具栏，走带鉴权的 blob 下载） ----------
  await send('Page.navigate', { url: BASE + '/admin/culture' })
  await sleep(4000)
  const csvClick = await evaluate(`(() => {
    const btn = [...document.querySelectorAll('.toolbar-btn-action button')].find(b => /导出 CSV/.test(b.textContent || ''))
    if (btn) btn.click()
    return !!btn
  })()`)
  await sleep(2500)
  const csvToast = await json(`(() => {
    const toast = document.querySelector('.ds-toast')
    return { text: toast ? toast.textContent.trim() : '', hasTableCheckbox: !!document.querySelector('#cultureTable th.bs-checkbox input, #cultureTable thead input[type=checkbox]') }
  })()`)
  record('文化页可导出 CSV（带鉴权下载并给出顶部提示）',
    csvClick && /CSV/.test(csvToast.text), `提示=${csvToast.text || '（无）'}`)
  record('文化表格具备批量勾选列（bootstrap-table checkbox）', csvToast.hasTableCheckbox, `checkbox 列=${csvToast.hasTableCheckbox}`)

  // ---------- 9. 菜单权限设置页（按角色勾选菜单 + 防自锁）----------
  // 注意：这一节会真实读写权限，因此必须「自还原」——结束时把角色的菜单集恢复成进入前的样子，
  //       否则会把「菜单权限」菜单从管理员角色里取消掉（等于关掉自己的入口）。
  await send('Page.navigate', { url: BASE + '/admin/permission' })
  await sleep(3500)
  const permPage = await json(`(() => ({
    roles: document.querySelectorAll('.perm-role').length,
    groups: document.querySelectorAll('.perm-group').length,
    items: document.querySelectorAll('.perm-item').length,
    saveBtn: !!document.querySelector('.card-toolbar .btn-primary'),
    original: [...document.querySelectorAll('.perm-item input')].filter(i => i.checked).map(i => Number(i.value))
  }))()`)
  record('菜单权限页渲染（角色列表 + 菜单树 + 保存按钮）',
    permPage.roles > 0 && permPage.groups > 0 && permPage.items > 0 && permPage.saveBtn && permPage.original.length > 0,
    `角色=${permPage.roles} 目录=${permPage.groups} 菜单项=${permPage.items} 当前勾选=${permPage.original.length}`)

  // 目录全选联动（勾上 → 子项应全部选中）；先切两次保证回到原状态
  await evaluate(`(() => {
    const group = document.querySelector('.perm-group')
    if (!group) return false
    const head = group.querySelector('.perm-group__head input')
    if (head.checked) head.click()
    head.click()
    return true
  })()`)
  await sleep(500)
  const groupToggle = await json(`(() => {
    const group = document.querySelector('.perm-group')
    if (!group) return { ok: false }
    return {
      ok: true,
      checkedChildren: group.querySelectorAll('.perm-group__body input:checked').length,
      totalChildren: group.querySelectorAll('.perm-group__body input').length
    }
  })()`)
  record('勾选目录会联动勾选其子菜单', groupToggle.ok && groupToggle.checkedChildren === groupToggle.totalChildren,
    `子项 ${groupToggle.checkedChildren}/${groupToggle.totalChildren}`)

  // 防自锁：取消勾选「菜单权限」 → 出现警告 + 保存时二次确认（点取消）
  await evaluate(`(() => {
    const label = [...document.querySelectorAll('.perm-item')].find(l => (l.textContent || '').indexOf('菜单权限') > -1)
    if (label) { const cb = label.querySelector('input'); if (cb.checked) cb.click() }
    return true
  })()`)
  await sleep(500)
  const warnShown = await evaluate(`!!document.querySelector('.perm-warn')`)
  const lockSave = await json(`(() => {
    const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /保存权限/.test(b.textContent || ''))
    if (!btn || btn.disabled) return { clicked: false }
    btn.click()
    return { clicked: true }
  })()`)
  await sleep(800)
  const lockDialog = await json(`(() => {
    const d = document.querySelector('.ds-dialog')
    if (!d) return { ok: false }
    const cancel = d.querySelector('.ds-btn--ghost')
    if (cancel) cancel.click()
    return { ok: true, title: (d.querySelector('.ds-dialog__title') || {}).textContent || '' }
  })()`)
  record('取消勾选本页菜单时给出「防自锁」警告与二次确认',
    warnShown && lockSave.clicked && lockDialog.ok,
    `警告=${warnShown} 确认框=「${lockDialog.title || '未出现'}」${lockSave.clicked ? '' : '（保存按钮不可点）'}`)

  // 真实验证保存链路：先取消一个「非本页」菜单并保存 → 再勾回来保存（最终回到进入前的权限集）。
  // 之所以要改一项再存，是因为「无改动」时保存按钮本来就是禁用的（这是期望行为）。
  const toggleSave = async (wantChecked) => {
    const info = await json(`(() => {
      const label = [...document.querySelectorAll('.perm-item')].find(l => (l.textContent || '').indexOf('操作日志') > -1)
      const cb = label ? label.querySelector('input') : null
      if (cb && cb.checked !== ${wantChecked}) cb.click()
      const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /保存权限/.test(b.textContent || ''))
      return { itemFound: !!cb, btnDisabled: btn ? btn.disabled : null, dialogs: document.querySelectorAll('.ds-dialog').length }
    })()`)
    await sleep(400)
    const clicked = await evaluate(`(() => {
      const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /保存权限/.test(b.textContent || ''))
      if (!btn || btn.disabled) return false
      btn.click()
      return true
    })()`)
    await sleep(2200)
    const after = await json(`(() => ({
      toast: (document.querySelector('.ds-toast') || {}).textContent || '',
      dialogs: document.querySelectorAll('.ds-dialog').length
    }))()`)
    return { ...info, clicked, ...after }
  }

  // 先把「菜单权限」勾回来并关掉可能残留的确认框，否则保存会被自锁确认拦截
  await evaluate(`(() => {
    const d = document.querySelector('.ds-dialog .ds-btn--ghost')
    if (d) d.click()
    const label = [...document.querySelectorAll('.perm-item')].find(l => (l.textContent || '').indexOf('菜单权限') > -1)
    if (label) { const cb = label.querySelector('input'); if (!cb.checked) cb.click() }
    return true
  })()`)
  await sleep(500)

  const offRes = await toggleSave(false)
  const onRes = await toggleSave(true)

  // 最终必须与进入前的权限集完全一致（自还原）
  await evaluate(`(async () => {
    const want = new Set(${JSON.stringify(permPage.original)})
    for (const input of document.querySelectorAll('.perm-item input')) {
      if (want.has(Number(input.value)) !== input.checked) input.click()
    }
    await new Promise(r => setTimeout(r, 200))
    const btn = [...document.querySelectorAll('.card-toolbar button')].find(b => /保存权限/.test(b.textContent || ''))
    if (btn && !btn.disabled) btn.click()
    return true
  })()`)
  await sleep(2200)
  const finalState = await json(`(() => ({
    checked: [...document.querySelectorAll('.perm-item input')].filter(i => i.checked).map(i => Number(i.value)).sort((a, b) => a - b)
  }))()`)
  const sameSet = JSON.stringify(finalState.checked) === JSON.stringify(permPage.original.slice().sort((a, b) => a - b))
  record('菜单权限可保存（取消→保存→勾回→保存，最终自还原）',
    /已保存/.test(offRes.toast) && /已保存/.test(onRes.toast) && sameSet,
    `#1 点击=${offRes.clicked} 按钮禁用=${offRes.btnDisabled} 弹窗=${offRes.dialogs} 提示=「${String(offRes.toast).slice(0, 16)}」 | #2 点击=${onRes.clicked} 提示=「${String(onRes.toast).slice(0, 16)}」 | 还原一致=${sameSet}`)

  record('全程无控制台报错', errors.length === 0, errors.slice(0, 2).join(' ; ').slice(0, 200))

  child.kill()
  try { fs.rmSync(profile, { recursive: true, force: true }) } catch (e) { /* ignore */ }
  const failed = results.filter(r => !r.ok)
  console.log(`\n=== 提示与确认弹窗验证：${results.length - failed.length}/${results.length} 通过 ===`)
  if (failed.length) { failed.forEach(f => console.log('  FAIL ' + f.name)); process.exitCode = 1 }
}

main().catch(e => { console.error(e); process.exitCode = 2 })
