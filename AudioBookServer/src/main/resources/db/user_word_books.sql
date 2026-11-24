-- ============================================================================
-- user_word_books —— 用户生词本表（跨端同步真源）
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到「生词本同步」版本前必须先建好这张表，否则应用启动时校验不到
--   实体 UserWordBook 对应的表，直接启动失败。
--   本地 dev 环境 ddl-auto=update 会自动建表，无需执行。
--
--   ⚠️ 执行顺序红线（本次有 schema 变更，不能只热补丁 class）：
--      ① 备份数据库  ② 执行本脚本（CREATE + 幂等 ALTER）
--      ③ 再传新 jar  ④ 最后重启服务 + curl 探活
--   实体 UserWordBook 新增 6 列，线上表若没有，validate 会抛
--   SchemaManagementException，服务起不来。
--
--   ⚠️ 请先备份数据库。上线前建议在预发环境先跑一遍完整启动验证。
--
-- 若执行后仍报 schema validation 失败（列类型不匹配），最可靠的取法是在
-- 任意 dev 库上用 ddl-auto=update 让 Hibernate 自己建表，再执行
--   SHOW CREATE TABLE user_word_books;
-- 把结果原样搬到生产库即可 —— 那是 Hibernate 自己认可的定义。
--
-- 建表后无需插入任何数据：生词本是纯用户数据，由用户在阅读页收藏词条产生。
--
-- 身份口径 = (user_id, sw)，sw 为 stripWord(word) = 去非字母数字 + 小写。
-- 用 sw 而非原形 word 做唯一键：Android 早就是 sw UNIQUE，PC 跨端合并必须对齐；
-- 同词的大小写/屈折变体（Apple/apples）在词典命中里归一，不会撑出多条重复收藏。
--
-- 2026-11-07：新增「收藏出处」6 列（book_name / book_key / store_book_id /
-- segment_number / line_number / sentence_text），供三端单词本「跳转到原书」。
-- 全部可空：老数据与旧客户端没有出处；身份仍按 (user_id, sw) 唯一，
-- 出处取「最近一次带出处的上报」，见 WordBookService.add / WordBookItemRequest.hasSource。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `user_word_books` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT       NOT NULL COMMENT '用户 id',
  `sw`              VARCHAR(128) NOT NULL COMMENT '归一化键 = stripWord(word)；与 user_id 组成唯一身份',
  `word`            VARCHAR(255) NOT NULL COMMENT '用户当时查的词形（保留大小写，跨端仅展示不判定身份）',
  `phonetic`        VARCHAR(255) DEFAULT NULL COMMENT '音标快照；卸载离线词典后仍能读出',
  `translations`    TEXT         DEFAULT NULL COMMENT '义项列表，LF(0x0A)分隔的字符串；快照语义同 phonetic',
  `created_at`      DATETIME(6)  DEFAULT NULL COMMENT '首次收藏时间；updatable=false 只写不改',
  `updated_at`      DATETIME(6)  DEFAULT NULL COMMENT '预留：本期不写入；将来引入"编辑词条/重传刷新"时启用',
  `book_name`       VARCHAR(255) DEFAULT NULL COMMENT '收藏时书名/展示名（Android 用它查书架）',
  `book_key`        VARCHAR(255) DEFAULT NULL COMMENT '跨端统一书键 s:/t:/n:（BookKeys 口径）',
  `store_book_id`   BIGINT       DEFAULT NULL COMMENT '书城书 id；本地书为 NULL',
  `segment_number`  INT          DEFAULT NULL COMMENT '段号（1-based）；Web 定位主判据',
  `line_number`     INT          DEFAULT NULL COMMENT '行号 hint；会随切分粒度漂移，仅作兜底',
  `sentence_text`   TEXT         DEFAULT NULL COMMENT '收藏时所在句子/段落原文；跨端定位第一判据',
  PRIMARY KEY (`id`),
  -- 同一用户对同一个归一化词只应有一条收藏：并发重复提交靠它兜底
  -- （WordBookService.add 捕获 DataIntegrityViolationException 后回查既有记录，保证幂等）
  UNIQUE KEY `uk_word_user_sw` (`user_id`, `sw`),
  -- 生词本列表走 findByUserIdOrderByCreatedAtDescIdDesc，命中此索引避免全表扫描后排序
  KEY `idx_word_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户生词本（跨端同步，按 (user_id, sw) 唯一）';

-- ----------------------------------------------------------------------------
-- 老库升级：幂等加列（MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，
-- 沿用 content_moderation.sql / reference_audio_topics.sql 的 information_schema
-- + PREPARE 守卫写法；可重复执行，列已存在时走 DO 0 空操作）
-- ----------------------------------------------------------------------------

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_word_books'
       AND COLUMN_NAME = 'book_name') = 0,
  'ALTER TABLE user_word_books ADD COLUMN book_name VARCHAR(255) DEFAULT NULL COMMENT ''收藏时书名/展示名'' AFTER updated_at',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_word_books'
       AND COLUMN_NAME = 'book_key') = 0,
  'ALTER TABLE user_word_books ADD COLUMN book_key VARCHAR(255) DEFAULT NULL COMMENT ''跨端统一书键 s:/t:/n:'' AFTER book_name',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_word_books'
       AND COLUMN_NAME = 'store_book_id') = 0,
  'ALTER TABLE user_word_books ADD COLUMN store_book_id BIGINT DEFAULT NULL COMMENT ''书城书 id；本地书为 NULL'' AFTER book_key',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_word_books'
       AND COLUMN_NAME = 'segment_number') = 0,
  'ALTER TABLE user_word_books ADD COLUMN segment_number INT DEFAULT NULL COMMENT ''段号（1-based）'' AFTER store_book_id',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_word_books'
       AND COLUMN_NAME = 'line_number') = 0,
  'ALTER TABLE user_word_books ADD COLUMN line_number INT DEFAULT NULL COMMENT ''行号 hint'' AFTER segment_number',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_word_books'
       AND COLUMN_NAME = 'sentence_text') = 0,
  'ALTER TABLE user_word_books ADD COLUMN sentence_text TEXT DEFAULT NULL COMMENT ''收藏时所在句子原文'' AFTER line_number',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
