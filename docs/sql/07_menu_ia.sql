-- ============================================================================
-- 07_menu_ia.sql —— 后台左侧菜单「信息架构」重规划（幂等，可重复执行）
--
-- 解决的问题（改造前实测）：
--   1) 5 个父级菜单 sort 全是 0 → 侧边栏顺序实际由 id 决定，「系统管理」排在最前面，
--      而日常最高频的「内容管理」被挤到第二；这次按「内容 → 互动 → 公告 → 句子 → 系统」重排。
--   2) 命名不统一：「用户维护 / 角色维护 / 文化列表 / 添加文化 / 文化分类 / 草长莺飞 / 公告模块」
--      混用「维护/列表/添加/莺飞」等叫法 → 统一为「××管理 / 内容列表 / 发布内容 / 分类管理 / 句子管理」。
--   3) 多数菜单没有图标 → 统一补齐 mdi 图标。
--   4) 新增菜单：回收站（/recycle/index）。
--
-- 幂等说明：
--   * 全部按 url（稳定的业务标识）匹配更新，重复执行结果一致；
--   * 新菜单用 INSERT ... SELECT ... FROM DUAL + NOT EXISTS（MySQL 5.7 不允许没有 FROM 的 WHERE）；
--   * 同步补 sys_permission 行并给角色 1（超级管理员）授权——只插 sys_menu 侧边栏不会显示；
--   * 末尾附自检 SELECT。
--
-- 用法（由主控执行）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/07_menu_ia.sql
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 父级（目录）：排序 + 命名 + 图标
--    内容管理 10 / 互动治理 20 / 公告管理 30 / 好词佳句 40 / 系统管理 90
-- ---------------------------------------------------------------------------
UPDATE sys_menu SET pid = NULL, sort = 10, name = '内容管理', icon = 'mdi mdi-image-multiple-outline'   WHERE url IS NULL AND name = '文化管理';
UPDATE sys_menu SET pid = NULL, sort = 20, name = '互动治理', icon = 'mdi mdi-forum-outline'            WHERE url IS NULL AND name = '内容治理';
UPDATE sys_menu SET pid = NULL, sort = 30, name = '公告管理', icon = 'mdi mdi-bullhorn-outline'         WHERE url IS NULL AND name = '公告模块';
UPDATE sys_menu SET pid = NULL, sort = 40, name = '好词佳句', icon = 'mdi mdi-format-quote-open'        WHERE url IS NULL AND name = '好词佳句';
UPDATE sys_menu SET pid = NULL, sort = 90, name = '系统管理', icon = 'mdi mdi-cog-outline'              WHERE url IS NULL AND name = '系统管理';

-- 兜底：万一上面按名字匹配不到（被改过名），按「有子菜单的父级」重新命名一次
UPDATE sys_menu SET icon = 'mdi mdi-image-multiple-outline' WHERE url IS NULL AND id = (SELECT pid FROM (SELECT pid FROM sys_menu WHERE url = '/culture/index' LIMIT 1) t);
UPDATE sys_menu SET icon = 'mdi mdi-forum-outline'          WHERE url IS NULL AND id = (SELECT pid FROM (SELECT pid FROM sys_menu WHERE url = '/comment/index' LIMIT 1) t);
UPDATE sys_menu SET icon = 'mdi mdi-bullhorn-outline'       WHERE url IS NULL AND id = (SELECT pid FROM (SELECT pid FROM sys_menu WHERE url = '/announcement/index' LIMIT 1) t);
UPDATE sys_menu SET icon = 'mdi mdi-format-quote-open'      WHERE url IS NULL AND id = (SELECT pid FROM (SELECT pid FROM sys_menu WHERE url = '/sentence/index' LIMIT 1) t);
UPDATE sys_menu SET icon = 'mdi mdi-cog-outline'            WHERE url IS NULL AND id = (SELECT pid FROM (SELECT pid FROM sys_menu WHERE url = '/user/index' LIMIT 1) t);

-- ---------------------------------------------------------------------------
-- 2. 子菜单：pid 归位 + 排序 + 命名 + 图标（一律按 url 匹配，最稳）
-- ---------------------------------------------------------------------------
-- 内容管理
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '内容管理'
SET m.pid = p.id, m.sort = 10, m.name = '内容列表', m.icon = 'mdi mdi-format-list-bulleted'
WHERE m.url = '/culture/index';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '内容管理'
SET m.pid = p.id, m.sort = 20, m.name = '发布内容', m.icon = 'mdi mdi-plus-box-outline'
WHERE m.url = '/culture/add';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '内容管理'
SET m.pid = p.id, m.sort = 30, m.name = '分类管理', m.icon = 'mdi mdi-folder-multiple-outline'
WHERE m.url = '/category/index';

