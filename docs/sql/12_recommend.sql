-- ============================================================================
-- 12_recommend.sql —— 内容推荐位 / 置顶（is_top + recommend_sort 两列 + 一个复合索引）
--
-- 一、需求背景
--   前台首页「热门」与详情页「推荐」需要人工干预排序：
--     · 置顶（is_top=1）：运营指定的内容必须排在最前面；
--     · 推荐位顺序（recommend_sort）：同为置顶 / 同为非置顶时，数值越小越靠前。
--   生效范围只限两处前台展示查询（CultureMapper#queryHotAll、#findTop4Culture）：
--     order by is_top desc, recommend_sort asc, <原排序字段>
--   后台列表与管理端排序（CultureMapper.xml#queryData / #queryAdminPage 的 order by u.id desc）
--   **不受影响**，后台仍然是按 id 倒序的管理视角。
--
-- 二、新增内容（只加列 + 只加索引，不改任何一行的数据）
--   1) biz_culture.is_top         TINYINT NOT NULL DEFAULT 0   是否置顶：0=否、1=是
--   2) biz_culture.recommend_sort INT     NOT NULL DEFAULT 0   推荐位排序：越小越靠前（0 为默认）
--   3) biz_culture(deleted, status, is_top, recommend_sort)    前台推荐/热门排序索引
--
--   为什么默认值都是 0：存量数据加列后 is_top 全为 0、recommend_sort 全为 0，
--   于是 `order by is_top desc, recommend_sort asc` 对所有存量行完全等价（全部并列），
--   再按原来的 view_count desc 排序 —— 即**上线后前台顺序与改造前逐字一致**，
--   只有运营显式置顶/设置推荐顺序后才会变化。这是本脚本不修改任何数据的前提。
--
-- 三、幂等实现（MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS）
--   与 04_indexes.sql、09_publish_schedule.sql 同一套写法：先查 information_schema，
--   已存在 → 打印 [skip] 跳过；不存在 → 才执行 ALTER TABLE（PREPARE/EXECUTE，不建存储过程，
--   不需要 CREATE ROUTINE 权限）。重复执行安全。
--
-- 用法（务必带上库名；未带库名时脚本按 culture_v2 处理）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/12_recommend.sql
--
-- 注意：应用侧新增的排序/筛选语句引用了这两列，必须先执行本脚本再启动新版本服务，
--       否则会报 Unknown column 'is_top'（与 09_publish_schedule.sql 的注意事项一致）。
-- ============================================================================

SET NAMES utf8mb4;

-- 未在命令行指定库时按 culture_v2 处理（information_schema 查询都要用 @db）
SET @db := IFNULL(DATABASE(), 'culture_v2');

-- ---------------------------------------------------------------------------
-- 1) 新增 is_top 列（是否置顶；不存在才加）
--    位置放在 publish_at 之后，与 status/publish_at 这些「内容状态类」列相邻，便于查看。
--    不加索引：单独按 is_top 过滤的场景不存在，统一走下面 (deleted,status,is_top,recommend_sort)。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND column_name = 'is_top');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.is_top 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD COLUMN `is_top` TINYINT NOT NULL DEFAULT 0 COMMENT ''是否置顶 0=否 1=是（前台热门/推荐排序优先，见 docs/sql/12_recommend.sql）'' AFTER `publish_at`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) 新增 recommend_sort 列（推荐位排序：越小越靠前；不存在才加）
--    默认 0：存量数据全部并列，排序结果与改造前一致（见文件头「为什么默认值都是 0」）。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND column_name = 'recommend_sort');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.recommend_sort 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD COLUMN `recommend_sort` INT NOT NULL DEFAULT 0 COMMENT ''推荐位排序，越小越靠前（0=默认；配合 is_top 使用，见 docs/sql/12_recommend.sql）'' AFTER `is_top`');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) 复合索引 (deleted, status, is_top, recommend_sort)
--    对应查询（前台首页热门 / 详情页推荐）：
--      select ... from biz_culture
--       where deleted=0 and status=1
--       order by is_top desc, recommend_sort asc, view_count desc limit 0,3
--      select ... from biz_culture
--       where deleted=0 and status=1
--       order by is_top desc, recommend_sort asc, view_count desc limit 0,10
--    预期收益：deleted + status 等值定位后，is_top/recommend_sort 在索引内天然有序，
--              优化器不必为「置顶优先」这一层排序做全量 filesort（最后一层 view_count 仍需
--              在候选集内排序，但候选集已被索引裁剪，代价远小于整表 filesort）。
--    命名：沿用 04_indexes.sql 的 idx_culture_ 前缀风格。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND index_name = 'idx_culture_top');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.idx_culture_top 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD INDEX `idx_culture_top` (`deleted`, `status`, `is_top`, `recommend_sort`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4) 自检（执行后自查用）
-- ---------------------------------------------------------------------------

-- 4.1 两列是否就位（类型 / 可空 / 默认值）
SELECT column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = @db AND table_name = 'biz_culture'
  AND column_name IN ('is_top', 'recommend_sort')
ORDER BY ordinal_position;

-- 4.2 索引是否就位
SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS index_columns
FROM information_schema.statistics
WHERE table_schema = @db AND table_name = 'biz_culture'
  AND index_name = 'idx_culture_top'
GROUP BY index_name;

-- 4.3 数据现状（应当全是 0/0 —— 本脚本不改数据；置顶/推荐顺序由后台接口写入）
SELECT `is_top`, `recommend_sort`, COUNT(*) AS cnt
FROM `biz_culture`
GROUP BY `is_top`, `recommend_sort`
ORDER BY `is_top` DESC, `recommend_sort` ASC;
