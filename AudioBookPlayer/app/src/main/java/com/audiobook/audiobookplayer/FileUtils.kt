package com.audiobook.audiobookplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import okhttp3.ResponseBody

/**
 * 文件名 / 路径安全工具（防路径穿越）。
 * BookViewModel.importEbook / importEbookFromFile 与 BookStoreViewModel.downloadBook 共用。
 */
object FileUtils {

    /** 文件名长度上限：超过后 ext4 的 255 字节限制会让写入直接失败 */
    private const val MAX_NAME_LENGTH = 120

    /**
     * 清洗文件名，防止路径穿越：
     * 1) 先把路径分隔符（/ \）与连续点（含 ..）替换为下划线；
     * 2) 再用**黑名单**替换真正危险的字符；
     * 3) 去首尾空白与多余点；
     * 4) 截断到 [MAX_NAME_LENGTH]；
     * 5) 空名兜底为 "book"。
     *
     * **为什么用黑名单而不是白名单**：
     * 白名单 `[^a-zA-Z0-9\u4e00-\u9fff_\-. ]` 会把所有「非英文非中文」
     * 的字符统统抹掉。后果是三本完全不同的书
     *   `한국어책.epub`（韩文）/ `Книга.epub`（俄文）/ `كتاب.epub`（阿拉伯文）
     * 被清洗成**同一个** `epub`，后导入的直接覆盖先导入的。
     *
     * 黑名单只处理真正会造成问题的字符：
     *  - 路径分隔符（/ \，第 1 步已处理）；
     *  - Windows 保留字符 `:*?"<>|`（跨端同步/导出时会出问题，且服务端同样拒绝）；
     *  - C0/C1 控制字符（`\u0000-\u001F`、`\u007F-\u009F`）——这类字符肉眼不可见，
     *    会让两个看起来一样的文件名实际不同，排查时极其费时。
     * 其余字符（韩文 / 西里尔 / 阿拉伯文 / 日文 / 中文标点等）一律原样保留。
     */
    fun sanitizeFileName(name: String): String {
        var cleaned = name
            .replace(Regex("[/\\\\]"), "_")
            .replace(Regex("\\.{2,}"), "_")
        // 控制字符 + Windows 保留字符；其余一律保留
        cleaned = cleaned.replace(Regex("[\\u0000-\\u001F\\u007F-\\u009F:*?\"<>|]"), "_")
        cleaned = cleaned.trim().trim('.')
        if (cleaned.length > MAX_NAME_LENGTH) {
            cleaned = cleaned.take(MAX_NAME_LENGTH)
        }
        // 截断后可能又露出尾部的点/空白，再收一次尾
        cleaned = cleaned.trim().trim('.')
        if (cleaned.isBlank()) cleaned = "book"
        return cleaned
    }

    /**
     * 服务端给书城文件加的重名前缀：`<32 位小写 hex>_`。
     * 见 `AdminService.uploadBook` —— 为了防重名与防目录穿越，落盘名一律是
     * `<随机码>_<书名>.<ext>`，界面要展示的书名必须先把这段随机码摘掉。
     */
    private val UPLOAD_UUID_PREFIX = Regex("^[0-9a-f]{32}_")

    /** 去掉服务端落盘名的 `<32 位随机码>_` 前缀；本来就没有前缀时原样返回。 */
    fun stripUploadUuidPrefix(name: String): String = name.replaceFirst(UPLOAD_UUID_PREFIX, "")

    /**
     * `BookStorage.TABLE_NAME_PATTERN` 的禁用字符：双引号 / 单引号 / 分号 / 反斜杠 / 圆括号 / 方括号。
     * 这几类字符能逃逸引号或终结 SQL 语句，校验不通过。
     */
    private val BOOK_NAME_UNSAFE_CHARS = Regex("[\"';\\\\()\\[\\]]")

    /**
     * 书名清洗：在 [sanitizeFileName] 之上再中和会触发 `TABLE_NAME_PATTERN` 拒绝的字符。
     *
     * **为什么必须单独一步**：书架条目的 display_name 同时被当作「书籍动态内容表」的表名，
     * 打开书时 `BookStorage.validateTableName` 会按该正则校验，命中即抛异常、书打不开
     * （与历史上「中文书名被 ASCII 正则挡掉、点开是白屏」是同一类故障）。
     *
     * 服务端文件名走的是**白名单**清洗（只留 `a-zA-Z0-9中文_.- `），天然安全；
     * 而书名是管理员手填的自由文本，《书名（卷一）》这种带括号的标题就会踩中，
     * 故凡是「书名将当作标识用」的地方都必须过这里。
     */
    fun sanitizeBookName(name: String): String =
        sanitizeFileName(name.replace(BOOK_NAME_UNSAFE_CHARS, "_"))

    /**
     * 书城书籍在本机的书名 —— 它同时是书架的 display_name、动态内容表名与
     * `audio_segments.book_name`（三者必须同源，否则音频/收藏关联不上）。
     *
     * 服务端落盘名是 `<32 位随机码>_<书名>.<ext>`，直接拿它当书名会把随机码带进书架；
     * 书名才是用户认得的标识。
     *
     * @param title         书城条目上的书名；为空时退回文件名（并去掉随机码前缀）
     * @param fileName      服务端落盘文件名，仅用于兜底书名与推断扩展名
     * @param fileExtension 服务端记录的扩展名；为空时从 [fileName] 推断
     */
    fun storeBookLocalName(title: String?, fileName: String, fileExtension: String?): String {
        val ext = fileExtension?.trim()?.takeIf { it.isNotEmpty() }
            ?: fileName.substringAfterLast('.', "")
        val base = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: stripUploadUuidPrefix(fileName.substringBeforeLast('.', fileName))
        val safeBase = sanitizeBookName(base)
        return if (ext.isBlank()) safeBase else "$safeBase.${ext.lowercase()}"
    }

