package com.audiobook.audiobookplayer.parser

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File

/**
 * PDF **降级预览**解析器（客户端本地，不依赖服务端）。
 *
 * ## 定位：这不是权威解析
 *
 * 本类用 **PDFium**（Chromium 的 PDF 引擎，`io.legere:pdfiumandroid`）解析，
 * 服务端用 **PDFBox 3.0.2**。两者实测**字符级输出完全一致**（见下），
 * 但仍坚持「本地只做预览」的架构：服务端是唯一权威，正文 / `EbookSegmentMap` / 目录
 * 三样必须由服务端结果**一起**覆盖，只换其中一样会静默失准。
 *
 * ## 为什么从 PDFBox-Android 换成 PDFium（2026-10-12）
 *
 * 之前用的是 `com.tom-roush:pdfbox-android:2.0.27.0`（Android 上没有 PDFBox 3.x），
 * 代价是**真实丢字**：跨主版本在「字体子集缺 ToUnicode CMap」时行为不同，
 * 3.0 扫系统字体兜底而 2.0 直接丢。实测《潜规则》145 页少 **127 个「最」字**
 * ⇒ 约 1.14% / 2.07% 的音频段在 [EbookSegmentMap] 里定位失败（点读不上、高亮错位）。
 *
 * 换成 PDFium 后实测（同一批生产书，过**完全相同**的服务端后处理）：
 *
 * | 指标 | PDFBox 2.0.27 | PDFium | 服务端 PDFBox 3.0.2 |
 * |---|---|---|---|
 * | a.pdf 与服务端字符级差异块 | 127 | **0** | — |
 * | b.pdf 与服务端字符级差异块 | 320 | **0** | — |
 * | 段定位失败（task 7 / task 6） | 1.14% / 2.07% | **0 / 0** | 0 |
 * | 跨引擎版本一致性 | — | PDFium 126 与 156 **逐字节一致** | — |
 *
 * 关键因果（决定这个结论**能否外推到 Android**）：PDFium 不丢字**不是**因为扫系统字体，
 * 而是**正确解析了内嵌 ToUnicode CMap**。实测 27 个中文 Type0 字体全部自带内嵌 CMap，
 * 「最」是标准映射 `<1cd4> <6700>`；而 pypdfium2 **默认不注册系统字体**，
 * 未注册仍输出完整的 151 个「最」⇒ 与宿主平台无关。
 *
 * ## 为什么不干脆全走服务端
 *
 * 服务端解析要上传整本。实测同一本 1.4MB 的 PDF 连开三次 2.53s / 1.94s / 2.22s，
 * 网络一差就是几十秒（限速 57.8KB/s 时 100 秒都出不来）。本地解析把这个等待去掉。
 *
 * ## 为什么后处理要逐行照抄服务端
 *
 * 服务端 `BookTextExtractor.stripPdfPages` 在提取后做了三处**就地**清洗：
 * [collapseDotLeaders]（折目录点线）、[dropRunningMargins]（删页眉页脚）与
 * [reflowPdfPages]（视觉行并回自然段）。
 * 这三处与「页起点累计」`pdfPageStartOffsets` 严丝合缝——服务端注释里记着一桩
 * 只清洗正文不清洗页文本、导致页起点与正文分叉、目录段落下标系统性偏移的事故。
 *
 * 本类同样把清洗施加在**页文本**上（而不是拼好的全文），保持同源。
 * 阈值等常量与服务端一一对应，改动必须同步两边，否则段起点会漂。
 */
object PdfPreviewParser {

    private const val TAG = "PdfPreviewParser"

    /** 目录点线：连续 4 个及以上 ASCII 点（3 个点的英文省略号是有意义标点，须避开） */
    private val DOT_LEADER = Regex("\\.{4,}")

    /** 页码行归一化用：`- 12 -` 与 `- 47 -` 归一后同形，才能被认出是同一行 */
    private val DIGIT_RUN = Regex("\\d+")

    /** 判定页眉/页脚所需的最少页数：页数太少时「在多页上重复」没有统计意义 */
    private const val MARGIN_MIN_PAGES = 8

    /** 该行至少要出现在这个比例的页面上，才判定为页眉/页脚 */
    private const val MARGIN_MIN_PAGE_RATIO = 0.6

    /** 只在每页前/后这么多非空行内查找（正文里偶尔重复的行不会被误删） */
    private const val MARGIN_MAX_LINES = 3

    /** 逐页进度的上报间隔（页）。见 [parse] 的 onPageProgress 说明 */
    private const val PROGRESS_PAGES = 16