-- 互动治理
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '互动治理'
SET m.pid = p.id, m.sort = 10, m.name = '评论审核', m.icon = 'mdi mdi-comment-check-outline'
WHERE m.url = '/comment/index';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '互动治理'
SET m.pid = p.id, m.sort = 20, m.name = '标签管理', m.icon = 'mdi mdi-tag-multiple-outline'
WHERE m.url = '/tag/index';

-- 公告管理
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '公告管理'
SET m.pid = p.id, m.sort = 10, m.name = '公告列表', m.icon = 'mdi mdi-format-list-checks'
WHERE m.url = '/announcement/index';

-- 好词佳句
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '好词佳句'
SET m.pid = p.id, m.sort = 10, m.name = '句子管理', m.icon = 'mdi mdi-format-quote-close'
WHERE m.url = '/sentence/index';

-- 系统管理
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '系统管理'
SET m.pid = p.id, m.sort = 10, m.name = '用户管理', m.icon = 'mdi mdi-account-multiple-outline'
WHERE m.url = '/user/index';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '系统管理'
SET m.pid = p.id, m.sort = 20, m.name = '角色管理', m.icon = 'mdi mdi-account-key-outline'
WHERE m.url = '/role/index';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '系统管理'
SET m.pid = p.id, m.sort = 30, m.name = '菜单权限', m.icon = 'mdi mdi-shield-key-outline'
WHERE m.url = '/permission/index';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '系统管理'
SET m.pid = p.id, m.sort = 40, m.name = '操作日志', m.icon = 'mdi mdi-history'
WHERE m.url = '/oplog/index';
UPDATE sys_menu m
    JOIN (SELECT * FROM sys_menu) p ON p.url IS NULL AND p.name = '系统管理'
SET m.pid = p.id, m.sort = 50, m.name = '邮件设置', m.icon = 'mdi mdi-email-cog-outline'
WHERE m.url = '/mail/index';

-- 全部菜单恢复为启用/未删除（历史上可能被改过）
UPDATE sys_menu SET status = 1, deleted = 0 WHERE url IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. 新增菜单：回收站（/recycle/index）—— 挂在「系统管理」下
--    同一批处理它的 sys_permission 行与角色 1 授权，否则侧边栏看不到
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted, remark)
SELECT '回收站', '/recycle/index', 'mdi mdi-delete-restore',
       (SELECT id FROM (SELECT id FROM sys_menu WHERE url IS NULL AND name = '系统管理' LIMIT 1) t),
       35, 1, 0, '已删除内容的恢复与彻底删除（docs/sql/07_menu_ia.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m WHERE m.url = '/recycle/index');

UPDATE sys_menu SET deleted = 0, status = 1, sort = 35, name = '回收站', icon = 'mdi mdi-delete-restore'
WHERE url = '/recycle/index' AND (deleted <> 0 OR status <> 1);

INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'permission:recycle', '回收站', 0, m.id, 35, 1, 0, '回收站（07_menu_ia.sql）'
FROM (SELECT * FROM sys_menu) m
WHERE m.url = '/recycle/index' AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p WHERE p.menu_id = m.id);

UPDATE sys_permission p
    JOIN (SELECT * FROM sys_menu) m ON m.id = p.menu_id AND m.url = '/recycle/index'
SET p.deleted = 0, p.status = 1
WHERE p.deleted <> 0;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM (SELECT * FROM sys_permission) p
         JOIN (SELECT * FROM sys_menu) m ON m.id = p.menu_id
WHERE m.url = '/recycle/index' AND m.deleted = 0 AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_role_permission) rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- ---------------------------------------------------------------------------
-- 4. 自检：打印重排后的菜单树（期望：内容管理10 / 互动治理20 / 公告管理30 / 好词佳句40 / 系统管理90）
-- ---------------------------------------------------------------------------
SELECT IFNULL(p.name, '（一级）') AS parent, p.sort AS parent_sort, m.sort AS sort,
       m.name, m.url, m.icon
FROM sys_menu m
         LEFT JOIN sys_menu p ON p.id = m.pid
WHERE m.deleted = 0
ORDER BY IFNULL(p.sort, m.sort), IFNULL(p.id, m.id), m.sort, m.id;
