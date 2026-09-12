# 遇你 · 传统文化网站 —— 项目说明

前后端一体（Spring Boot 托管页面与静态资源），认证为文件夹 1 风格（邮箱验证码 + JWT），
后台含"邮件设置"多账号轮换管理。

---

## 一、目录结构说明

```
culture/
├── 2/                      # 文件夹 2：用户提供的原始项目备份（未整理，勿动）
│   ├── 1/                  #   文件夹 1 的副本
│   └── culture/            #   原始 culture 项目（含原模板）
├── reference/
│   └── folder1/            # 文件夹 1 参考项目（登录注册页/后端源码，原样保留）
├── archive/
│   └── vue-frontend/       # 已停用的 Vue 前端（归档，不再使用）
├── web/                    # ★ Vue3 前端工程（前后端分离，4 个入口：front/auth/admin/admin-login）
│   ├── src/                #   api/router/stores/layouts/components/utils/views
│   ├── public/             #   ★ 老设计资源源目录（构建时复制到 dist/，URL 不变）
│   │   ├── index/          #     前台设计资源 → /index/**
│   │   └── static/         #     ├ admin/ 后台设计资源（bootstrap/jquery/图表 + culture-editor.js/.css）
│   │                       #     └ auth/  登录/注册/忘记密码页设计资源
│   ├── tools/              #   serve-dist（等价 Nginx）/ e2e-check（端到端验证）/ debug-page
├── upload/                 # ★ 运行时上传数据（不入库，由 scripts/backup.sh 备份）
│   ├── avatar/             #   用户头像
│   ├── culture/            #   文化封面
│   └── media/              #   富文本正文媒体：image|video / yyyyMM，URL 前缀 /upload/media/**
├── backend/                # ★ 后端（Spring Boot Maven 工程，纯 REST API）
│   ├── pom.xml             #   依赖与构建配置（不再打包前端资源；前端产物由 Nginx 托管）
│   └── src/main/
│       ├── java/com/culture/
│       │   ├── api/        #     REST API：JwtAuthFilter（前后台双 Token 鉴权）/ ApiHome / ApiUser / ApiAdmin / MailAdmin
│       │   ├── auth/       #     认证模块：JWT（前台/后台双密钥）/ 邮箱验证码 / 邮件服务 / 登录注册接口
│       │   ├── controller/ #     页面控制器（前台页面/后台页面/文件上传/Excel 导出）
│       │   ├── service/    #     业务服务接口与实现
│       │   ├── mapper/     #     MyBatis Mapper（接口 + XML）
│       │   ├── entity/     #     实体
│       │   ├── query/      #     分页查询对象
│       │   ├── util/       #     通用工具/统一返回
│       │   └── config/     #     安全/数据源/推荐/邮件等配置
│       └── resources/      #   application.yml、MyBatis XML、banner
├── docs/                   # ★ 文档、数据库脚本、项目说明
│   ├── README.md           #   本文件（目录说明 + 启动步骤）
│   ├── DB_MIGRATION.md     #   数据库迁移与改造详细说明（新旧表映射、预留字段、API 文档）
│   ├── 登录分离方案.md      #   前台/后台登录分离与双 Token 设计
│   ├── 富文本编辑器方案.md  #   CultureEditor 编辑器与正文媒体目录说明
│   └── sql/                #   数据库脚本
│       ├── 01_schema.sql   #     建库建表（culture_v2，含预留字段）
│       └── 02_migrate.sql  #     旧库数据迁移
├── README.md               # 根目录说明（简版入口）
└── target/                 # 构建产物（backend 的构建输出在 backend/target）
```

---

## 二、启动方式

### 方式一：一键启动（推荐）
```bash
cd /d/me/AI/culture

./start.sh            # 一键启动：MySQL + Redis + 后端，并自动打开浏览器
./start.sh --no-db    # 只启动后端（数据库已在运行时用）

./stop.sh             # 停止后端
./stop.sh --all       # 停止后端 + MySQL + Redis
```
- Windows 用户也可直接**双击项目根目录的 `start.bat`** 一键启动（自动打开浏览器）
- 脚本会自动检测 MySQL/Redis/后端是否已运行，已运行则跳过

