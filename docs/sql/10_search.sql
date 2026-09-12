-- ============================================================================
-- 10_search.sql —— 搜索增强（ngram 全文索引 + 热门搜索词表；幂等，可重复执行）
--
-- 目标：
--   1) 给 biz_culture(name, description, content) 与 biz_sentence(content) 建
--      FULLTEXT 索引（WITH PARSER ngram），让中文搜索从 `LIKE '%kw%'` 全表扫描
--      改为走倒排索引：应用侧 CultureMapper / SentenceMapper 用
--      `MATCH(...) AGAINST(#{kw} IN BOOLEAN MODE)` 查询，索引不存在或 SQL 报错
--      （ERROR 1191）时自动降级回原来的 LIKE 查询（见 CultureServiceImpl /
--      SentenceServiceImpl 的 querySearchTotal / querySearchData）。
--   2) 新建热门搜索词统计表 search_keyword_stat（/api/search/hot 的数据源）。
--
-- 本脚本只新增索引与一张新表：不改任何业务字段、不改数据、不删任何对象。
--
-- 用法（务必带库名）：
--   mysql --no-defaults --default-character-set=utf8mb4 -uroot -p123456 culture_v2 < docs/sql/10_search.sql
--   或在客户端内执行： source docs/sql/10_search.sql;
--
-- 执行时机：`ALTER TABLE ... ADD FULLTEXT` 会对整表重建全文索引，大表（十万行以上、
--   正文为 longtext）可能耗时数分钟并占用大量 IO / 临时空间，请安排在低峰期执行；
--   当前库 biz_culture 仅数十行，秒级完成。
--   InnoDB 全文索引会额外维护隐藏列 FTS_DOC_ID 与辅助表，属正常现象。
--
-- 幂等实现：MySQL 5.7 既不支持 `CREATE INDEX IF NOT EXISTS`，也不支持
--   `ALTER TABLE ... ADD INDEX IF NOT EXISTS`，因此先查 information_schema.statistics：
--   索引已存在 -> 打印 [skip]；不存在 -> 才 ALTER TABLE。写法与 04_indexes.sql 一致
--   （@exists + PREPARE/EXECUTE），不需要 CREATE ROUTINE 权限，也不建存储过程。
--   新表用 CREATE TABLE IF NOT EXISTS。重复执行本脚本不会报错、不会重复建索引。
--
-- ---------------------------------------------------------------------------
-- 关于 ngram 解析器（本机实测：MySQL 5.7.30，@@ngram_token_size = 2）
-- ---------------------------------------------------------------------------
--   · 默认全文解析器按「空格 / 标点」切词，一整句中文只会切出极少数 token，
--     中文全文检索必须显式 `WITH PARSER ngram`。
--   · ngram_token_size=2 表示把文本按「相邻 2 个字符」切分：
--       "博物馆之夜" → 博物 / 物馆 / 馆之 / 之夜
--     查询词按同一规则切分，于是：
--       1) 关键词必须 >= 2 个字符才可能命中：单个汉字切不出任何 token，
--          BOOLEAN MODE 裸词、BOOLEAN 短语、NATURAL LANGUAGE 三种写法实测命中都是 0 条。
--          => 前端建议最少输入 2 个字；服务端对长度 1 的关键词直接走 LIKE（ngram 查不出来）。
--       2) BOOLEAN MODE 下裸关键词是「token 或」语义，精度会下降：
--          实测关键词 `博物馆`（不带引号）会把 "故宫博物院"（只含 博物）也算命中。
--          因此应用侧统一拼成双引号短语 `"关键词"` 检索——短语要求 token 相邻，
--          等价于子串匹配：实测 `"博物馆"` 命中集合与 `LIKE '%博物馆%'` 完全一致（3/3 相同）。
--       3) 含空格 / 标点的关键词，ngram 与 LIKE 的命中范围可能略有差异：
--          实测 `'"中国 文化"'` 命中 0 条、`LIKE '%中国 文化%'` 也是 0 条，而裸词
--          `中国 文化` 命中 3 条（被拆成两个词做或匹配）。语义上短语写法最接近 LIKE。
--   · ngram_token_size 是只读的启动参数，改动需重启 mysqld 并重建全部 FULLTEXT 索引；
--     本脚本不修改它（2 是默认值，也是中文短词检索的常用配置）。
--
-- ---------------------------------------------------------------------------
-- 为什么应用侧选 BOOLEAN MODE 而不是 NATURAL LANGUAGE MODE
-- ---------------------------------------------------------------------------
--   · BOOLEAN MODE + 引号短语：命中集合确定，与旧 LIKE 的「子串匹配」语义一致，
--     count(*) 与分页数据用同一条件，不会出现「总数与列表对不上」；
--   · NATURAL LANGUAGE MODE：按 token 相关性打分，中文短词召回更宽但精度更差，
--     排序也会与旧接口的「名称命中优先 + 浏览量倒序」不一致（旧接口行为要兼容，前端无感）；
--   · 另外 MyISAM 表在自然语言模式下有 50% 阈值（关键词出现在超过半数的行里就不计分）：
--     本次在 MyISAM 临时表上复现过（5 行中 3 行含 "博物馆" → 自然语言模式返回 0 行）。
--     InnoDB 是否有同样行为本次无法实测（本库还没有 InnoDB 全文索引，且不允许在临时表上建
--     FULLTEXT：ERROR 1796），故不依赖它——BOOLEAN MODE 已能满足「等价于 LIKE 的精确子串」需求，
--     也就没有必要引入自然语言模式这份不确定性。
--     参考：https://dev.mysql.com/doc/refman/5.7/en/fulltext-search.html
--           https://dev.mysql.com/doc/refman/5.7/en/fulltext-natural-language.html
-- ============================================================================

