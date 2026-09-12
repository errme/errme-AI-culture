import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from '@/App.vue'
import { createRouter, createWebHistory } from 'vue-router'
import { loadScripts } from '@/utils/loadScript'

/**
 * 后台登录入口（admin-login.html）：/admin/login
 * 与前台登录同一套「落款」视觉（base.css + orb.css + auth.css），但走后台令牌接口。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/admin/login', name: 'adminLogin', component: () => import('@/views/auth/AdminLoginView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/admin/login' }
  ]
})

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
    console.warn('[admin-login] orb 动画资源加载失败：', e.message)
  }
})