    /**
     * 解析 PDF，返回可显示的行与目录。
     *
     * @param onPageProgress 逐页进度回调 `(已解析页数, 总页数)`。**只在解析期间**被调用，
     *        用于给用户一个「还在动」的反馈 —— 大部头 PDF（实测国富论 1281 页、234 万字）
     *        本机提取要好几秒，没有进度条用户只会看到一片白，以为卡死了。
     *        回调在**解析线程**上执行（调用方已放在 `Dispatchers.Default`），
     *        所以实现里不要碰 View，只 post。
     * @return 行列表 + 目录；解析不出文本时抛异常（调用方回退服务端）
     */
    fun parse(
        context: Context,
        file: File,
        onPageProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): LocalEbookParser.Result {
        val pages = extractPageTexts(context, file, onPageProgress)
        Log.d(TAG, "PDF 本地预览解析完成: ${file.name}, ${pages.size} 页")
        if (pages.all { it.isBlank() }) {
            throw IllegalStateException("PDF 未提取到文本（可能是扫描件或图片型 PDF）")
        }
        // 拼接规则与服务端 joinPdfPages 一致：非空页各追加一个 '\n'，最后整体 trim。
        // 与服务端一致才谈得上"同源"。
        val text = pages.joinToString("") { if (it.isNotEmpty()) "$it\n" else "" }.trim()
        if (text.isEmpty()) {
            throw IllegalStateException("PDF 未提取到任何文本内容")
        }
        // 行切分必须用服务端 splitRowsWithStarts 的口径（只裁行尾、保留行首缩进），
        // 不能用 ParagraphSplitter —— 那是切段落的，会把一个自然段并成一行，
        // 与服务端返回的 rows 粒度不同，进而在 EbookSegmentMap 里错位。
        return LocalEbookParser.Result(splitRows(text), emptyList())
    }

    /**
     * PDFium 引擎实例，**全进程复用**。
     *
     * [PdfiumCore] 的构造里会做 `System.loadLibrary("pdfium")` + `isReady.waitForReady()`，
     * 首次调用才真正加载 `.so`（后续只是等一个已就绪的锁）。但每次解析都新建一个实例
     * 仍要重复走一遍 `Logger.setLogger` 与 dpi 读取，且让「引擎何时加载」变得不可预期。
     * 这里缓存单例：`.so` 加载仍发生在**首次真正解析 PDF 时**（不在冷启动路径）。
     *
     * 解析本身是 CPU 密集且已放在 `Dispatchers.Default` 上（见 `BookViewModel`），
     * 而 `PdfDocument` 的 native 调用内部有 `synchronized(PdfiumCore.lock)` 串行化，
     * 所以这里**不需要**额外的锁。
     */
    @Volatile
    private var coreInstance: PdfiumCore? = null

    private fun pdfiumCore(context: Context): PdfiumCore {
        coreInstance?.let { return it }
        return synchronized(this) {
            coreInstance ?: PdfiumCore(context.applicationContext).also {
                coreInstance = it
                Log.d(TAG, "PDFium 引擎已加载 (libpdfium.so / libpdfiumandroid.so)")
            }
        }
    }

