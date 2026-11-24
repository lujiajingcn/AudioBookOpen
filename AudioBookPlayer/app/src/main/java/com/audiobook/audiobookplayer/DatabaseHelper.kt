package com.audiobook.audiobookplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 进程级 SQLite 帮助类（单例）。
 *
 * 被 BookViewModel / PlaybackService / TaskPollingManager 共享同一数据库实例。
 * 表结构与服务端语义保持一致；BookViewModel.init 也会用 createXTableIfNeeded
 * 以 IF NOT EXISTS 兜底建表，二者 schema 必须一致（下方建表 SQL 即照搬
 * BookViewModel 中的定义，避免偏差）。
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    /**
     * 开启 WAL（Write-Ahead Logging）。
     *
     * 原来整个 App 从未调用 `enableWriteAheadLogging()`，数据库跑在默认的 rollback journal
     * 模式下：**读写互斥**。后果不是「并发写丢数据」（同一个 SQLiteDatabase 实例跨线程
     * 访问会被 Android 串行化，普通并发写只会阻塞等待），而是：
     *  - 下载音频片段是长事务式写入，期间所有读操作（翻页、查书架、任务轮询）都要排队等，
     *    连续写入上千段时界面会明显卡住；
     *  - `BookStorage` 里的同步 `VACUUM` 一旦超过连接池的等待上限（约 30s）就会抛
     *    `SQLiteDatabaseLockedException`，而这个异常被 `catch (e: Exception)` 吞掉，
     *    表现为某些操作静默失败且毫无提示。
     *
     * 开 WAL 后读写走不同连接，下载的同时仍能正常播放与轮询。
     * 选在 `onConfigure` 而不是 `onCreate`：`onConfigure` 在每次打开数据库时都会调用，
     * 且是 Android 官方文档明确推荐的开启位置；`onCreate` 只在首次建库时走一次，
     * 老用户升级后根本不会执行。
     *
     * 注意：WAL 下 `VACUUM` 行为不变，仍需放在 try/catch 里（见 BookStorage）。
     */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 表结构统一取自 companion 中的 DDL 常量（与各 Repository 的兜底建表同一真源）
        ALL_DDL.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2：为 audio_segments 增加 (book_name, segment_number) 唯一约束。
            // v1 只有普通索引，BookViewModel.saveAudioSegments 的 INSERT OR REPLACE
            // 因此退化为普通 INSERT：重试 / 补下缺失片段 / 重复下载会产生重复段，
            // 播放队列按 segment_number 取全部行 → 每段被连播 N 遍，BLOB 成倍膨胀。
            // 迁移步骤：①存量去重（同书同段保留最新写入的 id）；
            //          ②删旧普通索引；③建唯一索引（与 onCreate 保持一致）。
            db.execSQL(
                """
                DELETE FROM $TABLE_AUDIO_SEGMENTS
                WHERE id NOT IN (
                    SELECT MAX(id) FROM $TABLE_AUDIO_SEGMENTS
                    GROUP BY book_name, segment_number
                )
                """.trimIndent()
            )
            db.execSQL("DROP INDEX IF EXISTS idx_audio_segments_book")
            db.execSQL(DDL_AUDIO_SEGMENTS_UNIQUE_INDEX)
        }
        if (oldVersion < 3) {
            // v3：新增本地参考音频表（「我的 - 参考音频管理」）。
            // 与 BookViewModel 的 createXTableIfNeeded 兜底建表保持同一 schema。
            // 存量用户只是缺这张表，不影响既有数据，IF NOT EXISTS 补齐即可。
            db.execSQL(DDL_REFERENCE_AUDIO)
        }
        if (oldVersion < 4) {
            // v4：ebooks 增加「最近阅读时间」与「分类标签」两列，支撑书架的
            // 「最近阅读」排序与分类筛选。旧版无这两列，用 ALTER TABLE 增量补齐，
            // 存量记录 last_read_at 默认 0（未读过）、category 默认 ''（未分类）。
            db.execSQL("ALTER TABLE $TABLE_EBOOKS ADD COLUMN last_read_at INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_EBOOKS ADD COLUMN category TEXT DEFAULT ''")
        }
        if (oldVersion < 5) {
            // v5：favorites 增加 server_id（收藏上云后「本地行 ↔ 服务端记录」的映射）。
            // 旧记录 server_id 为 NULL，首次同步时会作为「待上传」项补传，不会丢。
            db.execSQL("ALTER TABLE $TABLE_FAVORITES ADD COLUMN server_id INTEGER")
            // 同步时按 (book_name, line_number) 定位/去重（与服务端唯一身份口径一致）
            db.execSQL(DDL_FAVORITES_BOOK_LINE_INDEX)
        }
        if (oldVersion < 6) {
            // v6：新增「服务端解析结果缓存」表。
            // 当时 PDF 客户端无法本地解析（只有 PDFBox-Android 2.0.27），只能整本上传给服务端解析
            // —— 而原实现**每次打开都重传**，网络差时几十秒。这张表把首次解析结果存下来，之后秒开。
            // （现客户端已有 PDFium 本地预览，但正文仍以服务端权威结果为准并入此表。）
            // 存量用户只是缺这张表，不影响既有数据，IF NOT EXISTS 补齐即可。
            db.execSQL(DDL_EBOOK_CONTENT_CACHE)
        }
        if (oldVersion < 7) {
            // v7：tasks 增加 progress_text —— 书架里那条任务记录要能显示**当前阶段**。
            //
            // 起因是实测发现的一个体验缺陷：本机合成在开始推理前要先加载模型
            // （实测 13~42 秒），而这期间任务行只有「合成中 0 / N」——
            // 进度条一动不动、数字也不动，用户会以为点了没反应或者卡死了。
            // 通知栏本来会说「正在加载模型」，但用户看的是书架，不是通知栏。
            //
            // 存量记录 progress_text 为 NULL，界面按「无阶段信息」处理，不影响既有任务。
            db.execSQL("ALTER TABLE $TABLE_TASKS ADD COLUMN progress_text TEXT")
        }
        if (oldVersion < 8) {
            // v8：新增「单词本」表（「我的 - 单词本」）。
            // 阅读页查词弹窗里的收藏按钮写入这张表，存的是**词条快照**（音标+释义），
            // 所以卸载词典后收藏仍可读（快照已在本地，不依赖 dict.db）。
            // 存量用户只是缺这张表，不影响既有数据，IF NOT EXISTS 补齐即可。
            db.execSQL(DDL_WORD_BOOK)
            db.execSQL(DDL_WORD_BOOK_UNIQUE_INDEX)
        }
        if (oldVersion < 9) {
            // v9：ebooks 增加 sort_order —— 书架「长按拖放调整书籍顺序」的落库字段。
            //
            // 语义：**值越大越靠前**。新导入的书取 MAX(sort_order)+1，于是排在最前 ——
            // 与旧版「按 created_at DESC（新书在前）」的观感一致，升级后书架顺序不变。
            // 存量记录默认 0（全部相同），排序时用 created_at DESC 兜底，
            // 所以老用户升级后看到的顺序与升级前一模一样。
            db.execSQL("ALTER TABLE $TABLE_EBOOKS ADD COLUMN sort_order INTEGER DEFAULT 0")
        }
        if (oldVersion < 10) {
            // v10：ebooks 增加 store_book_id —— 「这本书来自书城哪一本」。
            //
            // 起因：书城下载的书只是 filesDir/ebooks 下的一个普通文件，本地没有任何字段记着
            // 它的书城 id。于是打开它时只能走「整本上传给服务端解析」那条通用路径
            // （POST /api/tts/extract-content），即使服务端早就有这本原书。
            // 有了 id 就能直接 GET /api/store/book/{id}/content —— 零上传，
            // 命中服务端正文快照时只要百毫秒级。
            //
            // 取值：-1 = 手工导入/非书城来源（保持旧行为，仍走上传解析）；> 0 = 书城书籍 id。
            // 存量已下载的书由 BookStoreViewModel 在书城列表加载时按文件名回填（见 alignLocalBookNames）。
            db.execSQL("ALTER TABLE $TABLE_EBOOKS ADD COLUMN store_book_id INTEGER DEFAULT -1")
        }
        if (oldVersion < 11) {
            // v11：word_book 增加 server_id（生词本上云后「本地行 ↔ 服务端 UserWordBook」的映射）。
            //
            // 与 v5 favorites.server_id 完全同族：NULL = 本机收藏但还没上云
            // （离线时点星、老版本升级上来的存量数据），> 0 = 已与服务端对齐。
            // 首次同步会把这些 NULL 行当作「待上传」批量 POST 到 /api/word-book/sync，
            // 服务端返回合并后的全量列表（含 id），客户端凭 sw 回填 server_id。
            //
            // 只加列，不动 sw UNIQUE 索引：跨端身份口径保持 (user_id, sw)，索引语义不变。
            db.execSQL("ALTER TABLE $TABLE_WORD_BOOK ADD COLUMN server_id INTEGER")
        }
        if (oldVersion < 12) {
            // v12：Phase3「有声覆盖图跨端」。两个改动：
            //
            // ① audio_segments 增加 source —— 标记每一段音频**从哪来**：
            //    'server' = 服务端有这份字节（书城下载/云端合成回来），别的设备可下载后播；
            //    'local'  = 本机合成（MossLocalSynthesizer 走本地模型），服务端**没有**这份字节，
            //               其它设备只能在覆盖图上「看到这本书在另一台设备合成过」，但播不了。
            //
            //    为什么要单独存一列：audio_segments 只按 book_name 索引，本机合成与书城下载混在同一张表，
            //    旧版靠 tasks.audio_name 前缀「本机合成 · 」间接推断，脆弱（改名/删任务就丢来源）。
            //    来源在写入那一刻最清楚（streaming=服务端下、direct=本机合成），故落列由写入方直接标。
            //
            //    存量回填：老库里 server_segment_id 非空 ⟺ 这段是从服务端下载/云端合成回来的（本机合成
            //    永远没有 server_segment_id），据此把已存在的段标 'server'，其余保持默认 'local'。
            //    （DDL 默认值 'local' 已随 ALTER 生效于新行，这里只回填历史 server 段。）
            db.execSQL("ALTER TABLE $TABLE_AUDIO_SEGMENTS ADD COLUMN source TEXT DEFAULT 'local'")
            db.execSQL(
                "UPDATE $TABLE_AUDIO_SEGMENTS SET source = 'server' " +
                    "WHERE server_segment_id IS NOT NULL"
            )
            // ② 新建 audio_coverage 缓存表（镜像服务端权威的并集覆盖图，供书架/阅读器画徽章）。
            //    存量用户缺这张表不影响既有数据，IF NOT EXISTS 补齐即可。
            db.execSQL(DDL_AUDIO_COVERAGE)
        }
        if (oldVersion < 13) {
            // v13：word_book 增加「收藏出处」6 列 —— 单词本长按「跳转到原书」用。
            //
            // 六列与服务端 wire（WordBookItemRequest）一一对应，**全部可空**：
            //  - book_name    书名/展示名（打开哪本书）
            //  - book_key     跨端统一书键 s:{storeBookId}/t:{taskId}/n:{归一化书名}
            //  - store_book_id 书城书 id，本地书为 NULL
            //  - segment_number 段号（1-based）
            //  - line_number  行号 hint —— 只是兜底：行号是「当时切分粒度」的函数，
            //                  切分规则一改就指向别的文字，绝不能单独承担定位；
            //  - sentence_text 收藏时所在句子/段落原文 —— **定位第一判据**（文字内容不随切分漂移）。
            //
            // 旧数据 / 服务端返回 null 时这 6 列都是 NULL：跳转到原书必须禁用或提示，
            // 绝不崩、绝不白屏（见 WordSource.canJumpToBook）。
            //
            // ⚠️ 必须「可重复执行」：v8 分支用 DDL_WORD_BOOK（单一真源）建表，而那份 DDL
            //    现在已含这 6 列 —— 从 v7 及更早版本一步升到 v13 时，表是**带 6 列**新建的，
            //    若此处无条件 ALTER 会撞 "duplicate column name"。SQLite 的 ALTER 又不支持
            //    IF NOT EXISTS，故先查 PRAGMA table_info 拿现有列名，只补缺的列（见下方辅助函数）。
            addColumnsIfMissing(
                db,
                TABLE_WORD_BOOK,
                "book_name" to "TEXT",
                "book_key" to "TEXT",
                "store_book_id" to "INTEGER",
                "segment_number" to "INTEGER",
                "line_number" to "INTEGER",
                "sentence_text" to "TEXT"
            )
        }
        Log.d(TAG, "DatabaseHelper onUpgrade $oldVersion -> $newVersion")
    }

    companion object {
        private const val DATABASE_NAME = "audiobook.db"
        private const val DATABASE_VERSION = 13
        private const val TAG = "DatabaseHelper"

        const val TABLE_EBOOKS = "ebooks"
        const val TABLE_TASKS = "tasks"
        const val TABLE_AUDIO_SEGMENTS = "audio_segments"
        const val TABLE_FAVORITES = "favorites"
        const val TABLE_REFERENCE_AUDIO = "reference_audio"
        const val TABLE_EBOOK_CONTENT_CACHE = "ebook_content_cache"
        const val TABLE_WORD_BOOK = "word_book"
        const val TABLE_AUDIO_COVERAGE = "audio_coverage"

        // ---------------- 建表 DDL（单一真源） ----------------
        // onCreate 与各 Repository 的 createXxxTableIfNeeded 共用同一份 DDL：
        // 原先 BookStorage / FavoriteRepository / TaskRepository 各自手抄一份 schema
        // （注释里写着「照搬」），任一处改动遗漏都会造成「首次建库」与「兜底建表」
        // 两条路径的 schema 漂移。此处集中为唯一真源。
        // 注意：这些 val 必须声明在 TABLE_* 常量之后（Kotlin companion 按文本顺序初始化）。

        val DDL_EBOOKS = """
            CREATE TABLE IF NOT EXISTS $TABLE_EBOOKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_read_at INTEGER DEFAULT 0,
                category TEXT DEFAULT '',
                sort_order INTEGER DEFAULT 0,
                store_book_id INTEGER DEFAULT -1
            )
            """.trimIndent()

        val DDL_TASKS = """
            CREATE TABLE IF NOT EXISTS $TABLE_TASKS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_name TEXT NOT NULL,
                audio_name TEXT NOT NULL,
                task_id INTEGER UNIQUE,
                server_task_id INTEGER,
                status TEXT DEFAULT 'processing',
                total_segments INTEGER DEFAULT 0,
                completed_segments INTEGER DEFAULT 0,
                progress_text TEXT,
                audio_files_path TEXT,
                error_message TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()

        val DDL_AUDIO_SEGMENTS = """
            CREATE TABLE IF NOT EXISTS $TABLE_AUDIO_SEGMENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_name TEXT NOT NULL,
                server_segment_id INTEGER,
                segment_number INTEGER NOT NULL,
                text_content TEXT NOT NULL,
                audio_data BLOB NOT NULL,
                status TEXT DEFAULT 'completed',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                source TEXT DEFAULT 'local'
            )
            """.trimIndent()

        /** INSERT OR REPLACE 的 upsert 语义依赖该唯一约束（与 v2 迁移保持一致） */
        val DDL_AUDIO_SEGMENTS_UNIQUE_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_audio_segments_book_unique
            ON $TABLE_AUDIO_SEGMENTS (book_name, segment_number)
            """.trimIndent()

        val DDL_FAVORITES = """
            CREATE TABLE IF NOT EXISTS $TABLE_FAVORITES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_name TEXT NOT NULL,
                line_number INTEGER NOT NULL,
                content TEXT NOT NULL,
                server_id INTEGER,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()

        /** 按书名+段落号+内容去重索引 */
        val DDL_FAVORITES_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_favorite_unique
            ON $TABLE_FAVORITES (book_name, line_number, content)
            """.trimIndent()

        /** 同步定位索引：按 (book_name, line_number) 匹配服务端身份（与服务端唯一键口径一致） */
        val DDL_FAVORITES_BOOK_LINE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_favorite_book_line
            ON $TABLE_FAVORITES (book_name, line_number)
            """.trimIndent()

        val DDL_REFERENCE_AUDIO = """
            CREATE TABLE IF NOT EXISTS $TABLE_REFERENCE_AUDIO (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                file_path TEXT NOT NULL,
                source TEXT NOT NULL,
                server_id INTEGER,
                description TEXT,
                file_size INTEGER DEFAULT 0,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()

        /**
         * 服务端电子书解析结果缓存（仅 PDF，以及 EPUB 本地解析失败后的回退结果）。
         *
         * 身份是 `file_path`，但**命中要看 `file_size` + `file_mtime`**：同名文件被重新导入
         * （内容变了）时，mtime 必变 ⇒ 缓存自然失效，不会拿旧正文去对新音频建映射。
         *
         * `rows_text` 存的是逐行文本用 `\n` 连接的结果 —— 每个行文本里**不含** `\n`
         * （切行时已按 `\n` 拆开），所以这个编码是无损的，也省掉了 JSON 序列化开销。
         * `toc_json` 用 JSON：标题内部可能含换行（`<h1>标题<br>副标题</h1>` 清完标签就是两行），
         * 自定义分隔符不安全。
         */
        val DDL_EBOOK_CONTENT_CACHE = """
            CREATE TABLE IF NOT EXISTS $TABLE_EBOOK_CONTENT_CACHE (
                file_path TEXT PRIMARY KEY,
                file_size INTEGER NOT NULL,
                file_mtime INTEGER NOT NULL,
                book_name TEXT,
                rows_text TEXT NOT NULL,
                toc_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()

        /**
         * 单词本（阅读页查词弹窗收藏的英文单词）。
         *
         * 存的是**词条快照**而不是外键：`sw`（strip 后的归一化键）+ `word`（展示原词）
         * + `phonetic` + `translations`（释义，存字面量 `\n` 分隔的原文，UI 再按行拆）。
         * 这样卸载 ECDICT 后收藏仍可读 —— 查词是「用得上词典」，收藏是「已经拿到的知识」，
         * 后者不该因为用户清理 55MB 存储就没了。
         *
         * 唯一身份是 `sw`：查词返回的展示词可能是 `hood`/`Hood`/`'hood` 三种形态
         * （去符号后同键，见 DictionaryManager 约定③），按 `word` 去重会存出三条。
         *
         * ## 收藏出处（v13 新增 6 列，与服务端 wire 对齐）
         * 收藏时额外记下「哪本书 + 当前句子位置」，供单词本长按「跳转到原书」。
         * 全部**可空**：老版本升级上来的存量行、以及服务端返回 null 的旧数据，
         * 这 6 列都是 NULL ⇒ UI 必须禁用跳转 / 提示重新收藏，绝不崩、绝不白屏。
         * `line_number` 只是行号 hint（会随切分粒度漂移，仅兜底），`sentence_text`
         * 才是定位第一判据；`book_key` 三端统一口径 s:/t:/n:（见 CoverageCodec.bookKey）。
         */
        val DDL_WORD_BOOK = """
            CREATE TABLE IF NOT EXISTS $TABLE_WORD_BOOK (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sw TEXT NOT NULL,
                word TEXT NOT NULL,
                phonetic TEXT DEFAULT '',
                translations TEXT DEFAULT '',
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                server_id INTEGER,
                book_name TEXT,
                book_key TEXT,
                store_book_id INTEGER,
                segment_number INTEGER,
                line_number INTEGER,
                sentence_text TEXT
            )
            """.trimIndent()

        /** 同一归一化键只收藏一次（收藏按钮的幂等语义靠它，不靠先查后插） */
        val DDL_WORD_BOOK_UNIQUE_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_word_book_sw_unique
            ON $TABLE_WORD_BOOK (sw)
            """.trimIndent()

        /**
         * 本机有声覆盖图缓存（Phase3 跨端「哪本书有哪些段已有音频、字节从哪来」）。
         *
         * 这里存的是**服务端权威的并集视图**（每个 bookKey×source 一行，done_ranges 是压缩区间串），
         * 不是本地音频的真相 —— 本机音频真相在 audio_segments。这张表只是把「其它设备/服务端已经
         * 处理到哪」镜像下来，供书架/阅读器画徽章。同步一次全量重建（无护栏：覆盖图是纯感知层，
         * 绝不删除本机任何音频，所以不需要 favorites/word_book 那种「待上传未确认则不重建」的保护）。
         *
         * 身份 = (book_key, source)：
         *  - book_key 三端逐字符一致，优先级 s:{storeBookId} > t:{serverTaskId} > n:{trim(displayName)}
         *    （见 CoverageCodec.bookKey，必须与 PC coverage.py / 服务端 DoneRanges 完全一致）；
         *  - source：server=服务端有字节可下载播放；local=某设备本地合成、服务端无字节（仅作感知，
         *    绝不对用户宣称可播）。
         */
        val DDL_AUDIO_COVERAGE = """
            CREATE TABLE IF NOT EXISTS $TABLE_AUDIO_COVERAGE (
                book_key TEXT NOT NULL,
                source TEXT NOT NULL,
                store_book_id INTEGER,
                server_task_id INTEGER,
                total_segments INTEGER DEFAULT 0,
                done_ranges TEXT DEFAULT '',
                done_count INTEGER DEFAULT 0,
                updated_at TEXT DEFAULT '',
                PRIMARY KEY (book_key, source)
            )
            """.trimIndent()

        /** 首次建库时按序执行的全部 DDL（顺序与原 onCreate 一致） */
        val ALL_DDL: List<String> = listOf(
            DDL_EBOOKS,
            DDL_TASKS,
            DDL_AUDIO_SEGMENTS,
            DDL_AUDIO_SEGMENTS_UNIQUE_INDEX,
            DDL_FAVORITES,
            DDL_FAVORITES_INDEX,
            DDL_FAVORITES_BOOK_LINE_INDEX,
            DDL_REFERENCE_AUDIO,
            DDL_EBOOK_CONTENT_CACHE,
            DDL_WORD_BOOK,
            DDL_WORD_BOOK_UNIQUE_INDEX,
            DDL_AUDIO_COVERAGE
        )

        /**
         * 逐列补列（SQLite 的 ALTER 不支持 IF NOT EXISTS，故先查 PRAGMA 再决定要不要 ADD）。
         *
         * 为什么需要「可重复」：逐版本累加的 onUpgrade 里，某个中间版本可能已经用**最新
         * DDL**（单一真源，含全部列）建过这张表，后面的版本分支若再无条件 ALTER 同一列
         * 就会撞 "duplicate column name" 直接崩在升级路径上。这里按列名去重，缺谁补谁，
         * 既适用于「老库逐列补」，也适用于「表已带新列直接跳过」。
         *
         * table 来自 companion 常量、列名来自调用方字面量，均非外部输入，无注入风险。
         */
        private fun addColumnsIfMissing(
            db: SQLiteDatabase,
            table: String,
            vararg columns: Pair<String, String>
        ) {
            val existing = HashSet<String>()
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                if (nameIdx < 0) return@use
                while (c.moveToNext()) {
                    c.getString(nameIdx)?.let { existing.add(it) }
                }
            }
            for ((name, type) in columns) {
                if (name !in existing) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN $name $type")
                }
            }
        }

        @Volatile
        private var instance: DatabaseHelper? = null

        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }
}
