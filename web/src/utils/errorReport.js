/**
 * 前端错误上报（配合后端 POST /api/client-error）
 *
 * 只上报「未捕获异常」与「未处理的 Promise 拒绝」，每次会话最多 5 条，避免错误风暴刷爆日志；
 * 用 sendBeacon（页面卸载也能发）优先，退化到 fetch keepalive。上报失败绝不影响页面。
 */
const MAX_PER_SESSION = 5
let sent = 0

function post(payload) {
  if (sent >= MAX_PER_SESSION) return
  sent++
  try {
    const body = JSON.stringify(payload)
    if (navigator.sendBeacon) {
      navigator.sendBeacon('/api/client-error', new Blob([body], { type: 'application/json' }))
      return
    }
    fetch('/api/client-error', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true
    }).catch(() => { /* 忽略 */ })
  } catch (e) { /* 忽略 */ }
}

function base() {
  return {
    url: location.href,
    userAgent: navigator.userAgent,
    t: Date.now()
  }
}

export function installErrorReporter() {
  if (typeof window === 'undefined' || window.__dshErrorReporterInstalled) return
  window.__dshErrorReporterInstalled = true

  window.addEventListener('error', e => {
    if (!e) return
    post({
      ...base(),
      message: String(e.message || 'script error').slice(0, 500),
      source: e.filename || '',
      line: e.lineno || 0,
      col: e.colno || 0,
      stack: (e.error && e.error.stack) ? String(e.error.stack).slice(0, 2000) : ''
    })
  })

  window.addEventListener('unhandledrejection', e => {
    const r = e && e.reason
    const msg = r && (r.message || r.msg) ? (r.message || r.msg) : String(r || '')
    post({
      ...base(),
      message: ('未处理的 Promise 拒绝：' + msg).slice(0, 500),
      stack: (r && r.stack) ? String(r.stack).slice(0, 2000) : ''
    })
  })
}
