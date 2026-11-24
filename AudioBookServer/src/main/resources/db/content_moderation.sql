-- ============================================================================
-- 内容审核：帖子逻辑下架 + 参考音频管理员下架
--
-- 背景（2026-10-13）：
--   AudioBookManager 的「发现 / 内容审核」模块此前只能只读查看，页面里明确写着
--   「涉及删除 / 下架需在后端 PostController 增加管理员端点后再接入」。
--   本脚本补上管理员处置能力所需的两个字段。
--
-- ⚠️ 执行顺序（红线①）：prod 是 ddl-auto: validate，**必须先跑本脚本再传新 jar**，
--    否则 Hibernate 校验找不到列会直接启动失败。
--
-- 幂等：下面两条 ALTER 都带「列不存在才加」的守卫，可重复执行。
--   注意 MySQL 的 ADD COLUMN IF NOT EXISTS 在 5.7 不支持，这里用 information_schema
--   + PREPARE 的通用写法（8.0 亦可）。
-- ============================================================================

-- ---- 1. posts：逻辑下架 ----
-- status 取值：active（正常，展示页可见）/ removed（管理员下架，数据保留可恢复）
-- 不用 boolean 而用 varchar：后续若要加 pending（待审）之类状态无需再改列类型。
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND COLUMN_NAME = 'status') = 0,
  'ALTER TABLE posts ADD COLUMN status varchar(20) NOT NULL DEFAULT ''active''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 冗余存下架时刻，便于后台排序「最近下架」与排查；未下架为 NULL
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND COLUMN_NAME = 'removed_at') = 0,
  'ALTER TABLE posts ADD COLUMN removed_at datetime(6) NULL',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 列表过滤（后台要按 status 筛，前台只看 active）走这条索引，避免全表扫
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND INDEX_NAME = 'idx_post_status_created') = 0,
  'ALTER TABLE posts ADD INDEX idx_post_status_created (status, created_at)',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---- 2. user_reference_audio：管理员下架（与用户「取消分享」是两个独立概念）----
-- shared    = 用户自己点了「分享 / 取消分享」
-- admin_hidden = 管理员判定内容违规而强制从分享列表隐藏
-- 两者正交：用户取消分享后又重新分享，若曾被管理员下架仍应保持隐藏。
SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_reference_audio'
       AND COLUMN_NAME = 'admin_hidden') = 0,
  'ALTER TABLE user_reference_audio ADD COLUMN admin_hidden bit(1) NOT NULL DEFAULT b''0''',
  'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
