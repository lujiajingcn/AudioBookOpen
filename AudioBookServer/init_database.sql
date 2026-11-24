-- 初始化数据库脚本：创建 audiobook_db 及其全部表，表结构与 JPA 实体一一对应。
-- 注意：生产环境 spring.jpa.hibernate.ddl-auto=validate，本脚本的表结构必须与
-- 实体（model/*.java）保持一致，否则启动时会因验证失败而报错。
CREATE DATABASE IF NOT EXISTS audiobook_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE audiobook_db;

-- 用户表（对应 model/User.java）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100),
    is_admin TINYINT(1) NOT NULL DEFAULT 0,
    banned_until DATETIME DEFAULT NULL,
    -- 积分余额。NOT NULL DEFAULT 0，避免历史用户读出 null 参与算术运算抛 NPE
    credits INT NOT NULL DEFAULT 0,
    -- Token 版本号（D4 账户安全）：改密/重置密码时递增，使旧 JWT 立即失效
    token_version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TTS 任务表（对应 model/Task.java）
CREATE TABLE IF NOT EXISTS tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    book_file_path VARCHAR(500) NOT NULL,
    original_book_name VARCHAR(500) DEFAULT NULL,
    -- #20 起可为 NULL：仅指定预设音色（voice_id）、不上传参考音频时无需参考音频
    reference_audio_path VARCHAR(500) DEFAULT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    total_segments INT DEFAULT 0,
    completed_segments INT DEFAULT 0,
    -- 本任务实际扣除的积分快照。退款以此为准，避免后台改规则后退错金额
    credits_cost INT NOT NULL DEFAULT 0,
    -- 已退还积分，退款幂等标记（>0 表示已退过，重复收敛不再退）
    credits_refunded INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) DEFAULT NULL,
    -- #20：任务级默认音色（预设 voiceId），为空表示整本沿用克隆参考音频
    default_voice_id VARCHAR(100) DEFAULT NULL,
    -- #20：段落级音色映射（JSON：{段落号: voiceId}），为空表示全部段落沿用 defaultVoiceId
    segment_voices TEXT,
    -- 书城书籍 id（v5/W1）：本任务是为哪本 store_books 合成的；用户自行上传的书为 NULL。
    -- 异步提取正文时据此落「正文快照」，让阅读页正文与音频同源。
    store_book_id BIGINT DEFAULT NULL,
    -- 按书反查任务（后台排障用）
    INDEX idx_task_store_book (store_book_id),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_task_user_created (user_id, created_at),
    INDEX idx_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 音频片段表（对应 model/AudioSegment.java）
CREATE TABLE IF NOT EXISTS audio_segments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    segment_number INT NOT NULL,
    text_content TEXT NOT NULL,
    audio_data LONGBLOB,
    audio_file_path VARCHAR(500),
    duration_seconds FLOAT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_message VARCHAR(500) DEFAULT NULL,
    -- #20：本段绑定的预设音色（voiceId）。为空表示回退到任务级 defaultVoiceId / 克隆参考音频
    voice VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    -- 唯一约束（原先只是普通索引）：仓储层全程按「(task_id, segment_number) 至多一行」的
    -- 语义使用本表 —— findByTaskIdAndSegmentNumber 返回 Optional、updateVoiceAndReset 按该
    -- 二元组 UPDATE、ZipAudioPacker 用段号作 ZIP 条目名。出现重复行会导致
    -- IncorrectResultSizeDataAccessException / 多段被一起重置为 pending 触发重复合成 /
    -- ZIP duplicate entry 让整本下载失败。老库升级需先去重再建（见文件末尾迁移区）。
    UNIQUE KEY uk_segment_task_number (task_id, segment_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 书城书籍表（对应 model/StoreBook.java）
CREATE TABLE IF NOT EXISTS store_books (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    -- 原书名（选填）：上架命名前的原始名称，仅存档展示，NULL 表示未填写
    original_title VARCHAR(200) DEFAULT NULL,
    author VARCHAR(100) DEFAULT NULL,
    category VARCHAR(50) DEFAULT NULL,
    description TEXT,
    file_name VARCHAR(500) NOT NULL,
    file_extension VARCHAR(10) DEFAULT NULL,
    file_size BIGINT DEFAULT NULL,
    cover_color VARCHAR(10) DEFAULT NULL,
    cover_image VARCHAR(500) DEFAULT NULL,
    download_count INT DEFAULT 0,
    -- #11：编辑推荐（书城首页/推荐 Tab 展示）
    editor_pick TINYINT(1) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'onshelf',
    -- 书城「合成语音」：关联的 TTS 任务 id，NULL 表示尚未合成音频。
    -- 产物归属为「这本书」（所有登录用户可下载），不是发起合成的管理员；
    -- 与 tasks.user_id 是两回事，故此处不建外键（任务删除时保留历史书侧记录）。
    task_id BIGINT DEFAULT NULL,
    -- 最新一次合成落库时间 / 触发该任务的管理员用户名（均为审计/展示用，
    -- 不参与「是否有音频」判定——客户端一律看 task_id 是否非空）
    synthesized_at DATETIME DEFAULT NULL,
    synthesized_by VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_store_book_task_id (task_id),
    -- 书城首页「已上架 + 按下载量倒序」分页：缺此索引会退化成全表扫 + filesort
    INDEX idx_store_book_onshelf_rank (status, download_count, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 书城书籍标签表（对应 model/StoreBookTag.java）
-- 一行 = 一本书的一个标签；一本书可有多个标签（多对多）。
-- 不用「store_books 上加一个逗号分隔字段」：按标签筛选会退化成 LIKE 全表扫，
-- 标签栏的「每个标签下有几本书」也无法在 SQL 里聚合。
-- 不建外键（与 shelf_books.store_book_id 同一风格）：删书时由
-- AdminService.deleteBookInTx 在同一短事务里显式清理标签行。
CREATE TABLE IF NOT EXISTS store_book_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NOT NULL,
    -- 标签名：写入前由 StoreBookTagService 规范化（trim / 去重 / 单标签最长 20 字）
    tag VARCHAR(50) NOT NULL,
    created_at DATETIME(6) DEFAULT NULL,
    -- 同一本书的同一标签只应有一行（utf8mb4_unicode_ci 大小写不敏感，与 Service 侧去重口径一致）
    UNIQUE KEY uk_store_book_tag (book_id, tag),
    -- 标签栏聚合与按标签筛选都命中此索引
    INDEX idx_store_book_tag_tag (tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 参考音频主题表（对应 model/ReferenceAudioTopic.java）
-- 把一批同源的参考音频归到一起管理，典型场景是「电视剧雍正王朝」这类主题，
-- 主题下挂着各个角色的台词片段（同一剧集里的不同音色）。
-- 刻意不建外键（与 shelf_books.store_book_id、posts.ref_audio_id 同一风格）：
-- 主题只是可空的分类标签，删主题/删音频的先后纠缠交给 Service 层判定更清晰。
CREATE TABLE IF NOT EXISTS reference_audio_topic (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 主题名全局唯一：管理端按名字认主题，重名会让「归属」变得没有意义
    UNIQUE KEY uk_ref_topic_name (name),
    INDEX idx_ref_topic_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 参考音频表（对应 model/ReferenceAudio.java）
CREATE TABLE IF NOT EXISTS reference_audio (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    file_name VARCHAR(500) NOT NULL,
    file_size BIGINT DEFAULT NULL,
    -- 所属主题（reference_audio_topic.id），NULL = 未归类。
    -- 存主键而非主题名快照：主题允许改名，用 id 才能保证归属判定稳定。
    topic_id BIGINT DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 管理端「按主题筛音频」走这条索引；最左列缺失时会退化成全表扫
    INDEX idx_ref_audio_topic (topic_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 用户级参考音频表（对应 model/UserReferenceAudio.java）
CREATE TABLE IF NOT EXISTS user_reference_audio (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    description TEXT,
    file_size BIGINT DEFAULT NULL,
    shared TINYINT(1) NOT NULL DEFAULT 0,
    use_count BIGINT NOT NULL DEFAULT 0,
    shared_at DATETIME DEFAULT NULL,
    -- 管理员下架标记（v5/S2）：与用户自己的 shared 正交。bit(1) 与实体一致；
    -- 直接 WHERE admin_hidden = 1 不会命中，SQL 侧要写 admin_hidden + 0（见实体注释）
    admin_hidden BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_ref_audio_user (user_id, created_at),
    INDEX idx_user_ref_audio_rank (shared, use_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 临时参考音频归属表（对应 model/TempReferenceAudio.java）
-- /api/tts/upload-reference 仅落盘、不入「我的库」，此表登记「文件名 → 归属用户」，
-- 供 /upload-book 引用校验（resolveReferenceFileName 的第三种来源）防越权引用。
CREATE TABLE IF NOT EXISTS temp_reference_audio (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_temp_ref_audio_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 积分规则表（对应 model/CreditRule.java）
-- 全局单行配置，固定 id=1，由 AudioBookManager 后台维护，是计费规则的唯一事实来源。
-- 放数据库而非 application.yml，是为了改规则不用重启服务。
CREATE TABLE IF NOT EXISTS credit_rule (
    id BIGINT PRIMARY KEY,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    credits_per_task INT NOT NULL DEFAULT 10,
    free_credits_on_register INT NOT NULL DEFAULT 50,
    -- #14：每日免费合成次数（按日重置，额度内免扣积分）
    daily_free_tasks INT NOT NULL DEFAULT 2,
    refund_on_task_failure TINYINT(1) NOT NULL DEFAULT 1,
    admin_exempt TINYINT(1) NOT NULL DEFAULT 1,
    description VARCHAR(500) DEFAULT NULL,
    updated_by VARCHAR(50) DEFAULT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初始化默认规则行（存在则不覆盖，避免重复执行脚本把后台改过的配置刷回默认值）
INSERT INTO credit_rule (id, enabled, credits_per_task, free_credits_on_register,
                         refund_on_task_failure, admin_exempt, description)
SELECT 1, 1, 10, 50, 1, 1, '默认规则：每个合成任务扣 10 积分，注册赠送 50 积分，任务失败全额退还'
WHERE NOT EXISTS (SELECT 1 FROM credit_rule WHERE id = 1);

-- 充值套餐表（对应 model/CreditPackage.java）
CREATE TABLE IF NOT EXISTS credit_packages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    credits INT NOT NULL,
    bonus_credits INT NOT NULL DEFAULT 0,
    price DECIMAL(10,2) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 内置几个示例套餐，便于开箱即用（后台可改可删）
INSERT INTO credit_packages (name, credits, bonus_credits, price, description, enabled, sort_order)
SELECT '体验包', 100, 0, 9.90, '100 积分，适合尝鲜', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM credit_packages WHERE name = '体验包');
INSERT INTO credit_packages (name, credits, bonus_credits, price, description, enabled, sort_order)
SELECT '标准包', 500, 50, 45.00, '500 积分 + 赠 50', 1, 2
WHERE NOT EXISTS (SELECT 1 FROM credit_packages WHERE name = '标准包');
INSERT INTO credit_packages (name, credits, bonus_credits, price, description, enabled, sort_order)
SELECT '超值包', 1000, 200, 88.00, '1000 积分 + 赠 200', 1, 3
WHERE NOT EXISTS (SELECT 1 FROM credit_packages WHERE name = '超值包');

-- 积分流水表（对应 model/CreditTransaction.java）
-- 只追加不修改：每一次余额变动都留痕，用于对账与幂等兜底查重。
CREATE TABLE IF NOT EXISTS credit_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    username VARCHAR(50) DEFAULT NULL,
    -- RECHARGE / CONSUME / REFUND / ADMIN_ADJUST / REGISTER_BONUS
    type VARCHAR(20) NOT NULL,
    -- 积分变动值，正为增加、负为扣减
    credits INT NOT NULL,
    -- 变动后余额快照
    balance_after INT NOT NULL,
    task_id BIGINT DEFAULT NULL,
    order_no VARCHAR(64) DEFAULT NULL,
    remark VARCHAR(255) DEFAULT NULL,
    operator VARCHAR(50) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_credit_tx_user_created (user_id, created_at),
    INDEX idx_credit_tx_type (type),
    INDEX idx_credit_tx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 充值订单表（对应 model/CreditOrder.java）
-- 下单时对套餐做快照（名称/积分/价格），套餐后续被改动不影响历史订单。
CREATE TABLE IF NOT EXISTS credit_orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    username VARCHAR(50) DEFAULT NULL,
    package_id BIGINT DEFAULT NULL,
    package_name VARCHAR(100) DEFAULT NULL,
    credits INT NOT NULL,
    bonus_credits INT NOT NULL DEFAULT 0,
    amount DECIMAL(10,2) NOT NULL,
    -- PENDING / PAID / CLOSED
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    pay_channel VARCHAR(20) DEFAULT NULL,
    pay_trade_no VARCHAR(128) DEFAULT NULL,
    paid_at DATETIME DEFAULT NULL,
    expire_at DATETIME DEFAULT NULL,
    -- #12：订单关闭/失败原因（用户取消、超时、支付渠道下单失败等），前端据此给出明确提示
    fail_reason VARCHAR(255) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 乐观锁版本号（H6 修复，对应 CreditOrder.@Version）
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_credit_order_user_created (user_id, created_at),
    INDEX idx_credit_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 发现页帖子表（对应 model/Post.java）
-- 只支持纯文字。username 冗余存储：列表要按时间顺序列出大量帖子，
-- 每次 join users 取用户名会让查询随帖子数线性变重。
CREATE TABLE IF NOT EXISTS posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    -- #10：帖子标题（可选，随手记可留空）
    title VARCHAR(200) DEFAULT NULL,
    content VARCHAR(500) NOT NULL,
    -- #10：图片 URL 列表（JSON 字符串）
    image_urls TEXT,
    -- #10：嵌入的分享参考音频 id（user_reference_audio.id）
    ref_audio_id BIGINT DEFAULT NULL,
    -- #10：@提醒的用户名列表（JSON 字符串）
    mentions TEXT,
    -- 冗余计数：评论新增/删除、收藏/取消收藏时同步维护
    comment_count INT DEFAULT 0,
    favorite_count INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    -- 内容审核状态（v5/S2）：active / removed；用 varchar 便于后续加 pending 等状态
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    -- 管理员下架时刻；未下架为 NULL（与 status 同一条 UPDATE 改，不会出现自相矛盾的行）
    removed_at DATETIME(6) DEFAULT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_post_created (created_at),
    -- 后台按「状态 + 时间」翻已下架列表：缺此索引会全表扫
    INDEX idx_post_status_created (status, created_at),
    -- 帖子列表按「引用了哪条参考音频」过滤聚合：缺此索引会全表扫
    INDEX idx_post_ref_audio (ref_audio_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 帖子评论表（对应 model/Comment.java，#10）：支持嵌套回复（parent_id 指向父评论）
CREATE TABLE IF NOT EXISTS comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    username VARCHAR(100) NOT NULL,
    content VARCHAR(500) NOT NULL,
    parent_id BIGINT DEFAULT NULL,
    parent_username VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    INDEX idx_comment_post (post_id),
    INDEX idx_comment_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 帖子收藏表（对应 model/PostFavorite.java，#10）：(user_id, post_id) 唯一
CREATE TABLE IF NOT EXISTS post_favorites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_fav_user_post (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 帖子点赞表（对应 model/PostLike.java，2026-11-02）：(user_id, post_id) 唯一。
-- 与 post_favorites 分开：收藏有「我的收藏」列表，点赞只是表态，共表会互相污染。
-- 点赞数不冗余成列，直接 COUNT 本表（见 PostLike 类注释）。
CREATE TABLE IF NOT EXISTS post_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_like_user_post (user_id, post_id),
    KEY idx_like_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 举报表（对应 model/Report.java，#10）：target_type = POST / COMMENT
CREATE TABLE IF NOT EXISTS reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id BIGINT NOT NULL,
    reporter_name VARCHAR(100) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_report_target (target_type, target_id),
    INDEX idx_report_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 阅读进度表（对应 model/ReadingProgress.java，#5）：跨端播放进度同步，(user_id, task_id) 唯一
CREATE TABLE IF NOT EXISTS reading_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    -- 当前播放到的段落号（1-based，与 Android 端 seg_ 进度键一致）
    segment_number INT NOT NULL DEFAULT 1,
    -- 段内播放偏移（毫秒）
    offset_ms INT NOT NULL DEFAULT 0,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
    UNIQUE KEY idx_rp_user_task (user_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 跨端阅读进度表（对应 model/UserBookProgress.java）：身份 = (user_id, book_key)
-- 与上面的 reading_progress 并存、互不覆盖：
--   reading_progress     = 「私有 TTS 任务」口径（任务维度，要求任务存在且归属本人）
--   user_book_progress   = 「书」口径（与有没有合成、任务归谁都无关）
-- 后者才能覆盖「无音频的书」——老口径下 task_id 为空 ⇒ 进度根本存不下来。
-- book_key 三种前缀 s:/t:/n: 与有声覆盖图（user_audio_coverage）同一套口径。
CREATE TABLE IF NOT EXISTS user_book_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    -- 跨端书名身份键（含前缀 s:/t:/n:），归一化规则见 BookKeys.normalizeName
    book_key VARCHAR(255) NOT NULL,
    -- 1-based 位置：有音频 = 音频段号；无音频 = 正文段落序号
    segment_number INT NOT NULL DEFAULT 0,
    -- 段内偏移（毫秒）；纯阅读恒为 0
    offset_ms INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) DEFAULT NULL,
    UNIQUE KEY uk_ubp_user_book (user_id, book_key),
    KEY idx_ubp_user_book (user_id, book_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 客户端发布版本表（对应 model/AppVersion.java，供 Android 端检查更新/下载 APK）
-- 同一平台 version_code 唯一；「当前对外版本」= released=1 中 version_code 最大的一条
CREATE TABLE IF NOT EXISTS app_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    platform VARCHAR(32) NOT NULL DEFAULT 'android',
    version_code INT NOT NULL,
    version_name VARCHAR(64) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    md5 VARCHAR(64) DEFAULT NULL,
    changelog TEXT,
    force_update TINYINT(1) NOT NULL DEFAULT 0,
    released TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT NULL,
    released_at DATETIME DEFAULT NULL,
    UNIQUE KEY uk_app_version_platform_code (platform, version_code),
    INDEX idx_app_version_lookup (platform, released, version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 听书收藏表（对应 model/TextFavorite.java，收藏上云/跨端同步）：
-- 唯一身份 = (user_id, book_name, segment_number)。task_id 仅为「去听」深链辅助字段，
-- 不参与去重（Web 端带 taskId、Android 端不带，纳入键会导致两端各存一条、跨端去不掉重）。
CREATE TABLE IF NOT EXISTS text_favorites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    book_name VARCHAR(255) NOT NULL,
    task_id BIGINT DEFAULT NULL,
    segment_number INT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_text_fav_user_book_seg (user_id, book_name, segment_number),
    INDEX idx_text_fav_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- TTS 合成节点配置表（对应 model/TtsNode.java）
-- 原先只存在于 src/main/resources/db/tts_nodes.sql，本脚本漏掉 → 只按本脚本建库的新环境
-- 在 prod（ddl-auto=validate）下校验不到实体对应的表，服务直接启动失败。
-- 建表后无需插数据：应用首次启动发现表为空时，会用 moss.tts.url 落一条默认节点记录。
CREATE TABLE IF NOT EXISTS tts_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '展示名',
    url VARCHAR(255) NOT NULL COMMENT '完整合成端点，如 http://10.0.0.11:18083/api/generate',
    weight INT NOT NULL DEFAULT 1 COMMENT '权重；最少在途选点中仅在途数相同时作为次级排序',
    enabled BIT(1) NOT NULL DEFAULT b'1' COMMENT '是否参与调度；停用后立即停止分配新请求但不打断在途调用',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '展示与选点排序序号',
    remark VARCHAR(255) DEFAULT NULL COMMENT '备注（机房、GPU 型号等）',
    created_at DATETIME(6) NOT NULL COMMENT '创建时间',
    updated_at DATETIME(6) DEFAULT NULL COMMENT '最后修改时间',
    -- 同一台机器登记两次会被调度器当成两个节点，实际负载翻倍，必须防重
    UNIQUE KEY uk_tts_node_url (url),
    -- 注册中心每次刷新走 enabled=? ORDER BY sort_order, id，走这条索引避免全表扫描
    INDEX idx_tts_node_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MOSS-TTS-Nano 合成节点配置';

-- 用户书架表（对应 model/ShelfBook.java）
-- 同样原先只在 src/main/resources/db/shelf_books.sql 里。整本书粒度，
-- 身份用 store_book_id：书名可重复、可被改名，用 id 判定「同一本书」才稳定。
CREATE TABLE IF NOT EXISTS shelf_books (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户 id',
    store_book_id BIGINT NOT NULL COMMENT '书城书籍 id（store_books.id）',
    -- 入架时冻结书名/作者（v5/S2）：书城改名或下架后书架仍要显示当时的名字
    title VARCHAR(255) DEFAULT NULL,
    author VARCHAR(255) DEFAULT NULL,
    created_at DATETIME(6) NOT NULL COMMENT '加入书架时间；书架默认按此列倒序',
    -- 同一用户对同一本书只应有一条记录：并发重复提交靠它兜底
    UNIQUE KEY uk_shelf_user_book (user_id, store_book_id),
    -- 书架列表走 findByUserIdOrderByCreatedAtDescIdDesc，命中此索引避免全表扫描后排序
    INDEX idx_shelf_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户书架（整本书粒度，按用户同步）';

-- 书城书籍正文快照（对应 model/BookContentSnapshot.java，v5/W1）
-- 合成那一刻提取到的正文（段落 + 目录 + 原文行 + 行↔段映射）原样存 JSON，
-- 阅读页在有音频时改读它 ⇒ 正文与音频永远同源；文件被替换也不会静默错位。
-- 与 store_books 一对一：重新合成时覆盖同一行，不保留历史版本。
CREATE TABLE IF NOT EXISTS book_content_snapshots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_book_id BIGINT NOT NULL COMMENT '书城书籍 id（store_books.id）',
    task_id BIGINT NOT NULL COMMENT '快照对应的合成任务 id（须与 store_books.task_id 一致才可复用）',
    file_size BIGINT NOT NULL COMMENT '快照时书文件字节数：与当前文件不同即视为已换版本',
    paragraph_count INT NOT NULL COMMENT '段落数（= 音频段数）',
    payload LONGTEXT NOT NULL COMMENT 'BookTextExtractor.BookContent 的 JSON',
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_bcs_book (store_book_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================================
-- 迁移语句（已有库升级时执行；dev 环境 ddl-auto=update 会自动加列/建表，prod 环境
-- ddl-auto=validate 需手动执行以下语句后再启动，否则实体校验会失败）
-- ============================================================================
-- ALTER TABLE audio_segments ADD COLUMN error_message VARCHAR(500) DEFAULT NULL AFTER status;
-- ALTER TABLE store_books ADD COLUMN cover_image VARCHAR(500) DEFAULT NULL AFTER cover_color;
-- ALTER TABLE store_books ADD COLUMN status VARCHAR(20) DEFAULT 'onshelf' AFTER download_count;
-- CREATE TABLE IF NOT EXISTS user_reference_audio (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     name VARCHAR(200) NOT NULL,
--     file_name VARCHAR(500) NOT NULL,
--     description TEXT,
--     file_size BIGINT DEFAULT NULL,
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
--     FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
--     INDEX idx_user_ref_audio_user (user_id, created_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- ALTER TABLE user_reference_audio ADD COLUMN description TEXT AFTER file_name;
--
-- ---- 积分功能迁移（v-credits）：老库升级需按顺序执行下面全部语句 ----
-- ALTER TABLE users ADD COLUMN credits INT NOT NULL DEFAULT 0 AFTER banned_until;
-- ALTER TABLE tasks ADD COLUMN credits_cost INT NOT NULL DEFAULT 0 AFTER completed_segments;
-- ALTER TABLE tasks ADD COLUMN credits_refunded INT NOT NULL DEFAULT 0 AFTER credits_cost;
-- ALTER TABLE tasks ADD COLUMN error_message VARCHAR(500) DEFAULT NULL AFTER credits_refunded;
--
-- ---- Token 版本号迁移（D4 账户安全）：老库升级需执行，否则 prod validate 启动失败 ----
-- ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0 AFTER credits;
--
-- ---- 订单乐观锁迁移（H6）：老库升级需执行，否则 prod validate 启动失败 ----
-- ALTER TABLE credit_orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER updated_at;
--
-- ---- 临时参考音频归属表（v-temp-ref）：老库升级需执行，否则 prod validate 启动失败 ----
-- CREATE TABLE IF NOT EXISTS temp_reference_audio (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     file_name VARCHAR(500) NOT NULL,
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
--     INDEX idx_temp_ref_audio_user (user_id, created_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
--
-- ---- 发现页帖子表（v-posts）：老库升级需执行，否则 prod validate 启动失败 ----
-- CREATE TABLE IF NOT EXISTS posts (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     username VARCHAR(100) NOT NULL,
--     content TEXT NOT NULL,
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
--     INDEX idx_post_created (created_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
--
-- ---- 参考音频分享排行（v-shared-ref）：老库升级需执行，否则 prod validate 启动失败 ----
-- ALTER TABLE user_reference_audio ADD COLUMN shared TINYINT(1) NOT NULL DEFAULT 0 AFTER file_size;
-- ALTER TABLE user_reference_audio ADD COLUMN use_count BIGINT NOT NULL DEFAULT 0 AFTER shared;
-- ALTER TABLE user_reference_audio ADD COLUMN shared_at DATETIME DEFAULT NULL AFTER use_count;
-- ALTER TABLE user_reference_audio ADD INDEX idx_user_ref_audio_rank (shared, use_count);
--
-- ---- 帖子正文长度兜底（D1）：老库升级时执行；新库已由上面的 CREATE TABLE 直接建为 VARCHAR(500) ----
-- 实体 Post.content 已去掉 columnDefinition="TEXT" 改用 length=500，prod ddl-auto=validate 要求列类型为
-- VARCHAR 而非 TEXT，必须在启动前把列改过来，否则实体校验失败。dev 环境 ddl-auto=update 会自动 MODIFY。
-- ALTER TABLE posts MODIFY COLUMN content VARCHAR(500) NOT NULL;
-- 注：created_at 精度（DATETIME -> DATETIME(3)）未改动——同秒排序已由 PostRepository 的
-- findAllByOrderByCreatedAtDescIdDesc 用 id 兜底保证稳定，改精度在 validate 模式下反而有启动失败风险。
--
-- ============================================================================
-- 本轮改进（#5/#10/#11/#12/#14/#20）迁移：老库升级需执行，否则 prod validate 启动失败
-- dev 环境 ddl-auto=update 会自动加列/建表，prod 环境 ddl-auto=validate 必须手动执行以下全部语句。
-- ============================================================================
--
-- ---- #20 段落/角色音色 + 免参考音频合成 ----
-- 关键：#20 允许「仅预设音色、不上传参考音频」，reference_audio_path 必须可空，
--       否则建任务时写 NULL 撞非空约束 → 500（dev/prod 均命中，ddl-auto 不会改已存在列的 nullability）
-- ALTER TABLE tasks MODIFY COLUMN reference_audio_path VARCHAR(500) DEFAULT NULL;
-- ALTER TABLE tasks ADD COLUMN default_voice_id VARCHAR(100) DEFAULT NULL AFTER error_message;
-- ALTER TABLE tasks ADD COLUMN segment_voices TEXT AFTER default_voice_id;
-- ALTER TABLE audio_segments ADD COLUMN voice VARCHAR(100) DEFAULT NULL AFTER error_message;
--
-- ---- #5 播放进度跨端同步 ----
-- CREATE TABLE IF NOT EXISTS reading_progress (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     task_id BIGINT NOT NULL,
--     segment_number INT NOT NULL DEFAULT 1,
--     offset_ms INT NOT NULL DEFAULT 0,
--     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
--     UNIQUE KEY idx_rp_user_task (user_id, task_id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
--
-- ---- 听书收藏上云（跨端同步，表定义见上方 CREATE TABLE 区）----
-- 老库升级必须执行，否则 prod profile 的 ddl-auto=validate 会因缺表启动失败
-- CREATE TABLE IF NOT EXISTS text_favorites (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     book_name VARCHAR(255) NOT NULL,
--     task_id BIGINT DEFAULT NULL,
--     segment_number INT NOT NULL,
--     content TEXT NOT NULL,
--     created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
--     UNIQUE KEY uk_text_fav_user_book_seg (user_id, book_name, segment_number),
--     INDEX idx_text_fav_user_created (user_id, created_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
--
-- ---- #10 讨论区（帖子扩展列 + 评论/收藏/举报三张新表；表定义见上方 CREATE TABLE 区）----
-- ALTER TABLE posts ADD COLUMN title VARCHAR(200) DEFAULT NULL AFTER username;
-- ALTER TABLE posts ADD COLUMN image_urls TEXT AFTER content;
-- ALTER TABLE posts ADD COLUMN ref_audio_id BIGINT DEFAULT NULL AFTER image_urls;
-- ALTER TABLE posts ADD COLUMN mentions TEXT AFTER ref_audio_id;
-- ALTER TABLE posts ADD COLUMN comment_count INT DEFAULT 0 AFTER mentions;
-- ALTER TABLE posts ADD COLUMN favorite_count INT DEFAULT 0 AFTER comment_count;
-- ALTER TABLE posts ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;
--
-- ---- #11 编辑推荐 ----
-- ALTER TABLE store_books ADD COLUMN editor_pick TINYINT(1) DEFAULT 0 AFTER download_count;
--
-- ---- #12 订单失败原因 ----
-- ALTER TABLE credit_orders ADD COLUMN fail_reason VARCHAR(255) DEFAULT NULL AFTER expire_at;
--
-- ---- #14 每日免费试用额度 ----
-- ALTER TABLE credit_rule ADD COLUMN daily_free_tasks INT NOT NULL DEFAULT 2 AFTER free_credits_on_register;
--
-- ---- 书城合成语音（v-store-tts）：老库升级需执行，否则 prod validate 启动失败 ----
-- 对应 model/StoreBook.java 新增的 task_id / synthesized_at / synthesized_by。
-- 三列全部可空：老数据保持 NULL 语义（= 尚未合成音频），无需回填。

-- ---- 多节点调度（v-multi-node）：老库升级需执行 ----
-- 若库是从旧版本升上来的（未跑过 src/main/resources/db/tts_nodes.sql），执行上方
-- CREATE TABLE IF NOT EXISTS tts_nodes 即可（该语句幂等，重复执行无副作用）。
--
-- ---- 书架（v-shelf）：老库升级需执行 ----
-- 同上，执行上方 CREATE TABLE IF NOT EXISTS shelf_books。

-- ---- audio_segments 唯一约束（v-seg-uniq）：老库升级需【先查重、再建索引】----
-- 新库已在 CREATE TABLE 里带 UNIQUE KEY uk_segment_task_number，无需执行本段。
-- 老库原先只有普通索引 idx_segment_task_number，必须先确认没有重复行，否则建索引会失败：
--
-- 第 1 步：查重（有输出说明存在重复，必须先做第 2 步）
-- SELECT task_id, segment_number, COUNT(*) AS c FROM audio_segments
--   GROUP BY task_id, segment_number HAVING c > 1;
--
-- 第 2 步：按 (task_id, segment_number) 去重，只保留 id 最小的那条。
--          ⚠️ 这是删除数据的操作，执行前请先备份。重复行本身是历史缺陷的产物
--          （同一段被合成两次），保留哪一条对业务影响都很小，但不能不处理。
-- DELETE a FROM audio_segments a
--   JOIN (SELECT task_id, segment_number, MIN(id) AS keep_id FROM audio_segments
--          GROUP BY task_id, segment_number HAVING COUNT(*) > 1) d
--     ON a.task_id = d.task_id AND a.segment_number = d.segment_number
--    AND a.id <> d.keep_id;
--
-- 第 3 步：建唯一索引
-- ALTER TABLE audio_segments DROP INDEX idx_segment_task_number;
-- ALTER TABLE audio_segments ADD UNIQUE KEY uk_segment_task_number (task_id, segment_number);

-- ---- 书城首页 / 帖子音色聚合页索引（v-idx-rank）----
-- 新库已在上方 CREATE TABLE 里带上这两条索引，无需执行本段；老库按下述语句补建。
-- ALTER TABLE store_books ADD INDEX idx_store_book_onshelf_rank (status, download_count, id);
-- ALTER TABLE posts ADD INDEX idx_post_ref_audio (ref_audio_id, created_at);
-- 不建外键：产物归属书而非任务，任务侧删除不应级联影响书侧历史记录。
-- ALTER TABLE store_books ADD COLUMN task_id BIGINT DEFAULT NULL AFTER status;
-- ALTER TABLE store_books ADD COLUMN synthesized_at DATETIME DEFAULT NULL AFTER task_id;
-- ALTER TABLE store_books ADD COLUMN synthesized_by VARCHAR(100) DEFAULT NULL AFTER synthesized_at;
-- ALTER TABLE store_books ADD INDEX idx_store_book_task_id (task_id);


-- ---- 内容审核 / 书架书名快照（v5/S2）：老库升级必须执行，否则 prod validate 启动失败 ----
-- ALTER TABLE posts ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active' AFTER updated_at;
-- ALTER TABLE posts ADD COLUMN removed_at DATETIME(6) DEFAULT NULL AFTER status;
-- ALTER TABLE posts ADD INDEX idx_post_status_created (status, created_at);
-- ALTER TABLE user_reference_audio ADD COLUMN admin_hidden BIT(1) NOT NULL DEFAULT b'0' AFTER shared_at;
-- ALTER TABLE shelf_books ADD COLUMN title VARCHAR(255) DEFAULT NULL AFTER store_book_id;
-- ALTER TABLE shelf_books ADD COLUMN author VARCHAR(255) DEFAULT NULL AFTER title;

-- ---- 书城正文快照（v5/W1）：老库升级必须执行，否则 prod validate 启动失败 ----
-- ALTER TABLE tasks ADD COLUMN store_book_id BIGINT DEFAULT NULL AFTER segment_voices;
-- ALTER TABLE tasks ADD INDEX idx_task_store_book (store_book_id);
-- CREATE TABLE IF NOT EXISTS book_content_snapshots (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     store_book_id BIGINT NOT NULL,
--     task_id BIGINT NOT NULL,
--     file_size BIGINT NOT NULL,
--     paragraph_count INT NOT NULL,
--     payload LONGTEXT NOT NULL,
--     created_at DATETIME(6) NOT NULL,
--     UNIQUE KEY uk_bcs_book (store_book_id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
--
-- ---- 参考音频主题（v-ref-topic）：老库升级必须执行，否则 prod validate 启动失败 ----
-- 推荐直接跑 src/main/resources/db/reference_audio_topics.sql（带幂等守卫，可重复执行）；
-- 下面三条等价，供不方便开脚本文件时手工粘贴。表定义见上方 CREATE TABLE 区。
-- CREATE TABLE IF NOT EXISTS reference_audio_topic (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     name VARCHAR(200) NOT NULL,
--     description TEXT,
--     sort_order INT NOT NULL DEFAULT 0,
--     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
--     UNIQUE KEY uk_ref_topic_name (name),
--     INDEX idx_ref_topic_sort (sort_order, id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- ALTER TABLE reference_audio ADD COLUMN topic_id BIGINT DEFAULT NULL AFTER file_size;
-- ALTER TABLE reference_audio ADD INDEX idx_ref_audio_topic (topic_id, created_at);
-- 已有音频的 topic_id 保持 NULL（= 未归类），无需回填；

-- ---- 书城「原书名」（v-orig-title）：老库升级必须执行，否则 prod validate 启动失败 ----
-- 管理端上传电子书时填写，仅为存档展示；老数据保持 NULL（= 未填写），无需回填。
-- ALTER TABLE store_books ADD COLUMN original_title VARCHAR(200) DEFAULT NULL AFTER title;


-- ---- 跨端阅读进度（v-book-progress）：老库升级必须执行，否则 prod validate 启动失败 ----
-- 推荐直接跑 src/main/resources/db/user_book_progress.sql（带幂等守卫，可重复执行）；
-- 下面等价，供不方便开脚本文件时手工粘贴。表定义见上方 CREATE TABLE 区。
-- CREATE TABLE IF NOT EXISTS user_book_progress (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     book_key VARCHAR(255) NOT NULL,
--     segment_number INT NOT NULL DEFAULT 0,
--     offset_ms INT NOT NULL DEFAULT 0,
--     updated_at DATETIME(6) DEFAULT NULL,
--     UNIQUE KEY uk_ubp_user_book (user_id, book_key),
--     KEY idx_ubp_user_book (user_id, book_key)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 老数据无需回填：reading_progress 里的存量进度由服务端在读写老端点时自动镜像到新表。


-- ---- 跨端单值偏好 / 每书元数据（v-user-settings）：老库升级必须执行，否则 prod validate 启动失败 ----
-- 推荐直接跑 src/main/resources/db/user_settings.sql（带幂等守卫，可重复执行）；
-- 下面等价，供不方便开脚本文件时手工粘贴。语义与版本号口径见该脚本顶部注释。
-- CREATE TABLE IF NOT EXISTS user_settings (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     setting_key VARCHAR(64) NOT NULL,
--     setting_value VARCHAR(512) DEFAULT NULL,
--     updated_at BIGINT NOT NULL DEFAULT 0,
--     UNIQUE KEY uk_us_user_key (user_id, setting_key),
--     KEY idx_us_user (user_id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- CREATE TABLE IF NOT EXISTS user_book_meta (
--     id BIGINT AUTO_INCREMENT PRIMARY KEY,
--     user_id BIGINT NOT NULL,
--     book_key VARCHAR(255) NOT NULL,
--     sort_order INT NOT NULL DEFAULT 0,
--     last_read_at BIGINT NOT NULL DEFAULT 0,
--     updated_at BIGINT NOT NULL DEFAULT 0,
--     UNIQUE KEY uk_ubm_user_book (user_id, book_key),
--     KEY idx_ubm_user (user_id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 老数据无需回填：客户端首次同步时上报本机现值。


-- ---- 生词本收藏出处（v-word-book-context，2026-11-07）----
-- 历史欠账：user_word_books 表此前只在 src/main/resources/db/user_word_books.sql
-- 定义，本初始化脚本没有它 ⇒ 全新建库会缺表，prod validate 启动失败。
-- 完整定义与幂等加列脚本见 src/main/resources/db/user_word_books.sql（含出处 6 列）。
-- ⚠️ 执行顺序红线：备份 → 跑该脚本（CREATE + 幂等 ALTER）→ 再传新 jar → 最后重启。
-- 老库升级的六条 ALTER（逐列 information_schema 守卫、可重复执行）：
-- ALTER TABLE user_word_books ADD COLUMN book_name      VARCHAR(255) DEFAULT NULL AFTER updated_at;
-- ALTER TABLE user_word_books ADD COLUMN book_key       VARCHAR(255) DEFAULT NULL AFTER book_name;
-- ALTER TABLE user_word_books ADD COLUMN store_book_id  BIGINT       DEFAULT NULL AFTER book_key;
-- ALTER TABLE user_word_books ADD COLUMN segment_number INT          DEFAULT NULL AFTER store_book_id;
-- ALTER TABLE user_word_books ADD COLUMN line_number    INT          DEFAULT NULL AFTER segment_number;
-- ALTER TABLE user_word_books ADD COLUMN sentence_text  TEXT         DEFAULT NULL AFTER line_number;
-- 老数据无需回填：出处全可空，客户端按 null 禁用「跳转到原书」按钮。
