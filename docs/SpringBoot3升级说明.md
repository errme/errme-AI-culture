# Spring Boot 3 升级说明

> 本次升级：**Spring Boot 2.4.4 → 3.4.3**（2.4.4 发布于 2021 年，早已 EOL）。
> 记录升级中的**每一处破坏性变更**、为什么这么改、以及踩到的坑，
> 便于后续排查与二次升级时对照。

---

## 一、结果速览

| 指标 | 升级前 | 升级后 |
|---|---|---|
| Spring Boot | 2.4.4 | 3.4.3 |
| Servlet API | `javax.servlet` | `jakarta.servlet` |
| Spring Security | 5.4（`WebSecurityConfigurerAdapter`） | 6.4（`SecurityFilterChain` Bean） |
| 后端 jar | 111.7 MB | **70.5 MB** |
| 运行状态 | 正常 | 正常（8 套端到端套件全通过） |

---

## 二、构建前提：Maven 版本

**Spring Boot 3 要求 Maven ≥ 3.6.3。** 本项目开发机上 PATH 里是 **3.6.1**，直接 `mvn` 会失败：

```
The plugin org.apache.maven.plugins:maven-clean-plugin:3.4.1 requires Maven version 3.6.3
```

处理方式：**引入 Maven Wrapper**（`backend/mvnw`、`backend/mvnw.cmd`、`backend/.mvn/wrapper/`），
锁定 Maven 3.9.16。这样：

- 不需要改动开发者的 PATH / 本机 Maven；
- CI 只装 JDK 即可（见 `.github/workflows/ci.yml`）；
- 所有人用的是同一个 Maven 版本，避免「我这儿能构建」类问题。

**构建命令统一改为：**

```bash
cd backend
./mvnw -DskipTests clean package     # Linux / macOS / Git Bash
mvnw.cmd -DskipTests clean package   # Windows cmd
```

> ⚠️ 务必带 `clean`。资源目录调整过之后，不带 clean 的 `package` 会把上一次构建残留在
> `target/classes/` 里的旧文件一起打进 jar（本次就遇到过 jar 体积不下降的问题）。

---

## 三、依赖变更

### 3.1 删除的依赖

| 依赖 | 原因 |
|---|---|
| `spring-boot-starter-thymeleaf` | 页面已全部迁到 Vue，`resources/templates/` 不存在，纯死依赖 |
| `thymeleaf-extras-springsecurity5` | 同上，且 SB3 对应的是 `...6` |
| `springfox-swagger2` / `springfox-swagger-ui` 2.9.2 | 不支持 Spring Boot 3；且 `/swagger-ui.html`、`/v2/api-docs` 原本是 **permitAll 公开**的，属于信息暴露 |
| `log4j:log4j:1.2.17` | 存在 CVE，且与 logback 重复 |
| `mahout-core` / `mahout-integration` 0.9 | 依赖 `hadoop-core 1.2.1`，整体基于 `javax.*`，与 SB3 不可能共存（详见第四节） |
| `easypoi-web` | 依赖 `javax.servlet`，项目只用到 `easypoi-base` 的 `ExcelExportUtil` |
| `guava` 30.0-jre（dependencyManagement） | 全项目 **0 处**引用 |

### 3.2 升级的依赖

| 依赖 | 前 | 后 | 说明 |
|---|---|---|---|
| `mybatis-spring-boot-starter` | 2.1.4 | 3.0.4 | 2.x 绑定 Spring 5 / javax |
| `com.alibaba:druid` | 1.1.21 | 1.2.24 | 见 3.3 |
| `cn.afterturn:easypoi-base` | 3.2.0 | 4.5.0 | 适配 POI 5 |
| MySQL 驱动 | `mysql:mysql-connector-java` | **`com.mysql:mysql-connector-j`** | 坐标变更，版本由父 POM 管理（实际解析到 9.1.0） |
| lombok | `optional` | **`provided`** | `optional` 只影响传递依赖，仍会被打进 fat jar（约 2MB）；`provided` 才不进 jar |
| jjwt | 0.11.5 | 0.11.5（不动） | 与 SB3/Java 17 兼容，保持不变以缩小改动面 |

### 3.3 Druid 的 jakarta 问题（重要）

`com.alibaba:druid` 的 **`StatViewServlet` 与 `WebStatFilter` 至今仍是 `javax.servlet` 实现**
（已实测 druid 1.2.24 jar 内字节码引用的是 `javax/servlet/*`），在 SB3 下会在启动时抛
`NoClassDefFoundError`。

因此 `DruidConfig` 中**已移除这两个 Bean**：

