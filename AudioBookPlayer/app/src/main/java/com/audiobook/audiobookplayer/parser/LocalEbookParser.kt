package com.audiobook.audiobookplayer.parser

import android.util.Log
import com.audiobook.audiobookplayer.model.TocEntry
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 本地电子书解析统一入口。
 *
 * 支持的格式：
 * - TXT：本地直接读取
 * - EPUB：通过 [EpubParser] 解析
 * - MOBI：通过 [MobiParser] 解析（服务端 `extractMobi` 的 1:1 移植，纯自研算法、无第三方库，
 *   因此本地结果与服务端**逐字符一致**；不像 PDF 那样受库版本漂移影响）
 * - PDF：**不在这里解析**。PDF 走独立的 [PdfPreviewParser]，因为它产出的只是
 *   「降级预览」（本地 PDFium 1.0.31，与服务端 PDFBox 3.0.2 字符级差异为 0），
 *   且行切分口径必须与服务端 `splitRowsWithStarts` 一致，与本类的段落切分不同。
 *   调用方见 `BookViewModel.readPdfRowsTwoPhase`。
 *
 * 段落切分由 [ParagraphSplitter] 完成，句末标点规则与服务端一致；但**粒度仍然不同**：
 * 本地对超长句会在 100 字处硬切，而服务端的音频片段就是**完整句子、长度不限**
 * （服务端那 30 字不是片段上限，是「送 TTS 引擎的单次文本上限」，超长句被拆成小片合成后
 * 又拼回一条音频，落库的片段仍是整句）。因此本地段落边界与服务端音频片段边界
 * 依然**不一一对应** —— 二者靠 [EbookSegmentMap] 按文本内容（忽略空白）定位建立映射，
 * 不依赖下标对齐。别为了「对齐」去动这里的上限：改成 30 会把整句打成碎片，
 * 改成无限又会把整页并成一段，两种都会让映射变差。
 */
object LocalEbookParser {

    private const val TAG = "LocalEbookParser"

    /**
     * 解析结果
     * @param paragraphs 段落列表（阅读显示粒度，与音频片段按内容映射，不按下标对齐）
     * @param toc        目录条目列表（`paragraphIndex` 为本地段落下标）
     */
    data class Result(
        val paragraphs: List<String>,
        val toc: List<TocEntry>
    )

    /**
     * 根据文件扩展名选择解析策略。
     *
     * @param file 电子书文件
     * @return 解析结果（段落 + 目录）
     * @throws UnsupportedOperationException 该格式暂不支持本地解析（PDF）
     * @throws Exception                    文件读取或解析失败
     */
    fun parse(file: File): Result {
        val extension = file.extension.lowercase()
        Log.d(TAG, "本地解析电子书: ${file.name}, 格式: $extension")

        return when (extension) {
            "txt" -> parseTxt(file)
            "md" -> parseMarkdown(file)
            "epub" -> {
                val epubResult = EpubParser.parse(file)
                Result(epubResult.paragraphs, epubResult.toc)
            }
            "mobi" -> {
                val mobiResult = MobiParser.parse(file)
                Result(mobiResult.rows, mobiResult.toc)
            }
            "pdf" -> {
                throw UnsupportedOperationException("$extension 格式暂不支持本地解析，请使用服务端解析")
            }
            else -> {
                throw UnsupportedOperationException("不支持的电子书格式: $extension")
            }
        }
    }

