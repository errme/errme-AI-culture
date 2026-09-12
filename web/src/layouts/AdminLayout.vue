<template>
  <div class="coder-layout-web">
    <div class="coder-layout-container">
      <!-- ===== 左侧导航（原 admin/common/include.html :: #asideStyle） ===== -->
      <aside class="coder-layout-sidebar">
        <div class="sidebar-header">
          <router-link to="/admin" class="ad-brand">
            <i class="mdi mdi-flower-tulip-outline"></i>
            <span>遇你<small>后台管理</small></span>
          </router-link>
        </div>
        <div class="coder-layout-sidebar-scroll">
          <nav class="sidebar-main">
            <ul class="nav nav-drawer">
              <li class="nav-item" :class="{ active: isHome }">
                <router-link to="/admin"><span>后台首页</span></router-link>
              </li>

              <li v-for="pmenu in menus" :key="pmenu.id" class="nav-item nav-item-has-subnav"
                  :id="'nav_' + pmenu.id" :class="{ active: isParentOpen(pmenu), open: isGroupOpen(pmenu.id) }">
                <!-- 展开状态完全由 Vue 管理（v-show + open 类）。
                     注意：这里刻意不用 jQuery 的 slideToggle —— 路由切换时 Vue 会重渲染侧边栏，
                     jQuery 动画定时器若仍持有被替换的节点，会抛
                     “Cannot read properties of null (reading 'parentNode')”，
                     并导致之后所有菜单点击失效（真实用户表现为「点一次之后再点没反应」）。 -->
                <a href="javascript:void(0)" @click.prevent="toggleSubmenu(pmenu)">
                  <span>{{ pmenu.name }}</span>
                </a>
                <ul class="nav nav-subnav" v-show="isGroupOpen(pmenu.id)">
                  <li v-for="cmenu in (pmenu.menus || [])" :key="cmenu.id" :class="{ active: isActive(cmenu.url) }">
                    <a :id="'id_' + cmenu.id" href="javascript:void(0)" @click.prevent="goPage(cmenu.url)">
                      <span>{{ cmenu.name }}</span>
                    </a>
                  </li>
                </ul>
              </li>
            </ul>
          </nav>
        </div>
        <!-- 底部入口放在滚动区之外：侧边栏是 flex 纵向布局，滚动再长它也不会被挤走 -->
        <div class="ad-sidebar-foot">
          <!--
            运维工具：清单来自 GET /api/admin/devtools（后端按当前生效配置输出）。
            刻意不在前端写死路径 —— 改了 app.druid.stat.path / app.api-doc.ui-path
            之后菜单会自动跟随；被关闭的工具也不会出现在这里。
          -->
          <div v-if="tools.length" class="ad-devtools">
            <div class="ad-devtools__title">运维工具</div>
            <a v-for="t in tools" :key="t.key" :href="t.url"
               :target="t.newTab ? '_blank' : '_self'" :rel="t.newTab ? 'noopener' : null"
               :title="t.description">
              <i :class="t.icon"></i> {{ t.name }}
            </a>
          </div>
          <a href="/" target="_blank" rel="noopener"><i class="mdi mdi-open-in-new"></i> 打开前台站点</a>
        </div>
      </aside>

      <!-- ===== 头部信息（原 #headerStyle） ===== -->
      <header class="coder-layout-header" style="background-image: url('/static/admin/images/bg.jpg')">
        <nav class="navbar navbar-default">
          <div class="topbar">
            <div class="topbar-left">
              <div class="coder-aside-toggler" title="收起/展开侧边栏">
                <span class="coder-toggler-bar"></span>
                <span class="coder-toggler-bar"></span>
                <span class="coder-toggler-bar"></span>
              </div>
              <span class="ad-topbar-title">{{ currentTitle }}</span>
            </div>

            <ul class="topbar-right">
              <li class="dropdown dropdown-profile">
                <a href="javascript:void(0)" data-toggle="dropdown">
                  <img class="img-avatar img-avatar-48 m-r-10" :src="avatar" alt="用户头像" />
                  <span><span>{{ username }}</span> <span class="caret"></span></span>
                </a>
                <ul class="dropdown-menu dropdown-menu-right">
                  <li><a href="javascript:void(0)" @click.prevent="goProfile"><i class="mdi mdi-account"></i> 个人信息</a></li>
                  <li class="divider"></li>
                  <li><a href="javascript:void(0)" @click.prevent="logout"><i class="mdi mdi-logout-variant"></i> 退出登录</a></li>
                </ul>
              </li>
            </ul>
          </div>
        </nav>
      </header>

      <!-- ===== 页面主要内容 ===== -->
      <main class="coder-layout-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminMenus, adminProfile, devTools } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { avatarUrl } from '@/utils/format'
