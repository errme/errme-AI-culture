-- ============================================================================
-- 04_indexes.sql —— 复合索引补充（A4；只新增索引，可重复执行）
--
-- 目的：给后台/前台的分页、筛选查询补上复合索引，消除 filesort 与全表扫描。
--       本脚本只 CREATE INDEX（不改表结构、不改数据、不删索引）。
--
-- 用法（务必带上库名；未带库名时脚本按 culture_v2 处理）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/04_indexes.sql
--
-- 幂等实现：MySQL 5.7 不支持 `CREATE INDEX IF NOT EXISTS`，也不支持
--   `ALTER TABLE ... ADD INDEX IF NOT EXISTS`，因此每个索引先查 information_schema.statistics：
--   已存在 -> 打印 [skip] 跳过；不存在 -> 才执行 ALTER TABLE。重复执行不会重复建索引，
--   也不需要 CREATE ROUTINE 权限（不建存储过程）。
--
-- 执行前索引现状（2026-09-11 在 culture_v2 实测 information_schema.statistics）：
--   biz_culture       PRIMARY(id) / idx_culture_category(category_id) / idx_culture_creator(creator_id)
--                     / idx_culture_status(status,deleted)
--   biz_comment       PRIMARY(id) / idx_comment_culture(culture_id,status,id) / idx_comment_status(status,id)
--   biz_culture_tag   PRIMARY(id) / uk_culture_tag(culture_id,tag_id) / idx_culture_tag_tag(tag_id)
--   sys_operation_log PRIMARY(id) / idx_oplog_created(created_at) / idx_oplog_user(user_id,created_at)
--                     / idx_oplog_module(module,created_at)
--   sys_user          PRIMARY(id) / uk_user_email(email,唯一) / uk_user_username(username,唯一)
--                     / idx_user_phone(phone) / idx_user_status(status,deleted)
--
-- 本脚本内容一览：
--   【新增】1. biz_culture(deleted,id)                逻辑删除 + 主键排序分页
--   【新增】2. biz_culture(deleted,category_id,id)    逻辑删除 + 分类筛选 + 排序
--   【新增】3. biz_culture(deleted,status,id)         逻辑删除 + 上下架状态 + 排序
--   【新增】4. biz_culture(deleted,created_at,id)     附加项：最新内容/时间倒序取 N 条（RSS）
--   【新增】5. biz_culture_tag(tag_id,culture_id)     标签反查（覆盖索引）
--   【已存在】6. biz_comment(culture_id,status,id) / biz_comment(status,id)
--   【已存在】7. sys_operation_log(created_at)
--   【已存在】8. sys_user(email) 唯一索引
--   6~8 已由 01_schema.sql / 03_features.sql 建好，这里保留幂等补建块（防止被误删后无人补），
--   正常情况下全部打印 [skip]。
--
-- 注意：脚本不做破坏性操作。第 8 条若 email 上存在重复数据会执行失败——这是需要人工去重
--       的信号，请不要为了跑通脚本而删数据。
-- ============================================================================

SET NAMES utf8mb4;

-- 未在命令行指定库时按 culture_v2 处理（information_schema 查询都要用 @db）
SET @db := IFNULL(DATABASE(), 'culture_v2');

