-- ============================================================================
-- 参考音频主题（管理端「参考音频管理 → 主题」）
--
-- 背景（2026-10-18）：
--   参考音频此前是一条平铺的列表，只能靠名称硬找。实际使用中素材天然成组，
--   比如「电视剧雍正王朝」下是一份该剧各个角色的台词片段，
--   平铺列表既看不出分组、也容易重复上传同一个角色。
--
-- ⚠️ 执行顺序（红线①）：prod 是 ddl-auto: validate，**必须先跑本脚本再传新 jar**，
--    否则 Hibernate 校验不到 reference_audio_topic 表 / reference_audio.topic_id
--    列会直接启动失败。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 下面两条带「不存在才加」的 ALTER，
--   可重复执行。MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，
--   这里用 information_schema + PREPARE 的通用写法（8.0 亦可）。
--
-- ⚠️ 请先备份数据库。上线前建议在预发环境先跑一遍完整启动验证。
-- ============================================================================

-- ---- 1. 主题表 ----
-- name 唯一：管理端靠主题名认分组，重名会让「归属」失去意义
-- sort_order 小的排前面；列表按 (sort_order, id) 排序，保证同序时顺序稳定
CREATE TABLE IF NOT EXISTS reference_audio_topic (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ref_topic_name (name),
    INDEX idx_ref_topic_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---- 2. reference_audio 增加归属列 ----
-- 可空且不建外键：NULL = 未归类，老数据无需回填即可继续用
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reference_audio'
       AND COLUMN_NAME = 'topic_id') = 0,
  'ALTER TABLE reference_audio ADD COLUMN topic_id BIGINT DEFAULT NULL AFTER file_size',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---- 3. 按主题筛音频的索引 ----
-- 管理端主题视图要按 topic_id 取该主题下的音频，缺此索引会退化成全表扫
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reference_audio'
       AND INDEX_NAME = 'idx_ref_audio_topic') = 0,
  'ALTER TABLE reference_audio ADD INDEX idx_ref_audio_topic (topic_id, created_at)',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---- 4. 校验（执行完手动看一眼，应有 topic 表 + topic_id 列）----
-- SHOW COLUMNS FROM reference_audio LIKE 'topic_id';
-- SHOW INDEX FROM reference_audio_topic;
