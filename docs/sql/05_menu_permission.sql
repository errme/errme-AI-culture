-- ============================================================================
-- 05_menu_permission.sql —— 后台「系统管理 → 菜单权限」菜单（幂等，可重复执行）
--
-- 目的：为「后台左侧菜单/目录的角色权限配置」页面（前端 /permission/index）注册菜单，
--       并补齐 sys_permission 行 + 角色 1（超级管理员）授权。
--
-- 重要（见 03_features.sql 第 6 节）：
--   后台侧边栏菜单由 MenuMapper.findAll 按
--     sys_user_role → sys_role_permission → sys_permission.menu_id → sys_menu
--   关联查询得出；只插 sys_menu 是不会显示的，必须同时有 sys_permission 记录，
--   并把该权限授予角色（管理员 = 1）。
--
-- 幂等说明：
--   * 全部使用 INSERT ... SELECT ... WHERE NOT EXISTS（配 (SELECT * FROM x) 派生表，
--     规避 MySQL 1093「不能在子查询里引用被写入的表」）；
--   * 无表可引用的那几条带 FROM DUAL —— MySQL 5.7 不允许没有 FROM 的 WHERE
--     （SELECT 1 WHERE 1=1 → ERROR 1064），本库为 MySQL 5.7.30，必须写 FROM DUAL；
--   * 已存在则跳过；权限行若被逻辑删除则恢复（deleted 1 → 0）；
--   * 重复执行不会产生重复菜单 / 重复权限 / 重复授权。
--
-- 用法（由主控执行，本文件不自行执行）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 \
--     < docs/sql/05_menu_permission.sql
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 菜单行：系统管理（pid）→ 菜单权限，url = /permission/index
--    若库里没有名为「系统管理」的一级目录，pid 落为 NULL（成为一级菜单），
--    此时请手工把 pid 指到正确的目录 id 后重跑本脚本。
-- ---------------------------------------------------------------------------
INSERT INTO sys_menu (name, url, icon, pid, sort, status, deleted, remark)
SELECT '菜单权限',
       '/permission/index',
       'mdi mdi-shield-key-outline',
       (SELECT m.id FROM (SELECT * FROM sys_menu) m
         WHERE m.name = '系统管理' AND m.deleted = 0
         ORDER BY m.id LIMIT 1),
       20, 1, 0,
       '后台左侧菜单/目录的角色权限配置（docs/sql/05_menu_permission.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_menu) m2 WHERE m2.url = '/permission/index');

-- 1.1 兜底：若该 url 的菜单存在但被逻辑删除，则恢复
UPDATE sys_menu
SET deleted = 0, status = 1
WHERE url = '/permission/index' AND deleted <> 0;

-- ---------------------------------------------------------------------------
-- 2. 权限行：menu_id 指向上面的菜单（name/title 用菜单名，sort 用菜单 sort）
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'permission:menu', '菜单权限', 0, m.id, m.sort, 1, 0, '菜单权限配置（05_menu_permission.sql）'
FROM (SELECT * FROM sys_menu) m
WHERE m.url = '/permission/index'
  AND m.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p WHERE p.menu_id = m.id);

-- 2.1 兜底：权限行存在但被逻辑删除的，恢复（复用原 id，授权关系不丢）
UPDATE sys_permission p
    JOIN (SELECT * FROM sys_menu) m ON m.id = p.menu_id AND m.url = '/permission/index'
SET p.deleted = 0, p.status = 1
WHERE p.deleted <> 0;

-- ---------------------------------------------------------------------------
-- 3. 授权：把该权限授予超级管理员角色（id = 1）
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, p.id
FROM (SELECT * FROM sys_permission) p
         JOIN (SELECT * FROM sys_menu) m ON m.id = p.menu_id
WHERE m.url = '/permission/index'
  AND m.deleted = 0
  AND p.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_role_permission) rp
                  WHERE rp.role_id = 1 AND rp.permission_id = p.id);

-- ---------------------------------------------------------------------------
-- 4. 执行结果自检（打印一行，供确认；不是变更语句）
--    期望：menu_id / permission_id 非空，granted_role1 = 1
-- ---------------------------------------------------------------------------
SELECT m.id                                          AS menu_id,
       m.name                                        AS menu_name,
       m.url                                         AS menu_url,
       m.pid                                         AS menu_pid,
       m.sort                                        AS menu_sort,
       p.id                                          AS permission_id,
       p.name                                        AS permission_name,
       (SELECT COUNT(*) FROM sys_role_permission rp
         WHERE rp.role_id = 1 AND rp.permission_id = p.id) AS granted_role1
FROM sys_menu m
         LEFT JOIN sys_permission p ON p.menu_id = m.id AND p.deleted = 0
WHERE m.url = '/permission/index' AND m.deleted = 0;
