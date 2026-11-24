package com.audiobook.audiobookplayer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.Favorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的收藏列表页面。
 *
 * - 显示所有已收藏的文本条目（书名 + 段落号 + 内容预览）
 * - 点击条目 → 跳转到该书的对应段落位置
 * - 点击删除 → 弹出确认框，确认后删除
 */
class FavoritesFragment : Fragment() {

    private lateinit var viewModel: BookViewModel
    // ---------- 视图引用 ----------
    // replace() + addToBackStack 装载的 Fragment，视图销毁后实例仍留在返回栈里
    // （状态只降到 CREATED，onDestroy 不会调用）。字段若一直指向旧 View，整棵 View 树
    // 连同 Activity 会被一直拽着不放，直到该返回栈条目被弹出。故这里用可空后备字段持有，
    // onDestroyView 统一置空；非空取值器让使用处无需改动，且销毁后误访问会立刻抛错，
    // 而不是静默操作一个已经死掉的视图。
    private var _recyclerView: RecyclerView? = null
    private val recyclerView: RecyclerView get() = _recyclerView ?: error("视图已销毁: recyclerView")
    private var _tvEmpty: TextView? = null
    private val tvEmpty: TextView get() = _tvEmpty ?: error("视图已销毁: tvEmpty")
    private lateinit var adapter: FavoritesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_favorites, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _recyclerView = view.findViewById(R.id.recyclerViewFavorites)
        _tvEmpty = view.findViewById(R.id.tvEmpty)

        viewModel = ViewModelProvider(requireActivity())[BookViewModel::class.java]

        adapter = FavoritesAdapter(
            onItemClick = { favorite -> openFavoriteInReader(favorite) },
            onDeleteClick = { favorite -> confirmDelete(favorite) }
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 观察收藏列表，数据变化时刷新 UI
        viewModel.favorites.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        // 进入页面时加载数据（ViewModel 持有，数据不会在切页面时丢失）
        viewModel.loadFavorites()
    }

    /**
     * 跳转到 BookContentActivity 并自动定位到收藏条目对应的段落。
     *
     * 定位信息传两份：`SCROLL_TO_TEXT`（收藏时选中的原文片段，**第一判据**）与
     * `SCROLL_TO_LINE`（当时的行号，兜底）。行号是切分粒度的函数，切分规则一变就漂，
     * 所以不能让行号单独承担定位职责。
     *
     * ## 为什么必须带上书城 id 与 STORE_ONLINE
     * 收藏是在**书城在线读**时记下的（那本书没有本地电子书文件），书名是服务端标题
     * （如 `Linux命令行大全_一句话解释`，**无扩展名**）；而书架里那本可能叫
     * `Linux命令行大全_一句话解释.txt`（**有扩展名**，下载/导入时的原始文件名）。
     *
     * 旧实现只传书名 + 文件路径：
     * - 文件路径按 display_name **精确**查 ⇒ 差一个扩展名就查不到 ⇒ 不传 FILE_PATH；
     * - 也不传 STORE_ONLINE / STORE_BOOK_ID ⇒ 阅读页走 `openBook()` 这条空壳路径
     *   ⇒ 日志「书籍表不存在或读取失败，以空内容打开」⇒ **正文空白，跳不过去**。
     *
     * 现在：按书名找书架条目（[ShelfBookLocation]）拿到真实展示名 + 书城 id，
     * 书城书带 `STORE_ONLINE`/`STORE_BOOK_ID` 走在线读，其余走本地文件。
     */
    private fun openFavoriteInReader(favorite: Favorite) {
        // 挂起函数直接返回路径
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val location = viewModel.findShelfBookLocation(favorite.bookName)
            withContext(Dispatchers.Main) {
                // 再次检查 Fragment 生命周期，防止用户在协程执行前已离开页面
                if (!isAdded || requireActivity().isFinishing) return@withContext

                val intent = Intent(requireContext(), BookContentActivity::class.java).apply {
                    // 书名用**收藏记录里的那个**：收藏是在「书城在线读」时记下的，
                    // 那时的书名就是服务端标题，也是 `audio_segments.book_name` 的取值
                    // ⇒ 在线读时能用同一份音频缓存。
                    putExtra("BOOK_TABLE_NAME", favorite.bookName)
                    val filePath = location?.filePath.orEmpty()
                    if (filePath.isNotEmpty()) {
                        putExtra("FILE_PATH", filePath)
                    }
                    // 书城来的书：走「在线读」路径（正文按 bookId 取，不下载电子书文件）。
                    // ⛔ 不传这个 ⇒ 阅读页找不到 ebooks 行 ⇒ 以空内容打开 ⇒ 跳不过去。
                    val storeId = location?.storeBookId ?: -1L
                    if (storeId > 0) {
                        putExtra("STORE_ONLINE", true)
                        putExtra("STORE_BOOK_ID", storeId)
                    }
                    // 通知阅读页在内容加载后自动定位到该段落
                    putExtra("SCROLL_TO_LINE", favorite.lineNumber)
                    // 定位的第一判据：收藏时选中的原文片段。
                    // 只传行号是不够的 —— 行号是「当时那一行的行号」，行切分规则一变
                    // （例如 TXT 从按句打包改成一行即一段）就指向别的文字了，文字内容不会变。
                    putExtra("SCROLL_TO_TEXT", favorite.content)
                }
                startActivity(intent)
            }
        }
    }

    /** 弹出确认删除对话框 */
    private fun confirmDelete(favorite: Favorite) {
        // 检查生命周期：Fragment detached 时不弹窗
        if (!isAdded) return

        AlertDialog.Builder(requireContext())
            .setTitle("删除收藏")
            .setMessage("确定要删除这条收藏吗？\n\n「${favorite.content.take(50)}${if (favorite.content.length > 50) "..." else ""}」")
            .setPositiveButton("删除") { _, _ ->
                // 对话框已Dismiss，回调中无需再检查 isAdded
                viewModel.deleteFavorite(favorite.id)
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        // 该 Fragment 由 replace() + addToBackStack 装载，视图销毁后实例仍留在返回栈里。
        // 必须先解绑 adapter，再断开视图引用——否则整棵 View 树会被一直拽着不放，
        // 直到该返回栈条目被弹出
        recyclerView.adapter = null

        // 断开视图引用：Fragment 实例在返回栈里还活着，
        // 不能让它继续拽着已经销毁的 View 树
        _recyclerView = null
        _tvEmpty = null
        super.onDestroyView()
    }
}
