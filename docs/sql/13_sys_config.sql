-- ============================================================================
-- 13_sys_config.sql —— 后台「系统设置」（把原先写死在配置文件里的可变项搬到后台）
--
-- 背景：站点名称、验证码策略、登录有效期、缓存开关、上传上限、接口限流阈值……
--   这些都属于「上线后可能还要调」的参数，但此前只能改 application.yml 再重启服务，
--   而限流阈值更是**直接硬编码在 RateLimitInterceptor 的静态 Map 里**。
--   本表把它们统一收进数据库，由后台 → 系统设置 页面维护，改完即时生效。
--
-- 取值范围与语义：
--   config_key        业务代码里读取用的键（唯一）
--   config_value      当前值（统一按字符串存，读取时按 value_type 转换）
--   value_type        string / int / bool —— 决定读取时的转换方式与前端控件类型
--   config_group      分组（site/code/jwt/cache/upload/limit），前端按组分区展示
--   label             中文名（前端显示）
--   description       说明（前端显示在输入框下方）
--   default_value     出厂默认值，供「恢复默认」使用
--   restart_required  1 = 改完需要重启才生效（本表目前全部为 0）
--   sort              组内排序
--
-- 读取策略（见 ConfigService）：
--   数据库为准 → 表里没有该键时回退到 application.yml 里的 @Value 默认值。
--   因此本脚本的初始化数据即使被删掉，服务也能正常启动（退回原配置），不会「少一行就崩」。
--
-- 与启动期配置的边界（刻意不放进本表）：
--   端口、数据源、Redis、上传目录、Druid 监控页与 API 文档的开关/路径等，
--   都在 Spring 容器启动阶段就要用到（Druid 本身还依赖数据源），
--   放进数据库会形成循环依赖。这类参数继续由 application.yml + 环境变量管理。
--
-- 幂等实现（与 04_indexes.sql / 09_publish_schedule.sql 同一风格）：
--   * 建表用 CREATE TABLE IF NOT EXISTS；
--   * 初始化数据用 INSERT ... SELECT ... WHERE NOT EXISTS，**已存在的键不会被覆盖**
--     —— 重复执行不会把运维改过的值冲回默认值。
--
-- 用法：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/13_sys_config.sql
-- ============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_config` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key`       VARCHAR(100)    NOT NULL                COMMENT '配置键（唯一，业务代码按它读取）',
  `config_value`     VARCHAR(1000)   NOT NULL                COMMENT '当前值（统一按字符串存储）',
  `value_type`       VARCHAR(20)     NOT NULL DEFAULT 'string' COMMENT '值类型：string/int/bool',
  `config_group`     VARCHAR(50)     NOT NULL DEFAULT 'common' COMMENT '分组：site/code/jwt/cache/upload/limit',
  `label`            VARCHAR(100)    NOT NULL                COMMENT '中文名（后台显示）',
  `description`      VARCHAR(500)    DEFAULT NULL            COMMENT '说明（后台显示在输入框下方）',
  `default_value`    VARCHAR(1000)   DEFAULT NULL            COMMENT '出厂默认值（恢复默认用）',
  `restart_required` TINYINT(3) UNSIGNED NOT NULL DEFAULT 0  COMMENT '1=改完需重启生效',
  `min_value`        BIGINT          DEFAULT NULL            COMMENT 'int 型最小值（校验与前端提示用，NULL=不限）',
  `max_value`        BIGINT          DEFAULT NULL            COMMENT 'int 型最大值（校验与前端提示用，NULL=不限）',
  `sort`             INT             NOT NULL DEFAULT 0      COMMENT '组内排序',
  `updated_at`       DATETIME        DEFAULT NULL            COMMENT '最后修改时间',
  `updated_by`       BIGINT UNSIGNED DEFAULT NULL            COMMENT '最后修改人（sys_user.id）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_config_group` (`config_group`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置（后台「系统设置」页维护）';

-- ---------------------------------------------------------------------------
-- 增量：min_value / max_value 两列（校验范围也放数据里，不在代码里写死）
-- 说明：CREATE TABLE IF NOT EXISTS 对已存在的表不会补列，因此这里单独做幂等 ALTER，
--       保证「先跑过旧版脚本的环境」也能升上来。
-- ---------------------------------------------------------------------------
SET @db := DATABASE();

SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `sys_config` ADD COLUMN `min_value` BIGINT DEFAULT NULL COMMENT ''int 型最小值（校验与前端提示用，NULL=不限）'' AFTER `restart_required`',
  'SELECT ''[skip] min_value 已存在''')
  FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_config' AND column_name = 'min_value');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE `sys_config` ADD COLUMN `max_value` BIGINT DEFAULT NULL COMMENT ''int 型最大值（校验与前端提示用，NULL=不限）'' AFTER `min_value`',
  'SELECT ''[skip] max_value 已存在''')
  FROM information_schema.columns
  WHERE table_schema = @db AND table_name = 'sys_config' AND column_name = 'max_value');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 初始化出厂值
-- 说明：这里写入的 default 与 application.yml 中的对应默认值保持一致，
--       便于「数据库未配置时行为 == 改造前行为」。
-- ---------------------------------------------------------------------------

-- ============ 站点信息 ============
INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'site.name', '遇你', 'string', 'site', '站点名称',
       '用于 RSS 频道标题、JSON-LD publisher.name、页面 meta 标题后缀', '遇你', 10
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'site.name');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'site.base-url', 'http://localhost:8080', 'string', 'site', '站点域名',
       '对外访问地址（含协议与端口，结尾不要带斜杠）。sitemap / robots / rss / og:image 的绝对地址由它拼接；生产环境务必改成真实域名', 'http://localhost:8080', 20
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'site.base-url');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'site.description', '遇你 · 传统文化 —— 传统文化图文记录与分享', 'string', 'site', '站点描述',
       'RSS 频道描述，以及没有单独设置描述时的 meta description 兜底', '遇你 · 传统文化 —— 传统文化图文记录与分享', 30
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'site.description');

-- ============ 验证码策略 ============
INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'code.expire-minutes', '5', 'int', 'code', '验证码有效期（分钟）',
       '注册 / 找回密码的邮箱验证码有效时长', '5', 10
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'code.expire-minutes');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'code.resend-seconds', '60', 'int', 'code', '验证码重发间隔（秒）',
       '同一邮箱再次发送验证码的最小间隔（防轰炸）', '60', 20
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'code.resend-seconds');

-- ============ 登录有效期 ============
INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'jwt.front-expire-hours', '24', 'int', 'jwt', '前台登录有效期（小时）',
       '前台用户令牌的有效时长；剩余不足一半时会自动续期', '24', 10
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'jwt.front-expire-hours');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'jwt.admin-expire-hours', '8', 'int', 'jwt', '后台登录有效期（小时）',
       '后台管理令牌的有效时长（建议比前台短）', '8', 20
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'jwt.admin-expire-hours');

-- ============ 缓存 ============
INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'cache.enabled', 'true', 'bool', 'cache', '前台缓存开关',
       '只影响「与登录用户无关、读多写极少」的前台公共列表（分类 / 标签）。关闭后直接查库，行为不变只是变慢，可用于排查「数据看起来没更新」', 'true', 10
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'cache.enabled');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'cache.ttl-seconds', '60', 'int', 'cache', '缓存有效期（秒）',
       '写路径会主动失效，这里只是兜底：万一将来新增写路径忘了失效，最多脏这么久', '60', 20
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'cache.ttl-seconds');

-- ============ 上传限制 ============
INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'upload.max-image-mb', '10', 'int', 'upload', '正文图片上限（MB）',
       '富文本正文与头像的单张图片大小上限。注意还需 ≤ spring.servlet.multipart.max-file-size', '10', 10
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'upload.max-image-mb');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'upload.max-video-mb', '200', 'int', 'upload', '正文视频上限（MB）',
       '富文本正文视频的单文件大小上限。注意还需 ≤ spring.servlet.multipart.max-file-size', '200', 20
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'upload.max-video-mb');

-- ============ 接口限流（原先硬编码在 RateLimitInterceptor 的静态 Map 里）============
INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'limit.search-per-minute', '60', 'int', 'limit', '全站搜索限流（次/分钟）',
       '按 IP 限制 /api/search 的每分钟请求数', '60', 10
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'limit.search-per-minute');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'limit.export-per-minute', '5', 'int', 'limit', 'CSV 导出限流（次/分钟）',
       '导出是全表流式扫描，最重的操作之一', '5', 20
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'limit.export-per-minute');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'limit.purge-per-minute', '20', 'int', 'limit', '彻底删除限流（次/分钟）',
       '回收站彻底删除不可逆，单独限流', '20', 30
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'limit.purge-per-minute');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'limit.rollback-per-minute', '20', 'int', 'limit', '版本回滚限流（次/分钟）',
       '内容版本回滚', '20', 40
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'limit.rollback-per-minute');

