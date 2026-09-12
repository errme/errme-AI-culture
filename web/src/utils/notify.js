/**
 * 全局消息提示（顶部居中）+ 二次确认弹窗 —— 统一入口
 *
 * 设计目标：
 *   1. 所有提示都从「模态 jconfirm 温馨提示」改成**顶部居中轻提示**（类似 platform.deepseek.com）：
 *      不打断操作、可堆叠、自动消失、鼠标悬停暂停计时。
 *   2. 删除等破坏性操作统一走 `confirmDelete()`：红色主按钮、写清影响与「不可恢复」、
 *      取消按钮默认聚焦（防误按回车）、支持键盘 Esc/Enter。
 *   3. 老代码里的 `$.confirm({...})` 由 `installLegacyNotifyShim()` 接管：
 *      单按钮（纯告知）→ 顶部 toast；双按钮 → 新确认弹窗。
 *      这样 56 处历史调用不用逐条改写也能立刻获得统一、现代的交互。
 *
 * 用法：
 *   import { toast, alertDialog, confirmDialog, confirmDelete } from '@/utils/notify'
 *   toast.success('保存成功')
 *   toast.error('保存失败', { detail: err.message })
 *   if (await confirmDelete({ name: '故宫博物院', extra: '该文化下的评论与标签关联会一并清理。' })) { ... }
 */
import { reactive } from 'vue'

let seq = 0

/** 各类提示的默认停留时间（毫秒），0 表示不自动消失 */
const DEFAULT_TIMEOUT = { success: 2800, info: 3000, warning: 4200, error: 5600 }

export const notifyState = reactive({
  toasts: [],
  dialog: null // { id, kind, title, content, detail, danger, confirmText, cancelText, requireInput, inputPlaceholder, inputValue, busy }
})

/* ==================== 顶部提示 ==================== */

function dismissToast(id) {
  const i = notifyState.toasts.findIndex(t => t.id === id)
  if (i > -1) notifyState.toasts.splice(i, 1)
}

function pushToast(type, message, opts = {}) {
  const text = message == null ? '' : String(message)
  if (!text) return -1
  const item = {
    id: ++seq,
    type,
    message: text,
    detail: opts.detail ? String(opts.detail) : '',
    timeout: opts.timeout == null ? (DEFAULT_TIMEOUT[type] || 3000) : opts.timeout,
    // 操作按钮（例如删除后的「撤销」）：点击后执行 onClick 并关闭该提示
    action: opts.action && opts.action.text ? {
      text: String(opts.action.text),
      onClick: typeof opts.action.onClick === 'function' ? opts.action.onClick : null
    } : null
  }
  notifyState.toasts.push(item)
  // 最多同时显示 5 条，超出丢弃最早的
  if (notifyState.toasts.length > 5) notifyState.toasts.splice(0, notifyState.toasts.length - 5)
  if (item.timeout > 0) item.timer = setTimeout(() => dismissToast(item.id), item.timeout)
  return item.id
}

/** 鼠标悬停暂停自动消失；移开后重新计时 */
export function pauseToast(id) {
  const t = notifyState.toasts.find(x => x.id === id)
  if (t && t.timer) { clearTimeout(t.timer); t.timer = null }
}
export function resumeToast(id) {
  const t = notifyState.toasts.find(x => x.id === id)
  if (t && !t.timer && t.timeout > 0) t.timer = setTimeout(() => dismissToast(t.id), 1800)
}

/**
 * 执行提示上的操作按钮（撤销等），成功/失败都关闭原提示并给出新的反馈。
 * 注意：撤销本身失败时要明确告诉用户，不能静默。
 */
export function runToastAction(id) {
  const t = notifyState.toasts.find(x => x.id === id)
  if (!t || !t.action) return
  const fn = t.action.onClick
  dismissToast(id)
  if (!fn) return
  try {
    const ret = fn()
    if (ret && typeof ret.then === 'function') {
      ret.catch(e => pushToast('error', (e && e.message) || '操作失败'))
    }
  } catch (e) {
    pushToast('error', (e && e.message) || '操作失败')
  }
}

/**
 * 「删除成功 + 撤销」提示：删除后 8 秒内可点提示上的「撤销」恢复（服务端是逻辑删除，恢复即 deleted=0）。
 * undoFn 返回 Promise 时会等它结束；失败会弹出错误提示（不会静默）。
 */