-- ---------------------------------------------------------------------------
-- 1) biz_culture(deleted, id)
--    对应查询：
--      · 文化列表分页：select ... from biz_culture where deleted=0 order by id desc limit ?,?
--        （CultureMapper#queryTotal / #queryData → /api/culture/list、/api/admin/culture/list）
--      · SEO sitemap：select id,created_at,updated_at from biz_culture where deleted=0 order by id desc
--      · 总量统计：select count(*) from biz_culture where deleted=0
--    预期收益：命中 (deleted,id) 后按索引倒序取 N 行即停，消除 filesort 与「扫描全部未删除行」
--              的开销；count(*) 走覆盖索引不回表。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND index_name = 'idx_culture_deleted_id');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.idx_culture_deleted_id 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD INDEX `idx_culture_deleted_id` (`deleted`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) biz_culture(deleted, category_id, id)
--    对应查询：列表页按分类筛选 + 分页
--      select ... from biz_culture u where u.deleted=0 and u.category_id=? order by u.id desc limit ?,?
--    预期收益：等值条件 deleted+category_id 直接定位区间，id 有序即取即停；
--              原 idx_culture_category(category_id) 无法消除 deleted 过滤与 id 排序。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND index_name = 'idx_culture_deleted_category_id');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.idx_culture_deleted_category_id 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD INDEX `idx_culture_deleted_category_id` (`deleted`, `category_id`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) biz_culture(deleted, status, id)
--    对应查询：按上下架状态过滤的列表（biz_culture.status：1 上架 / 0 下架）
--      select ... from biz_culture where deleted=0 and status=1 order by id desc limit ?,?
--    预期收益：deleted+status 等值定位 + id 有序取数，避免过滤 status 后 filesort。
--    说明：当前代码里文化列表还没有「只看上架」的过滤条件（现有查询都不带 status），
--          本索引是按需求预留 + 现有 idx_culture_status(status,deleted) 无法覆盖排序的补充；
--          若后续确认永远不会按 status 过滤，可以安全删掉这一条。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND index_name = 'idx_culture_deleted_status_id');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.idx_culture_deleted_status_id 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD INDEX `idx_culture_deleted_status_id` (`deleted`, `status`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4) biz_culture(deleted, created_at, id)   「附加项，不在需求清单内，可按需删除」
--    对应查询：最新内容（SEO/RSS）
--      select ... from biz_culture where deleted=0 order by created_at desc, id desc limit ?
--      （CultureMapper.xml#findSeoLatest，/rss.xml）
--    预期收益：按 created_at 倒序直接取前 N 条，消除整表 filesort（id 与 created_at 相关
--              但优化器并不知道，所以不能靠主键倒序代替）。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND index_name = 'idx_culture_deleted_created');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.idx_culture_deleted_created 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD INDEX `idx_culture_deleted_created` (`deleted`, `created_at`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 5) biz_culture_tag(tag_id, culture_id)
--    对应查询：标签反查内容（前台标签页 /api/tag/cultures）
--      select count(*) from biz_culture c join biz_culture_tag ct on ct.culture_id=c.id
--        where ct.tag_id=? and c.deleted=0
--      select ... from biz_culture c join biz_culture_tag ct on ct.culture_id=c.id
--        where ct.tag_id=? and c.deleted=0 order by c.id desc limit ?,?
--    预期收益：现有 idx_culture_tag_tag(tag_id) 只能定位 tag_id，还要回表取 culture_id；
--              (tag_id,culture_id) 是覆盖索引，回表次数与随机 IO 明显减少。
--    备注：新增后 idx_culture_tag_tag(tag_id) 变成冗余前缀索引，本脚本只新增不删除；
--          如需清理可人工执行：ALTER TABLE `biz_culture_tag` DROP INDEX `idx_culture_tag_tag`;
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture_tag'
                  AND index_name = 'idx_culture_tag_tag_culture');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture_tag.idx_culture_tag_tag_culture 已存在'' AS result',
               'ALTER TABLE `biz_culture_tag` ADD INDEX `idx_culture_tag_tag_culture` (`tag_id`, `culture_id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 6) biz_comment(culture_id, status, id) —— 已存在（03_features.sql），补建块仅为防误删
--    对应查询：前台评论树 select ... where culture_id=? and status=1 and deleted=0 order by id asc
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_comment'
                  AND index_name = 'idx_comment_culture');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_comment.idx_comment_culture 已存在'' AS result',
               'ALTER TABLE `biz_comment` ADD INDEX `idx_comment_culture` (`culture_id`, `status`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 7) biz_comment(status, id) —— 已存在（03_features.sql），补建块仅为防误删
--    对应查询：后台评论分页 where deleted=0 and status=? order by id desc limit ?,?
--              待审数量统计 select count(*) where status=? and deleted=0
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_comment'
                  AND index_name = 'idx_comment_status');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_comment.idx_comment_status 已存在'' AS result',
               'ALTER TABLE `biz_comment` ADD INDEX `idx_comment_status` (`status`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 8) sys_operation_log(created_at) —— 已存在（03_features.sql），补建块仅为防误删
--    对应查询：操作日志按时间倒序分页（后台 /api/admin/log/list 实际按 id 倒序，
--              PRIMARY(id) 已覆盖；created_at 索引供按时间的范围查询/统计使用）
--    说明：没有重复新增 (id) 索引——主键即 id，再建 KEY(id) 没有意义。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'sys_operation_log'
                  AND index_name = 'idx_oplog_created');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] sys_operation_log.idx_oplog_created 已存在'' AS result',
               'ALTER TABLE `sys_operation_log` ADD INDEX `idx_oplog_created` (`created_at`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 9) sys_user(email) 唯一索引 —— 已存在（01_schema.sql 的 uk_user_email），补建块仅为防误删
--    对应查询：登录/注册/找回密码全部按 email 查询，必须唯一且走唯一索引
--      select ... from sys_user where email = ?
--    注意：若历史数据里存在重复 email，这条 ALTER 会失败（提示 Duplicate entry），
--          请先人工去重；脚本其它部分不受影响。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'sys_user'
                  AND index_name = 'uk_user_email' AND non_unique = 0);
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] sys_user.uk_user_email 已存在（唯一）'' AS result',
               'ALTER TABLE `sys_user` ADD UNIQUE KEY `uk_user_email` (`email`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 校验：列出相关表当前的全部索引（执行后自查用）
-- ---------------------------------------------------------------------------
SELECT table_name,
       index_name,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS index_columns,
       IF(non_unique = 0, 'UNIQUE', 'INDEX') AS kind
FROM information_schema.statistics
WHERE table_schema = @db
  AND table_name IN ('biz_culture', 'biz_comment', 'biz_culture_tag', 'sys_operation_log', 'sys_user')
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
