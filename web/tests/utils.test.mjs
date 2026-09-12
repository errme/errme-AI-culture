/**
 * 前端最小单元测试（Node 内置 test runner，无需额外依赖）
 *
 * 运行：cd web && npm test        （等价于 node --test tests/）
 * 覆盖：顶部提示的队列/去重/上限、确认弹窗的语义（含「取消 resolve(false) 而不是 reject」这个
 *       曾经踩过的坑）、下载文件名解析、格式化工具、老静态资源版本号约定。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { notifyState, toast, confirmDialog, confirmDelete, settleDialog, cancelDialog } from '../src/utils/notify.js'
import { filenameFromHeaders } from '../src/utils/download.js'
import { LEGACY } from '../src/utils/legacyAssets.js'
import { coverUrl, avatarUrl, pickPage } from '../src/utils/format.js'

test('toast：入队、类型、详情、自动消失上限', () => {
  toast.clear()
  const id = toast.success('保存成功')
  assert.equal(notifyState.toasts.length, 1)
  assert.equal(notifyState.toasts[0].type, 'success')
  assert.equal(notifyState.toasts[0].message, '保存成功')
  toast.error('保存失败', { detail: '网络异常' })
  assert.equal(notifyState.toasts[1].detail, '网络异常')
  assert.ok(notifyState.toasts[1].timeout >= 5000, '错误提示应停留更久')
  toast.dismiss(id)
  assert.equal(notifyState.toasts.length, 1)
  // 最多同时 5 条
  for (let i = 0; i < 8; i++) toast.info('提示' + i)
  assert.equal(notifyState.toasts.length, 5)
  assert.equal(notifyState.toasts[4].message, '提示7')
  toast.clear()
})

test('toast：空内容不入队', () => {
  toast.clear()
  toast.success('')
  toast.success(null)
  assert.equal(notifyState.toasts.length, 0)
})

test('confirmDelete：危险样式 + 对象名 + 不可恢复说明', () => {
  notifyState.dialog = null
  let called = 0
  confirmDelete({ name: '故宫博物院', onConfirm: () => { called++ } })
  const d = notifyState.dialog
  assert.ok(d, '应弹确认框')
  assert.equal(d.danger, true)
  assert.equal(d.tone, 'danger')
  assert.equal(d.confirmText, '删除')
  assert.equal(d.cancelText, '取消')
  assert.match(d.content, /故宫博物院/)
  assert.match(d.detail, /不可恢复/)
  settleDialog(true)
  assert.equal(called, 1)
  assert.equal(notifyState.dialog, null)
})

test('confirmDialog：取消 resolve(false)（不是 reject）', async () => {
  notifyState.dialog = null
  const p = confirmDialog({ title: '确认操作', content: '确定吗？' })
  cancelDialog()
  const result = await p
  assert.equal(result, false)
  assert.equal(notifyState.dialog, null)
})

test('confirmDialog：onConfirm 返回 Promise 时先 busy 再关闭', async () => {
  notifyState.dialog = null
  let done = false
  const p = confirmDialog({
    title: '删除',
    danger: true,
    onConfirm: () => new Promise(r => setTimeout(() => { done = true; r() }, 10))
  })
  settleDialog(true)
  assert.equal(notifyState.dialog.busy, true, '等待异步动作时应处于 busy')
  const ok = await p
  assert.equal(ok, true)
  assert.equal(done, true)
  assert.equal(notifyState.dialog, null)
})

test('filenameFromHeaders：解析普通与 UTF-8 文件名', () => {
  assert.equal(filenameFromHeaders({ 'content-disposition': 'attachment; filename="cultures_1.csv"' }, 'x.csv'), 'cultures_1.csv')
  const utf8 = "attachment; filename*=UTF-8''%E6%96%87%E5%8C%96.csv"
  assert.equal(filenameFromHeaders({ 'content-disposition': utf8 }, 'x.csv'), '文化.csv')
  assert.equal(filenameFromHeaders({}, 'fallback.csv'), 'fallback.csv')
  assert.equal(filenameFromHeaders(null, 'fallback.csv'), 'fallback.csv')
})

test('format：封面/头像 URL 与分页字段兼容裸文件名和绝对地址', () => {
  assert.equal(coverUrl('a.png'), '/showFmImg/a.png')
  assert.equal(coverUrl('a_thumb.jpg'), '/showFmImg/a_thumb.jpg')
  assert.equal(coverUrl('http://x/a.png'), 'http://x/a.png')
  assert.equal(coverUrl(''), '')
  assert.equal(avatarUrl('u.png'), '/showimage/u.png')
  assert.deepEqual(pickPage({ rows: [1], total: 3 }), { rows: [1], total: 3 })
  assert.deepEqual(pickPage({ list: [2], total: 5 }), { rows: [2], total: 5 })
  assert.deepEqual(pickPage(null), { rows: [], total: 0 })
})

test('legacyAssets：老静态资源路径合法且会变的编辑器脚本带版本号', () => {
  for (const [k, v] of Object.entries(LEGACY)) {
    assert.ok(v.startsWith('/static/'), k + ' 应以 /static/ 开头')
  }
  assert.match(LEGACY.editorJs, /culture-editor\.js\?v=/, '编辑器脚本必须带版本号以绕过 1 天静态缓存')
})
