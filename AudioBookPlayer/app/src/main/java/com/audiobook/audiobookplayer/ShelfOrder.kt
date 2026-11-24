package com.audiobook.audiobookplayer

import com.audiobook.audiobookplayer.model.ShelfEntry

/**
 * 书架顺序（长按拖放调整显示顺序）的落库规则。
 *
 * 口径：**值越大越靠前**。排序 SQL 是 `sort_order DESC, created_at DESC`
 * （见 BookStorage.SHELF_ORDER_BY）：
 *  · sort_order DESC —— 用户拖出来的顺序；
 *  · created_at DESC —— 存量记录 sort_order 全为 0（相同），靠导入时间兜底，
 *    保证老用户升级后书架顺序与升级前一模一样。
 *
 * 这个方向不是随手的：它让**新导入的书**可以取 MAX(sort_order) + 1 直接排到最前，
 * 与旧版「按 created_at DESC（新书在前）」的观感一致；反过来（值越小越靠前）
 * 就得用负数，越用越乱。
 */

/**
 * 把「从上到下」的书籍 id 顺序换算成 sort_order 赋值。
 *
 * @param idsInOrder 从上到下（屏幕上第 1 个在前）的书籍 id
 * @return 书籍 id 到 sort_order 的映射。第一个（最靠上）的值最大。
 *
 * 例：`[3, 1, 2]` 得到 `{3: 3, 1: 2, 2: 1}`
 */
internal fun assignShelfSortOrders(idsInOrder: List<Int>): Map<Int, Int> {
    val size = idsInOrder.size
    val orders = LinkedHashMap<Int, Int>(size)
    for (i in idsInOrder.indices) {
        // 倒着赋值：下标 0（最靠上）拿到最大值 size，最后一个拿到 1。
        orders[idsInOrder[i]] = size - i
    }
    return orders
}

/**
 * 把「可见项的新顺序」映射回**全量**列表。
 *
 * ## 为什么需要这一步
 *
 * 分类筛选时屏幕上只有一部分书。用户拖动的是这个**子集**，
 * 而 sort_order 表达的是整本书架的相对顺序 —— 直接拿子集顺序去落库，
 * 没显示出来的书会被挤到末尾（它们不在列表里，于是分不到顺序值，
 * 只能拿默认 0，排在所有有顺序值的书之后）。
 *
 * ## 规则
 *
 *  · 可见项：按用户拖出的新顺序重排，彼此的相对位置不变；
 *  · 不可见项：保持各自在原列表中的位置（只是周围的可见项换了人）。
 *
 * @param full 全量列表（当前顺序）
 * @param visibleIds 可见项**新**顺序（书籍 id，从上到下）
 * @return 映射后的全量列表
 *
 * ## 为什么用 id 而不是书名做 key
 *
 * 同一本书名在 ebooks 表里**可能有多行**（重复导入 / 历史上重复登记过）：
 * 书名相同但 id 不同，是两条独立的书架记录，各有各的顺序。
 * 用书名做 key 会把它们当成同一本 —— 映射时互相顶替，
 * 结果是「有一本书被显示了两次、另一本消失了」，落库的顺序也就全乱了。
 * id 是主键，唯一 ✅。
 *
 * 虚拟条目（只有合成任务、没有电子书文件）的 id 恒为 0，
 * 它们不参与排序（顺序存不住），这里统一按「不可见项」处理、原地不动。
 */
internal fun mergeShelfOrderIntoFullList(
    full: List<ShelfEntry>,
    visibleIds: List<Int>
): List<ShelfEntry> {
    if (visibleIds.isEmpty()) return full
    val byId = HashMap<Int, ShelfEntry>(full.size)
    for (e in full) {
        // id == 0 是虚拟条目，不能作为 key（它们全都撞在一起）
        if (e.book.id != 0) byId[e.book.id] = e
    }
    // 只保留**真实存在**的可见项，且每本只算一次：
    // visibleIds 里混进了不存在的 id 时，它不能占用一个可见位置，
    // 否则后面的书会整体前移一位（表现为有一本书被重复显示、另一本消失）。
    val picked = LinkedHashSet<Int>()
    for (id in visibleIds) {
        if (byId.containsKey(id)) picked.add(id)
    }
    val pickedList = picked.toList()
    val result = ArrayList<ShelfEntry>(full.size)
    var vi = 0
    for (e in full) {
        if (e.book.id != 0 && picked.contains(e.book.id) && vi < pickedList.size) {
            // 可见项：换成本次拖动后的那一个（visibleIds 的第 vi 个）
            result.add(byId[pickedList[vi]] ?: e)
            vi++
        } else {
            // 不可见项（含虚拟条目）：原地不动
            result.add(e)
        }
    }
    return result
}

/**
 * 把「拖拽期间暂存的那份刷新」按**当前屏幕顺序**重排（拖拽结束回放时用）。
 *
 * ## 为什么需要它
 *
 * 拖拽期间收到的列表刷新来自数据库（`sort_order` 还是**拖之前**的值），
 * 里面不含用户刚拖出来的顺序。若松手时直接把它 submit 进去，
 * 适配器的条目会被整体替换成这份旧顺序 —— 表现正是「松手 → 整架书跳回原位 →
 * 等 `sort_order` 落库回来 → 再跳一次」。而暂存刷新本来就是为了**防止**这种跳动。
 *
 * 所以回放前先按当前（已拖好的）顺序重排：已有条目沿用屏幕上的相对位置，
 * 拖拽期间新出现的条目（别的设备同步来的任务行等）排在最后、彼此保持
 * `incoming` 里的相对顺序。
 *
 * ## 为什么 key 用 (id, tableName)
 *
 * 与 `ShelfAdapter.ShelfDiffCallback.areItemsTheSame` 同一口径。
 * 虚拟条目（只有合成任务、没有电子书文件）的 `id` 恒为 0 —— 只按 id 做 key
 * 会让它们互相覆盖，顺序计算随即失效。
 *
 * @param current 适配器当前顺序（拖拽后的顺序）
 * @param incoming 拖拽期间暂存的刷新列表
 */
internal fun sortIncomingByCurrentOrder(
    current: List<ShelfEntry>,
    incoming: List<ShelfEntry>
): List<ShelfEntry> {
    // 空书架 / 单条没有顺序可言，直接原样返回（省掉一次排序）
    if (current.isEmpty() || incoming.size < 2) return incoming
    val position = HashMap<Pair<Int, String>, Int>(current.size)
    current.forEachIndexed { index, entry ->
        position[entry.book.id to entry.book.tableName] = index
    }
    // sortedBy 是稳定排序：未命中（新条目）全部拿 Int.MAX_VALUE，
    // 它们之间的相对顺序因此原样保留。用 Int.MAX_VALUE 而不是 -1，
    // 是为了让「命中下标」与「未命中」在同一方向比较 —— 否则新条目会被排到最前。
    return incoming.sortedBy { position[it.book.id to it.book.tableName] ?: Int.MAX_VALUE }
}