- 代价很小：该监控页本来就是调试功能，且 `app.druid.stat.enabled` 默认 `false`，生产不暴露；
- 连接池本身（`DruidDataSource`）与 `stat` / `wall` 过滤器**不依赖 Servlet API，完整保留**；
- 运行时可观测性由项目自己的 `/api/metrics` 与 `SlowSqlInterceptor`（MyBatis 插件）承担。

### 3.4 Druid wall 过滤器变严格（实际踩到的坑）

Druid 1.1.21 → 1.2.24 后，`wall` 过滤器开始拦截 **`where 1 = 1`** 这类「恒真条件」：

```
java.sql.SQLException: sql injection violation, dbType mysql, druid-version 1.2.24,
select alway true condition not allow : select count(*) from sys_operation_log l where 1 = 1
```

**现象**：操作日志页面打不开（接口 401/异常），很容易误判成鉴权问题。

**修复**：`OperationLogMapper` 的两个查询改用 MyBatis 的 `<where>` 标签
（它会自动去掉紧随其后的多余 `AND`，条件全空时不生成 `WHERE`）。

> 刻意**没有**用 `druid.wall.selectWhereAlwayTrueCheck=false` 放宽 —— 那会削弱 SQL 注入防护。
> 全仓已确认只有这一处使用 `1 = 1`。

---

## 四、移除 Mahout：推荐功能改为纯 SQL

### 4.1 为什么要移除

- Mahout 0.9 发布于 2014 年，依赖 `hadoop-core 1.2.1`，整体基于 `javax.*`，与 SB3 无法共存；
- 本项目 `biz_like` 实际只有 **数百行**数据（实测 649 条 / 62 用户 / 29 目标），
  为此引入 Hadoop + Lucene + Mahout 约 30MB 依赖属于严重过度设计；
- 原实现还在**每次请求**重建 `UserSimilarity` / `UserNeighborhood` / `Recommender` 三个对象。

### 4.2 新的实现

`CultureMapper.findRecommendByUser`（XML），一段 SQL 完成协同过滤：

1. `l1` = 当前用户的收藏，`l2` = 其它用户的收藏，按 `target_id` 不同相连 → 得到候选；
2. `not exists` 排除当前用户已收藏的目标（协同过滤必须排除已交互物品）；
3. `supporters`（多少位相似用户收藏了它）优先，其次 `score`（偏好值之和）；
4. 只返回已发布且未逻辑删除的内容；用 `left join` 避免分类被删时整条推荐消失。

### 4.3 重要发现：这条路径原本就是死代码

全仓搜索 `getUserTjCulture`（协同过滤的唯一出口）**只有接口声明与实现，没有任何调用方**。
前台详情页的「猜你喜欢」实际走 `CultureServiceImpl.findRecommendCultures`
（同分类优先 + 热门兜底，调用点 `ApiHomeController`）。

**也就是说：移除 Mahout 对线上行为零影响。** 该路径连同上文的 SQL 实现一并保留并修正，
供将来真要启用个性化推荐时使用（已在方法注释中标注）。

---

## 五、Servlet API：javax → jakarta

共 **56 处 import** 迁移：

| 原包 | 新包 | 处数 |
|---|---|---|
| `javax.servlet.*` | `jakarta.servlet.*` | 42 |
| `javax.validation.*` | `jakarta.validation.*` | 13 |
| `javax.mail.*` | `jakarta.mail.*` | 1 |

**注意不要误改**（这些是 JDK 自带包，SB3 下保持不变）：
`javax.imageio`（6 处）、`javax.sql`（3 处）、`javax.crypto`（1 处）。

另有 3 处是**完全限定名**写法（`javax.servlet.http.HttpServletRequest` 直接写在参数里，
不在 import 行），批量替换脚本容易漏掉 —— 编译报错时按这个思路排查。

---

## 六、Spring Security 5 → 6

`WebSecurityConfig` 完全重写：

| 旧写法 | 新写法 |
|---|---|
| `extends WebSecurityConfigurerAdapter` + 覆写 `configure()` | 声明 `SecurityFilterChain` Bean（Security 6 **已删除**该基类） |
| `antMatchers(...)` | `requestMatchers(...)` |
| `@EnableGlobalMethodSecurity(prePostEnabled = true)` | **移除**（项目里没有任何 `@PreAuthorize`，属无效配置） |
| `configure(AuthenticationManagerBuilder)` | **移除**（登录由 `AuthService.login()` 自行校验，不走 DaoAuthenticationProvider） |

同时做了三项安全收紧：

1. **`SessionCreationPolicy.STATELESS`** —— 纯 Token 无状态（见第七节）；
2. **公开路径收窄** —— 移除 `/druid/**`、`/swagger-ui.html`、`/v2/api-docs`、`/swagger-resources`；
3. **恢复 `X-Content-Type-Options: nosniff`** —— 原先为了 Druid 页面显示而全局关闭，Druid 已移除。

