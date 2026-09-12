# 数据库重建与前后端分离改造说明（阶段 1）

> 目标：把 culture（传统 Thymeleaf 单体项目）按文件夹 1（落款 · 前后端分离项目）的模式改造。
> 阶段 1 交付：**新数据库 culture_v2 + 全量数据迁移 + JWT/邮箱验证码 auth 模块 + 登录注册页改造**。
> 阶段 2（后续）：前台/后台页面 Vue 化、业务 REST API 化。

---

## 一、新数据库 culture_v2

### 1.1 执行脚本（本地 MySQL 已执行）

| 脚本 | 作用 |
|---|---|
| `sql/01_schema.sql` | 创建 `culture_v2` 库与 11 张表（含索引/外键/预留字段） |
| `sql/02_migrate.sql` | 从旧库 `culture` + `demo_1` 迁移数据（邮箱去重、悬空外键清理） |

重新执行：
```bash
mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 < sql/01_schema.sql
mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 < sql/02_migrate.sql
```

### 1.2 表映射（旧 → 新）

| 旧表 | 新表 | 主要变化 |
|---|---|---|
| cul_user | **sys_user** | 新增 nickname/status/source/last_login_at/updated_at/deleted/remark/extra/reserved_1/reserved_2；email 唯一；password → password_hash |
| cul_role | **sys_role** | sn → code；desc → description；新增 sort/status/审计/预留字段 |
| cul_permission | **sys_permission** | menuId → menu_id；新增 sort/status/审计/预留字段 |
| cul_menu | **sys_menu** | 新增 sort/status/审计/预留字段 |
| cul_user_role | **sys_user_role** | userid→user_id、roleid→role_id；唯一索引 + 外键 |
| cul_role_permission | **sys_role_permission** | roleId/ permissionId → role_id/ permission_id；唯一索引 + 外键 |
| cul_category | **biz_category** | categoryName → name；新增 sort/status/审计/预留字段 |
| cul_culture | **biz_culture** | cultureName→name、desc→description、info→content、fmUrl→cover_url、view→view_count；新增 like_count（迁移时回填）/status/审计/预留字段 |
| cul_announcement | **biz_announcement** | announcement → title（新增 content 正文字段）；新增 status/审计/预留字段 |
| cul_sentence | **biz_sentence** | createId/createName/createImg → create_id/create_name/create_img；新增 status/审计/预留字段 |
| cul_like | **biz_like** | uid/bid/val → user_id/target_id/value；唯一索引 + 外键 |

### 1.3 预留字段（每张表都有）

| 字段 | 类型 | 说明 |
|---|---|---|
| `remark` | VARCHAR(255) | 备注 |
| `extra` | VARCHAR(512) | 预留扩展字段，建议存 JSON 字符串，如 `{"level":"vip","source":"wx"}` |
| `reserved_1` / `reserved_2` | VARCHAR(64) | 显式预留字段，后续迭代直接使用、避免频繁 ALTER TABLE |
| `status` | TINYINT | 状态（各表语义不同，默认 1=启用/上架/发布） |
| `deleted` | TINYINT | 逻辑删除（0=正常，1=已删除），代码中删除操作已改为逻辑删除 |
| `created_at` / `updated_at` | DATETIME | 审计时间戳，updated_at 自动更新 |

### 1.4 迁移数据说明

- 旧 7 个用户 + demo_1 的 2 个邮箱用户 → **9 个用户**；
- `cul_user` 中重复邮箱 `22@qq.com`（id=2、id=7）：保留 id 最小的一条，另一条邮箱改为 `22+7@qq.com`、用户名加 `_dup` 后缀；
- 密码为 BCrypt 哈希，直接迁入 `password_hash`，旧账号可用 **邮箱 + 原密码** 登录；
- 悬空外键数据自动跳过：`cul_user_role` 的 userid=8/9/10（3 条）、`cul_like` 的 uid=8（3 条）；
- `biz_culture.like_count` 由 `biz_like` 统计回填。

迁移后行数校验：sys_user=9、sys_role=3、sys_permission=10、sys_menu=11、sys_user_role=3、
sys_role_permission=16、biz_category=9、biz_culture=30、biz_announcement=3、biz_sentence=8、biz_like=16。

---

## 二、auth 模块（前后端分离 · JWT + 邮箱验证码）

### 2.1 后端接口（同源 `/api`，端口 8081）

统一响应：`{ "success": true, "message": "...", "data": {...} }`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/send-code | 发送验证码（scene=register 需未注册邮箱 / forgot 需已注册邮箱；60s 限频、5min 有效、一次性） |
| POST | /api/auth/register | 注册（email + code + password，BCrypt 加密，username 自动取邮箱前缀） |
| POST | /api/auth/reset | 重置密码（email + code + newPassword） |
| POST | /api/auth/login | **前台登录**，返回前台专用 Token（scope=front，24h），更新 last_login_at |
| GET | /api/auth/me | 前台当前用户信息（Header: `Authorization: Bearer <前台Token>`） |
| POST | /api/auth/logout | 前台退出（清理 Session 桥接信息） |
| POST | /api/auth/admin/login | **后台登录**（必须管理员角色），返回后台专用 Token（scope=admin，8h）并建立 Spring Security 会话 |
| GET | /api/auth/admin/me | 后台当前登录信息（Header: `Authorization: Bearer <后台Token>`） |
| POST | /api/auth/admin/logout | 后台退出（注销 Session） |