export function toastWithUndo(message, undoFn, opts = {}) {
  return pushToast('success', message, {
    detail: opts.detail === undefined ? '误删可在 8 秒内点「撤销」恢复' : opts.detail,
    timeout: opts.timeout == null ? 8000 : opts.timeout,
    action: { text: opts.text || '撤销', onClick: undoFn }
  })
}

export const toast = {
  success: (m, o) => pushToast('success', m, o),
  error: (m, o) => pushToast('error', m, o),
  warning: (m, o) => pushToast('warning', m, o),
  info: (m, o) => pushToast('info', m, o),
  withUndo: toastWithUndo,
  dismiss: dismissToast,
  clear: () => { notifyState.toasts.splice(0, notifyState.toasts.length) }
}

/* ==================== 弹窗（告知 / 确认） ==================== */

/** 颜色 -> 语义 */
function typeOf(type, danger) {
  if (danger) return 'danger'
  if (type === 'red') return 'danger'
  if (type === 'orange' || type === 'yellow') return 'warning'
  if (type === 'green') return 'success'
  return 'info'
}

function closeDialog(id, result) {
  const d = notifyState.dialog
  if (!d || d.id !== id) return
  notifyState.dialog = null
  // 确认/取消都 resolve(result)：取消就是 false，避免调用方 await 时抛错
  if (d._resolve) d._resolve(result)
}

/** 关闭当前弹窗（供宿主组件调用）；confirmed 为 true 表示用户点了主按钮 */
export function settleDialog(confirmed) {
  const d = notifyState.dialog
  if (!d) return
  const id = d.id
  const value = d.requireInput ? d.inputValue : true
  if (!confirmed) { closeDialog(id, false); return }
  let ret
  try {
    ret = d.onConfirm ? d.onConfirm(value) : undefined
  } catch (e) {
    closeDialog(id, false)
    toast.error('操作失败', { detail: (e && e.message) || String(e) })
    return
  }
  if (ret && typeof ret.then === 'function') {
    d.busy = true
    ret.then(() => { d.busy = false; closeDialog(id, true) })
      .catch(() => { d.busy = false; closeDialog(id, false) })
  } else {
    closeDialog(id, true)
  }
}

export function cancelDialog() {
  const d = notifyState.dialog
  if (!d || d.busy) return
  if (d.onCancel) { try { d.onCancel() } catch (e) { /* 忽略 */ } }
  closeDialog(d.id, false)
}

/** 告知型弹窗（一般不需要，优先用 toast）；返回 Promise<void> */
export function alertDialog({ title = '提示', content = '', detail = '', type = 'info', confirmText = '知道了', danger = false } = {}) {
  return new Promise(resolve => {
    const id = ++seq
    notifyState.dialog = {
      id, kind: 'alert', title, content, detail,
      tone: typeOf(type, danger), danger,
      confirmText, cancelText: '',
      onConfirm: () => resolve(),
      _resolve: () => resolve()
    }
  })
}

/**
 * 确认型弹窗；返回 Promise<boolean>（确认 true / 取消 false）
 * onConfirm 返回 Promise 时会显示 busy 状态，等它结束再关闭。
 */
export function confirmDialog({
  title = '确认操作', content = '', detail = '', type = 'info', danger = false,
  confirmText = '确定', cancelText = '取消', onConfirm, onCancel,
  requireInput = false, inputPlaceholder = '', inputValue = ''
} = {}) {
  return new Promise(resolve => {
    const id = ++seq
    notifyState.dialog = {
      id, kind: 'confirm', title, content, detail,
      tone: typeOf(type, danger), danger,
      confirmText, cancelText, onConfirm, onCancel,
      requireInput, inputPlaceholder, inputValue,
      busy: false,
      _resolve: resolve
    }
  })
}

/**
 * 破坏性操作确认（删除/清空/重置等）—— 统一文案与红色按钮
 * name 为被操作对象名称，extra 用于补充「影响范围」
 */
export function confirmDelete({ name, target = '该项', extra = '', title = '确认删除', onConfirm, confirmText = '删除' } = {}) {
  const label = name ? `「${name}」` : target
  return confirmDialog({
    title,
    content: `确定要删除${label}吗？`,
    detail: extra || '删除后不可恢复，请确认后继续。',
    danger: true,
    confirmText,
    cancelText: '取消',
    onConfirm
  })
}

