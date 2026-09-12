/**
 * 展示格式化工具（与后端字段保持一致）
 */

/** 文化封面：后端存文件名，走 /showFmImg/{name} */
export function coverUrl(fmUrl) {
  if (!fmUrl) return ''
  if (/^(https?:)?\/\//.test(fmUrl) || fmUrl.startsWith('/')) return fmUrl
  return '/showFmImg/' + fmUrl
}

/** 用户头像：后端存文件名，走 /showimage/{name} */
export function avatarUrl(headImg) {
  if (!headImg) return ''
  if (/^(https?:)?\/\//.test(headImg) || headImg.startsWith('/')) return headImg
  return '/showimage/' + headImg
}

/** yyyy-MM-dd HH:mm */
export function formatDateTime(value) {
  if (!value) return ''
  const d = value instanceof Date ? value : new Date(String(value).replace(' ', 'T'))
  if (isNaN(d.getTime())) return String(value)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 分页响应兼容：后端 PageList -> { rows, total } */
export function pickPage(res) {
  if (!res) return { rows: [], total: 0 }
  const rows = res.rows || res.list || res.data || []
  const total = res.total != null ? res.total : rows.length
  return { rows, total }
}
