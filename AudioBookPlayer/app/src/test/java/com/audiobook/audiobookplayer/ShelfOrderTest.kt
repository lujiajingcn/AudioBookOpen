package com.audiobook.audiobookplayer

import com.audiobook.audiobookplayer.model.Book
import com.audiobook.audiobookplayer.model.ShelfEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfOrderTest {

    private fun entry(name: String, id: Int, hasFile: Boolean = true) = ShelfEntry(
        Book(
            id = id,
            tableName = name,
            displayName = name,
            filePath = if (hasFile) "/books/$name.txt" else ""
        )
    )

    @Test
    fun `顺序值从前往后递减_值越大越靠前`() {
        val orders = assignShelfSortOrders(listOf(10, 11, 12))
        assertEquals(3, orders[10])
        assertEquals(2, orders[11])
        assertEquals(1, orders[12])
    }

    @Test
    fun `空列表与单元素都能处理`() {
        assertTrue(assignShelfSortOrders(emptyList()).isEmpty())
        assertEquals(mapOf(7 to 1), assignShelfSortOrders(listOf(7)))
    }

    @Test
    fun `映射回的顺序与输入顺序一致`() {
        val ids = listOf(5, 3, 9, 1)
        val orders = assignShelfSortOrders(ids)
        val restored = orders.entries.sortedByDescending { it.value }.map { it.key }
        assertEquals(ids, restored)
    }

    @Test
    fun `筛选状态下拖拽只改可见项之间的顺序`() {
        val full = listOf(
            entry("A", 1),
            entry("B", 2),
            entry("C", 3),
            entry("D", 4)
        )
        val merged = mergeShelfOrderIntoFullList(full, listOf(3, 1))
        assertEquals(listOf("C", "B", "A", "D"), merged.map { it.book.displayName })
    }

    @Test
    fun `筛选状态下不可见项保持原位`() {
        val full = listOf(
            entry("A", 1),
            entry("B", 2),
            entry("C", 3),
            entry("D", 4)
        )
        val merged = mergeShelfOrderIntoFullList(full, listOf(3, 1))
        assertEquals(
            listOf("B", "D"),
            merged.filter { it.book.displayName in listOf("B", "D") }.map { it.book.displayName }
        )
    }

    @Test
    fun `空可见顺序时原样返回`() {
        val full = listOf(entry("A", 1), entry("B", 2))
        assertEquals(full, mergeShelfOrderIntoFullList(full, emptyList()))
    }

    @Test
    fun `可见顺序含未知id时不丢条目`() {
        val full = listOf(entry("A", 1), entry("B", 2))
        val merged = mergeShelfOrderIntoFullList(full, listOf(99, 1, 2))
        // 99 不在列表里 → 跳过它，A B 仍按顺序落位，条目一个不少
        assertEquals(listOf("A", "B"), merged.map { it.book.displayName })
    }

    @Test
    fun `同名但不同id的两本书各自独立排序`() {
        // 回归基线：同一本书名在 ebooks 表里可能有多行（重复导入 / 历史遗留）。
        // 用书名做 key 会让它们互相顶替 —— 有一本被显示两次、另一本消失，
        // 落库的顺序也就全乱了。必须用 id。
        val full = listOf(
            entry("同名", 1),
            entry("其他", 2),
            entry("同名", 3)
        )
        val merged = mergeShelfOrderIntoFullList(full, listOf(3, 2, 1))
        assertEquals(listOf(3, 2, 1), merged.map { it.book.id })
        assertEquals(3, merged.size)
    }

    @Test
    fun `虚拟条目不参与排序且保持原位`() {
        // 虚拟条目 id 恒为 0（只有任务、没有电子书文件），顺序存不住。
        // 用户把 B 拖到 A 前面 → 可见项互换（B 在前），虚拟条目留在原来的那个坑位。
        val full = listOf(
            entry("A", 1),
            entry("虚拟", 0, hasFile = false),
            entry("B", 2)
        )
        val merged = mergeShelfOrderIntoFullList(full, listOf(2, 1))
        assertEquals(listOf(2, 0, 1), merged.map { it.book.id })
    }

    // ---------- 拖拽结束回放暂存列表时的顺序保护 ----------

    @Test
    fun `回放暂存列表时保住用户刚拖出来的顺序`() {
        // 暂存的那份来自数据库（sort_order 还是拖之前的值）：
        // 直接 submit 会让松手的瞬间整架书跳回原位，等落库回来再跳一次。
        val dragged = listOf(entry("B", 2), entry("A", 1))
        val fromDb = listOf(entry("A", 1), entry("B", 2))
        val applied = sortIncomingByCurrentOrder(dragged, fromDb)
        assertEquals(listOf(2, 1), applied.map { it.book.id })
    }

    @Test
    fun `拖拽期间新增的条目排在最后并保持相对顺序`() {
        val dragged = listOf(entry("C", 3), entry("A", 1), entry("B", 2))
        // 刷新里多了一条新书 D（比如另一台设备同步回来的任务行），它不在当前列表里
        val fromDb = listOf(entry("A", 1), entry("D", 4), entry("E", 5), entry("B", 2), entry("C", 3))
        val applied = sortIncomingByCurrentOrder(dragged, fromDb)
        assertEquals(
            "现有条目按屏幕顺序在前，新条目按 incoming 的相对顺序排在后面",
            listOf(3, 1, 2, 4, 5),
            applied.map { it.book.id }
        )
    }

    @Test
    fun `当前列表为空时原样返回`() {
        val fromDb = listOf(entry("A", 1), entry("B", 2))
        assertEquals(fromDb, sortIncomingByCurrentOrder(emptyList(), fromDb))
    }

    @Test
    fun `只有一条时不需要排序`() {
        val fromDb = listOf(entry("A", 1))
        assertEquals(fromDb, sortIncomingByCurrentOrder(listOf(entry("B", 2)), fromDb))
    }

    @Test
    fun `两条虚拟条目不会因为 id 都是0而互相顶替`() {
        // 虚拟条目 id 恒为 0：key 必须带 tableName，否则两条会指向同一个位置，
        // 排序结果变成「谁先出现谁在前」，用户拖出来的顺序被抹掉。
        val dragged = listOf(entry("虚拟乙", 0, hasFile = false), entry("虚拟甲", 0, hasFile = false))
        val fromDb = listOf(entry("虚拟甲", 0, hasFile = false), entry("虚拟乙", 0, hasFile = false))
        val applied = sortIncomingByCurrentOrder(dragged, fromDb)
        assertEquals(listOf("虚拟乙", "虚拟甲"), applied.map { it.book.tableName })
    }
}