    /**
     * 在 parent 目录下构造子文件：先清洗文件名，再断言其规范化路径仍位于 parent 内。
     * 若（极少见地）清洗后仍逃逸 parent，抛出 SecurityException，由调用方拒绝写入。
     */
    fun safeChildFile(parent: File, fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        val child = File(parent, safeName)

        // 使用 canonicalPath 替代 toPath()，兼容 API 21+
        val parentCanonical = parent.canonicalFile.normalize()
        val childCanonical = child.canonicalFile.normalize()

        // 检查子文件路径是否以父目录路径开头
        val parentPath = parentCanonical.absolutePath
        val childPath = childCanonical.absolutePath

        if (!childPath.startsWith(parentPath + File.separator) && childPath != parentPath) {
            throw SecurityException("非法的文件路径，疑似路径穿越: $fileName")
        }
        return child
    }

    /**
     * 把字节数格式化为人类可读文案：>=1MB 保留一位小数的 MB，>=1KB 取整的 KB，其余 B。
     * 原为 ReferenceAudioFragment / ReferenceAudioAdapter / SharedReferenceAdapter
     * 三份逐字相同的私有实现，统一收敛到此处。
     */
    fun formatSize(bytes: Long): String {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        }
        if (bytes >= 1024) {
            return String.format("%.0f KB", bytes / 1024.0)
        }
        return "$bytes B"
    }
}

/**
 * 响应体落盘：流式拷贝并在 finally 关闭输入/输出流，返回写入字节数（便于调用方做大小校验）。
 * 替代各处重复的 `body.byteStream().use { input -> FileOutputStream(f).use { output -> input.copyTo(output) } }` 样板。
 */
fun ResponseBody.saveTo(target: File): Long {
    byteStream().use { input -> target.outputStream().use { output -> return input.copyTo(output) } }
}

/**
 * 输入流落盘：流式拷贝并在 finally 关闭输入/输出流，返回写入字节数。
 * 替代 `inputStream.use { input -> FileOutputStream(f).use { output -> input.copyTo(output) } }` 样板。
 */
fun InputStream.saveTo(target: File): Long {
    this.use { input -> target.outputStream().use { output -> return input.copyTo(output) } }
}

/**
 * 导入单个文件的大小上限：200MB。
 *
 * 电子书（epub/txt/pdf/mobi）超过这个量级基本不是正常书籍，而是用户误选了大文件，
 * 或 content provider 返回了一个无限流。不设上限时拷贝会一直写到内部存储塞满，
 * 直到抛 IOException 为止——而那时整个手机的存储已经被拖垮，其它应用也跟着异常。
 */
const val MAX_IMPORT_BYTES = 200L * 1024 * 1024

/**
 * 带大小上限的落盘。
 *
 * 与 [saveTo] 有三点区别，都是为了解决「导入失败留下残file」：
 *
 * 1. **边拷边计数**：超过 [limitBytes] 立即抛异常中止，不会把存储写满；
 * 2. **先写临时文件**：拷贝到同目录下的 `.part` 临时文件，成功后才原子 rename 到 [target]，
 *    中途任何失败都不会污染 [target]；
 * 3. **finally 清理**：无论成败都删掉临时文件，磁盘上不留垃圾。
 *
 * 为什么必须先写临时文件：导入的目标路径由书名决定（见 `BookViewModel.importEbook`），
 * 直接写 [target] 时，若拷贝到一半失败（存储满、流中断），就会在「已导入的同名书」
 * 的位置留下一个截断的坏文件——用户书架里原本完好的那本书也跟着坏了。
 *
 * @throws IOException 超过大小上限、目录不可写、或 rename 失败
 */
fun InputStream.saveToWithLimit(target: File, limitBytes: Long = MAX_IMPORT_BYTES): Long {
    val parent = target.parentFile
        ?: throw IOException("目标文件没有父目录: $target")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("无法创建目录: $parent")
    }
    val temp = File.createTempFile("${target.name}.", ".part", parent)
    var written = 0L
    try {
        temp.outputStream().use { out ->
            this.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    written += read
                    if (written > limitBytes) {
                        throw IOException("文件过大，已超过 ${limitBytes / 1024 / 1024}MB 上限")
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
        // Android（Linux）上 rename 会覆盖同名目标文件；失败时抛异常由 finally 清理临时文件
        if (!temp.renameTo(target)) {
            throw IOException("无法把临时文件改名为目标文件: ${target.name}")
        }
        return written
    } finally {
        if (temp.exists()) {
            temp.delete()
        }
    }
}

/**
 * 从 Uri 解析展示文件名：
 * 1) content:// 通过 ContentResolver 读取 DISPLAY_NAME（部分 provider 无此列，
 *    getColumnIndex 返回 -1，需校验后再取字符串）；
 * 2) 取不到时回退到 path 末段；
 * 3) 仍取不到返回 null，由调用方决定兜底文案（如「未知文件」）。
 * 替代 BookViewModel / TtsFragment / TtsActivity 中三份重复的 getFileName 实现。
 */
fun Uri.getFileName(context: Context): String? {
    var name: String? = null
    if (scheme == "content") {
        context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = cursor.getString(index)
                }
            }
        }
    }
    if (name == null) {
        name = path
        val cut = name?.lastIndexOf('/')
        if (cut != null && cut != -1) {
            name = name?.substring(cut + 1)
        }
    }
    return name
}