SET NAMES utf8mb4;

-- 未在命令行指定库时按 culture_v2 处理（information_schema 查询都要用 @db）
SET @db := IFNULL(DATABASE(), 'culture_v2');

-- ---------------------------------------------------------------------------
-- 1) biz_culture：FULLTEXT(name, description, content) WITH PARSER ngram
--    对应查询（CultureMapper#querySearchTotalByFullText / #querySearchDataByFullText）：
--      select ... from biz_culture
--       where deleted = 0
--         and MATCH(name, description, content) AGAINST('"关键词"' IN BOOLEAN MODE)
--       order by (name like ? escape '!') desc, view_count desc, id desc
--       limit ?, ?
--    预期收益：把「三列 LIKE '%kw%' 全表扫描 + filesort」变为「倒排索引取候选行 + 对少量候选排序」。
--    索引列顺序必须是 (name, description, content)：MATCH() 的列清单必须与索引列清单完全一致，
--    否则报 ERROR 1191 Can't find FULLTEXT index matching the column list。
--    注意：只查 name 一列的 MATCH(name) AGAINST(...) 用不上这个复合索引（同样报 1191），
--    所以「名称命中优先」的排序仍用 name LIKE 完成，只作用于全文命中后的少量候选行。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_culture'
                  AND index_name = 'ft_culture_search');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_culture.ft_culture_search 已存在'' AS result',
               'ALTER TABLE `biz_culture` ADD FULLTEXT INDEX `ft_culture_search` (`name`, `description`, `content`) WITH PARSER ngram');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2) biz_sentence：FULLTEXT(content) WITH PARSER ngram
--    对应查询（SentenceMapper#querySearchTotalByFullText / #querySearchDataByFullText）：
--      select ... from biz_sentence
--       where deleted = 0
--         and (MATCH(content) AGAINST('"关键词"' IN BOOLEAN MODE)
--              or create_name like ? escape '!')       -- 作者名沿用 LIKE，保持旧语义
--       order by (content like ? escape '!') desc, created_at desc, id desc
--       limit ?, ?
--    说明：旧 LIKE 查询是 (content like ? or create_name like ?)，作者名（create_name）不在
--    全文索引内，所以全文条件仍要 or 上 create_name 的 LIKE，否则「搜作者名」会退化成 0 条。
--    已知代价：这条 OR 会让优化器放弃 FULLTEXT 索引——本次在 MyISAM 临时表上 EXPLAIN 实测
--    `type=ALL`（全表扫描）；InnoDB 是否也是同样计划未实测。考虑到句子表当前只有个位数行、
--    且「能按作者名搜到」比「省一点扫描」重要，这里选择保留 OR（正确性优先）。
--    若将来句子量级变大且确认不需要按作者名搜索，可去掉 OR 让 MATCH 单独生效。
-- ---------------------------------------------------------------------------
SET @exists := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = @db AND table_name = 'biz_sentence'
                  AND index_name = 'ft_sentence_search');
