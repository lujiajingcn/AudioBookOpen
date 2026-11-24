-- ============================================================================
-- user_audio_coverage —— 用户「有声覆盖图」表（Phase3 跨端同步，仅元数据/不含音频字节）
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到「有声覆盖图同步」版本前必须先建好这张表，否则应用启动校验不到
--   实体 UserAudioCoverage 对应的表会直接失败。本地 dev（ddl-auto=update）自动建表，无需执行。
--   ⚠️ 请先备份数据库。上线前建议在预发环境跑一遍完整启动验证。
--
-- 若仍报 schema validation 失败，最稳的取法是在 dev 库用 ddl-auto=update 建表后
--   SHOW CREATE TABLE user_audio_coverage; 原样搬到生产库。
--
-- 建表后无需插入数据：覆盖图由客户端在同步时上报（本机/服务端已合成哪些段）。
--
-- 身份口径 = (user_id, book_key, source)。
--   book_key 由客户端生成、三端逐字符一致：s:{storeBookId} / t:{serverTaskId} / n:{normalizeName(书名)}。
--   n: 尾部归一化规则（BookKeys.normalizeName，三端字节一致）：
--     ① 去两端"书键空白"（ASCII ws + NBSP/OGHAM/排版空格/LINE SEP/PARA SEP/NNBSP/MMSP/全角空格）；
--     ② Unicode 小写化（Locale.ROOT，与 Python str.lower() 一致）；
--     ③ 剥一次已知电子书扩展名（.azw3/.docx/.djvu/.epub/.mobi/.pdf/.azw/.doc/.fb2/.txt，按长度倒序）；
--     ④ 压缩内部连续空白为单个 ASCII 空格（0x20），再次去两端空白。
--   source ∈ {server, local}：server=服务端可下（跨端真可播）；local=仅某端本地合成（只提示/续合）。
--   done_ranges=1-based 闭区间压缩串（"1-40,41,45-50"）；合并语义=同 (book_key,source) 跨设备并集、只增不减。
--
-- 老数据迁移：Phase3 首版 n: 只做 trim(displayName)，保留了扩展名/大小写/空白差异。
--   升级到本版后，应用启动时由 AudioCoverageBookKeyMigrationRunner 自动扫描 book_key LIKE 'n:%'
--   的行，按 BookKeys.normalize 重算键并合并同 (user_id, source, 归一后 book_key) 的重复行
--   （doneRanges 取并集、totalSegments 取最大、storeBookId/serverTaskId 取首个非空）。
--   迁移幂等，失败不阻塞启动；可通过 audiobook.coverage-book-key-migration.enabled=false 关闭。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `user_audio_coverage` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT       NOT NULL COMMENT '用户 id',
  `book_key`       VARCHAR(255) NOT NULL COMMENT '跨端书名身份键（含前缀 s:/t:/n:）',
  `source`         VARCHAR(16)  NOT NULL COMMENT '来源：server（服务端可下）/ local（仅本地合成）',
  `store_book_id`  BIGINT       DEFAULT NULL COMMENT '映射回书城条目（可空）',
  `server_task_id` BIGINT       DEFAULT NULL COMMENT '映射回在线合成任务（可空）',
  `total_segments` INT          DEFAULT 0    COMMENT '该书总段数（各端上报最大值，仅展示用）',
  `done_ranges`    TEXT         DEFAULT NULL COMMENT '已完成段覆盖串（1-based 闭区间，逗号分隔，规范升序）',
  `done_count`     INT          DEFAULT 0    COMMENT 'done_ranges 段数冗余，便于排序/展示',
  `created_at`     DATETIME(6)  DEFAULT NULL COMMENT '首次覆盖时间；updatable=false 只写不改',
  `updated_at`     DATETIME(6)  DEFAULT NULL COMMENT '每次并集合并刷新；列表按其倒序',
  PRIMARY KEY (`id`),
  -- 同一用户对同一本书同一来源只有一条覆盖记录：并发上报靠它兜底
  -- （AudioCoverageService.sync 捕获 DataIntegrityViolationException 后回查重做并集）
  UNIQUE KEY `uk_cov_user_book_src` (`user_id`, `book_key`, `source`),
  -- 覆盖图列表走 findByUserIdOrderByUpdatedAtDescIdDesc，命中此索引避免全表扫描后排序
  KEY `idx_cov_user_updated` (`user_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户有声覆盖图（跨端同步元数据，按 (user_id, book_key, source) 唯一）';
