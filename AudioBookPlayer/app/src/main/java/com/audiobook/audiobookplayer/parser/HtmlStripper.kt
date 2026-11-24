package com.audiobook.audiobookplayer.parser

/**
 * 去除 HTML 标签，保留纯文本。
 *
 * 逻辑与后端 BookTextExtractor.stripHtmlTags() 保持一致：
 * 处理常见的 HTML 实体、块级标签换行和空白规范化。
 */
object HtmlStripper {

    // 正则提为对象级常量，避免每次调用（EPUB 每章一次）重复编译 10+ 个 Regex
    private val SCRIPT_BLOCK = Regex("(?is)<script[^>]*>.*?</script>")
    private val STYLE_BLOCK = Regex("(?is)<style[^>]*>.*?</style>")
    private val BR_TAG = Regex("(?i)<br\\s*/?>")
    private val P_TAG = Regex("(?i)</?p[^>]*>")
    private val DIV_TAG = Regex("(?i)</?div[^>]*>")
    private val BLOCK_TAG = Regex("(?i)</?(h[1-6]|li|tr|table)[^>]*>")
    private val ANY_TAG = Regex("<[^>]+>")
    private val DECIMAL_ENTITY = Regex("&#(\\d+);")
    private val HEX_ENTITY = Regex("&#x([0-9a-fA-F]+);")
    private val SPACES = Regex("[ \t]+")
    private val BLANK_LINES = Regex("\n{3,}")

    @Suppress("RegExpRepeatedChar")
    fun stripTags(html: String): String {
        if (html.isEmpty()) return ""

        // 移除 script/style 块
        var cleaned = html
            .replace(SCRIPT_BLOCK, " ")
            .replace(STYLE_BLOCK, " ")

        // 块级标签替换为换行
        cleaned = cleaned
            .replace(BR_TAG, "\n")
            .replace(P_TAG, "\n")
            .replace(DIV_TAG, "\n")
            .replace(BLOCK_TAG, "\n")

        // 移除剩余标签
        cleaned = cleaned.replace(ANY_TAG, " ")

        // 解码常见 HTML 实体
        cleaned = cleaned
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

        // 解码数字实体
        cleaned = DECIMAL_ENTITY.replace(cleaned) { mr ->
            mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
        }
        cleaned = HEX_ENTITY.replace(cleaned) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: mr.value
        }

        // 空白规范化
        cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n")
        cleaned = cleaned.replace(SPACES, " ")
        cleaned = cleaned.replace(BLANK_LINES, "\n\n")

        return cleaned.trim()
    }
}
