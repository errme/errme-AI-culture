# culture（遇你 · 传统文化网站）

**架构**：前后端分离。前端是 **Vue 3 + Vite** 的独立工程（`web/`，前台站点 + 后台管理两个区域、四个入口），
后端是 **Spring Boot 纯 REST API**（`backend/`，不再渲染任何页面）。**URL 一律不带 `.html`**，
旧地址在托管层 301 跳转到新地址；前台界面沿用原有 CSS 与 jQuery 插件，**外观保持不变**。

**已实现的功能**：前台（首页/文化列表/详情/句子/关于/个人中心/搜索/标签页）、后台（文化/分类/公告/句子/用户/邮件/标签/评论审核/操作日志）、
全文检索、SEO（sitemap/robots/meta/OG/JSON-LD + **内容页预渲染**）、图片自动压缩与缩略图、正文目录与阅读进度、
评论（**需登录**、含 XSS 白名单清洗与审核、署名取账号资料）、操作日志审计、数据库与媒体备份。

```
浏览器 ──▶ Nginx / 本机预览服 :80|8080  ── 静态托管 web/dist（history 路由）
                     │
                     └── /api、/static、/index、/upload、/showFmImg、/showimage、/file ──▶ Spring Boot :8081
```

## 快速启动

前置：本机 MySQL 5.7+（root/123456）、Redis（6379/123456）、Node 18+、**JDK 17+**。
**不需要单独安装 Maven** —— `backend/mvnw` 自带 Maven Wrapper（锁定 3.9.16），
Spring Boot 3 要求 Maven ≥ 3.6.3，用 wrapper 可避免本机版本过旧导致的构建失败。

> 后端已升级到 **Spring Boot 3.4.3**（Servlet 6 / jakarta 命名空间 / Spring Security 6）。
> 升级中的全部破坏性变更与踩坑点见 [`docs/SpringBoot3升级说明.md`](docs/SpringBoot3升级说明.md)。

```bash
./start.sh            # MySQL + Redis + 后端(8081) + 前端预览服(8080)
./start.sh --no-db    # 数据库已在运行时
./stop.sh             # 停止后端与前端预览服（--all 连数据库一起停）
```

访问：

| 地址 | 说明 |
|---|---|
| http://localhost:8080/ | 前台首页 |
| http://localhost:8080/culture | 文化列表 |
| http://localhost:8080/culture/1 | 文化详情 |
| http://localhost:8080/sentence /about | 琴弦上 / 关于我 |
| http://localhost:8080/center | 个人中心（需前台登录） |
| http://localhost:8080/search?keyword=博物 | 全站搜索（文化 + 句子） |
| http://localhost:8080/tag/1 | 标签聚合页 |
| http://localhost:8080/auth/login · /auth/register · /auth/forgot | 前台登录 / 注册 / 找回密码 |
| http://localhost:8080/admin/login | **后台登录**（仅管理员） |
| http://localhost:8080/admin · /admin/culture · /admin/user · /admin/mail … | 后台管理 |
| http://localhost:8080/admin/comment · /admin/tag · /admin/oplog | 评论审核 / 标签管理 / 操作日志 |
| http://localhost:8081/sitemap.xml · /robots.txt | SEO（动态生成） |
| `./scripts/rebuild-static.sh` | 发布后重建静态页（构建 + 预渲染 + 重载 Nginx） |
| http://localhost:8081/api/home | 后端 API（纯 JSON） |

> 生产环境用 Nginx 托管 `web/dist` 并把接口反代到 8081：见 `deploy/nginx.conf.example`（Docker 用 `deploy/nginx.docker.conf`）。
> 本机没有 Nginx 时，`web/tools/serve-dist.mjs` 提供等价能力（静态 + history 回退 + 旧地址 301 + 接口反代）。

## 前台 / 后台登录分离（双 Token）

| | 前台（用户端） | 后台（管理端） |
|---|---|---|
| 页面 | `/auth/login` | `/admin/login` |
| 接口 | `POST /api/auth/login` | `POST /api/auth/admin/login`（必须是管理员） |
| Token 密钥 | `app.jwt.front-secret` | `app.jwt.admin-secret`（另一套） |
| Token 声明 | `scope=front` | `scope=admin` |
| 浏览器存储 | `localStorage.culture_front_token` | `localStorage.culture_admin_token` |
| 失效跳转 | `/auth/login` | `/admin/login` |

