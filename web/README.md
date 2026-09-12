# web —— 遇你 · Vue3 前端（前后端分离）

前台站点、前台认证、后台管理全部由本工程承载，后端只提供 REST API 与媒体文件。

## 技术栈

- Vue 3 + Vite 5 + Vue Router 4（history 模式）+ Pinia + Axios
- **不引入 UI 组件库**：前台/后台继续使用站点原有的 CSS 与 jQuery 插件（bootstrap、bootstrap-table、
  jconfirm、fileinput、OwlCarousel、typed.js、jsmodern、orb 动画等），因此**外观与改造前完全一致**。

## 目录结构

```
web/
├── front.html          # 前台站点入口（/、/culture、/sentence、/about、/center）
├── auth.html           # 前台认证入口（/auth/login、/auth/register、/auth/forgot）
├── admin-login.html    # 后台登录入口（/admin/login）
├── admin.html          # 后台管理入口（/admin/**）
├── vite.config.js      # 多入口构建 + dev 代理 + dev 环境干净 URL 重写
├── public/             # 老设计资源源目录（构建时原样复制到 dist/，URL 不变）
│   ├── index/          #   → /index/**                前台设计资源
│   └── static/         #   → /static/{admin,auth}/**  后台与认证页设计资源
├── tools/serve-dist.mjs# 本机「等价 Nginx」预览服务（无第三方依赖）
└── src/
    ├── entries/        # 4 个入口的启动脚本
    ├── api/            # http.js（双 Token + 统一解包）、front/auth/admin 接口
    ├── router/         # front/auth/admin 三套路由（干净 URL）
    ├── stores/         # pinia：前台用户、后台管理员
    ├── layouts/        # FrontLayout（loader/导航/页脚）、AdminLayout（侧边栏/顶栏）
    ├── components/     # 认证外壳 AuthShell 等
    ├── utils/          # loadScript（按需加载原有 JS）、format（封面/头像/时间）
    └── views/          # front / auth / admin 页面组件
```

> **本工程已自包含**：`npm run build` 产出的 `dist/` 就是完整可部署产物
> （含 `index/` 与 `static/` 老设计资源）。生产环境只需用 Nginx 托管 `dist/`，
> 并把 `/api`、`/upload`、`/showimage`、`/showFmImg`、`/file` 与 SEO 根路径
> 反代到 Spring Boot；配置见仓库根的 `deploy/nginx.conf.example`（Docker 用 `deploy/nginx.docker.conf`）。

## 开发

```bash
npm install
npm run dev          # http://localhost:5173 ，/api 等自动代理到后端 8081
```

开发环境同样支持干净 URL：`/`、`/culture`、`/auth/login`、`/admin`、`/admin/culture`…

## 构建与部署

```bash
npm run build        # 产出 web/dist
npm run serve:dist   # 本机等价 Nginx 预览（默认 http://localhost:8080）
```

生产用 `deploy/nginx.conf.example`（Docker 用 `deploy/nginx.docker.conf`）：
Nginx 静态托管 `dist/`（含 `index/`、`static/` 老设计资源），只把
`/api`、`/upload`、`/showFmImg`、`/showimage`、`/file` 与
`/sitemap.xml`、`/robots.txt`、`/rss.xml` 反代到 Spring Boot(8081)；
旧 `.html` 地址 301 到干净 URL。

## URL 约定（无 .html）

| 旧地址 | 新地址 |
|---|---|
| /index.html、/index | / |
| /culture/detail/id/12 | /culture/12 |
| /static/auth/login.html、/static/auth/login | /auth/login |
| /static/auth/register.html | /auth/register |
| /static/auth/forgot.html | /auth/forgot |
| /static/auth/admin-login.html、/toLogin | /admin/login |
| /admin（后台首页） | /admin |
| 后台各页 | /admin/culture、/admin/category、/admin/announcement、/admin/sentence、/admin/user、/admin/mail |

## 双 Token 约定

| | 前台（用户端） | 后台（管理端） |
|---|---|---|
| 登录接口 | `POST /api/auth/login` | `POST /api/auth/admin/login` |
| 存储键 | `localStorage.culture_front_token` | `localStorage.culture_admin_token` |
| 失败跳转 | `/auth/login` | `/admin/login` |

两个 axios 实例（`api/http.js` 的 `frontHttp` / `adminHttp`）各自携带自己的 Token，互不覆盖；
后端用不同密钥签发，前台 Token 访问后台接口会被 403 拒绝。
