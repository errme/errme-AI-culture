-- =====================================================================
-- culture_v2 数据库结构（前后端分离改造 · 阶段1）
-- 设计原则：
--   1. 表名统一：sys_* 系统权限 / biz_* 业务 / 中间表；
--   2. 列名统一 snake_case，时间戳 created_at/updated_at 全表一致；
--   3. 每张表都带审计与预留字段：
--        status    启用状态(1=启用,0=禁用)
--        deleted   逻辑删除(0=正常,1=已删除)
--        remark    备注
--        extra     预留扩展字段(建议存 JSON 字符串,如 {"k":"v"})
--        reserved_1 / reserved_2  显式预留字段(后续迭代直接使用,避免改表)
--   4. 字符集 utf8mb4，外键统一 ON DELETE CASCADE（删除父记录自动清理关联）；
--   5. 中间表加唯一索引防重复授权。
-- 执行：mysql --no-defaults -uroot -p123456 < sql/01_schema.sql
-- =====================================================================

-- 删除旧库（如已存在），全新创建
DROP DATABASE IF EXISTS `culture_v2`;
CREATE DATABASE `culture_v2` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `culture_v2`;

-- ---------------------------------------------------------------------
-- 一、用户与权限（auth / RBAC）
-- ---------------------------------------------------------------------

-- 1. 用户表（由旧 cul_user 与 demo_1.users 合并；登录凭证 = 邮箱）
CREATE TABLE sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username      VARCHAR(50)     NOT NULL                COMMENT '登录名（注册时默认取邮箱@前缀）',
    email         VARCHAR(190)    NOT NULL                COMMENT '邮箱（唯一登录凭证）',
    password_hash VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 密码哈希',
    nickname      VARCHAR(50)     NULL                    COMMENT '昵称',
    phone         VARCHAR(20)     NULL                    COMMENT '手机号（旧库 tel）',
    sex           TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '性别 0未知 1男 2女',
    avatar        VARCHAR(255)    NULL                    COMMENT '头像地址（旧库 headImg）',
    status        TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态 1启用 0禁用',
    source        VARCHAR(20)     NOT NULL DEFAULT 'email' COMMENT '注册来源 legacy=旧库迁移 email=邮箱注册',
    last_login_at DATETIME        NULL                    COMMENT '最后登录时间',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除 0=正常 1=已删除',
    remark        VARCHAR(255)    NULL                    COMMENT '备注',
    extra         VARCHAR(512)    NULL                    COMMENT '预留扩展字段（JSON字符串）',
    reserved_1    VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2    VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_phone (phone),
    KEY idx_user_status (status, deleted)
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='用户表';

