/**
 * 每页 SEO 组合式函数（标题 / 描述 / 关键词 / canonical / Open Graph / Twitter Card / JSON-LD）
 *
 * 用法（在组件 setup 中调用一次即可）：
 *   useSeo({
 *     title: '文化列表 · 遇你',        // <title> 与 og:title
 *     description: '……',              // meta description 与 og:description
 *     keywords: '文化,传统文化',       // meta keywords（可选）
 *     image: coverUrl(c.fmUrl),       // og:image / twitter:image（可选，默认站点 Banner）
 *     type: 'article',                // og:type，默认 website
 *     canonical: 'https://x/culture', // 规范化地址（可选，默认当前 origin + pathname）
 *     noindex: true,                  // 需要「不被收录」的页面（如个人中心）
 *     jsonLd: { ... }                 // 结构化数据，注入 <script type="application/ld+json">
 *   })
 * 参数支持「对象 / ref / getter 函数」三种写法；传 getter 时，内部依赖的响应式数据变化后会
 * 自动重新写入 head（详情页先渲染骨架、再拿到接口数据，就靠这个自动更新）。
 *
 * SSR 安全（为 vite-ssg 预渲染做准备）：
 *   预渲染阶段没有 document/window，所有 DOM 操作前都先过 hasDom()/hasWindow() 判断，
 *   保证渲染期不会抛异常，产出的 HTML 只依赖调用处传入的数据。
 */
import { isRef, onUnmounted, unref, watchEffect } from 'vue'

/** 注入的 JSON-LD script 固定 id：保证「上一次注入」总能被精确定位并清理 */
const JSONLD_ID = 'page-jsonld'

/** 站点默认分享图（首页等没有封面的页面用它，避免分享卡片空白） */
const DEFAULT_IMAGE = '/index/images/banner_1.png'

/**
 * 站点对外地址。预渲染/生产可用环境变量 VITE_SITE_BASE_URL 指定真实域名；
 * 浏览器里留空则退回 window.location.origin（本地即 http://localhost:8080）。
 */
const ENV_BASE_URL = (import.meta.env && import.meta.env.VITE_SITE_BASE_URL
  ? String(import.meta.env.VITE_SITE_BASE_URL)
  : '').replace(/\/+$/, '')

/** 是否有可用的 DOM（预渲染期返回 false，所有写 head 的操作据此跳过） */
function hasDom() {
  return typeof document !== 'undefined' && !!document.head
}

/** 是否有 window（取规范地址需要 location，预渲染期没有） */
function hasWindow() {
  return typeof window !== 'undefined' && !!window.location
}

/** 站点根地址：优先环境变量，其次当前页面 origin */
function siteOrigin() {
  if (ENV_BASE_URL) return ENV_BASE_URL
  return hasWindow() ? window.location.origin : ''
}

/** 取「当前页面的规范化地址」：丢掉查询串/哈希，避免 ?page=2 被当成独立页面重复收录 */
function currentUrl() {
  if (!hasWindow()) return ''
  return siteOrigin() + window.location.pathname
}

/**
 * 转成绝对地址：og:image / canonical 等标签按协议必须是绝对 URL，
 * 而接口里的封面是 /showFmImg/xxx 这种站内相对路径（coverUrl 的产物）。
 */
