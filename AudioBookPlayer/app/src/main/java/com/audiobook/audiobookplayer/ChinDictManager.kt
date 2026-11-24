package com.audiobook.audiobookplayer

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * **中文单字词典**（新华字典��库）：从 `assets/chinese_dict.sqlite` 首次使用时
 * 释放到应用私有目录，之后只读打开。
 *
 * ## 与 [DictionaryManager]（ECDICT 英汉词典）的关系
 *
 * 两套词典**刻意不合并**，因为它们在三个维度上天然不同：
 *
 * | | ECDICT（英汉） | 本类（中文单字） |
 * |---|---|---|
 * | 来源 | 服务端下载 gzip | 随包 `assets` |
 * | 大小 | ~23 MB | ~4 MB |
 * | 查询入口 | 长按 →「翻译」 | 选中**单个汉字** →「查字典」 |
 * | 查得到 | 英文单词 | 常用字（含多音字全部读音） |
 *
 * 合在一起会让「下载英汉词典」这个 23 MB 的动作变成中文查字典的**前置条件** ——
 * 而中文单字是本项目的正文主体（《钟鼓楼》《潜规则》），绝大多数用户用不到 ECDICT。
 *
 * ## 为什么不用 SQLiteOpenHelper
 *
 * 与 [DictionaryManager] 同一理由：库是**随包只读**的，`openDatabase(OPEN_READONLY)`
 * 不会建 journal/WAL；若用 OpenHelper 的 `onCreate`，会尝试在私有目录里**新建**一个空库，
 * 于是「assets 没释放成功」会退化成「查询永远返回空」而不是报错 —— 静默失败最难查。
 *
 * ## 为什么释放到私有目录而不是直接读 assets
 *
 * `SQLiteDatabase` **不支持**从 `AssetFileDescriptor` 打开（assets 里的文件不是真实
 * 文件路径，没有 inode 可 mmap）。只能释放成真实文件。
 */
object ChinDictManager {

    private const val TAG = "ChinDictManager"

    /** assets 里的库文件名（清洗后的成品，见 `chinese-xinhua-master/clean_word.py`） */
    private const val ASSET_DB_NAME = "chinese_dict.sqlite"

    /** 释放到私有目录后的文件名 */
    private const val DB_FILE_NAME = "chinese_dict.db"

    /** 释放中的中转文件名；必须与目标不同名，否则重装时 `renameTo` 不可靠 */
    private const val TMP_SUFFIX = ".tmp"

    /**
     * 单字判定的码位区间。
     *
     * ⚠️ 与 [LineAdapter.containsCjk] 的范围**保持一致**（U+4E00–U+9FFF + 扩展 A
     * U+3400–U+4DBF）：两处都用「含 CJK」决定是否显示「查字典」，
     * 若这里放宽而那里收紧，用户会看到菜单项时有时无。
     */
    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    /** [isCjk] 的公开版：供调用方判断「这个字符能不能查字典」 */
    fun isCjkChar(ch: Char): Boolean = isCjk(ch)

    /** 一条释义（对应库里的一行：某字在某个读音下的解释） */
    data class ChinEntry(
        val word: String,
        /** 拼音，无声调分隔（如 `zhōnɡ`）；多音字会有多条 */
        val pinyin: String,
        /** 释义正文，已按空行切成条目（原文靠 `\n\n` 分条） */
        val definitions: List<String>
    )

    @Volatile
    private var cachedDb: SQLiteDatabase? = null

