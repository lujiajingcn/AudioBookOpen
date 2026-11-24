-- ============================================================================
-- shelf_books —— 用户书架表
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到「书架功能」版本前必须先建好这张表，否则应用启动时校验不到
--   实体 ShelfBook 对应的表，直接启动失败。
--   本地 dev 环境 ddl-auto=update 会自动建表，无需执行。
--
--   ⚠️ 请先备份数据库。上线前建议在预发环境先跑一遍完整启动验证。
--
-- 若执行后仍报 schema validation 失败（列类型不匹配），最可靠的取法是在
-- 任意 dev 库上用 ddl-auto=update 让 Hibernate 自己建表，再执行
--   SHOW CREATE TABLE shelf_books;
-- 把结果原样搬到生产库即可 —— 那是 Hibernate 自己认可的定义。
--
-- 建表后无需插入任何数据：书架是纯用户数据，由用户在前端「加入书架」产生。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `shelf_books` (
  `id`            BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT      NOT NULL COMMENT '用户 id',
  `store_book_id` BIGINT      NOT NULL COMMENT '书城书籍 id（store_books.id）；不存书名——书名可重复、可被改名，用 id 判定「同一本书」才稳定',
  `title`         VARCHAR(255) DEFAULT NULL COMMENT '入架时冻结的书名快照；书被物理删除后 store_books 已无此行，列表用它回显书名，避免失效卡片变成无名项',
  `author`        VARCHAR(255) DEFAULT NULL COMMENT '入架时冻结的作者快照，用途同 title',
  `created_at`    DATETIME(6) NOT NULL COMMENT '加入书架时间；书架默认按此列倒序（最近加入在前）',
  PRIMARY KEY (`id`),
  -- 同一用户对同一本书只应有一条记录：并发重复提交靠它兜底
  -- （ShelfService.add 捕获 DataIntegrityViolationException 后回查既有记录，保证幂等）
  UNIQUE KEY `uk_shelf_user_book` (`user_id`, `store_book_id`),
  -- 书架列表走 findByUserIdOrderByCreatedAtDescIdDesc，命中此索引避免全表扫描后排序
  KEY `idx_shelf_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户书架（整本书粒度，按用户同步）';