> 前台 / 后台两套 Token 使用不同密钥签发，互不通用，详见 `登录分离方案.md`。

### 2.2 关键代码位置（均含注释）

| 文件 | 职责 |
|---|---|
| `com.culture.auth.controller.AuthController` | REST 接口（前台/后台登录分离）+ 统一异常处理 |
| `com.culture.auth.service.AuthService` | 验证码（Redis）/注册/登录（按作用域）/重置逻辑 |
| `com.culture.auth.service.JwtService` | JWT 签发与解析（前台/后台双密钥 + scope 校验） |
| `com.culture.auth.service.MailService` | 验证码邮件（SMTP） |
| `com.culture.auth.config.CorsConfig` | 跨域配置（生产收紧为前端域名） |
| `web/src/views/auth/*` | 前台/后台登录、注册、忘记密码页面（Vue3 + 双 Token 管理，见 `前端Vue3迁移说明.md`） |
| `web/src/api/http.js` | 前台/后台两个 axios 实例，各自携带自己的 Token |

### 2.3 登录注册流程

- **注册**：邮箱 → send-code → 输入验证码+密码 → register → 跳前台登录页
- **前台登录**：邮箱/用户名+密码 → /api/auth/login → `localStorage.culture_front_token` → 进入首页或 `?redirect=` 目标页
- **后台登录**：`/admin/login`（Vue 页面）→ /api/auth/admin/login → `localStorage.culture_admin_token` → `/admin`
- **忘记密码**：邮箱（仅已注册）→ 验证码 → reset → 跳登录页

### 2.4 安全说明

- 验证码不通过接口返回，仅存 Redis（本地联调可 `redis-cli get vc:<email>` 查看）；
- JWT 密钥分前后台两套：`AUTH_JWT_FRONT_SECRET`、`AUTH_JWT_ADMIN_SECRET`（生产必须注入随机值）；邮件密码建议 `MAIL_PASSWORD` 注入；
- 后台管理仍保留 Spring Security Session 认证：后台登录接口会额外建立会话，`/api/admin/**` 也接受后台 Token。

---

## 三、运行方式

前置：本地 MySQL（root/123456）、Redis（6379，密码 123456）。

```bash
cd culture
mvn -DskipTests package
java -jar target/culture-0.0.1-SNAPSHOT.jar
# 首页        http://localhost:8081/
# 前端 SPA    http://localhost:8080/          （Nginx 托管 web/dist）
# 前台登录    http://localhost:8080/auth/login
# 后台登录    http://localhost:8080/admin/login
# 注册页      http://localhost:8080/auth/register
# 忘记密码    http://localhost:8080/auth/forgot
# 后端 API    http://localhost:8081/api/home
# Swagger     http://localhost:8081/swagger-ui.html
```

---

## 四、阶段 2（已完成）：Vue 前端 + REST API

### 4.1 前端（vue-frontend/）

- 技术栈：Vue 3 + Vite + Vue Router（hash 路由）+ Pinia + Axios + Element Plus；
- 前台页面：首页 / 文化列表 / 文化详情 / 登录 / 注册 / 个人中心 / 句子 / 关于；
- 后台页面：登录 / 布局（侧边栏）/ 仪表盘 / 文化 / 分类 / 公告 / 句子 / 用户管理；
- 开发：`npm run dev`（5173，/api 代理 8081）；构建：`npm run build` → `dist/`，
  已拷贝到 `src/main/resources/static/vue/`，生产由后端或 Nginx 托管。

### 4.2 后端 REST API（com.culture.api）

- `JwtAuthFilter`：按「令牌作用域 + 请求路径」鉴权（前台 Token / 后台 Token 双密钥，见 `登录分离方案.md`），
  无有效凭证时返回 401（前台 Token 访问后台接口返回 403）JSON；
- 公开接口：/api/home、/api/culture/list、/api/culture/detail、/api/culture/categorys、/api/sentence/list；
- 用户接口（前台 Token）：/api/user/center、/api/culture/like/{id}、/api/culture/cancel/{id}；
- 管理接口（后台 Token 或后台 Session + 管理员角色）：/api/admin/me、stats、menus、mail 与各业务 list/save/delete；
- 富文本正文媒体：/file/uploadEditorImage、/file/uploadEditorVideo → `upload/media/{image,video}`，URL 前缀 `/upload/media/**`；
- 安全优化：User.password 加 @JsonIgnore（响应不泄露哈希）；CommonUtil.getLoginUser()
  仅对后台 Session（principal 为 UserSecurity）返回登录人，前台 Token 不会误判为后台管理员。

### 4.3 部署

1. `mvn -DskipTests package` 打包（内含 Vue 静态资源）；
2. `java -jar target/culture-0.0.1-SNAPSHOT.jar`；
3. 访问 `http://host:8081/static/vue/index.html` 进入新前端；
4. 生产环境建议：Nginx 托管 vue-frontend/dist，/api 反向代理到 Spring Boot。
