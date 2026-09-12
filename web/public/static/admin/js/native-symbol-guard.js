/**
 * 原生 Symbol 保护（后台入口专用）
 *
 * 为什么需要：bootstrap-table（以及 quill）内置 core-js，加载时会用自带的 Symbol polyfill
 * 覆盖原生 Symbol。它原本位于 admin.html 的 <head> 并同步执行，而入口是 type="module"（延后执行），
 * 于是 Vue 初始化时拿到的 Symbol.for('v-txt')（文本节点类型）会变成「假 Symbol 对象」，
 * createVNode 因 isObject(type) 为真把文本节点误判成组件节点 → 路由切换卸载旧页面时崩溃
 * （Cannot destructure property 'bum' of 'instance' as it is null / parentNode 空指针），
 * 之后所有菜单点击失效。
 *
 * 做法：把 window.Symbol 固定为原生实现（读永远是原生，第三方的覆盖写入被忽略），
 * 并暴露 window.__guardNativeSymbol 供 loadScript 在每次动态加载脚本后复查。
 *
 * 注意：本文件是**外部脚本**（不是内联），这样后台就能上 CSP：
 * 内联脚本会让 script-src 必须带 'unsafe-inline'，等于白配 CSP。
 */
    (function () {
      var Native = window.Symbol
      if (!Native || typeof Native !== 'function' || typeof Native.for !== 'function') return
      function guard() {
        try {
          Object.defineProperty(window, 'Symbol', {
            configurable: true,
            get: function () { return Native },
            set: function () { /* 忽略第三方 polyfill 的覆盖 */ }
          })
        } catch (e) { /* 极端环境下降级为原生行为 */ }
      }
      guard()
      // 供动态加载脚本后复查（见 src/utils/loadScript.js）
      window.__guardNativeSymbol = guard
    })()
