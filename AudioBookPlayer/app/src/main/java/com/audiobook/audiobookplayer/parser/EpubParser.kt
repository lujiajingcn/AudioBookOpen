package com.audiobook.audiobookplayer.parser

import android.util.LruCache
import android.util.Xml
import com.audiobook.audiobookplayer.model.TocEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile
import kotlin.math.min

/**
 * EPUB 格式本地解析器。
 *
 * 不依赖任何第三方库，完全使用 Android 标准库：
 * - ZipInputStream 读取 EPUB（ZIP 格式）
 * - XmlPullParser 解析 XML（container.xml / OPF / NCX / nav）
 * - 正则表达式去除 HTML 标签
 *
 * 解析流程与后端 BookTextExtractor 保持一致，保证客户端与服务端分段落完全一致。
 */
object EpubParser {

    /** ZIP 条目索引最多缓存多少本 EPUB（超出按 LRU 淘汰，避免无界常驻堆内存） */
    private const val ZIP_ENTRY_INDEX_MAX = 4

    /**
     * 解析结果
     * @param paragraphs 按 spine 顺序排列的纯文本段落列表
     * @param toc        目录条目列表（标题 + 对应的段落索引）
     */
    data class Result(
        val paragraphs: List<String>,
        val toc: List<TocEntry>
    )

    /**
     * 解析 EPUB 文件。
     *
     * 采用惰性加载策略：先读取 ZIP 条目索引（仅文件名+大小，不读取内容），
     * 按需读取章节内容，避免大文件 OOM。
     *
     * @param file EPUB 文件
     * @return 解析结果（段落列表 + 目录）
     * @throws IOException 文件读取或解析失败
     */
    fun parse(file: File): Result {
        // 整本解析只打开一次 ZipFile 向下传递：每次按需 ZipFile(file).use{} 会让同一个 OPF
        // 被重复读取、大书解析反复开/关 ZIP 中央目录。
        ZipFile(file).use { zip ->
            // 1. 解析 container.xml 找到 OPF 路径
            val containerData = readZipEntryContent(file, "meta-inf/container.xml", zip)
                ?: throw IOException("EPUB 缺少 META-INF/container.xml: ${file.name}")
            val opfPath = parseContainerXml(containerData)

            // 3. 读取并解析 OPF 文件
            val opfData = readZipEntryContent(file, opfPath, zip)
                ?: throw IOException("EPUB 缺少 OPF 文件($opfPath): ${file.name}")

            val opfDir = if (opfPath.contains("/")) {
                opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
            } else ""

            // 4. 按 spine 顺序读取所有章节内容（逐章节惰性加载）
            val spineHrefs = parseOpfSpine(opfData, opfDir)

            // 构建 spine href → 该章首字符在【非空白坐标】中的位置，用于目录解析。
            //
            // 为什么是「非空白字符计数」而不是真实字符下标：段落由 ParagraphSplitter 流式切出，
            // 切分会丢掉句末标点后的空白与每句首尾的空白，所以「段落长度累加」永远小于原文下标
            // （压缩坐标）；而章节侧早期用的是章节原文真实长度 —— 两端不同源，目录映射出的
            // 段落下标会系统性偏大、越往后越明显，表现为「点目录跳过头」。
            //
            // 设计上又要求「流式、不驻留整本」，不能为了求真实下标把全文拼出来。于是两侧统一
            // 改用「非空白字符计数」：段落文本与原文只相差空白字符（切分只丢空白、不丢任何
            // 非空白字符），所以非空白计数是真实下标的一个**保序变换**，用它定位到的段落与用
            // 真实下标定位到的完全一致，且可流式累加。
            val spinePositions = mutableMapOf<String, Int>()
            var cumulativePos = 0
            // 流式分句，逐章喂给 ParagraphSplitter；跨章残句由它内部缓冲，
            // 切分结果与「整本一次喂」逐字节一致，不必用 StringBuilder 拼出整本纯文本
            val splitter = ParagraphSplitter()
            for (href in spineHrefs) {
                val xhtmlData = readZipEntryContent(file, href, zip) ?: continue
                val htmlContent = String(xhtmlData, Charsets.UTF_8)
                val plainText = HtmlStripper.stripTags(htmlContent)
                if (plainText.isNotEmpty()) {
                    spinePositions[href] = cumulativePos
                    // 章节间以 \n 拼接，与服务端（整本拼 \n）的段落边界保持一致；
                    // 该 \n 属空白字符，在非空白坐标里不占位，故两侧都不需要额外 +1
                    splitter.feed(plainText + "\n")
                    cumulativePos += TextPositions.countNonBlank(plainText)
                }
            }

            val paragraphs = splitter.finish()
            if (paragraphs.isEmpty()) {
                throw IOException("EPUB 文件未提取到文本内容: ${file.name}")
            }

            // 5. 解析目录（NCX / EPUB 3 nav）
            val toc = parseEpubToc(file, spineHrefs, spinePositions, opfPath, opfDir, paragraphs, zip)

            return Result(paragraphs, toc)
        }
    }