-- 2. 角色表（旧 cul_role）
CREATE TABLE sys_role (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    code        VARCHAR(30)     NULL                    COMMENT '角色编码（旧库 sn，如 admin）',
    name        VARCHAR(30)     NOT NULL                COMMENT '角色名称（管理员/编辑/用户）',
    description VARCHAR(255)    NULL                    COMMENT '角色描述（旧库 desc）',
    sort        INT             NOT NULL DEFAULT 0      COMMENT '排序',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态 1启用 0禁用',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark      VARCHAR(255)    NULL                    COMMENT '备注',
    extra       VARCHAR(512)    NULL                    COMMENT '预留扩展字段',
    reserved_1  VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2  VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='角色表';

-- 3. 权限表（旧 cul_permission）
CREATE TABLE sys_permission (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    name        VARCHAR(30)     NOT NULL                COMMENT '权限标识（如 user:add）',
    title       VARCHAR(30)     NULL                    COMMENT '权限标题（如 用户新增）',
    pid         BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '父权限ID（0=顶级）',
    menu_id     BIGINT UNSIGNED NULL                    COMMENT '关联菜单ID',
    sort        INT             NOT NULL DEFAULT 0      COMMENT '排序',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark      VARCHAR(255)    NULL                    COMMENT '备注',
    extra       VARCHAR(512)    NULL                    COMMENT '预留扩展字段',
    reserved_1  VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2  VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    KEY idx_perm_menu (menu_id),
    KEY idx_perm_pid (pid)
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='权限表';

-- 4. 菜单表（旧 cul_menu）
CREATE TABLE sys_menu (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    name        VARCHAR(30)     NOT NULL                COMMENT '菜单名称',
    url         VARCHAR(100)    NULL                    COMMENT '路由地址',
    icon        VARCHAR(50)     NULL                    COMMENT '图标',
    pid         BIGINT UNSIGNED NULL                    COMMENT '父菜单ID（NULL=顶级）',
    sort        INT             NOT NULL DEFAULT 0      COMMENT '排序',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark      VARCHAR(255)    NULL                    COMMENT '备注',
    extra       VARCHAR(512)    NULL                    COMMENT '预留扩展字段',
    reserved_1  VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2  VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    KEY idx_menu_pid (pid)
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='菜单表';

-- 5. 用户-角色 关联表（旧 cul_user_role）
CREATE TABLE sys_user_role (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT UNSIGNED NOT NULL                COMMENT '用户ID',
    role_id    BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_ur_role (role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='用户-角色关联表';

-- 6. 角色-权限 关联表（旧 cul_role_permission）
CREATE TABLE sys_role_permission (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id       BIGINT UNSIGNED NOT NULL                COMMENT '角色ID',
    permission_id BIGINT UNSIGNED NOT NULL                COMMENT '权限ID',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_perm (role_id, permission_id),
    KEY idx_rp_perm (permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission (id) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='角色-权限关联表';

-- ---------------------------------------------------------------------
-- 二、业务表（biz_*）
-- ---------------------------------------------------------------------

-- 7. 文化分类表（旧 cul_category）
CREATE TABLE biz_category (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    name       VARCHAR(50)     NOT NULL                COMMENT '分类名称（旧库 categoryName）',
    sort       INT             NOT NULL DEFAULT 0      COMMENT '排序',
    status     TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark     VARCHAR(255)    NULL                    COMMENT '备注',
    extra      VARCHAR(512)    NULL                    COMMENT '预留扩展字段',
    reserved_1 VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2 VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    KEY idx_category_status (status, deleted)
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='文化分类表';

-- 8. 文化表（旧 cul_culture）
CREATE TABLE biz_culture (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '文化ID',
    name        VARCHAR(100)    NOT NULL                COMMENT '文化名称（旧库 cultureName）',
    address     VARCHAR(255)    NULL                    COMMENT '地址',
    description VARCHAR(500)    NULL                    COMMENT '描述（旧库 desc，避免保留字）',
    content     LONGTEXT        NULL                    COMMENT '正文（旧库 info）',
    cover_url   VARCHAR(255)    NULL                    COMMENT '封面图（旧库 fmUrl）',
    category_id BIGINT UNSIGNED NULL                    COMMENT '分类ID',
    creator_id  BIGINT UNSIGNED NULL                    COMMENT '发布者ID',
    view_count  BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '浏览量（旧库 view）',
    like_count  INT             NOT NULL DEFAULT 0      COMMENT '点赞数（冗余统计，迁移时由 biz_like 回填）',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态 1上架 0下架',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark      VARCHAR(255)    NULL                    COMMENT '备注',
    extra       VARCHAR(512)    NULL                    COMMENT '预留扩展字段（JSON）',
    reserved_1  VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2  VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    KEY idx_culture_category (category_id),
    KEY idx_culture_creator (creator_id),
    KEY idx_culture_status (status, deleted),
    CONSTRAINT fk_culture_category FOREIGN KEY (category_id) REFERENCES biz_category (id) ON DELETE SET NULL,
    CONSTRAINT fk_culture_creator  FOREIGN KEY (creator_id)  REFERENCES sys_user (id)    ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='文化表';

-- 9. 公告表（旧 cul_announcement）
CREATE TABLE biz_announcement (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '公告ID',
    title      VARCHAR(255)    NOT NULL                COMMENT '公告标题（旧库 announcement）',
    content    LONGTEXT        NULL                    COMMENT '公告正文（预留，旧库无独立正文字段）',
    status     TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态 1发布 0下架',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark     VARCHAR(255)    NULL                    COMMENT '备注',
    extra      VARCHAR(512)    NULL                    COMMENT '预留扩展字段',
    reserved_1 VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2 VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    KEY idx_announcement_status (status, deleted)
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='公告表';

-- 10. 句子表（旧 cul_sentence）
CREATE TABLE biz_sentence (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '句子ID',
    content     VARCHAR(500)    NOT NULL                COMMENT '句子内容',
    create_id   BIGINT UNSIGNED NULL                    COMMENT '发布用户ID（旧库 createId）',
    create_name VARCHAR(30)     NULL                    COMMENT '发布用户名（冗余）',
    create_img  VARCHAR(255)    NULL                    COMMENT '发布人头像（冗余）',
    status      TINYINT UNSIGNED NOT NULL DEFAULT 1     COMMENT '状态 1展示 0隐藏',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT UNSIGNED NOT NULL DEFAULT 0     COMMENT '逻辑删除',
    remark      VARCHAR(255)    NULL                    COMMENT '备注',
    extra       VARCHAR(512)    NULL                    COMMENT '预留扩展字段',
    reserved_1  VARCHAR(64)     NULL                    COMMENT '预留字段1',
    reserved_2  VARCHAR(64)     NULL                    COMMENT '预留字段2',
    PRIMARY KEY (id),
    KEY idx_sentence_creator (create_id),
    CONSTRAINT fk_sentence_creator FOREIGN KEY (create_id) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='句子表';

-- 11. 点赞表（旧 cul_like）
CREATE TABLE biz_like (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '点赞ID',
    user_id    BIGINT UNSIGNED NOT NULL                COMMENT '点赞用户ID（旧库 uid）',
    target_id  BIGINT UNSIGNED NOT NULL                COMMENT '点赞目标ID（旧库 bid，指向 biz_culture.id）',
    value      INT             NOT NULL DEFAULT 5      COMMENT '点赞值（旧库 val）',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_like_user_target (user_id, target_id),
    KEY idx_like_target (target_id),
    CONSTRAINT fk_like_user   FOREIGN KEY (user_id)   REFERENCES sys_user (id)   ON DELETE CASCADE,
    CONSTRAINT fk_like_target FOREIGN KEY (target_id) REFERENCES biz_culture (id) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=100 COMMENT='点赞表';

-- ---------------------------------------------------------------------
-- 12. 邮件发送配置表（后台"邮件设置"页可动态修改发件账号/密码/模板/每日上限）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_mail_config (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  host VARCHAR(100) NOT NULL DEFAULT 'smtp.larksuite.com',
  port INT NOT NULL DEFAULT 465,
  username VARCHAR(100) NOT NULL COMMENT '发件账号（可轮换，单账号日限450封）',
  password VARCHAR(200) NOT NULL,
  from_name VARCHAR(50) DEFAULT '遇你',
  subject_template VARCHAR(200) NOT NULL DEFAULT '【遇你】邮箱验证码',
  body_template TEXT NOT NULL COMMENT '支持占位符 {code} {minutes}',
  daily_limit INT NOT NULL DEFAULT 450,
  sent_today INT NOT NULL DEFAULT 0,
  sent_date DATE NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件发送配置';

-- 13. 邮件发送日志表
CREATE TABLE IF NOT EXISTS sys_mail_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  to_email VARCHAR(190) NOT NULL,
  scene VARCHAR(20) DEFAULT 'verify',
  subject VARCHAR(200) DEFAULT '',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '1成功 0失败',
  error_msg VARCHAR(500) DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_mail_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件发送日志';

-- 初始化邮件配置（默认账号，可在后台更换）
INSERT INTO sys_mail_config (host, port, username, password, from_name, subject_template, body_template, daily_limit)
SELECT 'smtp.larksuite.com', 465, 'signup@luokuans.com', '7doAm9eFKIdYW4rK', '遇你',
       '【遇你】邮箱验证码',
       '您好：

您的验证码是 {code}，请在 {minutes} 分钟内完成验证。

若非本人操作，请忽略此邮件。

—— 遇你',
       450
WHERE NOT EXISTS (SELECT 1 FROM sys_mail_config);
