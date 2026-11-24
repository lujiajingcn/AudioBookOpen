package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书城下载到本机后的**书名**规则测试。
 *
 * 背景：服务端为防重名/防目录穿越，落盘名一律是 `<32 位随机码>_<书名>.<ext>`
 * （见 `AdminService.uploadBook`）。客户端原先直接拿它当本地文件名 + 书架 display_name，
 * 于是书架上的书名变成 `4ddec10615234e0c8be6a4147c55c5fb_中国的官办经济.pdf`。
 *
 * 本测试锁住两件事：
 * 1. 展示名取**原始书名**，随机码前缀必须去掉；
 * 2. 展示名同时是「书籍动态内容表」的表名，必须不含 `"` `'` `;` `\` `(` `)` `[` `]`
 *    —— 否则 `BookStorage.validateTableName` 抛异常，书打不开（白屏）。
 */
class FileUtilsBookNameTest {

    /** 线上真实值：store_books.file_name（书城 id=5《中国的官办经济》） */
    private val fileName = "4ddec10615234e0c8be6a4147c55c5fb_中国的官办经济.pdf"
    private val title = "中国的官办经济"

    @Test
    fun `书城书名取原始书名_不带服务端随机码前缀`() {
        val name = FileUtils.storeBookLocalName(title, fileName, "pdf")
        assertEquals("中国的官办经济.pdf", name)
        assertFalse("不能残留随机码", name.contains("4ddec106"))
    }

    /** 回归基线：旧的命名口径确实会把随机码带进书架，新规则必须与之不同。 */
    @Test
    fun `旧的命名口径会把随机码带进书架_作为回归基线`() {
        val legacy = FileUtils.sanitizeFileName(fileName)
        assertEquals("4ddec10615234e0c8be6a4147c55c5fb_中国的官办经济.pdf", legacy)
        assertTrue(legacy.contains("4ddec106"))
    }

    @Test
    fun `书名缺失时退回文件名_并去掉随机码前缀`() {
        assertEquals(
            "中国的官办经济.pdf",
            FileUtils.storeBookLocalName(null, fileName, "pdf")
        )
        assertEquals(
            "中国的官办经济.pdf",
            FileUtils.storeBookLocalName("   ", fileName, "pdf")
        )
    }

    @Test
    fun `扩展名缺失时从文件名推断`() {
        assertEquals("中国的官办经济.epub", FileUtils.storeBookLocalName(title, "abc_中国的官办经济.epub", null))
        // 推断出的扩展名统一小写，避免 "PDF" 造出两个不同的书名
        assertEquals("中国的官办经济.pdf", FileUtils.storeBookLocalName(title, "abc_中国的官办经济.PDF", null))
    }

    @Test
    fun `文件名无扩展名时不补点`() {
        assertEquals("中国的官办经济", FileUtils.storeBookLocalName(title, "abc_中国的官办经济", ""))
    }

    /**
     * 半角 `(` `)` 会触发表名校验失败（`TABLE_NAME_PATTERN` 拒绝圆括号），
     * 必须中和成下划线 —— 否则出现「书进了书架但点开是白屏」。
     */
    @Test
    fun `书名里的半角括号与引号被中和`() {
        val name = FileUtils.storeBookLocalName(
            "中国的官办经济(卷一)",
            "4ddec10615234e0c8be6a4147c55c5fb_中国的官办经济卷一.pdf",
            "pdf"
        )
        assertEquals("中国的官办经济_卷一_.pdf", name)
        assertTrue(必须作为表名可用(name))
    }

    /**
     * **反向护栏**：全角 `（）` 不在禁用字符集里，且表名校验正则同样放行，
     * 因此必须原样保留 —— 中文书名里的全角括号很常见，把它也替掉等于擅自改书名。
     */
    @Test
    fun `书名里的全角括号原样保留`() {
        val name = FileUtils.storeBookLocalName(
            "中国的官办经济（卷一）",
            "4ddec10615234e0c8be6a4147c55c5fb_中国的官办经济（卷一）.pdf",
            "pdf"
        )
        assertEquals("中国的官办经济（卷一）.pdf", name)
        assertTrue(必须作为表名可用(name))
    }

    @Test
    fun `英文书名里的引号与分号也会被中和`() {
        val name = FileUtils.storeBookLocalName(
            "It's a \"Test\"; drop table x",
            "abc_It's a Test; drop table x.pdf",
            "pdf"
        )
        assertTrue(必须作为表名可用(name))
    }

    @Test
    fun `只有标准随机码前缀会被去掉`() {
        // 32 位小写 hex + 下划线 → 去掉
        assertEquals("书名.pdf", FileUtils.stripUploadUuidPrefix("4ddec10615234e0c8be6a4147c55c5fb_书名.pdf"))
        // 长度不足 / 含大写 / 无下划线 / 非 hex → 一律原样保留（不能误吃书名的正常内容）
        assertEquals("4ddec106_书名.pdf", FileUtils.stripUploadUuidPrefix("4ddec106_书名.pdf"))
        assertEquals("4DDEC10615234E0C8BE6A4147C55C5FB_书名.pdf",
            FileUtils.stripUploadUuidPrefix("4DDEC10615234E0C8BE6A4147C55C5FB_书名.pdf"))
        assertEquals("书名.pdf", FileUtils.stripUploadUuidPrefix("书名.pdf"))
        assertEquals("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz_书名.pdf",
            FileUtils.stripUploadUuidPrefix("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz_书名.pdf"))
    }

    @Test
    fun `书名以随机码形态开头时不会被误当成前缀吃掉`() {
        // 书名本身就是一串 hex（罕见但合法）：只有紧跟下划线且凑满 32 位才算前缀
        assertEquals(
            "deadbeef.pdf",
            FileUtils.stripUploadUuidPrefix("deadbeef.pdf")
        )
    }

    /** 与 `BookStorage.TABLE_NAME_PATTERN`（`^[^"';\\()\[\]\x00-\x1F]+$`）同口径的校验。 */
    private fun 必须作为表名可用(name: String): Boolean =
        name.isNotBlank() && Regex("^[^\"';\\\\()\\[\\]\\x00-\\x1F]+$").matches(name)
}