    /**
     * 解析 Markdown 文件。
     *
     * Plan A：与服务端 [com.audiobook.server.service.BookTextExtractor] 同一口径，先把 Markdown
     * 「剥壳」成纯文本（去掉 # / ** / * / ` / 链接 / 图片 / 引用 / 列表 / 代码块 / 表格 / 水平线 /
     * HTML 标签），再交给 [ParagraphSplitter] 切行 —— 阅读页看到的是干净正文、本地合成也不会把
     * "# 标题"、"**粗体**" 念出来。
     *
     * 编码识别复用 [readTextWithEncodingFallback]（MD 多为 UTF-8，但中文环境偶有 GBK 导出）。
     * 切行模式与 TXT 一致（lineBreakAsParagraph = true）：Markdown 的换行即作者排版意图，
     * 不能抹平，否则阅读页丢段首缩进、整本挤成几大块。
     */
    private fun parseMarkdown(file: File): Result {
        val raw = readTextWithEncodingFallback(file)
        val plain = stripMarkdown(raw)
        val paragraphs = ParagraphSplitter(lineBreakAsParagraph = true).apply { feed(plain) }.finish()
        Log.d(TAG, "MD 解析完成(已剥壳为纯文本): ${paragraphs.size} 段落")
        // 本地不做目录（与 TXT 一致）；服务端解析 md 时会额外导出标题为目录
        return Result(paragraphs, emptyList())
    }

