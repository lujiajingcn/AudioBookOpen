-- ============================================================================
-- user_settings / user_book_meta —— 跨端「单值偏好 + 每书元数据」同步（LWW）
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到本版本前必须先建好这两张表，否则应用启动直接失败。
--   本地 dev（ddl-auto=update）自动建表，无需执行。
--   ⚠️ 执行顺序红线：先跑本脚本 → 再传新 jar → 最后重启。重启绝不重写 env。
--   ⚠️ 请先备份数据库。
--
-- 【为什么不用现有的 9 类同步表】
--   现有 9 类（进度/覆盖图/书架/收藏/生词本/任务/讨论区/积分/参考音频）全是**集合型**，
--   合并语义是 add-no-delete 并集 / 按项对账。本两表是**单值覆盖型**：
--   用户在 PC 把倍速从 1.0 改成 1.5，必须能覆盖服务端旧值；套并集会变成
--   「旧值永远赢，改了设置同步不过去」的静默 bug。
--   ⇒ 引入单调递增的版本号 updated_at（BIGINT 毫秒 epoch），按「版本大者胜」覆盖（LWW）。
--
-- 【版本号口径（三端必须一致）】
--   updated_at 不是数据库时间，而是**服务端裁决出的单调版本**：
--     newVersion = max(客户端上报的 updatedAt, 现有 version + 1, 首次插入时的 now)
--   客户端每次只上报「该项在本地最后一次被用户修改的时刻」；
--   从服务端拉回后把返回的 version 存成本地水位（PC: sync_state；Android: 独立 SP）。
--   ⇒ 本地没改 ⇒ 上报的版本 == 服务端版本 ⇒ 服务端不动；
--     本地改过 ⇒ 本地版本更大 ⇒ 覆盖服务端；
--     对端改过 ⇒ 服务端版本更大 ⇒ 本地不改、改为应用远端值。
--   +1 保证同毫秒并发 / 客户端时钟回拨时版本依然严格递增。
--
-- 【为什么不含 category】
--   两端 books/ebooks 表都有 category 列，但**语义不同**：
--     Android = 用户手工分类（BookListFragment 弹框修改）；
--     PC     = 文件扩展名 / 书城固定值（book_service.py category=ext、"store"），
--              且书架同步 _pull_shelf 会用服务端快照覆盖它。
--   同步会把 "pdf"/"store" 写进用户在手机上设的分类 ⇒ 明确排除。
--
-- 【为什么 last_read_at 是 BIGINT】
--   Android ebooks.last_read_at 是 INTEGER 毫秒 epoch；PC books.last_read_at 是 TEXT
--   ISO 串（还用于 ORDER BY）。线上统一毫秒 epoch，PC 在同步层做 ISO ↔ 毫秒转换，
--   本地列格式保持不变（不破坏既有排序与去重）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `user_settings` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT       NOT NULL COMMENT '用户 id',
  `setting_key`   VARCHAR(64)  NOT NULL COMMENT '设置键（服务端白名单校验）',
  `setting_value` VARCHAR(512)          DEFAULT NULL COMMENT '设置值（统一存字符串）',
  `updated_at`    BIGINT       NOT NULL DEFAULT 0 COMMENT 'LWW 单调版本号（毫秒 epoch）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_us_user_key` (`user_id`, `setting_key`),
  KEY `idx_us_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户级跨端单值偏好（LWW）';

CREATE TABLE IF NOT EXISTS `user_book_meta` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`      BIGINT       NOT NULL COMMENT '用户 id',
  `book_key`     VARCHAR(255) NOT NULL COMMENT '跨端书名身份键 s:/t:/n:，口径同 user_book_progress',
  `sort_order`   INT          NOT NULL DEFAULT 0 COMMENT '书架手动排序（越大越靠前）',
  `last_read_at` BIGINT       NOT NULL DEFAULT 0 COMMENT '最近阅读时间（毫秒 epoch）',
  `updated_at`   BIGINT       NOT NULL DEFAULT 0 COMMENT 'LWW 单调版本号（毫秒 epoch）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ubm_user_book` (`user_id`, `book_key`),
  KEY `idx_ubm_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每书跨端元数据（排序/最近阅读，LWW）';

-- 建表后无需插入数据：客户端首次同步时上报本机现值。