import { loadScript, loadScriptFresh } from '@/utils/loadScript'
import { LEGACY } from '@/utils/legacyAssets'

/**
 * 后台管理框架（对应原 admin/common/include.html 的侧边栏 + 顶栏 + main.min.js 主题行为）
 * 菜单来自 /api/admin/menus（按角色返回），不再依赖 Thymeleaf session。
 */
const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()
const menus = ref([])

/**
 * 运维工具清单（Druid 监控 / API 文档 / 运行指标）。
 * 由后端按当前生效配置返回，前端只渲染 —— 路径改了菜单自动跟随，不写死。
 */
const tools = ref([])

/** 拉取运维工具清单：失败不影响后台主流程，只是不显示这一块 */
async function loadDevTools() {
  try {
    const list = await devTools()
    tools.value = Array.isArray(list) ? list : []
  } catch (e) {
    tools.value = []
  }
}

/** 顶栏显示的当前页标题（来自路由 meta，避免用户不知道自己在哪一页） */
const currentTitle = computed(() => (route.meta && route.meta.title) || '后台管理')
const profile = ref(null)

const username = computed(() => {
  const u = (profile.value && profile.value.user) || adminStore.profile || {}
  return u.username || adminStore.account || '管理员'
})
const avatar = computed(() => {
  const u = (profile.value && profile.value.user) || adminStore.profile || {}
  return avatarUrl(u.headImg) || '/static/admin/images/reg.png'
})

const isHome = computed(() => route.path === '/admin' || route.path === '/admin/')

/**
 * 旧菜单 URL（/culture/index、/culture/add、/user/index…）→ 新的 SPA 干净路由。
 * 菜单数据仍来自数据库，前端做一层映射，避免为了改 URL 而动库。
 */
const MENU_ROUTE_MAP = {
  '/culture/index': '/admin/culture',
  '/culture/add': '/admin/culture',
  '/category/index': '/admin/category',
  '/announcement/index': '/admin/announcement',
  '/sentence/index': '/admin/sentence',
  '/user/index': '/admin/user',
  '/mail/index': '/admin/mail',
  '/comment/index': '/admin/comment',
  '/tag/index': '/admin/tag',
  '/oplog/index': '/admin/oplog',
  '/permission/index': '/admin/permission', // 菜单权限设置
  '/role/index': '/admin/role',              // 角色管理
  '/recycle/index': '/admin/recycle'         // 回收站
}

/**
 * 把菜单表里的 url 解析成 SPA 路由。
 *
 * <p>两种取值都支持：</p>
 * <ol>
 *   <li><b>直接写 SPA 路径</b>（推荐，形如 {@code /admin/settings}）—— 原样返回。
 *       这样新增后台页面只需往 sys_menu 插一行，<b>不需要改前端代码</b>；</li>
 *   <li>老菜单里遗留的旧 URL（{@code /culture/index} 这类）—— 查下面的映射表转换。</li>
 * </ol>
 * <p>早期实现只认映射表，等于「每加一个后台页面都要来前端加一条映射」，属于硬编码耦合；
 * 现在优先走第 1 种，映射表只用于兼容存量数据。</p>
 */
function routeOf(url) {
  if (!url) return null
  if (url.startsWith('/admin')) return url
  return MENU_ROUTE_MAP[url] || null
}

function isActive(url) {
  const target = routeOf(url)
  return !!target && (route.path === target || route.path.startsWith(target + '/'))
}

function isParentOpen(pmenu) {
  return (pmenu.menus || []).some(c => isActive(c.url))
}

/**
 * 父菜单展开/收起（手风琴：展开一个会自动收起同级其它分组）。
 *
 * 纯 Vue 状态驱动，不用 jQuery 动画 —— 原因见模板里的注释（jQuery 动画 + Vue 重渲染 =
 * parentNode 空指针，会让后续点击全部失效）。
 */
const openedGroups = ref([])

function isGroupOpen(id) {
  return openedGroups.value.indexOf(id) > -1
}

function toggleSubmenu(pmenu) {
  const id = pmenu.id
  if (isGroupOpen(id)) {
    openedGroups.value = openedGroups.value.filter(x => x !== id)
  } else {
    openedGroups.value = [id]          // 手风琴：同时只展开一个分组
  }
}