    // ---------- ZIP 读取 ----------

    /**
     * 按 ZIP 内名称读取单个条目的字节内容（惰性，按需加载，每次只读一个章节）。
     */
    // ZIP 条目名索引缓存（按文件绝对路径），避免每次查找都全量扫描 ZIP。
    // 容量上限 [ZIP_ENTRY_INDEX_MAX]（按 LRU 淘汰）：缓存无界会让每本已解析的 EPUB 常驻一份
    // 完整条目名列表，书越多占堆越多且永不释放。
    // 注意 LruCache 自身非线程安全，读写仍统一走 zipEntryIndexLock。
    private val zipEntryIndex = object : LruCache<String, Map<String, String>>(ZIP_ENTRY_INDEX_MAX) {
        override fun sizeOf(key: String, value: Map<String, String>): Int = 1
    }
    private val zipEntryIndexLock = Any()

    private fun getZipEntryIndex(file: File): Map<String, String> {
        val key = file.absolutePath
        synchronized(zipEntryIndexLock) {
            zipEntryIndex.get(key)?.let { return it }
        }
        val index = mutableMapOf<String, String>()
        ZipFile(file).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    index[entry.name.lowercase()] = entry.name
                }
            }
        }
        synchronized(zipEntryIndexLock) {
            zipEntryIndex.put(key, index)
        }
        return index
    }

    /**
     * 按 ZIP 内名称读取单个条目的字节内容（惰性，按需加载，每次只读一个章节）。
     * 通过预建的条目名索引定位，避免每次查找都全量扫描整个 ZIP。
     *
     * @param zipFile 可传入 [parse] 已打开的共享 [ZipFile] 以复用；不传则自行
     *               打开并在本次读取后关闭。复用时不会关闭调用方持有的 [ZipFile]。
     */
    private fun readZipEntryContent(file: File, targetName: String, zipFile: ZipFile? = null): ByteArray? {
        val actualName = getZipEntryIndex(file)[targetName.lowercase()] ?: return null
        return try {
            val ownZip = zipFile == null
            val zip = zipFile ?: ZipFile(file)
            try {
                val entry = zip.getEntry(actualName) ?: return null
                zip.getInputStream(entry).use { input ->
                    val baos = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var len: Int
                    while (input.read(buffer).also { len = it } > 0) {
                        baos.write(buffer, 0, len)
                    }
                    baos.toByteArray()
                }
            } finally {
                if (ownZip) zip.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- container.xml 解析 ----------

    /**
     * 解析 META-INF/container.xml，提取 OPF 文件的 full-path。
     * 使用 XmlPullParser（Streaming API，无外部依赖）。
     */
    private fun parseContainerXml(data: ByteArray): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrEmpty()) {
                    return fullPath.lowercase()
                }
            }
            eventType = parser.next()
        }
        // 默认值（与后端一致）
        return "oebps/content.opf"
    }

    // ---------- OPF 解析（manifest + spine） ----------

    /**
     * 解析 OPF 文件，返回按 spine 顺序排列的内容文件路径列表。
     * 使用 XmlPullParser Streaming 方式，遍历 manifest 和 spine 两遍：
     * - 第一遍：收集 manifest（id → href 映射）
     * - 第二遍：按 spine itemref 顺序输出
     */
    private fun parseOpfSpine(data: ByteArray, opfDir: String): List<String> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        // 第一遍：收集 manifest
        val manifest = mutableMapOf<String, String>() // id → 完整路径
        val spineItemrefs = mutableListOf<String>()   // spine 中的 idref 顺序

        var eventType = parser.eventType
        var insideManifest = false
        var insideSpine = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "manifest" -> insideManifest = true
                        "spine" -> insideSpine = true
                        "item" -> {
                            if (insideManifest) {
                                val id = parser.getAttributeValue(null, "id")
                                var href = parser.getAttributeValue(null, "href")
                                if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) {
                                    // URL 解码（如 %20 → 空格）
                                    href = try {
                                        URLDecoder.decode(href, "UTF-8")
                                    } catch (e: Exception) {
                                        href
                                    }
                                    val fullPath = normalizeZipPath(opfDir + href)
                                    manifest[id] = fullPath
                                }
                            }
                        }
                        "itemref" -> {
                            if (insideSpine) {
                                val idref = parser.getAttributeValue(null, "idref")
                                if (!idref.isNullOrEmpty()) {
                                    spineItemrefs.add(idref)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "manifest" -> insideManifest = false
                        "spine" -> insideSpine = false
                    }
                }
            }
            eventType = parser.next()
        }

        // 按 spine 顺序输出
        val result = mutableListOf<String>()
        for (idref in spineItemrefs) {
            manifest[idref]?.let { result.add(it) }
        }

        // Fallback：如果 spine 为空，使用 manifest 中所有 XHTML/HTML 项
        if (result.isEmpty()) {
            for ((_, href) in manifest) {
                if (href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm")) {
                    result.add(href)
                }
            }
        }

        return result
    }

    // ---------- 目录解析（NCX + EPUB 3 nav） ----------

    /**
     * 解析 EPUB 目录。
     * 依次尝试 NCX 文件（EPUB 2）和 nav 元素（EPUB 3）。
     *
     * @param file            EPUB 文件（按需读取条目内容）
     * @param spineHrefs      spine 中章节 href 列表（用于文件名匹配）
     * @param spinePositions  每个章节 href 首字符在非空白坐标中的位置（见 [parse] 里的说明）
     * @param opfPath         OPF 文件路径（用于解析相对路径）
     * @param opfDir          OPF 文件所在目录
     * @param paragraphs      解析后的段落列表（用于将章节位置映射到 paragraphIndex）
     */
    private fun parseEpubToc(
        file: File,
        spineHrefs: List<String>,
        spinePositions: Map<String, Int>,
        opfPath: String,
        opfDir: String,
        paragraphs: List<String>,
        zipFile: ZipFile? = null
    ): List<TocEntry> {
        // 计算每个段落在【同一非空白坐标】下的起始位置 —— 必须与章节侧同源，否则目录会跳过头。
        // （早期这里用 paragraphs[i].length + 1 累加，属压缩坐标，正是「点目录跳过头」的根因。）
        // 哨兵规则与坐标定义集中在 TextPositions，避免两侧再被改岔。
        val paragraphPositions = TextPositions.paragraphPositions(paragraphs)

        // 获取 manifest（OPF 已解析过一次，这里重新解析一次以获取 nav 元素）
        val opfData = readZipEntryContent(file, opfPath, zipFile) ?: return emptyList()
        val manifest = parseOpfManifest(opfData, opfDir)

        // 方式 1：从 NCX 文件解析（EPUB 2）
        for ((_, href) in manifest) {
            if (href.endsWith(".ncx")) {
                val ncxData = readZipEntryContent(file, href, zipFile) ?: continue
                val toc = parseNcxToc(ncxData, spineHrefs, spinePositions, paragraphs, paragraphPositions)
                if (toc.isNotEmpty()) return toc
            }
        }

        // 方式 2：从 EPUB 3 nav 元素解析
        val navToc = parseEpubNavToc(file, spineHrefs, spinePositions, opfPath, manifest, paragraphs, paragraphPositions, zipFile)
        if (navToc.isNotEmpty()) return navToc

        return emptyList()
    }

    private fun parseOpfManifest(data: ByteArray, opfDir: String): Map<String, String> {
        val manifest = mutableMapOf<String, String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        var insideManifest = false
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "manifest") {
                        insideManifest = true
                    } else if (parser.name == "item" && insideManifest) {
                        val id = parser.getAttributeValue(null, "id")
                        var href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) {
                            href = try {
                                URLDecoder.decode(href, "UTF-8")
                            } catch (e: Exception) {
                                href
                            }
                            manifest[id] = normalizeZipPath(opfDir + href)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "manifest") insideManifest = false
                }
            }
            eventType = parser.next()
        }
        return manifest
    }

    /** 从 NCX 文件解析目录（EPUB 2） */
    @Suppress("UNUSED_PARAMETER")
    private fun parseNcxToc(
        ncxData: ByteArray,
        spineHrefs: List<String>,
        spinePositions: Map<String, Int>,
        _paragraphs: List<String>,
        paragraphPositions: IntArray
    ): List<TocEntry> {
        val toc = mutableListOf<TocEntry>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(ncxData), "UTF-8")

            var eventType = parser.eventType
            var depth = 0
            var currentTitle = ""
            var currentSrc = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        when (parser.name) {
                            "navLabel" -> currentTitle = ""
                            "content" -> currentSrc = parser.getAttributeValue(null, "src") ?: ""
                            "text" -> {
                                // 读取 text 节点内容
                                eventType = parser.next()
                                if (eventType == XmlPullParser.TEXT) {
                                    currentTitle += parser.text ?: ""
                                    // v5/A7：再推进一格，越过刚消费的 TEXT、停在 END_TAG "text"。
                                    // 这样下面的 continue 回到主循环时，END_TAG 分支仍会执行 depth--，
                                    // 而外层 TEXT 分支不会二次追加（原实现因此把标题读成两遍）。
                                    // 自闭合 <text/> 时进不来这个 if，指针本就停在 END_TAG，深度同样平衡。
                                    eventType = parser.next()
                                }
                                continue
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (depth >= 3 && currentTitle.isNotEmpty()) {
                            currentTitle += parser.text ?: ""
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "navPoint" -> {
                                val title = currentTitle.trim()
                                if (title.isNotEmpty()) {
                                    val chapterPos = chapterPositionFromSrc(currentSrc, spineHrefs, spinePositions)
                                    val paraIndex = TextPositions.indexOfContainingParagraph(chapterPos, paragraphPositions)
                                    toc.add(TocEntry(title = title, paragraphIndex = paraIndex, level = depth - 3))
                                }
                                currentTitle = ""
                                currentSrc = ""
                            }
                        }
                        depth--
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // 解析失败返回空目录
        }
        return toc
    }

    /** 从 EPUB 3 nav 元素解析目录 */
    @Suppress("UNUSED_PARAMETER")
    private fun parseEpubNavToc(
        file: File,
        spineHrefs: List<String>,
        spinePositions: Map<String, Int>,
        opfPath: String,
        _manifest: Map<String, String>,
        paragraphs: List<String>,
        paragraphPositions: IntArray,
        zipFile: ZipFile? = null
    ): List<TocEntry> {
        try {
            // 找 properties="nav" 的 nav 文件
                val opfData = readZipEntryContent(file, opfPath, zipFile)
                if (opfData != null) {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(ByteArrayInputStream(opfData), "UTF-8")

                var eventType = parser.eventType
                var insideManifest = false
                val opfDir = if (opfPath.contains("/")) {
                    opfPath.substring(0, opfPath.lastIndexOf('/') + 1)
                } else ""

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "item") {
                        if (insideManifest) {
                            val props = parser.getAttributeValue(null, "properties") ?: ""
                            if (props.contains("nav")) {
                                var href = parser.getAttributeValue(null, "href") ?: ""
                                href = try {
                                    URLDecoder.decode(href, "UTF-8")
                                } catch (e: Exception) {
                                    href
                                }
                                val navPath = normalizeZipPath(opfDir + href)
                                val navData = readZipEntryContent(file, navPath, zipFile) ?: return emptyList()
                                return parseNavHtml(navData, spineHrefs, spinePositions, paragraphs, paragraphPositions)
                            }
                        }
                    } else if (eventType == XmlPullParser.START_TAG && parser.name == "manifest") {
                        insideManifest = true
                    } else if (eventType == XmlPullParser.END_TAG && parser.name == "manifest") {
                        insideManifest = false
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            // 解析失败
        }
        return emptyList()
    }

    /** 解析 nav XHTML 文件中的目录（EPUB 3） */
    @Suppress("UNUSED_PARAMETER")
    private fun parseNavHtml(
        navData: ByteArray,
        spineHrefs: List<String>,
        spinePositions: Map<String, Int>,
        _paragraphs: List<String>,
        paragraphPositions: IntArray
    ): List<TocEntry> {
        val html = String(navData, Charsets.UTF_8)

        // 用正则匹配 <nav epub:type="toc">...</nav>
        val navPattern = Regex("(?is)<nav[^>]*epub:type=[\"']toc[\"'][^>]*>(.*?)</nav>", RegexOption.DOT_MATCHES_ALL)
        val navMatch = navPattern.find(html) ?: return emptyList()

        val navContent = navMatch.groupValues[1]

        // 匹配 <a href="...">标题文本</a>
        val linkPattern = Regex("(?is)<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)

        val toc = mutableListOf<TocEntry>()
        for (linkMatch in linkPattern.findAll(navContent)) {
            val href = linkMatch.groupValues[1]
            val rawText = linkMatch.groupValues[2]
            // 去除子元素标签得到纯文本标题
            val title = rawText.replace(Regex("(?s)<[^>]+>"), "").trim()
            if (title.isEmpty()) continue

            val srcBase = if (href.contains("#")) href.substring(0, href.indexOf('#')) else href
            val chapterPos = chapterPositionFromSrc(srcBase, spineHrefs, spinePositions)
            val paraIndex = TextPositions.indexOfContainingParagraph(chapterPos, paragraphPositions)
            toc.add(TocEntry(title = title, paragraphIndex = paraIndex))
        }
        return toc
    }

    // ---------- 辅助方法 ----------

    /**
     * 规范化 ZIP 内文件路径，处理 ../ 和 ./。
     * ZIP 条目始终使用正斜杠，此方法将相对路径规范化为统一格式。
     */
    private fun normalizeZipPath(path: String): String {
        val parts = path.split("/")
        val stack = mutableListOf<String>()
        for (part in parts) {
            when {
                part == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                part != "." && part.isNotEmpty() -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    /**
     * 从 NCX/nav 的 content src 中取出该章的起始位置（非空白坐标）。
     * src 格式如 "chapter1.xhtml#section1"，通过文件名匹配 spine 中对应章节的起始位置。
     */
    private fun chapterPositionFromSrc(
        src: String,
        spineHrefs: List<String>,
        spinePositions: Map<String, Int>
    ): Int {
        if (src.isBlank()) return 0
        // 提取文件名部分（去掉 #anchor）
        val fileName = if (src.contains("#")) src.substringBefore('#') else src
        // 精确匹配
        spinePositions[fileName]?.let { return it }
        // 模糊匹配：spineHrefs 可能带目录路径（如 "OPS/chapter1.xhtml"），src 可能只含文件名
        val baseName = fileName.substringAfterLast('/')
        for (href in spineHrefs) {
            if (href.endsWith(baseName)) {
                spinePositions[href]?.let { return it }
            }
        }
        return 0
    }
}