    /**
     * 逐页提取文本并就地清洗。
     *
     * 逐页取是**刻意的**：服务端就是这样逐页取的（`setStartPage/setEndPage`），
     * 两边页边界一致才能让页起点累计与目录映射同源。
     *
     * 用 [ParcelFileDescriptor] 让 PDFium 自己 mmap 打开，**不把整本读进 byteArray** ——
     * 大 PDF（几百页）一次性读入会造成内存峰值，这正是低端机 OOM 的来源。
     */
    private fun extractPageTexts(
        context: Context,
        file: File,
        onPageProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        val pageTexts = mutableListOf<String>()
        val core = pdfiumCore(context)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            core.newDocument(pfd).use { document ->
                // ⚠️ 必须写 `getPageCount()` 而不是 `pageCount`：`PdfDocument.getPageCount()`
                // 在 Kotlin 源里是**函数**（`fun getPageCount(): Int`），不是属性。
                // 属性语法 `document.pageCount` 只对 **Java** getter 成立，对 Kotlin 函数不成立
                // ⇒ 会报 `Unresolved reference: pageCount`。
                val pages = document.getPageCount()
                for (i in 0 until pages) {
                    document.openPage(i).use { page ->
                        document.openTextPage(page).use { textPage ->
                            val count = textPage.textPageCountChars()
                            val raw = if (count > 0) {
                                textPage.textPageGetText(0, count)
                            } else {
                                ""
                            }
                            // ⚠️ PDFium 的换行是 CRLF，服务端 PDFBox 是 LF。
                            // 详见 normalizeNewlines 的说明（**真正必需的是孤立 CR，
                            // CRLF 那一侧其实已被 stripTrailingJava 兜住**）。
                            pageTexts.add(normalizeNewlines(raw ?: ""))
                        }
                    }
                    // 进度按块上报（每 PROGRESS_PAGES 页一次 + 末页一次）：
                    // 1281 页逐页回调会产生 1281 次 LiveData post，纯属刷屏幕。
                    val done = i + 1
                    if (onPageProgress != null && (done % PROGRESS_PAGES == 0 || done == pages)) {
                        onPageProgress(done, pages)
                    }
                }
            }
        }
        collapseDotLeaders(pageTexts)
        dropRunningMargins(pageTexts)
        // 第三处清洗：把「视觉行」并回自然段。必须排在 dropRunningMargins **之后** ——
        // 那边靠「位于页面前/后若干行」判断页眉页脚，只有视觉行粒度才成立；
        // 先合并成自然段，一页可能只剩两三条「行」，页眉页码就再也认不出来了。
        reflowPdfPages(pageTexts)
        return pageTexts
    }

    // ---------- PDF 版面重排：视觉行 → 自然段 ----------
    //
    // ⚠️ 本节与服务端 BookTextExtractor.reflowPdfPageText 是**逐行同构**的两份实现，
    // 改任何一条阈值/判据都必须两边同时改。理由和风险见 PdfPreviewParser 类注释：
    // 客户端预览文本与服务端权威正文只要在一个字符上分叉，EbookSegmentMap 就会静默失准。

    /**
     * 单字母连串：`S O C I E T I E S` 这种「一个字母 + 一个空格」重复 **4 次及以上**。
     *
     * 门槛取 4 而不是 2：`A I`（人工智能）、`a b`（变量名）这类两三个字母的组合在正文里
     * 是合法写法，合并会毁掉语义；连续 4 个以上单字母几乎只可能来自「字距被当空格」。
     */
    private val SINGLE_LETTER_RUN = Regex("(?<![A-Za-z])(?:[A-Za-z] ){3,}[A-Za-z](?![A-Za-z])")

    /** 行内连续空白（≥2 个空格/制表符）压成一个 —— 两种引擎的空格宽度算法不同，压缩后才同源 */
    private val INNER_SPACES = Regex("[ \\t]{2,}")

    /**
     * 不可映射字符：`U+FFFE`（非字符）与 `U+FFFD`（替换符）。
     *
     * PDFium 在「行尾连字符的 ToUnicode 映射缺失」时会把断行吞掉、把一个 U+FFFE
     * 塞进词中间 —— 实测《曼昆经济学原理》得到 `gov\ufffeernment`、`per\ufffeson`。
     * 服务端 PDFBox 同一处输出的是 `gov-` + 换行 + `ernment`，两边**必须都归到
     * `government`**，否则预览与权威正文在第一个断词处就分叉。
     */
    private const val UNMAPPED = '\uFFFE'
    private const val REPLACEMENT = '\uFFFD'

    /** 软连字符：语义就是「此处可断行、连字符不显示」，直接删 */
    private const val SOFT_HYPHEN = '\u00AD'

    /**
     * 只由这些字符与空白组成的行 = 装饰/图片占位，整行丢弃。
     *
     * 实测封面出版社 logo 的位置被提取成 `■ ■ ■ ■ ■■■■■■`，阅读页显示成一排乱码。
     * 判据是「**整行**只剩这些」—— 正文里夹一个方块（字体缺字）不会触发。
     */
    private const val NOISE_CHARS = "■□▪▫◾◽●○◆◇★☆►◄▲▼\uFFFD\u25A0\u25A1\u25AA\u25AB"

    /** 版心宽度取行长分布的 80 分位（末行、缩进行、图注会把平均值拉低） */
    private const val WIDTH_PERCENTILE = 0.80

    /**
     * 「这一行是段末」的长度阈值（相对版心宽度）。
     *
     * 取 0.80 而不是 0.85：实测《Collapse》第 21 页首行 58 字符、版心 69，
     * 0.85 会把 58 判成短行、把一个跨页段落劈成两段；0.80 的阈值是 55.2，正确判为满行。
     */
    private const val SHORT_LINE_RATIO = 0.80

    /**
     * 「短行占比」达到这个值的页视为**非流式正文**（版权页、目录、索引、诗歌、表格），
     * 整页不做合并。那类页面每行都是独立条目、没有「满行」概念。
     */
    private const val LIST_PAGE_SHORT_RATIO = 0.40

    /** 行数太少的页面（封面、扉页）没有版心统计意义，整页原样保留 */
    private const val REFLOW_MIN_LINES = 5

    /** 全大写短行视为标题，不与正文合并 */
    private const val HEADING_MAX_LEN = 60

    /**
     * 整本逐页重排，就地改写 `pageTexts`。
     *
     * 语料必须**先全本建好再逐页重排**：字距串该不该拆成两个词，单看一页判不出来
     * （见 [collapseLetterSpacedRun]）。
     */
    private fun reflowPdfPages(pageTexts: MutableList<String>) {
        val corpus = pdfCorpus(pageTexts)
        for (i in pageTexts.indices) {
            pageTexts[i] = reflowPageText(pageTexts[i], corpus)
        }
    }

    /**
     * 一本书的文本语料：词表 + 相邻词组表。只服务于一个很窄的问题 ——
     * 某个字距串是一个词、还是两个词连排。不做拼写纠正、不参与别的判断。
     */
    private class PdfCorpus(val words: Set<String>, val adjacentPairs: Set<String>) {
        fun isWord(lowerWord: String) = words.contains(lowerWord)

        /** 两段是否在书里作为**相邻两个词**出现过（`of economics`） */
        fun areAdjacent(first: String, second: String) = adjacentPairs.contains("$first $second")

        companion object {
            val EMPTY = PdfCorpus(emptySet(), emptySet())
        }
    }

    /**
     * 建全书语料：词表（全小写、长度 ≥2）+ 相邻词组表。
     *
     * 必须在 [cleanPdfLine] **之前**取，否则「字距被当空格」的
     * `O F E C O N O M I C S` 已被折成 `OFECONOMICS`，用它自己证明自己该合并。
     *
     * **为什么单靠词表不够**：词表被断词残片污染（行尾 `build-` + 下行 `ing`
     * 让 `ing` 成了「词」），几乎任何长单词都能被切成两个「合法」组合 ——
     * 实测放开切分，`Obeying`→`Obey ing`、`Fleece`→`Flee ce`、
     * `MONTANA`→`MONT AN` 全被误拆。加上「两半必须相邻出现过」后区分度干净：
     * `of economics`(88) / `an economist`(39) / `common resources`(39) 全部命中，
     * 而 `obey ing` / `flee ce` / `mont an` 一次都没有。
     */
    private fun pdfCorpus(pageTexts: List<String>): PdfCorpus {
        if (pageTexts.isEmpty()) return PdfCorpus.EMPTY
        val words = HashSet<String>(1 shl 14)
        val pairs = HashSet<String>(1 shl 15)
        val wordPattern = Regex("[A-Za-z]+")
        for (page in pageTexts) {
            if (page.isEmpty()) continue
            for (line in expandSoftBreaks(page).split("\n")) {
                // 词组不跨行：跨行的「两半」不是真正的相邻词
                var previous: String? = null
                for (m in wordPattern.findAll(line)) {
                    val word = m.value.lowercase()
                    if (word.length < 2) {
                        previous = null
                        continue
                    }
                    words.add(word)
                    if (previous != null) pairs.add("$previous $word")
                    previous = word
                }
            }
        }
        return PdfCorpus(words, pairs)
    }

    /**
     * 把一页的**视觉行**并回**自然段**：PDF 没有段落概念，文本提取只按坐标换行，
     * 于是一页 60 行就是 60 个「段落」—— 阅读页每行之间都带段间距、看起来像一堆碎句。
     *
     * 判据只用**行长度**（相对版心），不用坐标：PDFium 与 PDFBox 的坐标系、精度都不同，
     * 任何坐标判据都会让两端产出不同文本；行长则来自文本本身，两端天然一致。
     *
     * 输入口径与 [splitRows] 完全一致：按 `\n` 切行、**只裁行尾**、丢弃空行。
     * 这一步是「两端同源」的关键 —— PDFium 输出 `"VIKING"`、PDFBox 输出 `"VIKING "`，
     * 不先裁行尾，行长差 1 就会让合并判据在两端得出不同结论。
     */
    private fun reflowPageText(pageText: String): String = reflowPageText(pageText, PdfCorpus.EMPTY)

    /**
     * @param corpus 全书语料（见 [pdfCorpus]）；[PdfCorpus.EMPTY] 表示「没有语料证据」，
     *               此时字距串按老规矩直接合并、不尝试插回词间空格
     */
    private fun reflowPageText(pageText: String, corpus: PdfCorpus): String {
        if (pageText.isEmpty()) return ""
        // 必须在切行**之前**做：PDFium 会把「行尾连字符 + 换行」吞成一个 U+FFFE 并
        // 把两行黏在同一行里，行长因此失真（实测一行 118 字符，而真实版心是 66）。
        val prepared = expandSoftBreaks(pageText)
        var lines: List<String> = prepared.split("\n").map { cleanPdfLine(it, corpus) }
            .filter { it.isNotEmpty() && !isNoiseLine(it) }
        // 断词续行先黏合，再做行长统计。顺序不能反：`gov-`+`ernment` 若留在统计里，
        // 会同时贡献一个 40 字符的「伪短行」和一个 16 字符的「伪短行」，
        // 把短行占比抬到列表页阈值以上 ⇒ 整页拒绝重排，PDF 又退回「每行一段」。
        lines = joinHyphenatedRows(lines)
        if (lines.size < REFLOW_MIN_LINES) return lines.joinToString("\n")

        val lengths = IntArray(lines.size) { lines[it].length }
        lengths.sort()
        val typical = lengths[(lengths.size * WIDTH_PERCENTILE).toInt()]
        if (typical <= 0) return lines.joinToString("\n")

        var shortCount = 0
        for (len in lengths) {
            if (len < typical * SHORT_LINE_RATIO) shortCount++
        }
        if (shortCount >= lines.size * LIST_PAGE_SHORT_RATIO) {
            // 列表/版权/目录/索引页：每行都是独立条目，合并只会把地址块并成一坨
            return lines.joinToString("\n")
        }

        val shortThreshold = typical * SHORT_LINE_RATIO
        val paras = ArrayList<String>()
        val cur = StringBuilder(lines[0])
        // ⚠️ 判据必须看「**最后并入的那一行**」的长度，不能看 cur 的总长度：
        // cur 一旦并入两行就必然超过阈值，此后永远不再断开 ⇒ 整页并成一段。
        var last = lines[0]
        for (i in 1 until lines.size) {
            val next = lines[i]
            var join = true
            if (isHeadingLine(last, typical) || isHeadingLine(next, typical)) {
                join = false
            } else if (last.length < shortThreshold) {
                // 上一行明显短于版心 ⇒ 它是段落末行，本行开始新段
                join = false
            }
            if (join) {
                if (needsSpaceBetween(cur, next)) {
                    cur.append(' ').append(next)
                } else {
                    cur.append(next)
                }
            } else {
                paras.add(cur.toString())
                cur.setLength(0)
                cur.append(next)
            }
            last = next
        }
        paras.add(cur.toString())
        return paras.joinToString("\n")
    }

    /**
     * 把 U+FFFE 还原成「行尾连字符 + 换行」，**必须在切行之前**做。
     *
     * PDFium 遇到「行尾连字符的 ToUnicode 映射缺失」时，不只丢字符，还会**把换行一起吞掉**
     * —— `gov-` + `\n` + `ernment` 变成同一行里的 `gov␞ernment`。两个后果：
     * ① 两个词黏成一个，要靠断词规则才能正确合并；
     * ② **行长失真**：实测某页出现一行 118 字符，而真实版心只有 66 ⇒ 80 分位被抬高，
     *    「短行=段末」判据随之偏移，与服务端（PDFBox 输出两行）的段落边界分叉。
     *
     * 还原成 `-` + 换行后，走的是与服务端**完全相同**的断词合并路径，两端逐字符一致。
     */
    private fun expandSoftBreaks(pageText: String): String {
        if (pageText.indexOf(UNMAPPED) < 0) return pageText
        val sb = StringBuilder(pageText.length + 64)
        for (i in pageText.indices) {
            val c = pageText[i]
            if (c == UNMAPPED) {
                val prev = if (i > 0) pageText[i - 1] else ' '
                val next = if (i + 1 < pageText.length) pageText[i + 1] else ' '
                if (Character.isLetter(prev) && Character.isLetter(next)) {
                    sb.append('-').append('\n')
                } else {
                    sb.append(' ')
                }
                continue
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 预合并断词续行：行尾 `-`（非 `--`）+ 下一行首小写 ⇒ 去掉连字符直接黏合。
     *
     * 必须独立于段落合并、且排在**行长统计之前**（理由见调用处注释）。
     */
    private fun joinHyphenatedRows(lines: List<String>): List<String> {
        if (lines.size < 2) return lines
        val out = ArrayList<String>(lines.size)
        val cur = StringBuilder(lines[0])
        for (i in 1 until lines.size) {
            val next = lines[i]
            if (isHyphenBreak(cur, next)) {
                cur.setLength(cur.length - 1)
                cur.append(next)
            } else {
                out.add(cur.toString())
                cur.setLength(0)
                cur.append(next)
            }
        }
        out.add(cur.toString())
        return out
    }

    /**
     * 行级清洗：断词连字符、词内空格、行内多空格。**保留行首缩进**（段落排版信息）。
     *
     * 行尾空白在此裁掉，与 [splitRows] 的 `stripTrailingJava()` 同口径。
     */
    private fun cleanPdfLine(raw: String): String = cleanPdfLine(raw, PdfCorpus.EMPTY)

    private fun cleanPdfLine(raw: String, corpus: PdfCorpus): String {
        var line = raw.stripTrailingJava()
        if (line.isEmpty()) return ""
        if (line.indexOf(UNMAPPED) >= 0 || line.indexOf(REPLACEMENT) >= 0) {
            val sb = StringBuilder(line.length)
            for (i in line.indices) {
                val c = line[i]
                if (c == UNMAPPED || c == REPLACEMENT) {
                    val prev = if (i > 0) line[i - 1] else ' '
                    val next = if (i + 1 < line.length) line[i + 1] else ' '
                    // 两侧都是字母 ⇒ 这是被吞掉的「行尾连字符」，黏合而不留空格
                    if (!(Character.isLetter(prev) && Character.isLetter(next))) {
                        sb.append(' ')
                    }
                } else {
                    sb.append(c)
                }
            }
            line = sb.toString()
        }
        if (line.indexOf(SOFT_HYPHEN) >= 0) {
            line = line.replace(SOFT_HYPHEN.toString(), "")
        }
        line = SINGLE_LETTER_RUN.replace(line) { collapseLetterSpacedRun(it.value, corpus) }
        var lead = 0
        while (lead < line.length && (line[lead] == ' ' || line[lead] == '\t')) lead++
        if (lead < line.length) {
            line = line.substring(0, lead) + INNER_SPACES.replace(line.substring(lead), " ")
        }
        return line.stripTrailingJava()
    }

    /**
     * 折叠一个字距串（`O F E C O N O M I C S`）：多数情况是一个词被拉开
     * （`S O C I E T I E S`），少数是两个词连排。
     *
     * 纯文本判不出是哪种，只能问全书语料：
     * 1. 合并后的串在全书出现过 ⇒ 就是一个词，直接合并（老行为）；
     * 2. 否则若存在**唯一**一处切分，左右两半都在全书出现过、并且**作为相邻两个词
     *    出现过** ⇒ 两词连排，合并并插回空格。
     *
     * 实测《曼昆》第 1 页章节大标题 `T E N  P R I N C I P L E S / O F E C O N O M I C S`：
     * 第二行是 `OF` + `ECONOMICS`，只有 `of|economics` 一种切法成立，且 `of economics`
     * 在书里出现 88 次。不做这一步会得到 `OFECONOMICS`，TTS 念成 "ofeconomics"。
     *
     * 「相邻出现过」是必要条件，不能省：只用词表的话 `Obeying` 会被切成 `Obey ing`、
     * `MONTANA` 会被切成 `MONT AN`（详见 [pdfCorpus]）。
     *
     * 没有证据（语料为空）或切法不唯一时，一律按老规矩直接合并 —— 宁可不改也不猜。
     */
    private fun collapseLetterSpacedRun(run: String, corpus: PdfCorpus): String {
        val collapsed = run.replace(" ", "")
        if (corpus.words.isEmpty()) return collapsed
        val lower = collapsed.lowercase()
        if (corpus.isWord(lower)) return collapsed
        val split = uniqueWordSplit(lower, corpus)
        return if (split > 0) collapsed.substring(0, split) + " " + collapsed.substring(split) else collapsed
    }

    /**
     * 在 [word] 里找**唯一**一处切分，使左右两半都在语料里、且相邻出现过。
     *
     * @return 切分位置（左右各至少 2 个字母）；无解或多解返回 -1
     */
    private fun uniqueWordSplit(word: String, corpus: PdfCorpus): Int {
        var found = -1
        for (i in 2..word.length - 2) {
            val first = word.substring(0, i)
            val second = word.substring(i)
            if (corpus.isWord(first) && corpus.isWord(second) && corpus.areAdjacent(first, second)) {
                if (found > 0) return -1
                found = i
            }
        }
        return found
    }

    /** 整行只剩装饰字符（图片/logo 占位）⇒ 丢弃 */
    private fun isNoiseLine(line: String): Boolean {
        for (c in line) {
            if (Character.isWhitespace(c)) continue
            if (NOISE_CHARS.indexOf(c) < 0) return false
        }
        return true
    }

    /** 行尾 `-`（非 `--`）且下一行首小写 ⇒ 断词续行，合并时去掉连字符 */
    private fun isHyphenBreak(cur: CharSequence, next: String): Boolean {
        val n = cur.length
        if (n < 2 || cur[n - 1] != '-') return false
        if (cur[n - 2] == '-') return false
        return next.isNotEmpty() && Character.isLowerCase(next[0])
    }

    /**
     * 全大写短行 = 标题/小标题，不与正文合并。
     *
     * 要求「行内至少有 2 个 ASCII 字母且全为大写」：中文行没有 ASCII 字母，
     * 若只按 `Character.isLowerCase` 判断，整行中文会被当成「全大写」而误判成标题。
     */
    private fun isHeadingLine(line: CharSequence, typical: Int): Boolean {
        if (line.length > HEADING_MAX_LEN || line.length >= typical) return false
        var asciiLetters = 0
        for (i in 0 until line.length) {
            val c = line[i]
            if (c < 'A' || c > 'Z') {
                if (c in 'a'..'z') return false
                continue
            }
            asciiLetters++
        }
        return asciiLetters >= 2
    }

    /** 合并两行时是否需要补空格：中日韩字符之间不需要，其余需要 */
    private fun needsSpaceBetween(cur: CharSequence, next: String): Boolean {
        if (cur.isEmpty() || next.isEmpty()) return false
        return !(isCjk(cur[cur.length - 1]) || isCjk(next[0]))
    }

    private fun isCjk(c: Char): Boolean =
        c in '\u4E00'..'\u9FFF' ||
            c in '\u3400'..'\u4DBF' ||
            c in '\uF900'..'\uFAFF' ||
            c in '\u3040'..'\u30FF' ||
            c in '\uAC00'..'\uD7AF'

    /**
     * CRLF / 孤立 CR → LF。PDFium 产出 `\r\n`，服务端 PDFBox 产出 `\n`。
     *
     * **实测澄清（2026-10-12，写这行时先信错了自己）**：
     * 本方法最初的理由写的是「不归一则每行尾部残留 `\r` ⇒ 与服务端不一致」。
     * 单测跑出来证明**这对 CRLF 是错的** —— [splitRows] 末尾的 `stripTrailingJava()`
     * 已经把行尾 `\r` 裁掉了（`\r` 属于 Java `Character.isWhitespace`），
     * 所以 CRLF 场景下归一与否结果相同。
     *
     * 归一**真正必需**的场景是**孤立 CR**（老式 PDF 生成器只吐 `\r`）：
     * [splitRows] 只按 `\n` 切，遇到孤立 CR 时**整段文字塌成一行**，
     * 且中间的 `\r` 不在行尾、裁不掉 ⇒ 行的粒度与服务端完全不同。
     *
     * 仍然保留归一的理由：让「行内绝不含隐藏 `\r`」成为**不依赖下游**的性质，
     * 而不是寄望于 `stripTrailingJava` 一直兜着。代价是一次带短路判断的全串扫描。
     */
    private fun normalizeNewlines(s: String): String =
        if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

    /** 折叠目录点线：把连续点串换成一个空格（点线是排版装饰，不是内容） */
    private fun collapseDotLeaders(pageTexts: MutableList<String>) {
        for (i in pageTexts.indices) {
            val t = pageTexts[i]
            if (t.isEmpty() || !t.contains('.')) continue
            pageTexts[i] = DOT_LEADER.replace(t, " ")
        }
    }

    /**
     * 剔除跨页重复出现的页眉/页脚（含页码行）。
     *
     * 判定三条同时成立才删：页数 ≥8、该行（数字归一化后）出现在 ≥60% 页面上、
     * 且位于所在页的前/后 3 个非空行之内。第三条是关键护栏。
     */
    private fun dropRunningMargins(pageTexts: MutableList<String>) {
        val pages = pageTexts.size
        if (pages < MARGIN_MIN_PAGES) return
        val pageHits = HashMap<String, Int>()
        for (pageText in pageTexts) {
            // 同一页内同名行只记一次，避免「一页里重复两遍」把比例抬上去
            val seen = HashSet<String>()
            for (line in marginCandidateLines(pageText)) {
                val key = normalizeMarginLine(line)
                if (key.isNotEmpty() && seen.add(key)) {
                    pageHits.merge(key, 1, Int::plus)
                }
            }
        }
        val threshold = Math.ceil(pages * MARGIN_MIN_PAGE_RATIO).toInt()
        for (i in 0 until pages) {
            pageTexts[i] = filterMarginLines(pageTexts[i], pageHits, threshold)
        }
    }

    /** 取一页里「可能是页眉页脚」的行：前/后 [MARGIN_MAX_LINES] 个非空行 */
    private fun marginCandidateLines(pageText: String): List<String> {
        if (pageText.isEmpty()) return emptyList()
        val nonBlank = pageText.split("\n").filter { it.isNotBlank() }
        return nonBlank.filterIndexed { k, _ ->
            k < MARGIN_MAX_LINES || k >= nonBlank.size - MARGIN_MAX_LINES
        }
    }

    /** 页眉页脚比较键：去掉首尾空白 + 把数字串归一成 `#` */
    private fun normalizeMarginLine(line: String): String =
        DIGIT_RUN.replace(line.trim(), "#")

    /** 删掉命中页眉页脚模式的行（其余行原样保留，含空行） */
    private fun filterMarginLines(
        pageText: String,
        pageHits: Map<String, Int>,
        threshold: Int
    ): String {
        if (pageText.isEmpty()) return pageText
        val lines = pageText.split("\n")
        val nonBlankIdx = lines.indices.filter { lines[it].isNotBlank() }
        val drop = BooleanArray(lines.size)
        var any = false
        for (k in nonBlankIdx.indices) {
            if (k >= MARGIN_MAX_LINES && k < nonBlankIdx.size - MARGIN_MAX_LINES) continue
            val idx = nonBlankIdx[k]
            val hits = pageHits[normalizeMarginLine(lines[idx])] ?: continue
            if (hits >= threshold) {
                drop[idx] = true
                any = true
            }
        }
        if (!any) return pageText
        val sb = StringBuilder(pageText.length)
        for (i in lines.indices) {
            if (!drop[i]) sb.append(lines[i]).append('\n')
        }
        return sb.toString()
    }

    /**
     * 按 `\n` 切行，**只裁行尾**（CRLF 的 '\r' 与行尾空格都在这步去掉，行首缩进原样留下）。
     *
     * 与服务端 `splitRowsWithStarts` 同口径。⚠️ 不要换成 Kotlin 的
     * `trimEnd { it <= ' ' }` 之外的花样：服务端 Java 用的是
     * `String.stripTrailing()`（`Character.isWhitespace`，**不含** U+00A0 / U+2007 / U+202F），
     * 而 Kotlin 的 `Char.isWhitespace()` = `isWhitespace || isSpaceChar`，**会多删这三个**。
     * 这里显式复刻 Java 语义。
     */
    private fun splitRows(text: String): List<String> {
        val rows = ArrayList<String>()
        var from = 0
        val len = text.length
        while (from <= len) {
            val nl = text.indexOf('\n', from)
            val end = if (nl < 0) len else nl
            val row = text.substring(from, end).stripTrailingJava()
            if (row.isNotEmpty()) rows.add(row)
            if (nl < 0) break
            from = nl + 1
        }
        return rows
    }

    /**
     * Java `String.stripTrailing()` 的等价实现。
     *
     * Java 版按 `Character.isWhitespace` 判定，**不含** U+00A0(NBSP) / U+2007 / U+202F；
     * Kotlin 的 [Char.isWhitespace] 会把这三个也算进去 ⇒ 直接用它会多裁掉行尾的 NBSP，
     * 导致行文本与服务端不一致（单字符差异就会让该行整段定位失败）。
     */
    private fun String.stripTrailingJava(): String {
        var end = length
        while (end > 0 && Character.isWhitespace(this[end - 1])) end--
        return if (end == length) this else substring(0, end)
    }

    // ---------- 以下为单测桥接 ----------
    //
    // 上面的清洗/切行/换行归一逻辑是本类与服务端对齐的核心，也是最容易在后续重构中被改坏的
    // 部分（尤其是 stripTrailingJava 的 NBSP 语义、CRLF 归一）。它们本身是纯函数、
    // 无需 Android 上下文，因此**保持 private**（不扩大生产 API 面），另开 internal 桥接给
    // JVM 单测断言。删改这些方法时务必同步 PdfPreviewParserTest，否则会失去这层保护。

    internal fun stripTrailingForTest(s: String): String = s.stripTrailingJava()

    internal fun splitRowsForTest(text: String): List<String> = splitRows(text)

    internal fun collapseDotLeadersForTest(pages: MutableList<String>) = collapseDotLeaders(pages)

    internal fun dropRunningMarginsForTest(pages: MutableList<String>) = dropRunningMargins(pages)

    internal fun normalizeNewlinesForTest(s: String): String = normalizeNewlines(s)

    /** 版面重排端到端口径：原始页文本 → 自然段文本（服务端 reflowPdfPageText 的镜像） */
    internal fun reflowPageTextForTest(pageText: String): String = reflowPageText(pageText)

    /**
     * 整本口径：先建全书语料，再逐页重排（服务端 `reflowPdfPages` 的镜像）。
     *
     * 金标向量必须走这条路径 —— 字距串的切分依赖全书语料，逐页独立算会与生产不一致。
     */
    internal fun reflowPagesForTest(pages: List<String>): List<String> {
        val corpus = pdfCorpus(pages)
        return pages.map { reflowPageText(it, corpus) }
    }

    internal fun cleanPdfLineForTest(raw: String): String = cleanPdfLine(raw)

    /** 用给定「全书」页文本建语料后清洗一行（验证字距串切分，`PdfCorpus` 不外泄） */
    internal fun cleanPdfLineWithCorpusForTest(raw: String, bookPages: List<String>): String =
        cleanPdfLine(raw, pdfCorpus(bookPages))

    /** 端到端口径：换行归一 → 切行。PDFium 原样输出必须先过归一才能得到与服务端一致的 rows。 */
    internal fun rowsFromPdfiumForTest(raw: String): List<String> = splitRows(normalizeNewlines(raw))
}
