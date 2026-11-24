-- ============================================================================
-- user_book_progress —— 用户跨端阅读进度表（身份 = book_key，与合成状态无关）
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到「跨端阅读位置同步」版本前必须先建好这张表，否则应用启动校验不到
--   实体 UserBookProgress 对应的表会直接失败。本地 dev（ddl-auto=update）自动建表，无需执行。
--   ⚠️ 请先备份数据库。上线前建议在预发环境跑一遍完整启动验证。
--
-- 若仍报 schema validation 失败，最稳的取法是在 dev 库用 ddl-auto=update 建表后
--   SHOW CREATE TABLE user_book_progress; 原样搬到生产库。
--
-- 建表后无需插入数据：进度由客户端在阅读时上报。
--
-- 【为什么不用 reading_progress 表】
--   老表以 (user_id, task_id) 为键，服务于「私有 TTS 任务」的断点续播，两个硬伤：
--     ① 无音频的书 task_id 为空（store_books.task_id IS NULL）⇒ 进度**根本无法保存**
--        （老书级端点直接 400「该书尚未合成音频，无阅读进度可保存」）。
--        而「纯阅读」是阅读器最基础的场景——用户只是想记住读到哪一行。
--     ② task_id 指向**合成任务**而非书：书城书的音频常由管理员合成 ⇒ 普通用户调私有
--        端点 /api/progress 必然 403，进度永远传不上去；书重新合成、task_id 一换，
--        老进度也随任务孤立。
--   本表以 book_key 为身份，与「有没有合成」「任务归谁」都无关 ⇒ 三端必然算出同一个 key。
--
-- 身份口径 = (user_id, book_key)，book_key 由客户端生成、三端逐字符一致
--   （与服务端 BookKeys / Android CoverageCodec.bookKey / PC coverage.book_key 同一套规则）：
--     s:{storeBookId}  书城条目（与是否合成无关）
--     t:{serverTaskId}  在线合成任务
--     n:{normalizeName(书名)}  本地导入书；n: 尾部归一化规则（BookKeys.normalizeName，
--       三端字节一致）：① 去两端「书键空白」；② Unicode 小写化（Locale.ROOT，
--       与 Python str.lower() 一致）；③ 剥一次已知电子书扩展名（按长度倒序）；
--       ④ 压缩内部连续空白为单个 ASCII 空格，再次去两端空白。
--
-- segment_number 语义（1-based）：
--   有音频的书 = 音频段号；无音频的书 = 正文段落序号（服务端 /content 的 paragraphs 下标 +1）。
--   两者共用一个字段，因为「读到哪了」都是「第几个自然段」，有音频时恰好还等于音频段号。
--   offset_ms = 段内偏移（毫秒）；纯阅读恒为 0。
--
-- 与老表 reading_progress 的关系：两表并存、互不覆盖。老端点写入时额外镜像到本表
--   （让老客户端的进度新客户端也能读到），本表写入不影响老表（老客户端行为完全不变）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `user_book_progress` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`        BIGINT       NOT NULL COMMENT '用户 id',
  `book_key`       VARCHAR(255) NOT NULL COMMENT '跨端书名身份键（含前缀 s:/t:/n:）',
  `segment_number` INT          NOT NULL DEFAULT 0 COMMENT '1-based 位置：音频段号或正文段落序号',
  `offset_ms`      INT          NOT NULL DEFAULT 0 COMMENT '段内偏移（毫秒）；纯阅读恒为 0',
  `updated_at`     DATETIME(6)  DEFAULT NULL COMMENT '最后一次上报时间（时间戳新者胜）',
  PRIMARY KEY (`id`),
  -- 同一用户对同一本书只有一条进度：并发上报靠它兜底
  UNIQUE KEY `uk_ubp_user_book` (`user_id`, `book_key`),
  -- 按 (user_id, book_key) 点查走这条索引
  KEY `idx_ubp_user_book` (`user_id`, `book_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户跨端阅读进度（按 (user_id, book_key) 唯一）';
