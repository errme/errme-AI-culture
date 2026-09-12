# 前端 Vue3 迁移说明（前后端分离 + 去 .html）

> 本次改造把项目从「Thymeleaf 服务端渲染 + 少量 JWT 接口」升级为
> **Vue 3 完整 SPA（前台 + 后台） + Spring Boot 纯 REST API**，并把所有页面 URL 去掉 `.html`。
> 约束：**外观保持不变** —— 不引入 UI 组件库，继续引用原有 CSS 与 jQuery 插件。

## 一、为什么是「多入口 SPA」而不是单一 SPA

前台站点用 `index.css + jQuery + OwlCarousel + typed.js`，前台认证页用 `base.css/orb.css/auth.css + three.js`，
后台用 `bootstrap + bootstrap-table + jconfirm + fileinput`。这几套样式互相污染（body/reset/全局选择器），
塞进同一个 HTML 入口会导致「样式变了」。

因此 `web/` 采用 **4 个入口**，每个入口只加载它原本那套资源：

| 入口 | 路径 | 加载的原有资源 |
|---|---|---|
| `front.html` | `/`、`/culture`、`/culture/:id`、`/sentence`、`/about`、`/center` | `/index/**` 全套 |
| `auth.html` | `/auth/login`、`/auth/register`、`/auth/forgot` | `/static/auth/{css,orb,three}` |
| `admin-login.html` | `/admin/login` | 同上（后台登录沿用「落款」视觉） |
| `admin.html` | `/admin/**` | `/static/admin/**` 全套 |

## 二、URL 约定（无 .html）

| 旧地址 | 新地址 |
|---|---|
| `/index.html`、`/index` | `/` |
| `/culture/detail/id/12`、`/culture/detail?id=12` | `/culture/12` |
| `/static/auth/login.html`、`/static/auth/login`、`/login` | `/auth/login` |
| `/static/auth/register.html`、`/signup` | `/auth/register` |
| `/static/auth/forgot.html` | `/auth/forgot` |
| `/static/auth/admin-login.html`、`/toLogin` | `/admin/login` |
| `/admin`（后台首页） | `/admin` |
| 后台各页 | `/admin/culture`、`/admin/category`、`/admin/announcement`、`/admin/sentence`、`/admin/user`、`/admin/mail` |

301 跳转在**托管层**实现（`deploy/nginx.conf.example` 与 `web/tools/serve-dist.mjs`），
后端不再保留任何页面路由（旧地址在后端一律 404）。

## 三、目录与数据流

```
web/src/
  api/http.js     双 Token axios 实例（frontHttp / adminHttp）+ 响应解包（{code,data} 与 {success,data} 两种信封）
  api/{front,auth,admin}.js   接口封装
  router/{front,auth,admin}.js  history 路由 + 登录/管理员守卫
  stores/{frontUser,admin}.js  pinia 状态
  layouts/{FrontLayout,AdminLayout}.vue  复刻原 topbar/footer/侧边栏/顶栏
  utils/loadScript.js  按需加载原有 JS（必须先渲染 DOM 再执行老插件）
  views/{front,auth,admin}/*.vue  1:1 迁移的页面
```

后端只提供：`/api/**`（REST）、`/static/**`、`/index/**`（设计资源）、`/upload/media/**`（正文媒体）、
`/showFmImg/**`、`/showimage/**`（封面/头像）、`/file/**`（上传）、`/user/downloadExcel`（Excel 导出）。

## 四、迁移中踩到并修复的坑（都已在代码里注释）

1. **Vue 会把模板里的 `<script>` 丢掉**：首页的 underscore 轮播模板改为在 `onMounted` 注入 `<head>`。
2. **老插件必须晚于 DOM**：`orb.js/controller.js`（认证页 orb 动画）、后台 `main.min.js`（侧边栏滚动条/折叠）
   在脚本加载时即按 DOM 初始化，改为「Vue 渲染完成后再动态加载」，否则报
   `no element is specified to initialize PerfectScrollbar` 且动画/交互失效。
3. **`jquery.cdtop.min.js` 内嵌 jQuery 1.7.2** 会顶掉全局 jQuery，导致 bootstrap 的 `.carousel()` 等失效；
   改为用原生实现「返回顶部」，不再加载该文件。
