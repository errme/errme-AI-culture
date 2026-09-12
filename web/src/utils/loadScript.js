/**
 * 动态加载原有静态资源（CSS/JS）
 *
 * 前后端分离后，Vue 页面仍复用站点原有的 CSS 与 jQuery 插件，
 * 但必须「先渲染 DOM、再执行老插件」——否则老插件在 load 时找不到元素会静默失败。
 * 因此统一用本工具在组件挂载后按需、按序加载，并做去重。
 */
const cache = new Map()

export function loadScript(src) {
  if (cache.has(src)) return cache.get(src)
  // 若文档里已经有同 src 的脚本（例如预渲染产物把脚本写进了 HTML），直接复用，
  // 避免重复注入导致 jQuery 等库被加载两次、插件绑定到废旧实例上。
  const existed = Array.prototype.some.call(
    document.querySelectorAll('script[src]'),
    el => el.getAttribute('src') === src
  )
  if (existed) {
    const done = Promise.resolve(src)
    cache.set(src, done)
    return done
  }
  const promise = new Promise((resolve, reject) => {
    const el = document.createElement('script')
    el.src = src
    el.async = false
    el.onload = () => {
      // 旧库（如 quill）内置 core-js，加载时会覆盖原生 Symbol，见 admin.html 顶部说明
      if (typeof window.__guardNativeSymbol === 'function') window.__guardNativeSymbol()
      // 老页面可能自带 jQuery（会替换全局 jQuery），重新接管其 $.confirm
      if (typeof window.__dshInstallNotifyShim === 'function') window.__dshInstallNotifyShim()
      resolve(src)
    }
    el.onerror = () => reject(new Error('脚本加载失败：' + src))
    document.head.appendChild(el)
  })
  cache.set(src, promise)
  return promise
}

export function loadStyle(href) {
  const key = 'css:' + href
  if (cache.has(key)) return cache.get(key)
  // 若入口 HTML 的 <head> 已静态引入同款样式，直接复用，避免重复插入
  const already = Array.prototype.some.call(
    document.querySelectorAll('link[rel="stylesheet"]'),
    link => link.getAttribute('href') === href
  )
  if (already) {
    const done = Promise.resolve(href)
    cache.set(key, done)
    return done
  }
  const promise = new Promise(resolve => {
    const el = document.createElement('link')
    el.rel = 'stylesheet'
    el.href = href
    el.onload = () => resolve(href)
    el.onerror = () => resolve(href)
    document.head.appendChild(el)
  })
  cache.set(key, promise)
  return promise
}

/** 按顺序加载多个脚本（保证依赖顺序，如 jquery -> bootstrap -> 插件） */
export function loadScripts(list) {
  return list.reduce((chain, src) => chain.then(() => loadScript(src)), Promise.resolve())
}

/**
 * 每次都重新注入的脚本（不去重）。
 * 用于「按 DOM 初始化的老主题脚本」：SPA 中组件重新挂载时需要它们重新执行一次。
 */
export function loadScriptFresh(src) {
  return new Promise((resolve, reject) => {
    const el = document.createElement('script')
    el.src = src
    el.async = false
    el.onload = () => {
      // 旧库（如 quill）内置 core-js，加载时会覆盖原生 Symbol，见 admin.html 顶部说明
      if (typeof window.__guardNativeSymbol === 'function') window.__guardNativeSymbol()
      // 老页面可能自带 jQuery（会替换全局 jQuery），重新接管其 $.confirm
      if (typeof window.__dshInstallNotifyShim === 'function') window.__dshInstallNotifyShim()
      resolve(src)
    }
    el.onerror = () => reject(new Error('脚本加载失败：' + src))
    document.head.appendChild(el)
  })
}

/** 等待 jQuery 可用（老插件依赖全局 jQuery） */
export function whenJQuery(timeout = 5000) {
  if (window.jQuery) return Promise.resolve(window.jQuery)
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const timer = setInterval(() => {
      if (window.jQuery) { clearInterval(timer); resolve(window.jQuery) }
      else if (Date.now() - start > timeout) { clearInterval(timer); reject(new Error('jQuery 未加载')) }
    }, 30)
  })
}

/** 站点公共 jQuery 资源 */
export const JQUERY_MAIN = '/index/lib/jquery-3.4.1/jquery-3.4.1.min.js'