export function absoluteUrl(url) {
  const value = text(url)
  if (!value) return ''
  if (/^(https?:)?\/\//i.test(value)) return value
  const origin = siteOrigin()
  if (!origin) return value
  return origin + (value.startsWith('/') ? value : '/' + value)
}

/** 统一的取值：兼容 ref / getter 函数 / 普通值 */
function val(v) {
  if (typeof v === 'function') return v()
  return isRef(v) ? unref(v) : v
}

/** 参数归一化：对象、ref、getter 都收敛成普通对象 */
function normalize(options) {
  const raw = val(options)
  return raw && typeof raw === 'object' ? raw : {}
}

/** 文本净化：null/undefined -> ''，并去掉首尾空白 */
function text(v) {
  const value = val(v)
  return value == null ? '' : String(value).trim()
}

/**
 * 通用描述截断（详情页把 content/desc 截成 120 字左右作 meta description，
 * 太长会被搜索引擎截断，太短又浪费展示位）。
 */
export function truncate(value, max = 120) {
  const str = text(value).replace(/\s+/g, ' ')
  if (str.length <= max) return str
  return str.slice(0, max) + '…'
}

/** 写 meta：空值表示「本页不声明该项」，直接移除，防止上一页的关键词/分享图残留到本页 */
function setMeta(attr, key, content) {
  if (!hasDom()) return
  const value = content == null ? '' : String(content).trim()
  const selector = `meta[${attr}="${key}"]`
  const existing = document.head.querySelector(selector)
  if (!value) {
    if (existing && existing.parentNode) existing.parentNode.removeChild(existing)
    return
  }
  const el = existing || document.createElement('meta')
  if (!existing) {
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  el.setAttribute('content', value)
}

/** 写 <link>（canonical）：同为空值即移除 */
function setLink(rel, href) {
  if (!hasDom()) return
  const value = href == null ? '' : String(href).trim()
  const selector = `link[rel="${rel}"]`
  const existing = document.head.querySelector(selector)
  if (!value) {
    if (existing && existing.parentNode) existing.parentNode.removeChild(existing)
    return
  }
  const el = existing || document.createElement('link')
  if (!existing) {
    el.setAttribute('rel', rel)
    document.head.appendChild(el)
  }
  el.setAttribute('href', value)
}

/**
 * 注入页面级 JSON-LD（结构化数据，帮助搜索引擎理解正文）：
 * 先删掉上一次注入的 #page-jsonld，再写入新的，保证同一时刻只有一份、不会被 SPA 切换累积。
 */
export function setJsonLd(obj) {
  if (!hasDom()) return
  clearJsonLd()
  if (!obj) return
  const el = document.createElement('script')
  el.type = 'application/ld+json'
  el.id = JSONLD_ID
  try {
    el.textContent = JSON.stringify(obj)
  } catch (e) {
    // 循环引用等无法序列化的情况：放弃注入，不影响页面本身
    console.warn('[seo] JSON-LD 序列化失败：', e && e.message)
    return
  }
  document.head.appendChild(el)
}

/** 清理页面级 JSON-LD（路由离开时调用，避免旧页面的结构化数据留在新页面上） */
export function clearJsonLd() {
  if (!hasDom()) return
  const el = document.getElementById(JSONLD_ID)
  if (el && el.parentNode) el.parentNode.removeChild(el)
}

/** 移除本页写入的 noindex 指令（SPA 共享同一个 head，离开时必须撤销） */
function clearNoindex() {
  if (!hasDom()) return
  const el = document.head.querySelector('meta[name="robots"]')
  if (el && el.getAttribute('content') === 'noindex' && el.parentNode) {
    el.parentNode.removeChild(el)
  }
}

/**
 * 把一份 SEO 配置写入 head（幂等：重复调用只是覆盖同名标签）
 */
function apply(rawOptions) {
  if (!hasDom()) return
  const options = rawOptions || {}

  // ---- 1) <title>：搜索结果标题与浏览器标签页 ----
  const title = text(options.title)
  if (title) document.title = title

  // ---- 2) 页面描述 / 关键词：影响搜索结果摘要与主题相关度 ----
  const description = text(options.description)
  if (description) setMeta('name', 'description', description)
  setMeta('name', 'keywords', text(options.keywords))

  // ---- 3) canonical：声明规范化地址，集中权重，避免同页多址（?page=2、带参广告链）重复收录 ----
  setLink('canonical', absoluteUrl(text(options.canonical) || currentUrl()))

  // ---- 4) Open Graph：微信/QQ/微博等社交平台分享卡片的标题、摘要、图片、类型、地址 ----
  const type = text(options.type) || 'website'
  const image = absoluteUrl(text(options.image) || DEFAULT_IMAGE)
  setMeta('property', 'og:title', title)
  setMeta('property', 'og:description', description)
  setMeta('property', 'og:type', type)
  setMeta('property', 'og:url', absoluteUrl(text(options.canonical) || currentUrl()))
  setMeta('property', 'og:image', image)
  setMeta('property', 'og:site_name', '遇你 · 传统文化')

  // ---- 5) Twitter Card：大图卡片（有配图时），否则用普通摘要卡 ----
  setMeta('name', 'twitter:card', image ? 'summary_large_image' : 'summary')
  setMeta('name', 'twitter:title', title)
  setMeta('name', 'twitter:description', description)
  setMeta('name', 'twitter:image', image)

  // ---- 6) 收录控制：个人中心等私有页面用 noindex，避免被搜索引擎索引 ----
  if (options.noindex) setMeta('name', 'robots', 'noindex')
  else clearNoindex()

  // ---- 7) JSON-LD：正文结构化数据（Article 等），让搜索结果有富摘要 ----
  if (options.jsonLd) setJsonLd(val(options.jsonLd))
}

/**
 * 每页 SEO 入口。在 setup 中调用一次：
 *   const { setJsonLd: setPageJsonLd } = useSeo(() => ({ title: culture.value?.cultureName, ... }))
 * 返回的 setJsonLd / clearJsonLd 供需要「手动补一次」的场景使用。
 */
export function useSeo(options) {
  // watchEffect 会立即执行一次；若 options 是 getter/ref，内部读取到的响应式数据变化后会重新写入
  watchEffect(() => {
    apply(normalize(options))
  })

  onUnmounted(() => {
    // 离开页面：清掉页面级结构化数据与 noindex，防止污染后续路由（head 是整站共享的）
    clearJsonLd()
    clearNoindex()
  })

  return { setJsonLd, clearJsonLd }
}

export default useSeo
