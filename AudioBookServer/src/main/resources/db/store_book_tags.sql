-- ============================================================================
-- store_book_tags —— 书城书籍标签表（一本书可有多个标签）
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到「书籍标签」版本前必须先建好这张表，否则应用启动时校验不到
--   实体 StoreBookTag 对应的表，直接启动失败。
--   本地 dev 环境 ddl-auto=update 会自动建表，无需执行。
--
--   ⚠️ 请先备份数据库。
--
-- 与 store_books 的关系：
--   一行 = 一本书的一个标签。没有外键（与 shelf_books.store_book_id、
--   posts.ref_audio_id 同一风格）：删书时由 AdminService.deleteBookInTx
--   在同一短事务里显式 DELETE，不依赖数据库级联。
--
-- 无历史数据需要回填：标签是新增功能，旧书默认无标签，
-- 由管理员在「书城管理 → 编辑」里补，或上传时填。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `store_book_tags` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `book_id`    BIGINT      NOT NULL COMMENT '书城书籍 id（store_books.id）；不存书名——书名可重复、可被改名',
  `tag`        VARCHAR(50) NOT NULL COMMENT '标签名；写入前由 StoreBookTagService 规范化（trim/去重/单标签最长 20 字）',
  `created_at` DATETIME(6) DEFAULT NULL COMMENT '写入时间，仅审计用',
  PRIMARY KEY (`id`),
  -- 同一本书的同一标签只应有一行：
  --   ① 前端重复提交时不产生重复行；
  --   ② utf8mb4_unicode_ci 大小写不敏感，Service 侧也按小写去重，两边口径一致
  UNIQUE KEY `uk_store_book_tag` (`book_id`, `tag`),
  -- 标签栏聚合（GROUP BY tag）与按标签筛选（EXISTS 子查询）都命中此索引
  KEY `idx_store_book_tag_tag` (`tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='书城书籍标签（多对多，一本书多个标签）';