SET @ddl := IF(@exists > 0,
               'SELECT ''[skip] biz_sentence.ft_sentence_search 已存在'' AS result',
               'ALTER TABLE `biz_sentence` ADD FULLTEXT INDEX `ft_sentence_search` (`content`) WITH PARSER ngram');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3) search_keyword_stat：热门搜索词统计表（GET /api/search/hot 的数据源）
--    写入（SearchStatMapper#upsertKeyword，由 SearchStatServiceImpl 在每次搜索后调用）：
--      insert into search_keyword_stat(keyword, search_count, last_search_at, created_at)
--      values(#{keyword}, 1, now(), now())
--      on duplicate key update search_count = search_count + 1, last_search_at = now()
--    读取（SearchStatMapper#findHotKeywords）：
--      select keyword, search_count as searchCount from search_keyword_stat
--       order by search_count desc, last_search_at desc limit ?
--    说明：
--      · keyword 上的唯一键 uk_keyword 是 ON DUPLICATE KEY UPDATE 生效的前提，
--        keyword 由服务端 SearchUtil.normalizeKeyword 统一 trim + 截断到 50 字后再写入，
--        不会超过 VARCHAR(100)；
--      · 这张表只做「计数 + 取前 N」，长期只留下有限个热词（长尾词也远小于内容表），
--        因此不额外建 (search_count, last_search_at) 排序索引，filesort 成本可忽略；
--        若将来统计词量级到十万以上，再补 `KEY idx_count_last(search_count, last_search_at)`。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `search_keyword_stat` (
  `id`             bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `keyword`        varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '搜索关键词（trim + 截断 50 字后写入）',
  `search_count`   int(11) NOT NULL DEFAULT '0' COMMENT '累计搜索次数',
  `last_search_at` datetime DEFAULT NULL COMMENT '最近一次搜索时间',
  `created_at`     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次记录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_keyword` (`keyword`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热门搜索词统计';

-- ============================================================================
-- 验证语句（只读，可单独执行；脚本执行完请务必跑一遍）
-- ============================================================================

-- 1) 索引是否建好：应分别看到 ft_culture_search / ft_sentence_search，Index_type=FULLTEXT
SHOW INDEX FROM `biz_culture` WHERE Index_type = 'FULLTEXT';
SHOW INDEX FROM `biz_sentence` WHERE Index_type = 'FULLTEXT';

-- 2) 统计口径核对（应为 2；重复执行脚本该值不变）
SELECT COUNT(DISTINCT index_name) AS fulltext_index_count
FROM information_schema.statistics
WHERE table_schema = @db AND index_type = 'FULLTEXT'
  AND table_name IN ('biz_culture', 'biz_sentence');

-- 3) 分词长度确认（本机应为 2）
SHOW VARIABLES LIKE 'ngram_token_size';

-- 4) 新表结构确认
SHOW CREATE TABLE `search_keyword_stat`;

-- 5) 命中效果自测：把关键词换成真实存在的词（注意：必须带引号，且至少 2 个字）
--    select id, name
--      from biz_culture
--     where deleted = 0
--       and MATCH(name, description, content) AGAINST('"博物馆"' IN BOOLEAN MODE)
--     limit 10;
--
--    与旧 LIKE 结果对比（两者应一致）：
--    select id, name
--      from biz_culture
--     where deleted = 0
--       and (name like '%博物馆%' or description like '%博物馆%' or content like '%博物馆%')
--     limit 10;

-- 6) 执行计划确认：type 应为 fulltext、key 为 ft_culture_search（而不是 ALL 全表扫描）
--    EXPLAIN select id
--      from biz_culture
--     where deleted = 0
--       and MATCH(name, description, content) AGAINST('"博物馆"' IN BOOLEAN MODE);
--
--    索引尚未建立时同一条 SQL 会报：
--      ERROR 1191 (HY000): Can't find FULLTEXT index matching the column list
--    应用侧捕获该异常后会自动降级为 LIKE，搜索接口不会 500。