/* ==================== 老代码兼容层（$.confirm / $.alert） ==================== */

/**
 * 把老的 `$.confirm({ title, content, type, buttons })` 映射到新交互：
 *   · 只有一个按钮（纯告知，如「温馨提示/操作成功」）→ 顶部轻提示，不弹模态
 *   · 有两个按钮（真确认）→ 新确认弹窗；`type: red`、按钮带 red/danger 样式或文案含
 *     删除/清空/重置类字眼时按「危险操作」渲染（红色主按钮 + 影响说明）
 * 返回带 close()/then() 的对象，兼容老写法 `confirmBox = $.confirm({...})`。
 */
function legacyConfirm(options = {}) {
  const buttons = options.buttons || {}
  const keys = Object.keys(buttons)
  const confirmKey = keys.find(k => buttons[k] && typeof buttons[k].action === 'function') || keys[0]
  const cancelKey = keys.find(k => k !== confirmKey)
  const confirmBtn = buttons[confirmKey] || {}
  const cancelBtn = buttons[cancelKey] || {}
  const danger = /red|danger/i.test(confirmBtn.btnClass || '') ||
    /删除|清空|重置|下架|禁用|退出/.test(String(confirmBtn.text || '')) ||
    options.type === 'red'
  const tone = typeOf(options.type, danger)
  const detail = typeof options.contentDetail === 'string' ? options.contentDetail : ''

  // 纯告知：用顶部轻提示，避免打断操作
  if (!cancelKey) {
    const fn = tone === 'success' ? toast.success : tone === 'danger' ? toast.error : tone === 'warning' ? toast.warning : toast.info
    const content = String(options.content == null ? '' : options.content)
    const title = options.title && options.title !== '温馨提示' ? options.title : ''
    if (content.length <= 60 && !detail) fn(content)
    else fn(title || content.slice(0, 40), { detail: detail || content, timeout: tone === 'danger' ? 6000 : 3600 })
    return { close() { /* 轻提示无需关闭句柄 */ } }
  }

  let settle
  const promise = new Promise(resolve => { settle = resolve })
  const id = ++seq
  notifyState.dialog = {
    id,
    kind: 'confirm',
    title: options.title || '确认操作',
    content: String(options.content == null ? '' : options.content),
    detail,
    tone,
    danger: tone === 'danger',
    confirmText: confirmBtn.text || '确定',
    cancelText: cancelBtn.text || '取消',
    onConfirm: () => {
      const r = confirmBtn.action && confirmBtn.action()
      if (r && typeof r.then === 'function') return r.then(v => { settle(true); return v })
      settle(true)
      return undefined
    },
    onCancel: () => { if (cancelBtn.action) cancelBtn.action(); settle(false) },
    requireInput: false,
    busy: false,
    _resolve: () => {}
  }
  return {
    close: () => settleDialog(false),
    then: (fn, rej) => promise.then(fn, rej),
    catch: rej => promise.catch(rej),
    finally: fn => promise.finally(fn)
  }
}

/** jQuery/jconfirm 就绪后接管 $.confirm / $.alert
 *  注意：老页面会动态加载自带 jQuery 的脚本（如 jquery-1.11.1），会**替换全局 jQuery**，
 *  所以这里不做一次性开关，而是「按当前 jQuery 实例」幂等安装；loadScript 每次加载脚本后都会再调一次。 */
export function installLegacyNotifyShim() {
  const tryInstall = () => {
    const $ = window.jQuery
    if (!$) return false
    if (typeof $.confirm === 'function' && $.confirm.__dshShim) return true
    $.confirm = Object.assign(legacyConfirm, { __dshShim: true })
    $.alert = (options = {}) => legacyConfirm({
      ...(typeof options === 'string' ? { content: options } : options),
      buttons: (options && options.buttons) || { ok: { text: '知道了' } }
    })
    window.__dshNotify = { toast, alertDialog, confirmDialog, confirmDelete }
    return true
  }
  if (tryInstall()) return
  // jQuery 可能稍后才加载（老页面按需 loadScript），轮询几次
  let tries = 0
  const timer = setInterval(() => {
    if (tryInstall() || ++tries > 40) clearInterval(timer)
  }, 250)
}