CSRF 仍然 `disable()`，但理由变了：**服务端不再使用任何 Cookie 会话**
（没有 Session、没有表单登录），浏览器跨站请求带不上 `Authorization` 头，CSRF 攻击面消失。
此前「禁用 CSRF + 同时使用 Cookie 会话」才是自相矛盾的。

---

## 七、Session 桥接移除（纯 Token 无状态）

### 7.1 原来的问题

`JwtAuthFilter` 会把**每个带 Token 的请求**桥接成服务端 Session
（`request.getSession(true)`，后台还会写入完整 Spring Security 上下文）：

- 「无状态 JWT」名不副实，并发高时堆内存被 Session 吃掉，多实例还要处理会话一致性；
- 同时还存在 Cookie 会话，却把 CSRF 关掉 —— 安全姿态自相矛盾。

### 7.2 改动

- `JwtAuthFilter`：不再创建 Session；`applyAuthentication` 只写 `request` 属性与 `SecurityContext`；
- `AuthController`：登录不再写 Session，登出接口保留（前端清本地 Token 即完成登出）；
- 后台 Token 不再调用 `userDetailsService.loadUserByUsername()` ——
  那一步会多发「用户 + 角色 + 权限」三条 SQL，而项目里没有任何 `@PreAuthorize` 用到这些细粒度权限。
  **去掉后每个后台请求少 3 条无关查询。**

### 7.3 连带修复：`CommonUtil.getLoginUser()`

改成纯 Token 后 principal 一度被写成 `"admin:1"` 这样的字符串，
而 `CommonUtil.getLoginUser()` 只认 `UserSecurity`，于是返回 `null`，
业务层紧接着调用 `getLoginUser().getId()` 抛 NPE，**表现为「后台新增句子永远失败」**
（上层把异常吞成了「保存失败」，排查成本很高）。

现在：principal 直接放**已加载的 `User` 实体**（避免为取一个 id 再查一次库），
`CommonUtil` 同时识别 `User` 与 `UserSecurity`，并新增 `getLoginUserId()`；
`SentenceServiceImpl` 也改为取一次 + 显式判空 + 抛业务异常。

---

## 八、配置文件的两处破坏性变更

| 旧键 | 新键 | 说明 |
|---|---|---|
| `spring.redis.*` | **`spring.data.redis.*`** | 3.0 起迁移，旧键被**静默忽略** → Redis 密码不生效，连接 requirepass 的实例直接失败 |
| `spring.resources.cache.*` | **`spring.web.resources.cache.*`** | 2.4 起迁移，旧键在 3.x 下不再生效（等于没配） |

另有一处**启动即失败**的非法配置：

```yaml
spring.mvc.static-path-pattern: /static/**/     # ✗ 非法
```

Spring Boot 3 用 `PathPatternParser`，它不接受 `**` 之后还有内容：

```
Invalid mapping pattern detected: /static/**/
No more pattern data allowed after {*...} or ** pattern element
```

已改为合法的 `/**` 写法。`spring.thymeleaf.*` 也随依赖一并删除。

---

## 九、顺带修复的越权漏洞

`/user/downloadExcel`（用户表 Excel 导出）**原先没有任何管理员校验**：

- 该路径不在 `/api/` 前缀下，`JwtAuthFilter.isProtectedApi()` 直接跳过它；
- 最终只落到 Spring Security 的 `anyRequest().authenticated()`；
- 而前台 Token 同样被视为「已认证」→ **任何自助注册的前台用户都能下载全站用户
  （姓名 / 邮箱 / 电话 / 注册时间）**。

修复后路径为 **`/api/admin/user/export`**，纳入 `/api/admin/**` 统一鉴权，
方法内再做一次显式管理员校验，并排除逻辑删除的用户。

实测：

| 请求方 | 结果 |
|---|---|
| 前台 Token | **403** |
| 后台 Token | 200 |
| 无 Token | 401 |

因 Session 桥接已移除，浏览器直接跳转 URL 的方式无法携带登录态，
前端改为 axios blob 下载（与既有的「文化 CSV 导出」同一套写法）。

---

## 十、升级后的已知遗留

1. **`UserDetailsServiceImpl` / `UserSecurity` 已无实际调用方**
   （Session 桥接移除后失去用途），保留未删，可在后续清理中评估；
2. **`spring-security-test`** 仍在依赖里，项目没有使用；
3. 推荐路径 `getUserTjCulture` 仍是死代码（见 4.3），若要启用需先接入调用方；
4. `RoleServiceImpl` 类级 `@Transactional(propagation=SUPPORTS, readOnly=true)`
   在无外层事务时不创建事务，`readOnly` 不生效（升级前即存在）。