INSERT INTO `sys_config` (config_key, config_value, value_type, config_group, label, description, default_value, sort)
SELECT 'limit.permission-per-minute', '30', 'int', 'limit', '权限写限流（次/分钟）',
       '角色 / 按钮权限的覆盖式写入', '30', 50
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM `sys_config`) t WHERE t.config_key = 'limit.permission-per-minute');

-- ---------------------------------------------------------------------------
-- 校验范围（int 型）
-- 这两列只是「允许范围」，改成 UPDATE 是幂等的，重复执行安全；
-- 后端 ConfigService 与后台页面都从这里读范围，不在代码里写死。
-- ---------------------------------------------------------------------------
UPDATE `sys_config` SET min_value = 1,    max_value = 1440  WHERE config_key = 'code.expire-minutes';
UPDATE `sys_config` SET min_value = 10,   max_value = 3600  WHERE config_key = 'code.resend-seconds';
UPDATE `sys_config` SET min_value = 1,    max_value = 720   WHERE config_key = 'jwt.front-expire-hours';
UPDATE `sys_config` SET min_value = 1,    max_value = 168   WHERE config_key = 'jwt.admin-expire-hours';
UPDATE `sys_config` SET min_value = 1,    max_value = 86400 WHERE config_key = 'cache.ttl-seconds';
UPDATE `sys_config` SET min_value = 1,    max_value = 100   WHERE config_key = 'upload.max-image-mb';
UPDATE `sys_config` SET min_value = 1,    max_value = 1024  WHERE config_key = 'upload.max-video-mb';
UPDATE `sys_config` SET min_value = 1,    max_value = 6000  WHERE config_key = 'limit.search-per-minute';
UPDATE `sys_config` SET min_value = 1,    max_value = 6000  WHERE config_key = 'limit.export-per-minute';
UPDATE `sys_config` SET min_value = 1,    max_value = 6000  WHERE config_key = 'limit.purge-per-minute';
UPDATE `sys_config` SET min_value = 1,    max_value = 6000  WHERE config_key = 'limit.rollback-per-minute';
UPDATE `sys_config` SET min_value = 1,    max_value = 6000  WHERE config_key = 'limit.permission-per-minute';

-- ---------------------------------------------------------------------------
-- 完成提示
-- ---------------------------------------------------------------------------
SELECT CONCAT('sys_config 就绪，共 ', COUNT(*), ' 项配置') AS result FROM `sys_config`;

-- ============================================================================
-- 4. 后台菜单：把「系统设置」挂到「系统管理」目录下
--
--    url 直接写 SPA 路径（/admin/settings）—— AdminLayout 的 routeOf() 会原样采用，
--    因此新增后台页面只需在这里插一行，**不需要改前端代码**（旧写法要求在前端
--    的 MENU_ROUTE_MAP 里再加一条映射）。
--    若库里没有名为「系统管理」的目录，pid 落为 NULL（成为一级菜单），
--    此时请手工把 pid 指到正确的目录 id 后重跑本脚本。
-- ============================================================================
INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted, remark)
SELECT '系统设置',
       '/admin/settings',
       'mdi mdi-cog-outline',
       (SELECT m.id FROM (SELECT * FROM sys_menu) m
         WHERE m.name = '系统管理' AND m.deleted = 0
         ORDER BY m.id LIMIT 1),
       90, 1, 0,
       '站点信息/验证码策略/登录有效期/缓存/上传限制/接口限流（docs/sql/13_sys_config.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m2 WHERE m2.url = '/admin/settings');

-- 兜底：菜单存在但被逻辑删除的，恢复
UPDATE sys_menu SET deleted = 0, status = 1 WHERE url = '/admin/settings' AND deleted <> 0;

-- 权限行
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'config:settings', '系统设置', 0, m.id, m.sort, 1, 0, '系统设置（13_sys_config.sql）'
FROM (SELECT * FROM sys_menu) m
WHERE m.url = '/admin/settings'
  AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p WHERE p.menu_id = m.id);

UPDATE sys_permission p
    JOIN (SELECT * FROM sys_menu) m ON m.id = p.menu_id AND m.url = '/admin/settings'
SET p.deleted = 0, p.status = 1
WHERE p.deleted <> 0;

-- 授权给超级管理员角色（id = 1）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM (SELECT * FROM sys_permission) p
         JOIN (SELECT * FROM sys_menu) m ON m.id = p.menu_id
WHERE m.url = '/admin/settings'
  AND m.deleted = 0 AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_role_permission) rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

SELECT CONCAT('系统设置菜单已就绪（menu=', m.id, '）') AS result
FROM (SELECT * FROM sys_menu) m WHERE m.url = '/admin/settings' AND m.deleted = 0;