4. **bootstrap-table 是 1.15.3**：自定义 `ajax` 的**返回值会被丢弃**，必须显式 `params.success({total,rows})`；
   且默认 `queryParamsType='limit'`，`params.data` 只有 `offset/limit`，需要换算 `pageNumber`。
5. **文件域上的 `value="上传"`**：HTML 里浏览器忽略，但 Vue 会当 DOM property 设置，抛 `InvalidStateError`；已移除该无效属性。
6. **`<template v-else>` 在 `<tbody>` 内生成 Fragment**，异步数据到达时补丁错位并抛
   `Cannot read properties of null (reading 'subTree')`；改为计算属性 + 元素级 `v-if/v-for`。
7. **入口必须 `router.isReady()` 后再 mount**：否则 RouterView 先用未匹配的空树渲染，随后更新崩溃（同上错误）。
8. **后台路由不要用 `createWebHistory('/admin')` 的 base**：访问 `/admin`（无尾斜杠）时的规范化重定向会触发同类崩溃；
   改为默认 base + 绝对路径。
10. **后台左侧菜单点击无反应**：父菜单原为 `href="javascript:expandNode()"`，而 `expandNode()` 定义在
    `soulcoder.js` 里（已随页面控制器一起移除）；同时 `main.min.js` 是在 DOM ready 时按选择器绑定
    `.nav-item-has-subnav > a`，SPA 的菜单是**异步渲染**的，等渲染出来时它已错过绑定。
    现由 `AdminLayout` 用 Vue 接管子菜单展开/收起（手风琴 + `slideToggle` + 滚动定位，逻辑与原
    `main.min.js` 一致），主题脚本只保留滚动条与侧边栏折叠；`[data-toggle="tooltip"]` 在每次路由切换后补初始化。

11. **打开页面先闪一下无样式内容（FOUC）**：各页面的 CSS 原本在 `onMounted` 里用 `loadStyle()` 动态插入，
    而 Vue 已经先渲染了一帧 → 浏览器先按无样式排版、样式到位后再重排。
    现在由**路由守卫在导航完成前预加载该页所需样式**（`meta.styles`，`loadStyle` 去重+缓存），
    各页仍只加载自己那套 CSS（不跨页污染）；`loadStyle` 还会检测 head 里已存在的同款 `<link>` 并跳过。
    入口在 mount 后写入 `window.__appMountedAt`，`e2e-check.mjs` 据此断言「所有 CSS 都在渲染前加载完成」。

9. **逻辑删除未过滤（后端真实 bug）**：`biz_culture` 的列表/热门/详情/统计 SQL 漏了 `deleted=0`，
   已删除的文化仍会出现在前台与后台列表中 —— 已全部补上。

12. **「点第一个菜单能跳，之后所有菜单点击全部失效」（最隐蔽的一个）**
    现象：进入后台后第一次点菜单正常，第二次开始 URL 会变但页面/交互全废，控制台报
    `Cannot destructure property 'bum' of 'instance' as it is null` 或
    `Cannot read properties of null (reading 'parentNode')`（都在 `componentUpdateFn / unmount` 里）。

    定位过程（可复用）：用 `tools/click-seq.mjs` 在同一页面里连续真实点击复现；把构建切到
    `--mode development` 保留函数名与组件 `__file`；再对 Vue 运行时的 `unmount` / `nodeOps.parentNode`
    插临时探针，发现崩在一个 **`type === Text`（`Symbol.for('v-txt')`）的文本 vnode** 上：
    `shapeFlag = 12`（`STATEFUL_COMPONENT(4) | TEXT_CHILDREN(8)`），而文本 vnode 正常应当是 `8`。

    根因：`bootstrap-table.min.js`（和 `quill.js`）**内置了 core-js**，加载时会用自带的 Symbol polyfill
    覆盖原生 `Symbol`。它是在 `admin.html` 的 `<head>` 里同步执行的，而入口是 `type="module"`（延后执行），
    于是 **Vue 模块初始化时拿到的 `Symbol.for('v-txt')` 是「假 Symbol 对象」**（`typeof === 'object'`）。
    `createVNode` 用 `isObject(type)` 判断是否组件，假 Symbol 让文本节点被标成组件节点；
    首次导航卸载旧页面时走进 `unmountComponent(vnode.component /* null */)` 直接抛错，
    Vue 的补丁流程中断，后续路由更新全部失效。core-js 用 `Object.defineProperty` 换掉全局，
    因此「加 setter 拦截」无效，必须从**加载顺序**上解决。

    修复：
    - `admin.html` 里 **不再静态引入 bootstrap-table**，改由 `src/entries/admin.js` 在
      `import`（Vue 已初始化）之后、`app.mount()` 之前用 `loadScript()` 按序加载；
    - `admin.html` `<head>` 最前面加「原生 Symbol 保护」（`window.__guardNativeSymbol`）：
      读取永远是原生 Symbol，第三方覆盖写入被忽略；`loadScript`/`loadScriptFresh` 每次动态加载脚本后
      都会复查一次（覆盖 `quill.js` 这类运行时才加载的库）。
    - 约定：**任何内置 core-js 的老库都必须在 Vue 初始化之后加载**，不要放进入口 HTML 的 `<head>`。

