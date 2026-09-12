import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from '@/App.vue'
import router from '@/router/front'

/**
 * 前台站点入口（front.html）
 * 路由：/、/culture、/culture/:id、/sentence、/about、/center
 * 页面用到的原有 CSS / jQuery 插件由各视图在挂载后自行加载（utils/loadScript）。
 *
 * 与后台入口一致：等首次导航解析完成后再 mount，避免 RouterView 先渲染空树
 * 导致的 vnode 更新异常。
 */
const app = createApp(App).use(createPinia()).use(router)

router.isReady().then(() => {
  app.mount('#app')
  // 供端到端验证使用：记录挂载时刻，用于断言所有 CSS 均在渲染前加载完成（避免 FOUC）
  window.__appMountedAt = performance.now()
})
