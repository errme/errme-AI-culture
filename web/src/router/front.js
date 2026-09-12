import { createRouter, createWebHistory } from 'vue-router'
import { tokenStore } from '@/api/http'
import { loadStyle } from '@/utils/loadScript'

/**
 * 前台站点路由（history 模式，无 .html 后缀）
 * 旧地址 /culture/detail/id/:id 等一并重定向，保证老链接可用。
 *
 * meta.styles：该页面在【渲染前】必须就位的样式表。
 * 这些 CSS 原本由各页面在 onMounted 里动态插入，会导致「先渲染无样式内容、随后样式才到位」
 * 的闪屏（FOUC）。现在改由路由守卫在导航完成前预加载（loadStyle 内部去重 + 缓存），
 * 各页面依旧只加载自己那套样式，不会互相污染。
 */
const FRONT_COMMON = [
  '/index/css/common.css',          // 原 common.html topbar 片段
  '/index/css/font-awesome.css',    // 原 common.html topbar 片段
  '/index/css/loader.css'           // 原 common.html loader 片段
]
const CD_TOP = ['/index/css/cd-top.css']   // 返回顶部（culture/detail 原本未启用）

const routes = [
  {
    path: '/', name: 'home', component: () => import('@/views/front/HomeView.vue'),
    meta: { title: '遇你', styles: [...FRONT_COMMON, ...CD_TOP] }
  },
  {
    path: '/culture', name: 'cultureList', component: () => import('@/views/front/CultureListView.vue'),
    meta: {
      title: '倾一世',
      styles: [...FRONT_COMMON,
        '/index/css/culture/font-awesome.min.css',
        '/index/css/culture/bootstrap-select.min.css',
        '/index/css/culture/bootstrap.min.css',
        '/index/css/culture/style.css']
    }
  },
  {
    path: '/culture/:id(\\d+)', name: 'cultureDetail', component: () => import('@/views/front/CultureDetailView.vue'),
    meta: { styles: [...FRONT_COMMON, '/index/css/diaspora.css', '/index/css/default-skin.css'] }
  },
  {
    path: '/sentence', name: 'sentence', component: () => import('@/views/front/SentenceView.vue'),
    meta: { title: '琴弦上', styles: [...FRONT_COMMON, '/index/css/zhilou.css', ...CD_TOP] }
  },
  {
    path: '/about', name: 'about', component: () => import('@/views/front/AboutView.vue'),
    meta: { title: '关于我', styles: [...FRONT_COMMON, ...CD_TOP] }
  },
  {
    path: '/search', name: 'search', component: () => import('@/views/front/SearchView.vue'),
    meta: { title: '寻一寻', styles: [...FRONT_COMMON, ...CD_TOP] }
  },
  {
    path: '/tag/:id(\\d+)', name: 'tag', component: () => import('@/views/front/TagView.vue'),
    meta: { title: '标签 · 遇你', styles: [...FRONT_COMMON, ...CD_TOP] }
  },
  {
    path: '/center', name: 'center', component: () => import('@/views/front/CenterView.vue'),
    meta: {
      requiresLogin: true, title: '个人中心',
      styles: [...FRONT_COMMON, ...CD_TOP,
        '/index/css/plugin.css', '/index/css/responsive1.css', '/index/css/style-5.0.11.css',
        '/index/css/font1.css', '/index/css/frontend.min.css']
    }
  },

  /* ===== 旧地址兼容（Thymeleaf 时代的 URL） ===== */
  { path: '/culture/detail/id/:id(\\d+)', redirect: to => `/culture/${to.params.id}` },
  { path: '/culture/detail', redirect: to => ({ path: '/culture/' + to.query.id }) },
  { path: '/index', redirect: '/' },
  { path: '/login', redirect: '/auth/login' },
  { path: '/signup', redirect: '/auth/register' },
  { path: '/logout', redirect: '/' },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/** 目标页面所需样式：先加载完成，再进入页面（避免 FOUC） */
async function preloadStyles(to) {
  const list = to.meta.styles
  if (!list || !list.length) return
  await Promise.all(list.map(href => loadStyle(href).catch(() => href)))
}

router.beforeEach(async to => {
  if (to.meta.requiresLogin && !tokenStore.get('front')) {
    // /auth/login 属于独立入口（auth.html），前台路由表内不存在该路径，
    // 必须整页跳转，否则会被下面的 catch-all 重定向到首页。
    window.location.assign('/auth/login?redirect=' + encodeURIComponent(to.fullPath))
    return false
  }
  // 统一在渲染前把该页样式准备好（首次进入需等 CSS 到位；已缓存的页面瞬时完成）
  await preloadStyles(to)
  if (to.meta.title) document.title = to.meta.title
})

export default router
