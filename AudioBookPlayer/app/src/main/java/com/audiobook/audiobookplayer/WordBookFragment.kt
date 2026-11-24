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
import com.audiobook.audiobookplayer.model.WordBookEntry
import com.audiobook.audiobookplayer.model.WordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单词本列表页（「我的 - 单词本」）。
 *
 * 数据源是本地 SQLite（[WordBookRepository]）的**词条快照**，不依赖 ECDICT 是否安装：
 * 收藏时音标/释义已写进库里，卸载词典后这里照常可读。
 *
 * - 点整行 → 朗读该词（复用 [TextToSpeaker]，与阅读页点读同一引擎；引擎不可用弹 Toast）
 * - 点右侧删除 → 确认后从库里删
 * - 长按整行 → 弹「跳转到原书」，打开该书并定位到收藏时的句子；旧收藏无出处则提示重收
 * - onResume 时重新加载：从阅读页弹窗收藏完返回、或在别处取消收藏，回到本页要看到最新值
 *
 * 视图引用用可空后备字段（与 [FavoritesFragment] 同款）：本 Fragment 由 replace() +
 * addToBackStack 装载，视图销毁后实例仍留在返回栈里，字段若一直指向旧 View 会把整棵
 * View 树连同 Activity 拽住不放，直到该返回栈条目被弹出。
 */
class WordBookFragment : Fragment() {

    private var _recyclerView: RecyclerView? = null
    private val recyclerView: RecyclerView get() = _recyclerView ?: error("视图已销毁: recyclerView")
    private var _tvEmpty: TextView? = null
    private val tvEmpty: TextView get() = _tvEmpty ?: error("视图已销毁: tvEmpty")
    private lateinit var adapter: WordBookAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_word_book, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _recyclerView = view.findViewById(R.id.recyclerViewWordBook)
        _tvEmpty = view.findViewById(R.id.tvWordBookEmpty)

        adapter = WordBookAdapter(
            onSpeakClick = { entry -> speakWord(entry.word) },
            onDeleteClick = { entry -> confirmDelete(entry) },
            onLongClick = { entry -> onWordLongClick(entry) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // 收藏可能发生在阅读页弹窗里（用户收藏完才返回），所以每次回到本页都重新读库。
        // 顺便跑一次云端对账：跨设备新增/删除的词在这一次刷新里就会体现。
        // 未登录 / 网络失败 → syncWithServer 返回 false，本地列表照常展示（缓存读）。
        reload()
    }

    private fun reload() {
        viewLifecycleOwner.lifecycleScope.launch {
            // 先拉一次本地立即渲染，再后台同步；同步成功就再刷一遍覆盖新数据。
            // 顺序而不是 sync→reload 的理由：网络慢时列表不至于白屏，用户观感与
            // FavoritesFragment / BookListFragment 的书架同步一致。
            val local = withContext(Dispatchers.IO) { WordBookRepository.listAll() }
            if (_recyclerView == null) return@launch
            adapter.submitList(local)
            val showEmpty = local.isEmpty()
            _tvEmpty?.visibility = if (showEmpty) View.VISIBLE else View.GONE

            val synced = withContext(Dispatchers.IO) { WordBookRepository.syncWithServer() }
            if (!synced) return@launch
            if (_recyclerView == null) return@launch
            val merged = withContext(Dispatchers.IO) { WordBookRepository.listAll() }
            if (_recyclerView == null) return@launch
            adapter.submitList(merged)
            _tvEmpty?.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** 点整行朗读。引擎不可用（无 Google TTS 等）时给提示，而不是静默无声。 */
    private fun speakWord(word: String) {
        val ok = TextToSpeaker.speak(requireContext(), word)
        if (!ok) {
            Toast.makeText(requireContext(), "本机未安装可用的语音引擎", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(entry: WordBookEntry) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("取消收藏")
            .setMessage("确定要从单词本移除「${entry.word}」吗？")
            .setPositiveButton("移除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { WordBookRepository.remove(entry.sw) }
                    Toast.makeText(
                        requireContext(),
                        if (ok) "已移除" else "移除失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 长按单词：弹出「跳转到原书」。
     *
     * 出处 6 列全 null 的旧收藏（v13 之前 / 服务端返回 null）**禁用跳转**：
     * 只有书名不知道定位到哪句，只有锚点却不知道打开哪本。给明确提示引导重新收藏，
     * 而不是跳过去白屏（见 [WordSource.canJumpToBook]）。
     */
    private fun onWordLongClick(entry: WordBookEntry) {
        if (!isAdded) return
        if (!WordSource.canJumpToBook(entry.bookName, entry.sentenceText, entry.lineNumber)) {
            Toast.makeText(
                requireContext(),
                "该词没有记录出处（旧收藏），请在阅读页重新收藏一次",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("跳转到原书")
            .setMessage("打开《${entry.bookName}》并定位到收藏时的句子？")
            .setPositiveButton("跳转") { _, _ -> openWordInReader(entry) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开原书并定位到收藏时的句子（照搬 [FavoritesFragment.openFavoriteInReader]）。
     *
     * 定位信息传两份：`SCROLL_TO_TEXT`（收藏时所在句子原文，**第一判据**）与
     * `SCROLL_TO_LINE`（行号，**兜底**）—— 行号会随切分粒度漂移，文字不会。
     *
     * ## 书城书务必带 STORE_ONLINE + STORE_BOOK_ID
     * 书城「在线读」收藏时没有本地电子书文件，书架按 display_name 反查可能落空，
     * 只传书名 + 路径会让阅读页走「书籍表不存在 → 以空内容打开」⇒ 正文空白跳不过去。
     * 收藏时已把书城 id 记在 [WordBookEntry.storeBookId]，**优先用它**，书架反查仅兜底。
     */
    private fun openWordInReader(entry: WordBookEntry) {
        val vm = ViewModelProvider(requireActivity())[BookViewModel::class.java]
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val location = vm.findShelfBookLocation(entry.bookName.orEmpty())
            withContext(Dispatchers.Main) {
                // 再次检查生命周期：协程排队期间用户可能已经离开本页
                if (!isAdded || requireActivity().isFinishing) return@withContext

                val intent = Intent(requireContext(), BookContentActivity::class.java).apply {
                    // 书名用收藏时记下的那个（= audio_segments.book_name，在线读共用音频缓存）
                    putExtra("BOOK_TABLE_NAME", entry.bookName.orEmpty())
                    val filePath = location?.filePath.orEmpty()
                    if (filePath.isNotEmpty()) putExtra("FILE_PATH", filePath)

                    val storeId = entry.storeBookId?.takeIf { it > 0 }
                        ?: location?.storeBookId?.takeIf { it > 0 }
                    if (storeId != null && storeId > 0) {
                        putExtra("STORE_ONLINE", true)
                        putExtra("STORE_BOOK_ID", storeId)
                    }

                    // 定位第一判据：句子原文；行号仅兜底
                    if (!entry.sentenceText.isNullOrBlank()) {
                        putExtra("SCROLL_TO_TEXT", entry.sentenceText.orEmpty())
                    }
                    entry.lineNumber?.takeIf { it > 0 }?.let {
                        putExtra("SCROLL_TO_LINE", it)
                    }
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroyView() {
        // 必须先解绑 adapter 再断开视图引用，否则整棵 View 树会被返回栈里的实例一直拽着
        recyclerView.adapter = null
        _recyclerView = null
        _tvEmpty = null
        super.onDestroyView()
    }
}