/** 当前路由所在分组默认展开 */
function expandActiveGroup() {
  const hit = menus.value.find(m => isParentOpen(m))
  openedGroups.value = hit ? [hit.id] : []
}

/** 主题小部件（原 main.min.js 在 DOM ready 初始化；SPA 每次路由切换后补一次） */
function initThemeWidgets() {
  const $ = window.jQuery
  if (!$) return
  const $tips = $('[data-toggle="tooltip"]')
  if ($tips.length) $tips.tooltip({ container: 'body' })
}

/** 左侧菜单跳转（原 goPage(id, url) 会跳 Thymeleaf 页面，这里改为路由跳转） */
/**
 * 菜单跳转。
 * 注意：文化菜单里「文化列表」(/culture/index) 与「添加文化」(/culture/add) 指向同一个页面组件，
 * 若都 push 到 /admin/culture，从列表页点「添加文化」会因为「目标路由相同」而毫无反应。
 * 因此后者带上 ?action=add，由页面监听后自动打开新增弹窗。
 */
function goPage(url) {
  if (url === '/culture/add') {
    router.push({ path: '/admin/culture', query: { action: 'add', t: String(Date.now()) } })
    return
  }
  const target = routeOf(url)
  if (target) router.push(target)
  else if (url) window.location.assign(url.startsWith('/') ? url : '/' + url)
}

function goProfile() {
  router.push('/me')
}

async function logout() {
  await adminStore.logout()
  window.location.assign('/admin/login')
}

onMounted(async () => {
  // 运维工具清单独立加载：不 await，失败也不影响后台主流程
  loadDevTools()
  // 主题脚本（侧边栏滚动条 + 折叠按钮）按 DOM 初始化，必须等 Vue 渲染完成
  await nextTick()
  try {
    // 主题脚本依赖 PerfectScrollbar（侧边栏滚动条）：两者都改为按需加载，不再进入口 head
    await loadScript(LEGACY.perfectScrollbarJs)
    await loadScriptFresh(LEGACY.mainThemeJs)
  } catch (e) {
    console.warn('[AdminLayout] 后台主题脚本加载失败：', e.message)
  }

  try {
    profile.value = await adminProfile()
    adminStore.profile = profile.value
  } catch (e) { /* 由 http 拦截器统一处理 401 */ }
  try {
    menus.value = await adminMenus()
  } catch (e) {
    menus.value = []
  }
  // 菜单渲染完成后再展开当前分组 / 初始化 tooltip
  await nextTick()
  expandActiveGroup()
  initThemeWidgets()
})

/** 路由切换后重新初始化内容区里的小部件（tooltip 等由子页面渲染） */
watch(() => route.fullPath, async () => {
  await nextTick()
  initThemeWidgets()
})

onBeforeUnmount(() => {
  // 主题脚本绑定的侧边栏遮罩与展开状态需要清理，避免残留到下次挂载
  const mask = document.querySelector('.coder-mask-modal')
  if (mask) mask.remove()
  document.body.classList.remove('coder-layout-sidebar-close')
  const $ = window.jQuery
  if ($) $('.coder-aside-toggler').off('click')
})
</script>

<style scoped>
.ad-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-size: 15px;
  letter-spacing: 1px;
  padding-left: 14px;
}
.ad-brand i { font-size: 20px; color: #8ab4ff; }
.ad-brand small { display: block; font-size: 11px; opacity: 0.72; letter-spacing: 0; }

.ad-topbar-title {
  margin-left: 12px;
  font-size: 14px;
  font-weight: 600;
  color: #33404d;
  white-space: nowrap;
}
@media (max-width: 576px) {
  .ad-topbar-title { display: none; }
}

.ad-sidebar-foot {
  padding: 14px 16px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.ad-sidebar-foot a {
  display: flex;
  align-items: center;
  gap: 6px;
  color: rgba(255, 255, 255, 0.72);
  font-size: 12.5px;
}
.ad-sidebar-foot a:hover { color: #fff; }

/* 运维工具区（Druid 监控 / API 文档 / 运行指标） */
.ad-devtools {
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.12);
}
.ad-devtools__title {
  margin-bottom: 6px;
  font-size: 11px;
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.42);
  text-transform: uppercase;
}
.ad-devtools a {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  margin-left: -8px;
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 12.5px;
  transition: background-color 0.18s ease, color 0.18s ease;
}
.ad-devtools a:hover {
  background-color: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.ad-devtools a i { font-size: 14px; }
</style>
