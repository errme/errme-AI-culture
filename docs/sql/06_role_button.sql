-- ============================================================================
-- 06_role_button.sql —— 角色管理 + 按钮级权限（幂等，可重复执行）
--
-- 目的：
--   1) 把现有三个角色的 code 补齐（sys_role.code 目前全为 NULL）：
--      管理员 → admin、编辑 → editor、用户 → user；已非空的不覆盖；
--   2) 确认/补齐 4 条「按钮级权限」行：user:add / user:delete / user:get / user:update
--      （menu_id IS NULL 即按钮级；被逻辑删除的恢复为 deleted = 0）。
--
-- 明确不做的事：
--   * 不给任何角色自动授予按钮权限 —— 按钮授权由后台界面（/api/admin/permission/button）
--     按角色配置，脚本只保证「可选按钮清单」存在；
--   * 不动 sys_role_permission 里已有的任何行（菜单类授权、按钮类授权都保留原样）；
--   * 不动 sys_menu / 菜单权限行 / 新增依赖（本脚本只用 INSERT ... SELECT ... WHERE NOT EXISTS）。
--
-- 幂等说明：
--   * 无表可引用时一律写 FROM DUAL —— MySQL 5.7 不允许没有 FROM 的 WHERE
--     （SELECT 1 WHERE 1=1 → ERROR 1064），本库为 MySQL 5.7.30；
--   * 所有「存在性判断 / 自引用」都走 (SELECT * FROM x) 派生表快照，
--     规避 MySQL 1093「You can't specify target table for update in FROM clause」；
--   * 角色 code 只在 code IS NULL 或 '' 时补，且带「code 未被别的角色占用」保护 +
--     ORDER BY id LIMIT 1，避免撞唯一键 uk_role_code；
--   * 按钮权限按 (name, menu_id IS NULL) 判断存在，已存在则跳过、被逻辑删除则恢复；
--   * 重复执行不产生重复角色编码 / 重复权限行 / 重复授权。
--
-- 用法（由主控执行，本文件不自行执行）：
--   D:/me/SQL/MySQL/MySQL/bin/mysql.exe --no-defaults --default-character-set=utf8mb4 \
--     -uroot -p123456 culture_v2 < docs/sql/06_role_button.sql
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 角色 code 补齐（按 name 匹配；已非空的不覆盖）
--    uk_role_code 是唯一索引，因此每条都先用「未取快照」确认目标 code 还没被任何角色占用，
--    并用 ORDER BY id LIMIT 1 兜住「同名的多行」这种极端情况（name 上没有唯一约束）。
--    NOT EXISTS 的派生表是 UPDATE 前的快照，所以判断是「改之前有没有人用这个 code」。
-- ---------------------------------------------------------------------------

-- 1.1 管理员 → admin
UPDATE sys_role
SET code = 'admin'
WHERE name = '管理员'
  AND deleted = 0
  AND (code IS NULL OR code = '')
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_role) x
                  WHERE LOWER(IFNULL(x.code, '')) = 'admin')
ORDER BY id
LIMIT 1;

-- 1.2 编辑 → editor
UPDATE sys_role
SET code = 'editor'
WHERE name = '编辑'
  AND deleted = 0
  AND (code IS NULL OR code = '')
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_role) x
                  WHERE LOWER(IFNULL(x.code, '')) = 'editor')
ORDER BY id
LIMIT 1;

-- 1.3 用户 → user
UPDATE sys_role
SET code = 'user'
WHERE name = '用户'
  AND deleted = 0
  AND (code IS NULL OR code = '')
  AND NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_role) x
                  WHERE LOWER(IFNULL(x.code, '')) = 'user')
ORDER BY id
LIMIT 1;

-- ---------------------------------------------------------------------------
-- 2. 按钮级权限（menu_id IS NULL）
--    pid 指向「用户模块」菜单权限行（sys_permission.name='user' 且 menu_id 非空）的
--    最小 id；查不到时落 0（顶级），保证语句在干净库上也能跑。
--    sort 与库里既有的 4 行保持一致（0）。
-- ---------------------------------------------------------------------------