    /**
     * 把 assets 里的库释放到私有目录（幂等）。
     *
     * 必须在 IO 线程调（会做 4 MB 文件 IO）。
     *
     * 之所以能安全地「释放一次就再也不管」：这个库**随包发布、内容不变**，
     * 没有服务端那样的「服务端更新了字典」问题，因此**不需要版本比对**——
     * 已存在即视为最新。APK 升级后想换内容，得手动卸载重装（或改文件名）。
     *
     * @return 释放后的数据库文件；失败返回 null
     */
    suspend fun ensureInstalled(context: Context): File? = withContext(Dispatchers.IO) {
        val target = dbFile(context)
        if (target.isFile && target.length() > 0) {
            closeDb()   // 换文件前必须关掉旧连接，否则 Windows-like 的文件锁语义会失败
            return@withContext target
        }
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + TMP_SUFFIX)
        try {
            context.assets.open(ASSET_DB_NAME).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            // 判据用**文件头**而不是「大小等于某个常量」：大小会随字库增删变化，
            // 写死常量等于给自己埋一个「以后改字库就静默不更新」的雷。
            if (!looksLikeSqlite(tmp)) {
                throw IOException("assets里的 $ASSET_DB_NAME 不是有效的 SQLite 库")
            }
            if (target.exists() && !target.delete()) {
                throw IOException("无法删除旧的 ${target.name}")
            }
            if (!tmp.renameTo(target)) throw IOException("无法把释放结果改名为 ${target.name}")
            Log.i(TAG, "中文词典已释放：${target.absolutePath} (${target.length()} B)")
            target
        } catch (e: Exception) {
            Log.w(TAG, "释放中文词典失败", e)
            // ⚠️ tmp 清理必须在 finally：截断包会抛异常跳过正常清理路径，
            //   而残留的 .tmp 会被下一次 ensureInstalled 当成半成品反复重试。
            tmp.delete()
            null
        }
    }

    /** 库里是否已就绪（**会触发释放**，因此只能在 IO 线程调） */
    suspend fun isInstalled(context: Context): Boolean =
        ensureInstalled(context) != null

    /**
     * 查一个字。
     *
     * @param ch 单个汉字；非汉字直接返回空列表（不抛异常）
     * @return 该字的**全部读音**对应释义（多音字会返回多条，按拼音升序）。
     *   未收录返回空列表。
     */
    suspend fun lookup(ch: Char): List<ChinEntry> = withContext(Dispatchers.IO) {
        if (!isCjk(ch)) return@withContext emptyList()
        val db = openDb(appContextOrNull()) ?: return@withContext emptyList()
        try {
            db.rawQuery(
                "SELECT pinyin, explanation FROM word WHERE word = ? ORDER BY pinyin ASC",
                arrayOf(ch.toString())
            ).use { c ->
                val out = ArrayList<ChinEntry>()
                while (c.moveToNext()) {
                    val raw = c.getString(1) ?: ""
                    out.add(
                        ChinEntry(
                            word = ch.toString(),
                            pinyin = c.getString(0) ?: "",
                            // 释义靠**空行**分条（源数据如此），不是靠单换行；
                            // 单换行是排版换行，混进来会让释义读起来断断续续。
                            definitions = raw.split("\n\n")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            // 与 DictionaryManager.lookup 一致：吞掉异常返回空，
            // 绝不让一次查字把阅读页搞崩。
            Log.w(TAG, "查字典失败：$ch", e)
            emptyList()
        }
    }

    /**
     * 从一段选区里取出「唯一的一个汉字」。
     *
     * 选区可能带前后空白或标点（如长按后手柄微微越界），所以不能直接取首字符。
     *
     * @return 选区恰好是一个汉字时返回它；否则返回 null
     *   （**0 个**、**2 个及以上**都返回 null ——「查字典」只对单字有意义）
     */
    fun singleCjkOf(selection: CharSequence): Char? {
        // ⚠️ 不要用 `selection.filter { ... }.size`：
        //   CharSequence 不是 Iterable，`filter` 走的是 String 重载（要先 toString），
        //   在 Kotlin 1.9.20 下解析出的返回类型不带 `size`（编译报 Unresolved reference）。
        // 显式循环最直白，也不产生中间字符串（选区可能很长）。
        var found: Char? = null
        for (c in selection) {
            if (c.isWhitespace() || c.isPunctuationChar()) continue
            // 第二个「有效」字符即判定失败 —— 直接返回，不必数到底
            if (found != null) return null
            found = c
        }
        val c = found ?: return null
        return if (isCjk(c)) c else null
    }

    /**
     * 选区里是否有**恰好一个**汉字 —— 用来决定「查字典」菜单项是否显示。
     * 内部再取一次字，避免调用方拿到 null 后还要判断。
     */
    fun hasSingleCjk(selection: CharSequence): Boolean = singleCjkOf(selection) != null

    /**
     * 标点集合。
     *
     * ⚠️ 用 `\uXXXX` 转义而不是直接把引号写进 `Char` 字面量：
     * 弯引号 `“ ” ‘ ’` 在源码里是**非 ASCII**，容易在某次编辑/转码中被悄悄改掉，
     * 而这里漏掉一个标点会让「选中了『中。』」也算成单字。
     */
    private val PUNCTUATION = charArrayOf(
        '，', '。', '！', '？', '、', '；', '：',
        '\u201c', '\u201d', '\u2018', '\u2019',
        '（', '）', '《', '》', '【', '】', '「', '」', '『', '』',
        '…', '—', '～', '·',
        ',', '.', '!', '?', ';', ':', '\'', '"', '(', ')', '[', ']', '{', '}',
        '<', '>', '@', '#', '$', '%', '^', '&', '*', '_', '+', '=', '|', '/', '\\', '`', '~'
    )

    private fun Char.isPunctuationChar(): Boolean = this in PUNCTUATION

    private fun dbFile(context: Context): File =
        File(File(context.filesDir, "chindict"), DB_FILE_NAME)

    private fun appContextOrNull(): Context? = appContext

    private fun openDb(context: Context?): SQLiteDatabase? {
        cachedDb?.let { if (it.isOpen) return it }
        val ctx = context ?: return null
        val f = dbFile(ctx)
        if (!f.isFile) return null
        return try {
            SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .also { cachedDb = it }
        } catch (e: Exception) {
            Log.w(TAG, "打开中文词典失败", e)
            null
        }
    }

    private fun closeDb() {
        cachedDb?.let {
            try { it.close() } catch (_: Exception) {}
        }
        cachedDb = null
    }

    /** SQLite 文件头：`SQLite format 3\0` */
    private fun looksLikeSqlite(f: File): Boolean = try {
        f.inputStream().use { input ->
            val head = ByteArray(16)
            if (input.read(head) != 16) return false
            String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 进程级 Context：assets 读取需要它，但 [lookup] 的签名里不想要一个 Context 参数。
     * 沿用 DictionaryManager 的做法——由 Application 启动时调一次 [init]。
     *
     * ⚠️ **刻意用 `var x: Context? = null` 而不是 `lateinit var`**：
     * object 上的 `lateinit` 配`x.isInitialized` 在 Kotlin 1.9.20 会解析到
     * `KProperty0.isInitialized`（属性引用那个重载）而报 receiver type mismatch。
     * 可空字段没有这个歧义，判空也更直白。
     */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }
}