-- ============================================================================
-- 09_sensitive_word.sql —— 评论敏感词表（幂等，可重复执行）
--
-- 目的：
--   给「评论提交」链路提供一份可后台维护的敏感词表：
--     action = 1 → 命中后直接拒绝提交（接口返回 400，内容不入库）；
--     action = 2 → 命中后仍然入库，但强制 status = 0（待审核），并给前端温和提示。
--   命中判定在服务端用「清洗后的纯文本 + 大小写不敏感包含匹配」完成
--   （见 com.culture.service.impl.SensitiveWordServiceImpl#matchAction）。
--
-- 只做「新增」：
--   * CREATE TABLE IF NOT EXISTS —— 表已存在时原样保留，不删列、不改列；
--   * 示例词用 INSERT ... SELECT ... WHERE NOT EXISTS 写入，重复执行不会产生重复行。
--
-- 明确不做的事：
--   * 不动 biz_comment / 其它任何既有表；
--   * 不建存储过程、不建触发器、不需要 CREATE ROUTINE 权限（本库 MySQL 5.7.30
--     不支持 CREATE INDEX IF NOT EXISTS，但本文件只需要 CREATE TABLE IF NOT EXISTS）。
--
-- 幂等说明（与 05/06/07 脚本一致）：
--   * 无表可引用的 SELECT 一律写 FROM DUAL —— MySQL 5.7 不允许没有 FROM 的 WHERE；
--   * 存在性判断走 (SELECT * FROM biz_sensitive_word) 派生表快照，
--     避免 MySQL 1093「不能在子查询里引用被写入的表」。
--
-- 用法（由主控执行，本文件不自行执行）：
--   D:/me/SQL/MySQL/MySQL/bin/mysql.exe --no-defaults --default-character-set=utf8mb4 \
--     -uroot -p123456 culture_v2 < docs/sql/09_sensitive_word.sql
--
-- 维护提示：
--   * 示例词只是「中性示例」，可在后台「敏感词管理」里停用/删除，也可直接改这个文件；
--   * action=1 请谨慎使用（会直接挡掉用户提交），建议只放明确违规的词；
--   * word 列 utf8mb4 默认排序规则大小写不敏感，uk_word 唯一键因此天然防「广告/广告」之外的
--     英文大小写重复（如 AD / ad 视为同一个词），与运行期的 lower() 匹配语义一致；
--   * 服务端对启用词表有 60 秒进程内缓存：执行本脚本后，最多 60 秒（或重启后端）
--     新词才会生效；后台增删改会立即清掉本实例缓存。
-- ============================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- 1. 敏感词表
--    uk_word(word)         —— 词不重复（大小写不敏感由 utf8mb4 排序规则保证）
--    idx_sensitive_enabled —— (enabled, deleted) 覆盖「取启用词表」这个唯一的读热点
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS biz_sensitive_word (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    word        VARCHAR(100) NOT NULL COMMENT '敏感词（命中判定为大小写不敏感的包含匹配）',
    action      TINYINT      NOT NULL DEFAULT 2 COMMENT '命中处理：1 直接拒绝 2 转待审核',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    remark      VARCHAR(255) DEFAULT NULL COMMENT '备注（说明词从哪里来、为何加入）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_word (word),
    KEY idx_sensitive_enabled (enabled, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '评论敏感词';

-- ---------------------------------------------------------------------------
-- 2. 示例词（中性示例，action=2 转待审核；可自行增删）
--    想加「直接拒绝」的词，把 action 改成 1 即可，例如：
--      INSERT INTO biz_sensitive_word (word, action, enabled, remark)
--      SELECT '某某词', 1, 1, '明确违规，直接拒绝'
--      FROM DUAL
--      WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM biz_sensitive_word) x WHERE x.word = '某某词');
-- ---------------------------------------------------------------------------

-- 2.1 广告
INSERT INTO biz_sensitive_word (word, action, enabled, remark)
SELECT '广告', 2, 1, '示例词：营销推广类，命中转人工审核'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM biz_sensitive_word) x WHERE x.word = '广告');

-- 2.2 加微信
INSERT INTO biz_sensitive_word (word, action, enabled, remark)
SELECT '加微信', 2, 1, '示例词：站外引流类（联系方式），命中转人工审核'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM biz_sensitive_word) x WHERE x.word = '加微信');

-- 2.3 代刷
INSERT INTO biz_sensitive_word (word, action, enabled, remark)
SELECT '代刷', 2, 1, '示例词：刷量作弊类，命中转人工审核'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM biz_sensitive_word) x WHERE x.word = '代刷');

-- 2.4 无条件送
INSERT INTO biz_sensitive_word (word, action, enabled, remark)
SELECT '免费领取', 2, 1, '示例词：诱导点击类，命中转人工审核'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT * FROM biz_sensitive_word) x WHERE x.word = '免费领取');

-- ---------------------------------------------------------------------------
-- 3. 自检：应能查到 4 条示例词（deleted = 0）
-- ---------------------------------------------------------------------------
SELECT id, word, action, enabled, remark, created_at
FROM biz_sensitive_word
WHERE deleted = 0
ORDER BY id;
