-- ============================================================================
-- post_likes —— 帖子点赞表（2026-11-02，UI 走查 P2-14）
--
-- 何时需要执行本脚本：
--   生产 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表。
--   部署「帖子点赞」版本的 jar **之前**必须先建好这张表，否则应用启动时
--   校验不到实体 PostLike 对应的表，直接启动失败。
--   本地 dev（ddl-auto=update）会自动建表，无需执行。
--
--   ⚠️ 顺序不能反：先建表 → 再换 jar。反了就是一次线上启动失败。
--
-- 为什么单独一张表，而不是复用 post_favorites：
--   收藏是「存起来以后看」（有「我的收藏」列表），点赞只是「表个态」。
--   共表会让「我的收藏」里混进一堆只为点赞的帖子，取消点赞又会连带删掉收藏。
--
-- 为什么不像 posts.favorite_count 那样冗余一个 like_count 列：
--   冗余计数要靠 +1/-1 的原子 UPDATE 维护，一旦并发或回滚不同步就永久偏移。
--   点赞数直接从本表 COUNT 出来，只有一个事实来源；发现页一页最多 200 条，
--   按 post_id IN (...) 批量取一次即可（不是 N+1）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `post_likes` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT      NOT NULL COMMENT '点赞用户 id',
  `post_id`    BIGINT      NOT NULL COMMENT '被点赞的帖子 id（posts.id）；刻意不建外键，与 posts.ref_audio_id、shelf_books.store_book_id 同一风格',
  `created_at` DATETIME(6) DEFAULT NULL COMMENT '点赞时间，由 Hibernate @CreationTimestamp 自动写入',
  PRIMARY KEY (`id`),
  -- 同一用户对同一帖子只应有一条点赞记录：并发重复点赞靠它兜底
  -- （toggle 前先 existsByUserIdAndPostId 判断，唯一约束只做最后一道保险）
  UNIQUE KEY `uk_like_user_post` (`user_id`, `post_id`),
  -- 列表批量取赞时走 findByPostIdIn，命中此索引避免全表扫描
  KEY `idx_like_post` (`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='帖子点赞（用户维度，一用户一帖一条）';
