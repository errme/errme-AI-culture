import { createRouter, createWebHistory } from "vue-router";
import { tokenStore } from "@/api/http";
import { loadScript, loadStyle } from "@/utils/loadScript";
import { LEGACY } from "@/utils/legacyAssets";
import AdminLayout from "@/layouts/AdminLayout.vue";

/**
 * 后台管理路由（history 模式，干净路径 /admin、/admin/culture …）
 *
 * 注意：这里刻意【不使用 createWebHistory('/admin') 的 base】。
 * base 模式在访问 /admin（无尾斜杠）时会产生一次「规范化重定向」，
 * 使顶层 RouterView 先渲染出一个尚未挂载的组件 vnode，随后更新时崩溃
 * （TypeError: Cannot read properties of null (reading 'subTree')）。
 * 改为默认 base + 绝对路径，行为等价但稳定。
 */
const routes = [
  {
    path: "/admin/login",
    beforeEnter: () => {
      window.location.assign("/admin/login");
    },
  },
  {
    path: "/admin",
    component: AdminLayout,
    meta: { requiresAdmin: true },
    children: [
      {
        path: "",
        name: "dashboard",
        component: () => import("@/views/admin/DashboardView.vue"),
        // 首页已改为纯数据看板（不再使用 Chart.js），因此无需再加载 568KB 的图表库
        meta: { title: "后台首页" },
      },
      {
        path: "culture",
        name: "cultureManage",
        component: () => import("@/views/admin/CultureManageView.vue"),
        meta: {
          title: "文化管理",
          styles: [LEGACY.validatorCss, LEGACY.fileinputCss],
          scripts: [LEGACY.validatorJs, LEGACY.fileinputJs, LEGACY.fileinputZhJs]
        },
      },
      {
        path: "category",
        name: "categoryManage",
        component: () => import("@/views/admin/CategoryManageView.vue"),
        meta: { title: "分类管理", styles: [LEGACY.validatorCss], scripts: [LEGACY.validatorJs] },
      },
      {
        path: "announcement",
        name: "announcementManage",
        component: () => import("@/views/admin/AnnouncementManageView.vue"),
        meta: { title: "公告管理", styles: [LEGACY.validatorCss], scripts: [LEGACY.validatorJs] },
      },
      {
        path: "sentence",
        name: "sentenceManage",
        component: () => import("@/views/admin/SentenceManageView.vue"),
        meta: { title: "句子管理", styles: [LEGACY.validatorCss], scripts: [LEGACY.validatorJs] },
      },
      {
        path: "user",
        name: "userManage",
        component: () => import("@/views/admin/UserManageView.vue"),
        meta: {
          title: "用户管理",
          styles: [LEGACY.validatorCss, LEGACY.fileinputCss],
          scripts: [LEGACY.validatorJs, LEGACY.fileinputJs, LEGACY.fileinputZhJs]
        },
      },
      {
        path: "mail",
        name: "mailSettings",
        component: () => import("@/views/admin/MailSettingsView.vue"),
        meta: { title: "邮件设置" },
      },
      {
        path: "me",
        name: "adminProfile",
        component: () => import("@/views/admin/ProfileView.vue"),
        meta: { title: "个人资料", styles: [LEGACY.fileinputCss], scripts: [LEGACY.fileinputJs, LEGACY.fileinputZhJs] },
      },
      {
        path: "settings",
        name: "systemSettings",
        component: () => import("@/views/admin/SettingsView.vue"),
        // 系统设置：配置项、分组、类型、范围全部由后端 sys_config 驱动，前端无对照表
        meta: { title: "系统设置" },
      },
      {
        path: "comment",
        name: "commentManage",
        component: () => import("@/views/admin/CommentManageView.vue"),
        meta: { title: "评论审核" },
      },
      {
        path: "tag",
        name: "tagManage",
        component: () => import("@/views/admin/TagManageView.vue"),
        meta: { title: "标签管理" },
      },
      {
        path: "recycle",
        name: "recycleBin",
        component: () => import("@/views/admin/RecycleView.vue"),
        meta: { title: "回收站" },
      },
      {
        path: "role",
        name: "roleManage",
        component: () => import("@/views/admin/RoleView.vue"),
        meta: { title: "角色管理" },
      },
      {
        path: "permission",
        name: "permissionSettings",
        component: () => import("@/views/admin/PermissionView.vue"),
        meta: { title: "菜单权限" },
      },
      {
        path: "oplog",
        name: "opLog",
        component: () => import("@/views/admin/OpLogView.vue"),
        meta: { title: "操作日志" },
      },
    ],
  },
  { path: "/:pathMatch(.*)*", redirect: "/admin" },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

/**
 * 目标页面所需的老静态资源：
 *  · styles  —— 渲染前必须就位的 CSS（避免 FOUC）
 *  · scripts —— 该页用到的 jQuery 插件（按顺序加载，例如 fileinput 必须先于其中文语言包）
 * 只在首次进入该页时真正走网络，之后命中 loadScript/loadStyle 的缓存。
 */
async function preloadAssets(to) {
  const styles = to.meta.styles || [];
  const scripts = to.meta.scripts || [];
  if (styles.length) await Promise.all(styles.map(href => loadStyle(href).catch(() => href)));
  for (const src of scripts) {
    try {
      await loadScript(src);
    } catch (e) {
      console.warn("[admin] 页面资源加载失败：", src, (e && e.message) || e);
    }
  }
}

router.beforeEach(async (to) => {
  const token = tokenStore.get("admin");
  if (to.meta.requiresAdmin && !token) {
    window.location.assign("/admin/login");
    return false;
  }
  await preloadAssets(to);
  if (to.meta.title) document.title = to.meta.title + " · 后台管理";
});

export default router;
