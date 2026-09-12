import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from '@/App.vue'
import router from '@/router/auth'
import { loadScripts } from '@/utils/loadScript'

/**
 * 前台认证入口（auth.html）：/auth/login、/auth/register、/auth/forgot
 *
 * orb 背景动画脚本（orb.js / controller.js）在加载时就会访问 .intro-canvas / .guts，
 * 因此必须等 Vue 渲染完成后再加载，否则动画会静默失败（与原始页面表现不一致）。
 */
const app = createApp(App).use(createPinia()).use(router)

router.isReady().then(async () => {
  app.mount('#app')
  try {
    await loadScripts([
      '/static/auth/js/vendor/anime.js',
      '/static/auth/js/util.js',
      '/static/auth/js/vendor/three.min.js',
      '/static/auth/js/vendor/postprocessing.min.js',
      '/static/auth/js/orb.js',
      '/static/auth/js/controller.js'
    ])
  } catch (e) {
    console.warn('[auth] orb 动画资源加载失败：', e.message)
  }
})
