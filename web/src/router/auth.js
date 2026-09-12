import { createRouter, createWebHistory } from 'vue-router'

/**
 * 前台认证路由（登录/注册/找回密码）—— 独立入口 auth.html
 * 干净路径：/auth/login、/auth/register、/auth/forgot
 */
const routes = [
  { path: '/auth/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { title: '登录 · 落款' } },
  { path: '/auth/register', name: 'register', component: () => import('@/views/auth/RegisterView.vue'), meta: { title: '注册 · 落款' } },
  { path: '/auth/forgot', name: 'forgot', component: () => import('@/views/auth/ForgotView.vue'), meta: { title: '找回密码 · 落款' } },
  { path: '/auth/:pathMatch(.*)*', redirect: '/auth/login' },
  { path: '/:pathMatch(.*)*', redirect: '/auth/login' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(to => {
  if (to.meta.title) document.title = to.meta.title
})

export default router