### 方式二：手动命令（完整步骤）


### 0. 前置依赖
- JDK 17（已装：D:\me\Environment\Java\jdk17.0.7）
- Maven 3.6+（已装）
- MySQL 5.7+（本机：root / 123456，数据目录 D:\me\SQL\MySQL）
- Redis（本机：127.0.0.1:6379，密码 123456，目录 D:\me\SQL\Redis）

### 1. 启动 MySQL 与 Redis
```bash
# MySQL（若服务未启动；管理员可用 net start MySQL）
cd /d/me/SQL/MySQL/MySQL && nohup bin/mysqld --defaults-file=my.ini > /tmp/mysqld.log 2>&1 &

# Redis
cd /d/me/SQL/Redis && nohup redis-server.exe redis.windows.conf > /tmp/redis.log 2>&1 &
```
检查：`mysql --no-defaults -uroot -p123456 -e "SELECT 1"`、`redis-cli -a 123456 ping`（返回 PONG）

### 2. 初始化数据库（首次或重建时执行）
```bash
cd /d/me/AI/culture
mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 < docs/sql/01_schema.sql
mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 < docs/sql/02_migrate.sql
```
说明：01 建库建表（culture_v2），02 从旧库迁移数据；已执行过可跳过。
邮件配置表（sys_mail_config / sys_mail_account / sys_mail_log）由 01_schema.sql 末尾创建并初始化账号。

### 3. 构建后端（只打包后端自身，不再包含前端资源）
```bash
cd /d/me/AI/culture/backend
mvn -DskipTests clean package
```
产物：`backend/target/culture-0.0.1-SNAPSHOT.jar`

> 注意加 `clean`：资源目录调整过之后，不带 clean 的 `package` 会把上一次构建
> 残留在 `target/classes/` 里的旧资源一起打进 jar。
>
> 前端资源由 `cd web && npm run build` 产出到 `web/dist`，交给 Nginx 托管。

### 4. 启动后端
```bash
cd /d/me/AI/culture/backend
nohup java -jar target/culture-0.0.1-SNAPSHOT.jar > /tmp/culture.log 2>&1 &
```
看到日志 `Started CultureApplication` 即启动成功（约 15~25 秒）。

### 5. 访问地址（前后端分离：前端 8080/Nginx，后端 API 8081）

| 地址 | 说明 |
|---|---|
| http://localhost:8080/ | 前台首页（Vue3 SPA，history 路由，无 .html） |
| http://localhost:8080/culture · /culture/1 | 文化列表 / 详情 |
| http://localhost:8080/auth/login | **前台登录**（用户端） |
| http://localhost:8080/admin/login | **后台登录**（管理端，仅管理员） |
| http://localhost:8080/admin | 后台管理（文化/分类/公告/句子/用户/邮件设置） |
| http://localhost:8081/api/home | 后端 REST API |
| http://localhost:8081/swagger-ui.html | Swagger 接口文档 |

> 本机没有 Nginx 时由 `web/tools/serve-dist.mjs` 提供等价能力（静态托管 + history 回退 + 旧 .html 301 + 接口反代）；
> 生产用 `deploy/nginx.conf.example`。旧地址（`/static/auth/login.html`、`/toLogin`、`/index.html` 等）统一 301 到新地址。

### 6. 登录账号
- 管理员：`admin` / `123456`（或 `admin@qq.com` / `123456`）—— 从**后台登录页**登录
- 普通用户：后台"用户管理"添加，或前台注册（验证码邮件发送到邮箱）—— 从**前台登录页**登录

> 前台登录与后台登录使用**两套 Token**（不同密钥、不同作用域），前台 Token 访问 `/api/admin/**` 会被拒绝，
> 详见 `登录分离方案.md`。

### 7. 常用运维命令
```bash
# 查看日志
tail -f /tmp/culture.log
# 停止应用（按 PID）
powershell -NoProfile -Command "Get-Process java | Where-Object { \$_.Id -ne 928 -and \$_.Id -ne 3344 } | Stop-Process -Force"
# 邮件配置（数据库直查）
mysql --no-defaults -uroot -p123456 -e "SELECT id,username,daily_limit,sent_today FROM culture_v2.sys_mail_account;"
```
