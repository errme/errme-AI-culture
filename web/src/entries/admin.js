import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from '@/App.vue'
import router from '@/router/admin'
import '@/styles/admin-ui.css'   // 后台统一视觉规范（只新增 ad-* 命名空间，不影响老样式）

/**
 * 后台管理入口（admin.html）
 * 路由：/admin、/admin/culture、/admin/category、/admin/announcement、
 *       /admin/sentence、/admin/user、/admin/mail、/admin/me
 * 后台登录是独立入口（/admin/login → admin-login.html）。
 *
 * 注意：必须等 router.isReady()（首次导航解析完成）后再 mount。
 * 否则 RouterView 会先用「未匹配」的空树渲染一次，紧接着的更新会因前一棵
 * vnode 树里的组件尚未挂载而抛：
 *   TypeError: Cannot read properties of null (reading 'subTree')
 */
// 第三方旧库可能覆盖原生 Symbol（见 admin.html 顶部说明），挂载前再确认一次
if (typeof window.__guardNativeSymbol === 'function') window.__guardNativeSymbol()

const app = createApp(App).use(createPinia()).use(router)

// 组件异常统一记录：避免某个页面的生命周期异常静默破坏路由状态
function _compName(t) {
  if (!t) return String(t)
  if (typeof t === 'string') return t
  return t.__file || t.name || t.__name || 'Anonymous'
}
app.config.errorHandler = (err, instance, info) => {
  // 诊断增强：打印组件链，便于定位出错的具体页面/祖先
  const chain = []
  for (let i = instance; i; i = i.parent) {
    const key = i.vnode && i.vnode.key != null ? '#' + i.vnode.key : ''
    chain.push(_compName(i.type) + key)
  }
  console.error('[admin] 组件异常：', info, (err && err.message) || err, '| 组件链(自身→根):', chain.join(' > '))
}

/**
 * 挂载入口。
 *
 * 这里原先还有一步「先按序加载 bootstrap-table 及其中文包，再 mount」——
 * 因为当时页面表格由该插件渲染，且它内置 core-js 会覆盖原生 Symbol，
 * 必须刻意安排在 Vue 模块初始化之后。
 *
 * 后台 5 个列表页现已全部迁移为纯 Vue 渲染（v-for + 自建分页/排序），
 * 全仓已无任何有效的 bootstrapTable() 调用，因此该步骤整体移除：
 * 后台每个页面少下载约 135KB JS（108KB + 27KB），
 * 同时也去掉了「插件自带 core-js 污染原生 Symbol」这一事故来源。
 */
router.isReady().then(() => {
  app.mount('#app')
  // 供端到端验证使用：记录挂载时刻，用于断言所有 CSS 均在渲染前加载完成（避免 FOUC）
  window.__appMountedAt = performance.now()
})
