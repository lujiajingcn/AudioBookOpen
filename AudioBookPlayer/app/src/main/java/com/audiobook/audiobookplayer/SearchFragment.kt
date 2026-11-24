package com.audiobook.audiobookplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.ShelfEntry
import com.audiobook.audiobookplayer.model.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：从书架页右上角的放大镜进入。
 *
 * - 顶部返回 + 输入框 + 提交键（参考主流音乐/阅读类应用的搜索页）。
 * - 下方实时显示搜索结果（书名包含关键字的本地 + 任务聚合条目）。
 * - 搜索的目标只是「找到想读的书并打开」，不展示任务行 / 下载 / 删除等管理操作。
 */
class SearchFragment : Fragment() {

    private lateinit var viewModel: BookViewModel
    private lateinit var btnBack: ImageButton
    private lateinit var etSearch: EditText
    private lateinit var btnSearchSubmit: ImageButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: SearchResultAdapter

    private var searchKeyword: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[BookViewModel::class.java]

        btnBack = view.findViewById(R.id.btnBack)
        etSearch = view.findViewById(R.id.etSearch)
        btnSearchSubmit = view.findViewById(R.id.btnSearchSubmit)
        recyclerView = view.findViewById(R.id.recyclerView)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = SearchResultAdapter { entry -> onEntryClick(entry) }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // 返回一律交给宿主：直接 popBackStack 只做出栈，不会恢复底部导航栏，
        // 出栈后底栏会一直消失（系统返回键有单独处理，只有点这个箭头才复现）
        btnBack.setOnClickListener {
            val host = activity as? MainActivity
            if (host != null) {
                host.popFullScreenFragment()
            } else {
                parentFragmentManager.popBackStack()
            }
        }
        btnSearchSubmit.setOnClickListener {
            searchKeyword = etSearch.text.toString().trim()
            refreshList()
            hideKeyboard()
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchKeyword = s?.toString()?.trim().orEmpty()
                refreshList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchKeyword = etSearch.text.toString().trim()
                refreshList()
                true
            } else false
        }

        // 观感：打开搜索页时直接获得输入焦点 + 弹起键盘。
        // 此时宿主页（书架）已经 STOPPED，本 Fragment 是 STARTED，requestFocus 立即生效。
        etSearch.requestFocus()
        view.post { showKeyboard() }

        viewModel.shelfEntries.observe(viewLifecycleOwner) { all -> refreshList(all) }
    }

    /**
     * 重算结果并刷新。
     *
     * 注意：空关键字时**不**显示「全部书架」——搜索页是「主动找一本书」的场景，
     * 把全部条目都列出来既无意义又和首页书架重复；用「请输入书名或作者名」做引导。
     */
    private fun refreshList(all: List<ShelfEntry>? = viewModel.shelfEntries.value) {
        if (all == null) return
        val kw = searchKeyword
        val visible = if (kw.isEmpty()) emptyList() else all.filter { entry ->
            entry.book.displayName.contains(kw, ignoreCase = true)
        }
        adapter.submit(visible)

        val isEmpty = visible.isEmpty()
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        tvEmpty.text = when {
            kw.isEmpty() -> "请输入书名或作者名"
            all.isEmpty() -> "书架还没有书，先去导入一本吧"
            else -> "没有匹配「$kw」的书"
        }
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        etSearch.clearFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    /**
     * 命中结果点击：复用 BookListFragment 的 onShelfEntryClick 逻辑——
     * 有本地电子书文件 → 打开阅读器；只有任务 → 用任务的服务端 ID 打开阅读器。
     */
    private fun onEntryClick(entry: ShelfEntry) {
        val book = entry.book
        if (entry.hasEbookFile) {
            viewModel.markBookOpened(book.displayName)
            val intent = Intent(requireContext(), BookContentActivity::class.java).apply {
                putExtra("BOOK_TABLE_NAME", book.displayName)
                putExtra("FILE_PATH", book.filePath)
            }
            startActivity(intent)
            return
        }

        val task = entry.latestTask
        if (task != null && task.hasAudio) {
            openBookReader(task)
        } else {
            Toast.makeText(requireContext(), "该书暂无可阅读内容", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBookReader(task: Task) {
        viewModel.markBookOpened(task.bookName)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val filePath = viewModel.findEbookFilePathByDisplayName(task.bookName)
            withContext(Dispatchers.Main) {
                val ctx = context ?: return@withContext
                val intent = Intent(ctx, BookContentActivity::class.java).apply {
                    putExtra("BOOK_TABLE_NAME", task.bookName)
                    putExtra("BOOK_DISPLAY_NAME", task.bookName)
                    task.getQueryId()?.let { putExtra("SERVER_TASK_ID", it) }
                    filePath?.let { putExtra("FILE_PATH", it) }
                }
                startActivity(intent)
            }
        }
    }
}
