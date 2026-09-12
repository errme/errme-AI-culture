-- ============================================================================
-- 09_publish_schedule.sql —— 定时发布（status 语义扩展 + publish_at 列）
--
-- 一、status 语义扩展（只改注释，不改任何数据）
--   0 = 草稿
--   1 = 已发布
--   2 = 定时待发布（到 publish_at 后由 PublishScheduler 自动置为 1）
--   历史语义：0=下架、1=上架。**本脚本不修改任何一行的 status 值**，
--   现有数据（实测全部为 1=已发布）行为不变。
--
-- 二、新增列 publish_at DATETIME NULL：定时发布时间（status=2 时必填）。
--   调度扫描：status=2 AND deleted=0 AND publish_at <= NOW()  → 批量置 status=1。
--   现有索引 idx_culture_deleted_status_id(deleted,status,id) 已能覆盖该扫描的等值前缀，
--   命中的行数很少，剩下的 publish_at 条件在索引结果上过滤即可，故不额外建索引。
--
-- 幂等实现（参考 04_indexes.sql 的风格）：
--   * publish_at：先查 information_schema.columns，不存在才 ALTER TABLE ADD COLUMN；
--   * status 列注释：仅当当前注释里没有 ASCII 标记 'scheduled' 时才 MODIFY（重复执行第二次打印 [skip]，
--     只改列注释，不改类型/默认值/数据）。
--
-- 用法（由主控执行；务必带库名，未带库名时脚本按 culture_v2 处理）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/09_publish_schedule.sql
--
-- 注意：应用侧查询（详情接口的 COLS、新增的定时发布语句）引用了 publish_at，
--       必须先执行本脚本再启动新版本服务，否则会报 Unknown column 'publish_at'。
-- ============================================================================

SET NAMES utf8mb4;

-- 未在命令行指定库时按 culture_v2 处理（information_schema 查询都要用 @db）
SET @db := IFNULL(DATABASE(), 'culture_v2');

-- ---------------------------------------------------------------------------
-- 1) 新增 publish_at 列（不存在才加）
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND column_name = 'publish_at');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.publish_at 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD COLUMN `publish_at` DATETIME NULL DEFAULT NULL COMMENT ''定时发布时间（status=2 定时待发布 时必填，到点由定时任务置为已发布）'' AFTER `status`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) 更新 status 列注释，把新的三态语义写进表结构（仅注释变化，数据不动）
--    幂等判断刻意用 ASCII 标记 'scheduled' 而不是中文「草稿」：
--    万一客户端字符集不是 utf8mb4，中文匹配会失配导致每次执行都重复 MODIFY（甚至写坏注释），
--    ASCII 标记在任何字符集下都稳定。
-- ---------------------------------------------------------------------------
SET @needs := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = @db AND table_name = 'biz_culture'
                 AND column_name = 'status' AND column_comment NOT LIKE '%scheduled%');
SET @ddl := IF(@needs > 0,
               'ALTER TABLE `biz_culture` MODIFY COLUMN `status` TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT ''状态 0=草稿(draft) 1=已发布(published) 2=定时待发布(scheduled)''',
               'SELECT ''[skip] biz_culture.status 注释已包含新语义'' AS result');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) 自检：status 分布（用于确认「没有改过任何数据」）+ publish_at 列结构
-- ---------------------------------------------------------------------------
SELECT `status`, COUNT(*) AS cnt FROM `biz_culture` GROUP BY `status`;

SELECT column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = @db AND table_name = 'biz_culture'
  AND column_name IN ('status', 'publish_at');