-- 2.1 user:add（用户新增）
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'user:add', '用户新增',
       IFNULL((SELECT MIN(p0.id) FROM (SELECT * FROM sys_permission) p0
               WHERE p0.name = 'user' AND p0.menu_id IS NOT NULL AND p0.deleted = 0), 0),
       NULL, 0, 1, 0, '按钮级权限（docs/sql/06_role_button.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p
                  WHERE p.name = 'user:add' AND p.menu_id IS NULL);

-- 2.2 user:delete（用户删除）
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'user:delete', '用户删除',
       IFNULL((SELECT MIN(p0.id) FROM (SELECT * FROM sys_permission) p0
               WHERE p0.name = 'user' AND p0.menu_id IS NOT NULL AND p0.deleted = 0), 0),
       NULL, 0, 1, 0, '按钮级权限（docs/sql/06_role_button.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p
                  WHERE p.name = 'user:delete' AND p.menu_id IS NULL);

-- 2.3 user:get（用户查询）
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'user:get', '用户查询',
       IFNULL((SELECT MIN(p0.id) FROM (SELECT * FROM sys_permission) p0
               WHERE p0.name = 'user' AND p0.menu_id IS NOT NULL AND p0.deleted = 0), 0),
       NULL, 0, 1, 0, '按钮级权限（docs/sql/06_role_button.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p
                  WHERE p.name = 'user:get' AND p.menu_id IS NULL);

-- 2.4 user:update（用户更新）
INSERT INTO sys_permission (name, title, pid, menu_id, sort, status, deleted, remark)
SELECT 'user:update', '用户更新',
       IFNULL((SELECT MIN(p0.id) FROM (SELECT * FROM sys_permission) p0
               WHERE p0.name = 'user' AND p0.menu_id IS NOT NULL AND p0.deleted = 0), 0),
       NULL, 0, 1, 0, '按钮级权限（docs/sql/06_role_button.sql）'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM sys_permission) p
                  WHERE p.name = 'user:update' AND p.menu_id IS NULL);

-- 2.5 兜底：这 4 条按钮权限行若被逻辑删除，则恢复（复用原 id，已有授权关系不丢）
UPDATE sys_permission
SET deleted = 0,
    status  = 1
WHERE menu_id IS NULL
  AND deleted <> 0
  AND name IN ('user:add', 'user:delete', 'user:get', 'user:update');

-- ---------------------------------------------------------------------------
-- 3. 执行结果自检（只读 SELECT，打印结果供确认；不是变更语句）
--    期望 3.1：三条角色 code 分别为 admin / editor / user，且互不重复；
--    期望 3.2：4 行按钮权限，deleted = 0；
--    期望 3.3：granted_count 反映各角色「已配置」的按钮数（脚本不自动授权，
--              因此新建/初始库上可能为 0，属正常）。
-- ---------------------------------------------------------------------------

SELECT r.id                                            AS role_id,
       r.name                                          AS role_name,
       r.code                                          AS role_code,
       r.status                                        AS role_status,
       r.deleted                                       AS role_deleted,
       (SELECT COUNT(*) FROM sys_user_role ur WHERE ur.role_id = r.id) AS user_count
FROM sys_role r
WHERE r.deleted = 0
ORDER BY r.id;

SELECT p.id                                                          AS permission_id,
       p.name                                                        AS permission_name,
       p.title                                                       AS permission_title,
       p.pid                                                         AS permission_pid,
       p.sort                                                        AS permission_sort,
       p.status                                                      AS permission_status,
       p.deleted                                                     AS permission_deleted,
       (SELECT COUNT(*) FROM sys_role_permission rp
         WHERE rp.permission_id = p.id)                              AS granted_count
FROM sys_permission p
WHERE p.menu_id IS NULL
ORDER BY p.sort, p.id;

-- 3.3 重名/重复检查：期望两列均为 0 行
SELECT code, COUNT(*) AS cnt
FROM sys_role
WHERE deleted = 0 AND code IS NOT NULL
GROUP BY code
HAVING COUNT(*) > 1;

SELECT name, COUNT(*) AS cnt
FROM sys_permission
WHERE menu_id IS NULL AND deleted = 0
GROUP BY name
HAVING COUNT(*) > 1;