    /**
     * Markdown → 纯文本（与服务端 convertMarkdown 同口径，仅去掉语法、保留换行与缩进）。
     *
     * 顺序：围栏代码块（```/~~~，块内原文保留）→ 标题 → 图片/链接/参考链接/自动链接 →
     * 引用块与列表标记 → 水平线/表格分隔行整行丢弃 → 行内代码 → 粗体/斜体/删除线
     * （成对才去标记，避免误伤 `2 * 3` 与 `some_var_name`）→ 表格数据行的 `|` 换空格 → HTML 标签。
     */
    private fun stripMarkdown(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder()
        var inCode = false
        for (rawLine in normalized.split("\n")) {
            val trimmed = rawLine.trim()
            val conv = if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inCode = !inCode
                ""
            } else if (inCode) {
                rawLine // 代码块内容原样保留
            } else {
                val heading = MD_HEADING.matchEntire(trimmed)
                if (heading != null) {
                    convertMdInline(heading.groupValues[2].trim())
                } else {
                    convertMdInline(rawLine)
                }
            }
            out.append(conv).append('\n')
        }
        return out.toString()
    }

    private fun convertMdInline(line: String): String {
        var s = line
        s = MD_IMAGE.replace(s) { m -> m.groupValues[1] }      // 图片 → 替代文字
        s = MD_LINK.replace(s) { m -> m.groupValues[1] }        // 链接 → 文字
        s = MD_REF_LINK.replace(s) { m -> m.groupValues[1] }    // 参考式链接 → 文字
        s = MD_AUTOLINK.replace(s) { m -> m.groupValues[1] }    // 自动链接 → 地址
        s = MD_BLOCKQUOTE.replace(s) { "" }                    // 引用块
        s = MD_LIST_MARKER.replace(s) { m -> m.groupValues[1] } // 列表标记（保留前导缩进）
        if (MD_HR.matches(s)) return ""
        if (MD_TABLE_SEP.matches(s)) return ""
        s = MD_INLINE_CODE.replace(s) { m -> m.groupValues[1] } // 行内代码
        s = MD_BOLD3.replace(s) { m -> m.groupValues[1] }
        s = MD_BOLD.replace(s) { m -> m.groupValues[1] }
        s = MD_ITALIC.replace(s) { m -> m.groupValues[1] }      // 成对才去，避免误伤 2 * 3
        s = MD_BOLD_U3.replace(s) { m -> m.groupValues[1] }
        s = MD_BOLD_U.replace(s) { m -> m.groupValues[1] }      // 仅去 __，保留 some_var_name
        s = MD_STRIKE.replace(s) { m -> m.groupValues[1] }
        if (s.contains('|')) s = s.replace('|', ' ')
        s = MD_HTML_TAG.replace(s) { "" }
        return s
    }

    // ---------- Markdown 语法正则（与服务端 BookTextExtractor 同口径）----------
    private val MD_HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val MD_IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
    private val MD_LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val MD_REF_LINK = Regex("""\[([^\]]*)\]\[[^]]*\]""")
    private val MD_AUTOLINK = Regex("""<((?:https?://|mailto:)[^>]+)>""")
    private val MD_INLINE_CODE = Regex("""`([^`\n]+)`""")
    private val MD_BOLD3 = Regex("""\*\*\*([^*\n]+?)\*\*\*""")
    private val MD_BOLD = Regex("""\*\*([^*\n]+?)\*\*""")
    private val MD_ITALIC = Regex("""\*([^*\n]+?)\*""")
    private val MD_BOLD_U3 = Regex("""___([^_\n]+?)___""")
    private val MD_BOLD_U = Regex("""__([^_\n]+?)__""")
    private val MD_STRIKE = Regex("""~~([^~\n]+?)~~""")
    private val MD_BLOCKQUOTE = Regex("""^>\s?""")
    private val MD_LIST_MARKER = Regex("""^(\s*)(?:[-*+]|\d+[.)])\s+""")
    private val MD_HR = Regex("""^(\s*)([-*_])(\s*\2){2,}\s*$""")
    private val MD_TABLE_SEP = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")
    private val MD_HTML_TAG = Regex("""<[^>]+>""")

    /**
     * 解析 TXT 文件。
     * 读取全文（自动识别编码），按与后端一致的规则切分段落（TXT 不含目录信息）。
     */
    private fun parseTxt(file: File): Result {
        val text = readTextWithEncodingFallback(file)
        // v5/A6：与阅读页同口径（BookViewModel 的 TXT 路径传的就是 true）。
        // 原实现用默认 false，于是硬折行 TXT 在「本地合成」与「阅读页」切出的段落边界不同，
        // 段落下标一错，高亮/段号映射就对不上。
        // 代价：段数会变多（缩进行、硬折行都成了独立段落），合成调用次数随之上升 —— 这是
        // 正确性优先的取舍；若要压段数，应同步改阅读页口径，不能只改一边。
        val paragraphs = ParagraphSplitter(lineBreakAsParagraph = true).apply { feed(text) }.finish()
        Log.d(TAG, "TXT 解析完成: ${paragraphs.size} 段落")
        return Result(paragraphs, emptyList())
    }

    /**
     * 读取 TXT 全文并自动识别编码。
     *
     * 原先直接 file.readText()，固定按 UTF-8 解码：中文电子书大量是 GBK/GB18030
     * 编码，按 UTF-8 解不会抛异常（无效字节被替换成 U+FFFD），用户只会看到整篇乱码。
     *
     * 策略：
     * 1) 先按 UTF-8 严格解码，非法序列直接 REPORT（而不是静默替换）；
     * 2) 失败则回退 GB18030（向下兼容 GBK / GB2312）；
     * 3) 去掉 UTF-8 BOM，否则 BOM 会作为第一个字符混进第一段正文。
     */
    private fun readTextWithEncodingFallback(file: File): String {
        // 仅读前 64KB 探测编码，整文件用检测到的编码流式读取：先 readBytes() 把整本书
        // 一次性读进堆（20MB TXT 峰值约 100MB）再二次 decode，会导致大文件 OOM
        val probeSize = 64 * 1024
        val prefix = ByteArray(probeSize)
        var total = 0
        file.inputStream().buffered().use { st ->
            while (total < probeSize) {
                val r = st.read(prefix, total, probeSize - total)
                if (r < 0) break
                total += r
            }
        }
        val hasUtf8Bom = total >= 3 &&
            prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()
        val probeOffset = if (hasUtf8Bom) 3 else 0

        // 先按 UTF-8 严格解码前 64KB：非法字节序列直接报错（而非静默替换为 U+FFFD），
        // 据此判断文件到底是不是 UTF-8
        val isUtf8 = try {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(prefix, probeOffset, total - probeOffset))
            true
        } catch (_: CharacterCodingException) {
            false
        }

        val charset = if (isUtf8) Charset.forName("UTF-8") else Charset.forName("GB18030")
        Log.d(TAG, "TXT 编码识别: ${charset.name()}")
        return file.inputStream().bufferedReader(charset).use { it.readText() }
    }
}
