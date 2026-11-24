package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChinDictManager] 里**不依赖 Android 框架**的纯逻辑单测。
 *
 * ## 为什么只测这几个
 *
 * `ensureInstalled` / `lookup` 都碰 `Context` 与 `SQLiteDatabase`（JVM 单测里没有），
 * 只能上仪器测试。而 [singleCjkOf] 是「查字典」菜单的**全部判据** ——
 * 它决定「查字典」这一行出现与否，一旦判错，现象是
 * 「明明选中一个字却没这一项」或「选了一句话却冒出这一项且点不动」，
 * 而这两种都无法靠肉眼定位到是这个函数。
 */
class ChinDictManagerTest {

    @Test
    fun `单个汉字直接返回该字`() {
        assertEquals('中', ChinDictManager.singleCjkOf("中"))
        assertEquals('听', ChinDictManager.singleCjkOf("听"))
    }

    @Test
    fun `选区带前后空白时仍能取出该字`() {
        // 长按后手柄微微越界时，选区常常带空白/换行
        assertEquals('钟', ChinDictManager.singleCjkOf(" 钟\n"))
        assertEquals('楼', ChinDictManager.singleCjkOf("\n楼  "))
    }

    @Test
    fun `选区带标点时只取汉字`() {
        // 「听。」= 汉字 + 句号，仍应判定为单字
        assertEquals('听', ChinDictManager.singleCjkOf("听。"))
        assertEquals('楼', ChinDictManager.singleCjkOf("“楼”"))
    }

    @Test
    fun `两个字及以上一律返回 null`() {
        // ⭐ 这条是「查字典」不出现乱弹的关键：选一整句时必须没有该项
        assertNull(ChinDictManager.singleCjkOf("钟鼓楼"))
        assertNull(ChinDictManager.singleCjkOf("听广播"))
    }

    @Test
    fun `空选区与纯标点返回 null`() {
        assertNull(ChinDictManager.singleCjkOf(""))
        assertNull(ChinDictManager.singleCjkOf("   \n  "))
        assertNull(ChinDictManager.singleCjkOf("，。！？"))
    }

    @Test
    fun `英文与数字不算汉字`() {
        assertNull(ChinDictManager.singleCjkOf("a"))
        assertNull(ChinDictManager.singleCjkOf("1"))
        // 英文单词查英词典而非中文字典
        assertNull(ChinDictManager.singleCjkOf("hello"))
    }

    @Test
    fun `扩展A区汉字也算`() {
        // 罕用字（U+3400–U+4DBF）；字库里可能没有，但**判据**必须放行，
        // 否则「菜单不出现」与「字典没收录」两种原因混在一起无法区分
        assertTrue(ChinDictManager.isCjkChar('㐀'))
        assertEquals('㐀', ChinDictManager.singleCjkOf("㐀"))
    }

    @Test
    fun `isCjkChar 放行常用字区间`() {
        // ⚠️ 边界一律用 \uXXXX 转义写字面量。手打汉字容易看错码位
        // （本次就把「U+4DC0」误写成 U+4D40，断言失败查了半天才发现）。
        assertTrue(ChinDictManager.isCjkChar('\u4E00'))// 下界
        assertTrue(ChinDictManager.isCjkChar('\u9FFF'))      // 上界
        assertTrue(ChinDictManager.isCjkChar('\u3400'))      // 扩展 A 下界
        assertTrue(ChinDictManager.isCjkChar('\u4DBF'))      // 扩展 A 上界
        assertFalse(ChinDictManager.isCjkChar('\u4DC0'))     // 4DBF+1，界外
        assertFalse(ChinDictManager.isCjkChar('\u4DFF'))     // 扩展 B 起，界外
        assertFalse(ChinDictManager.isCjkChar('A'))
        assertFalse(ChinDictManager.isCjkChar('\u3002'))
    }

    @Test
    fun `hasSingleCjk 与 singleCjkOf 判定一致`() {
        // 菜单项的显示完全依赖 hasSingleCjk；两者若不一致，
        // 就会出现「菜单显示了但点了提示请只选中一个字」
        for (sel in listOf("中", " 钟\n", "听。", "钟鼓楼", "", "a", "。", "、", " ")) {
            assertEquals(
                "选区=$sel",
                ChinDictManager.singleCjkOf(sel) != null,
                ChinDictManager.hasSingleCjk(sel)
            )
        }
    }
}