-- ============================================================================
-- 08_culture_version.sql —— 内容版本历史（保存即留痕 / 可对比 / 可回滚）
--
-- 目的：给 biz_culture 的「编辑保存」与「回滚」留痕。
--   * 后台保存走 POST /api/admin/culture/save（新增/修改分流，逻辑在 CultureServiceImpl）；
--   * 修改保存前，先把「修改前」的整条记录快照写入 biz_culture_version（新增不写）；
--   * 同一个 culture 在同一自然分钟内重复保存只保留一条（避免频繁编辑刷爆版本表）；
--   * 回滚前也会先写一条「当前内容」的快照，保证回滚本身可逆（见 CultureVersionServiceImpl）。
--
-- 幂等实现（参考 04_indexes.sql 的风格，MySQL 5.7 没有 CREATE TABLE/INDEX IF NOT EXISTS 的完整支持）：
--   1) 表用 CREATE TABLE IF NOT EXISTS（表内不带二级索引）；
--   2) 索引单独用 information_schema.statistics 判断后再 ALTER TABLE ADD INDEX，
--      这样「表已存在但索引缺失」的情况也能补上，重复执行全部打印 [skip]。
--
-- 用法（由主控执行；务必带库名，未带库名时脚本按 culture_v2 处理）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/08_culture_version.sql
--
-- 注意：本脚本只新增对象，不修改 biz_culture 的任何数据（status 一律不动）。
-- ============================================================================

SET NAMES utf8mb4;

-- 未在命令行指定库时按 culture_v2 处理（information_schema 查询都要用 @db）
SET @db := IFNULL(DATABASE(), 'culture_v2');

-- ---------------------------------------------------------------------------
-- 1) 版本历史表 biz_culture_version
--    字段与 biz_culture 的「内容字段」一一对应（不含 address/status/publish_at/deleted：
--    这几个字段不参与版本快照与回滚，回滚只还原正文相关内容）。
--    刻意不加外键：内容被删除、用户被删除都不应该连带删掉历史版本。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `biz_culture_version` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '版本ID',
    `culture_id`    BIGINT UNSIGNED NOT NULL                COMMENT 'biz_culture.id（所属内容）',
    `name`          VARCHAR(255)    NULL                    COMMENT '快照时的标题（biz_culture.name）',
    `description`   TEXT            NULL                    COMMENT '快照时的描述（biz_culture.description）',
    `content`       LONGTEXT        NULL                    COMMENT '快照时的正文（biz_culture.content，longtext）',
    `cover_url`     VARCHAR(500)    NULL                    COMMENT '快照时的封面（biz_culture.cover_url）',
    `category_id`   BIGINT UNSIGNED NULL                    COMMENT '快照时的分类ID（biz_culture.category_id）',
    `operator_id`   BIGINT UNSIGNED NULL                    COMMENT '操作人 sys_user.id（取不到时为 NULL）',
    `operator_name` VARCHAR(100)    NULL                    COMMENT '操作人用户名（冗余保存，用户改名/删除后仍可追溯）',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '内容版本历史（保存前快照，可对比可回滚）';

-- ---------------------------------------------------------------------------
-- 2) 索引 (culture_id, id)：版本列表按内容查、按 id 倒序分页
--      select ... from biz_culture_version where culture_id=? order by id desc limit ?,?
--    幂等：MySQL 5.7 不支持 CREATE INDEX IF NOT EXISTS，先查 information_schema.statistics。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture_version'
                  AND index_name = 'idx_culture_version_culture_id');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture_version.idx_culture_version_culture_id 已存在'' AS result',
               'ALTER TABLE `biz_culture_version` ADD INDEX `idx_culture_version_culture_id` (`culture_id`, `id`)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) 自检：表结构 + 索引（执行后核对用）
-- ---------------------------------------------------------------------------
SELECT column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = @db AND table_name = 'biz_culture_version'
ORDER BY ordinal_position;

SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS index_columns
FROM information_schema.statistics
WHERE table_schema = @db AND table_name = 'biz_culture_version'
GROUP BY index_name;
