-- ============================================================================
-- 03_features.sql —— 标签 / 评论 / 操作日志（全部为「只新增」变更，可重复执行）
--
-- 说明：
--   * 只做 CREATE TABLE IF NOT EXISTS 与 ADD COLUMN IF NOT EXISTS，不删除、不修改既有列；
--   * 字符集与既有库一致（utf8mb4 / utf8mb4_general_ci）；
--   * 执行前建议先备份：scripts/backup.sh
-- 用法：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 < docs/sql/03_features.sql
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 标签
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz_tag (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(50)  NOT NULL COMMENT '标签名',
    slug        VARCHAR(80)  DEFAULT NULL COMMENT '英文标识（URL 用，可空）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序（越小越前）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tag_name (name),
    KEY idx_tag_sort (sort, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '内容标签';

-- 文化 ↔ 标签 中间表
CREATE TABLE IF NOT EXISTS biz_culture_tag (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    culture_id  BIGINT   NOT NULL COMMENT 'biz_culture.id',
    tag_id      BIGINT   NOT NULL COMMENT 'biz_tag.id',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_culture_tag (culture_id, tag_id),
    KEY idx_culture_tag_tag (tag_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '文化-标签关联';

-- ---------------------------------------------------------------------------
-- 2. 评论（先审后发：status 0 待审 1 通过 2 拒绝）
--    content 存「已清洗的 HTML」（服务端 HtmlSanitizer 白名单过滤后入库）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz_comment (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    culture_id    BIGINT       NOT NULL COMMENT '所属文化 biz_culture.id',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '父评论 id，0 为顶层',
    user_id       BIGINT       DEFAULT NULL COMMENT '登录用户 id（匿名可空）',
    nickname      VARCHAR(50)  DEFAULT NULL COMMENT '昵称（匿名时必填）',
    email         VARCHAR(120) DEFAULT NULL COMMENT '邮箱（不公开展示）',
    content       TEXT         NOT NULL COMMENT '评论内容（已白名单清洗的 HTML）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待审 1 通过 2 拒绝',
    ip            VARCHAR(64)  DEFAULT NULL COMMENT '来源 IP',
    user_agent    VARCHAR(255) DEFAULT NULL COMMENT 'UA（截断保存）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    audited_at    DATETIME     DEFAULT NULL COMMENT '审核时间',
    audited_by    BIGINT       DEFAULT NULL COMMENT '审核人 sys_user.id',
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_comment_culture (culture_id, status, id),
    KEY idx_comment_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '文化评论';

-- ---------------------------------------------------------------------------
-- 3. 后台操作日志（审计）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       DEFAULT NULL COMMENT '操作人 sys_user.id',
    username    VARCHAR(50)  DEFAULT NULL COMMENT '操作人用户名（冗余保存，防用户被删后不可追溯）',
    module      VARCHAR(50)  DEFAULT NULL COMMENT '模块：culture/category/announcement/sentence/user/mail/tag/comment',
    action      VARCHAR(50)  DEFAULT NULL COMMENT '动作：save/delete/audit/upload',
    target_id   VARCHAR(64)  DEFAULT NULL COMMENT '目标对象 id',
    detail      VARCHAR(500) DEFAULT NULL COMMENT '摘要（如“新增文化：故宫博物院”）',
    method      VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP 方法',
    uri         VARCHAR(255) DEFAULT NULL COMMENT '请求路径',
    ip          VARCHAR(64)  DEFAULT NULL,
    success     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 成功 0 失败',
    cost_ms     BIGINT       DEFAULT NULL COMMENT '耗时毫秒',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_oplog_created (created_at),
    KEY idx_oplog_user (user_id, created_at),
    KEY idx_oplog_module (module, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '后台操作日志';

-- ---------------------------------------------------------------------------
-- 4. 友情提示：种子标签（可按需删掉这段）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO biz_tag (name, sort) VALUES
  ('博物馆', 10), ('古建筑', 20), ('非遗', 30), ('书画', 40), ('诗词', 50), ('民俗', 60);

-- ---------------------------------------------------------------------------
-- 5. 后台菜单：评论审核 / 标签管理 / 操作日志
--    表结构：sys_menu(id,name,url,icon,pid,sort,status,deleted)
--    pid 为 NULL 表示一级菜单；url 沿用「/xxx/index」风格，前端 AdminLayout 做路由映射
--
--    MySQL 兼容性（5.7）：MySQL 5.7 不允许「没有 FROM 的 WHERE」
--      SELECT 1 WHERE ...  →  ERROR 1064 (42000)
--    因此下面所有 `INSERT ... SELECT 常量 ... WHERE NOT EXISTS (...)` 都补上 FROM DUAL，
--    语义与幂等行为完全不变（只补 FROM，不动插入的数据）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted)
SELECT '内容治理', NULL, 'mdi mdi-comment-text-outline', NULL, 20, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m WHERE m.name = '内容治理');

INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted)
SELECT '评论审核', '/comment/index', NULL,
       (SELECT id FROM (SELECT * FROM sys_menu) m WHERE m.name = '内容治理' LIMIT 1), 10, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m WHERE m.url = '/comment/index');

INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted)
SELECT '标签管理', '/tag/index', NULL,
       (SELECT id FROM (SELECT * FROM sys_menu) m WHERE m.name = '内容治理' LIMIT 1), 20, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m WHERE m.url = '/tag/index');

INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted)
SELECT '操作日志', '/oplog/index', NULL,
       (SELECT id FROM (SELECT * FROM sys_menu) m WHERE m.name = '系统管理' LIMIT 1), 30, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m WHERE m.url = '/oplog/index');

-- ---------------------------------------------------------------------------
-- 6. 给新增菜单补「权限 + 角色授权」
--    重要：后台侧边栏菜单由 MenuMapper.findAll 通过
--      sys_user_role → sys_role_permission → sys_permission → sys_menu(menu_id)
--    关联查询得出；只插 sys_menu 是不会显示的，必须同时有 sys_permission 记录
--    并把该权限授予角色（管理员=1）。
--
--    MySQL 兼容性（5.7）：同样补 FROM DUAL，否则 SELECT 常量 + WHERE 会报 1064。
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted)
SELECT 'comment:audit', '评论审核', 0, 102, 10, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p WHERE p.menu_id = 102);

INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted)
SELECT 'tag:manage', '标签管理', 0, 103, 20, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p WHERE p.menu_id = 103);

INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted)
SELECT 'oplog:list', '操作日志', 0, 104, 30, 1, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p WHERE p.menu_id = 104);

-- 授予「管理员」角色（id=1）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id FROM (SELECT * FROM sys_permission) p
WHERE p.menu_id IN (102, 103, 104)
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT * FROM sys_role_permission) rp
    WHERE rp.role_id = 1 AND rp.permission_id = p.id
  );