两套 Token 由不同密钥签发：前台 Token 访问 `/api/admin/**` 返回 **403**，后台 Token 也不能当前台用户身份使用。
详见 `docs/登录分离方案.md`。

## 富文本编辑器

后台文化介绍使用自研 `CultureEditor`（本地 Quill 2 封装，**已彻底移除 135 编辑器 / 秀米**）：

- 工具栏：标题 / 字号 / 加粗斜体下划线删除线 / 字体色背景色 / 列表缩进 / 对齐 / 引用代码块 /
  链接 / 图片 / 视频 / 分割线 / 表格 / 清除格式 / 撤销重做 / 字数统计 / HTML 源码 / 全屏；
- 支持 `Ctrl+V` 粘贴、拖拽上传图片与视频；
- 正文媒体保存到项目根新目录 **`upload/media/{image,video}/yyyyMM/`**，URL 前缀 `/upload/media/**`
  （由 `editor.upload.path` 配置，与头像/封面隔离，便于单独备份）。

详见 `docs/富文本编辑器方案.md`。

## 目录结构

```
web/                        # ★ Vue3 前端工程（前后端分离，自包含）
  ├── front.html            #   前台站点入口（/、/culture、/sentence、/about、/center）
  ├── auth.html             #   前台认证入口（/auth/login、/auth/register、/auth/forgot）
  ├── admin.html            #   后台管理入口（/admin/**）
  ├── admin-login.html      #   后台登录入口（/admin/login）
  ├── src/{api,router,stores,layouts,components,utils,views}
  ├── public/               #   ★ 老设计资源源目录（构建时原样复制到 dist/，URL 不变）
  │   ├── index/            #     → /index/**          前台设计资源
  │   └── static/{admin,auth}  #  → /static/{admin,auth}/**  后台与认证页设计资源
  └── tools/{serve-dist,e2e-check,debug-page}.mjs   # 本机预览服 / 端到端验证 / 定点调试
backend/                    # ★ Spring Boot 纯 REST API
  └── src/main/java/com/culture/
      ├── api/              #   REST 接口、JwtAuthFilter（前后台双 Token 鉴权）
      ├── auth/             #   认证：JWT（双密钥）、邮箱验证码、邮件服务
      ├── controller/       #   仅保留 Excel 导出与文件上传（页面控制器已删除）
      ├── service/ mapper/ entity/ query/ util/ config/
      └── resources/        #   application.yml、MyBatis XML
upload/                     # ★ 运行时上传数据（不入库，由 scripts/backup.sh 备份）
  ├── avatar/               #   用户头像
  ├── culture/              #   文化封面
  └── media/                #   富文本正文图片与视频（含自动生成的 thumb_ 缩略图）
scripts/backup.sh           # 数据库 + 媒体 + 上传目录备份（含 MD5 清单与恢复说明）
docs/                       # 文档：登录分离 / 富文本编辑器 / Vue3 迁移 / 功能增强 / DB 迁移
  └── sql/03_features.sql   #   标签 / 评论 / 操作日志建表（第 4 批功能所需）
start.sh start.bat stop.sh  # 一键启动与停止
```

> **前端已自包含**：`cd web && npm run build` 产出的 `web/dist` 就是完整可部署产物
> （含老设计资源），由 Nginx 直接托管；后端只提供 `/api`、`/upload/media`、
> `/showimage`、`/showFmImg` 与 SEO 根路径。

## 管理员账号

`admin@qq.com` / `123456`（或 `admin` / `123456`），从 **http://localhost:8080/admin/login** 登录。

## 前端开发（热更新）

```bash
cd web
npm install
npm run dev        # http://localhost:5173 ，接口自动代理到 8081，同样支持干净 URL
npm run build      # 产出 web/dist
```

## 说明

- **新增功能需要先建表**：`mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/03_features.sql`
  （纯新增、可重复执行），然后重启后端；详见 `docs/功能增强说明.md`；
- 备份：`./scripts/backup.sh`（DB + 正文媒体 + 头像封面，默认保留 14 天）；
- 逻辑删除（`deleted=1`）的记录不会出现在列表、前台热门/最新与详情页；
- 密码哈希不出现在任何 JSON 响应中；
- 生产环境务必通过 `AUTH_JWT_FRONT_SECRET`、`AUTH_JWT_ADMIN_SECRET` 注入两套随机密钥；
- 详见 `docs/README.md`、`docs/前端Vue3迁移说明.md`、`docs/DB_MIGRATION.md`。
