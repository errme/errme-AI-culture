-- =====================================================================
-- culture_v2 数据迁移脚本（旧库 culture + demo_1 合并）
-- 前提：01_schema.sql 已执行，旧库 culture / demo_1 存在
-- 执行：mysql --no-defaults -uroot -p123456 < sql/02_migrate.sql
-- 说明：
--   * 旧用户密码(BCrypt)直接迁入 password_hash，可无缝登录
--   * cul_user 邮箱重复(22@qq.com)时保留最小 id，其余邮箱加 +<id> 后缀
--   * demo_1.users 邮箱去重后合并，来源标记为 email
--   * 悬空外键数据（如 cul_user_role 的 userid=9、cul_like 的 uid=8）自动跳过
-- =====================================================================

USE culture_v2;
SET FOREIGN_KEY_CHECKS = 0;   -- 迁移期间临时关闭外键校验（数据已提前清洗）

-- ---------------------------------------------------------------------
-- 1. 用户：旧库 cul_user（邮箱保留最小 id，其余加后缀去重）
-- ---------------------------------------------------------------------
-- 1.1 每个邮箱保留 id 最小的一条
INSERT INTO sys_user (id, username, email, password_hash, nickname, phone, sex, avatar, status, source, created_at, remark)
SELECT u.id,
       u.username,
       LOWER(TRIM(u.email)),
       u.password,
       u.username,
       u.tel,
       IFNULL(CAST(u.sex AS UNSIGNED), 0),
       u.headImg,
       1,
       'legacy',
       u.createTime,
       '由旧库 cul_user 迁移'
FROM culture.cul_user u
WHERE u.id = (SELECT MIN(u2.id) FROM culture.cul_user u2 WHERE u2.email = u.email);

-- 1.2 重复邮箱的其余记录：邮箱加 +<id> 后缀（如 22+7@qq.com），用户名加 _dup
INSERT INTO sys_user (username, email, password_hash, nickname, phone, sex, avatar, status, source, created_at, remark)
SELECT CONCAT(u.username, '_dup'),
       CONCAT(SUBSTRING_INDEX(LOWER(TRIM(u.email)), '@', 1), '+', u.id, '@', SUBSTRING_INDEX(LOWER(TRIM(u.email)), '@', -1)),
       u.password,
       u.username,
       u.tel,
       IFNULL(CAST(u.sex AS UNSIGNED), 0),
       u.headImg,
       1,
       'legacy',
       u.createTime,
       '旧库重复邮箱，已自动加后缀去重'
FROM culture.cul_user u
WHERE u.id NOT IN (SELECT MIN(u2.id) FROM culture.cul_user u2 WHERE u2.email = u.email);

-- ---------------------------------------------------------------------
-- 2. 用户：合并 demo_1.users（邮箱去重，来源=email）
-- ---------------------------------------------------------------------
INSERT INTO sys_user (username, email, password_hash, nickname, status, source, created_at)
SELECT SUBSTRING_INDEX(LOWER(TRIM(u.email)), '@', 1),
       LOWER(TRIM(u.email)),
       u.password_hash,
       SUBSTRING_INDEX(LOWER(TRIM(u.email)), '@', 1),
       1,
       'email',
       u.created_at
FROM demo_1.users u
WHERE NOT EXISTS (SELECT 1 FROM sys_user s WHERE s.email = LOWER(TRIM(u.email)));

-- ---------------------------------------------------------------------
-- 3. 角色 / 权限 / 菜单（RBAC）
-- ---------------------------------------------------------------------
INSERT INTO sys_role (id, code, name, description, sort, status, created_at, remark)
SELECT id, sn, name, `desc`, 0, 1, NOW(), '由旧库 cul_role 迁移'
FROM culture.cul_role;

INSERT INTO sys_permission (id, name, title, pid, menu_id, sort, status, created_at, remark)
SELECT id, name, title, pid,
       IF(menuId IS NULL OR menuId = '', NULL, CAST(menuId AS UNSIGNED)),
       0, 1, NOW(), '由旧库 cul_permission 迁移'
FROM culture.cul_permission;

INSERT INTO sys_menu (id, name, url, icon, pid, sort, status, created_at, remark)
SELECT id, name, url, icon, pid, 0, 1, NOW(), '由旧库 cul_menu 迁移'
FROM culture.cul_menu;

-- 4. 用户-角色：跳过悬空用户（旧数据 userid=9 无对应用户）
INSERT INTO sys_user_role (user_id, role_id, created_at)
SELECT ur.userid, ur.roleid, NOW()
FROM culture.cul_user_role ur
WHERE EXISTS (SELECT 1 FROM sys_user u WHERE u.id = ur.userid)
  AND EXISTS (SELECT 1 FROM sys_role r WHERE r.id = ur.roleid);

-- 5. 角色-权限
INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT rp.roleId, rp.permissionId, NOW()
FROM culture.cul_role_permission rp;

-- ---------------------------------------------------------------------
-- 6. 业务数据
-- ---------------------------------------------------------------------
INSERT INTO biz_category (id, name, sort, status, created_at, remark)
SELECT id, categoryName, 0, 1, NOW(), '由旧库 cul_category 迁移'
FROM culture.cul_category;

INSERT INTO biz_culture (id, name, address, description, content, cover_url,
                         category_id, creator_id, view_count, status, created_at, remark)
SELECT id, cultureName, address, `desc`, info, fmUrl,
       categoryId, creatorId, IFNULL(view, 0), 1, createTime, '由旧库 cul_culture 迁移'
FROM culture.cul_culture;

INSERT INTO biz_announcement (id, title, content, status, created_at, remark)
SELECT id, announcement, announcement, 1, createTime, '由旧库 cul_announcement 迁移'
FROM culture.cul_announcement;

INSERT INTO biz_sentence (id, content, create_id, create_name, create_img, status, created_at, remark)
SELECT id, content, createId, createName, createImg, 1, createTime, '由旧库 cul_sentence 迁移'
FROM culture.cul_sentence;

-- 7. 点赞：跳过悬空用户（uid=8 的三条），createTime 为空时取当前时间
INSERT INTO biz_like (id, user_id, target_id, value, created_at)
SELECT l.id, l.uid, l.bid, IFNULL(l.val, 5), COALESCE(l.createTime, NOW())
FROM culture.cul_like l
WHERE EXISTS (SELECT 1 FROM sys_user u WHERE u.id = l.uid)
  AND EXISTS (SELECT 1 FROM biz_culture c WHERE c.id = l.bid);

-- 8. 回填点赞冗余统计字段
UPDATE biz_culture c
SET c.like_count = (SELECT COUNT(*) FROM biz_like l WHERE l.target_id = c.id);

SET FOREIGN_KEY_CHECKS = 1;
