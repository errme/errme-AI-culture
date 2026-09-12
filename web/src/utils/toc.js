/**
 * 正文目录（TOC）与阅读进度工具
 *
 * 设计原则：**不改动正文 HTML 本身**。
 * 详情页正文是用 v-html 渲染的，这里在渲染完成后遍历真实 DOM，
 * 给标题补 id 并返回目录数据 —— 不重新序列化 HTML，避免破坏原有内联样式。
 */

/** 生成稳定的锚点 id（中文标题也可用，兜底用序号） */
function makeId(text, index) {
  const slug = String(text || '')
    .trim()
    .toLowerCase()
    .replace(/[\s]+/g, '-')
    .replace(/[^\w\u4e00-\u9fa5-]/g, '')
    .slice(0, 40)
  return 'toc-' + (slug || 'section') + '-' + index
}

/**
 * 遍历容器内的标题，补 id 并返回目录项。
 * @param {HTMLElement} root 正文容器
 * @param {{min?:number,max?:number}} [options] 参与目录的标题层级范围（默认 h2~h4）
 * @returns {{id:string,text:string,level:number,top:number}[]}
 */
export function collectHeadings(root, options = {}) {
  if (!root) return []
  const min = options.min || 2
  const max = options.max || 4
  const selector = []
  for (let l = min; l <= max; l++) selector.push('h' + l)
  const nodes = root.querySelectorAll(selector.join(','))
  const items = []
  Array.prototype.forEach.call(nodes, (el, i) => {
    const text = (el.textContent || '').trim()
    if (!text) return
    let id = el.getAttribute('id')
    if (!id) {
      id = makeId(text, i)
      el.setAttribute('id', id)
    }
    items.push({ id, text, level: Number(el.tagName.substring(1)), top: el.offsetTop })
  })
  return items
}

/** 滚动到某个锚点（考虑顶部导航高度） */
export function scrollToHeading(id, offset = 80) {
  const el = document.getElementById(id)
  if (!el) return
  const top = el.getBoundingClientRect().top + window.pageYOffset - offset
  window.scrollTo({ top, behavior: 'smooth' })
}

/**
 * 阅读进度与当前章节（配合 TOC 高亮）。
 * 返回 { progress, activeId, destroy }，progress 为 0~1。
 */
export function trackReading(root, items, onChange) {
  let ticking = false
  const compute = () => {
    ticking = false
    const doc = document.documentElement
    const total = doc.scrollHeight - window.innerHeight
    const progress = total > 0 ? Math.min(1, Math.max(0, window.pageYOffset / total)) : 0
    let activeId = items.length ? items[0].id : ''
    for (let i = 0; i < items.length; i++) {
      const el = document.getElementById(items[i].id)
      if (!el) continue
      if (el.getBoundingClientRect().top - 120 <= 0) activeId = items[i].id
      else break
    }
    onChange({ progress, activeId })
  }
  const handler = () => {
    if (ticking) return
    ticking = true
    window.requestAnimationFrame(compute)
  }
  window.addEventListener('scroll', handler, { passive: true })
  window.addEventListener('resize', handler)
  compute()
  return {
    destroy() {
      window.removeEventListener('scroll', handler)
      window.removeEventListener('resize', handler)
    }
  }
}