13. **抢在编辑器资源加载完前点「添加文化」会没有富文本区**：`quill.js/culture-editor.js` 是
    `onMounted` 里异步加载的，`openAdd()/edit()` 直接创建编辑器会静默失败。
    现在两者先 `await ensureEditorAssets()`，并把 `fileinput('clear')` 这类「控件可能尚未初始化」的
    调用单独 try/catch，保证弹窗主体与编辑器一定就位。

## 五、验证

```bash
cd web
npm run build:all             # 构建 4 个入口 + 预渲染 36 条前台路由
node tools/serve-dist.mjs     # 本机等价 Nginx（静态 + history 回退 + 旧地址 301 + 接口反代）
node tools/e2e-check.mjs      # 无头 Chrome + CDP 端到端断言（47 项）
node tools/debug-page.mjs http://localhost:8080/admin admin   # 单页定点调试（打印真实异常堆栈）
node tools/debug-menu.mjs     # 后台菜单交互（展开/手风琴/跳转/侧边栏折叠，8 项）
node tools/click-check.mjs    # 真实鼠标点击父分组/子项（3 项）
node tools/click-all-menus.mjs # 逐个菜单点击（每项重新加载，10 项）
node tools/click-seq.mjs      # 同一页面内连续点击菜单（10 项，本轮回归的关键用例）
node tools/screenshot.mjs http://localhost:8080/ tools/_shot.png full   # 整页截图（视觉核对）
```

> ⚠️ **只改样式/组件时也要用 `build:all`，不要用 `npm run build`**：Vite 默认 `emptyOutDir`，
> `npm run build` 会把 `dist/` 清空重建，**上一步预渲染出来的 36 条前台路由（`dist/culture/**` 等）
> 会一起被删掉**，只剩 SPA 入口 HTML。功能测试仍可能全绿（history 回退会把请求兜到 `front.html`），
> 但 SEO 快照与静态托管的产物就残缺了。改完样式后要么跑 `build:all`，要么补跑一次
> `npm run prerender`（需要后端 8081 已启动）。


`e2e-check.mjs` 覆盖：旧地址 301、前台登录真实提交与 Token 落库、前台 5 个页面渲染、
后台登录页与双 Token 共存、后台 7 个页面（含表格数据行数）渲染、
**原生 Symbol 未被 core-js 覆盖 / 旧插件在 Vue 之后加载 / quill 动态加载后 Symbol 仍原生且编辑器可用**、
前台 Token 访问后台接口被 403、各页「CSS 先于渲染就位（无 FOUC）」（34 项）。
`debug-menu.mjs` 覆盖：菜单初始收起、点击展开、子菜单跳转取数、手风琴互斥、直达子页面自动展开、侧边栏折叠（8 项）。
`click-seq.mjs` 覆盖「一个页面里连续点 10 个菜单」——这正是上面第 12 条 bug 的复现路径，改为回归用例。
`tools/diag-detach.mjs` 是可复用的「DOM 被谁摘掉 / vnode 树为何错乱」诊断工具（注入 DOM 破坏性操作探针 +
Vue `unmount` 探针，输出分组调用栈），排查同类「路由切换崩溃」时可直接复用。
