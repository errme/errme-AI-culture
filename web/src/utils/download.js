/**
 * 触发浏览器下载（用于带鉴权头的二进制响应，例如后台导出 CSV）。
 *
 * 用法：
 *   const res = await cultureExport(params)          // axios responseType: 'blob'
 *   saveBlob(res.data, filenameFromHeaders(res.headers), 'cultures.csv')
 */
export function filenameFromHeaders(headers, fallback = 'download') {
  const cd = (headers && (headers['content-disposition'] || headers['Content-Disposition'])) || ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(cd)
  if (star) { try { return decodeURIComponent(star[1].trim()) } catch (e) { /* 继续 */ } }
  const plain = /filename="?([^";]+)"?/i.exec(cd)
  return plain ? plain[1].trim() : fallback
}

export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 2000)
}
