package com.audiobook.audiobookplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.network.AuthManager
import org.json.JSONArray
import org.json.JSONObject

class BookListActivity : BaseActivity() {

    private lateinit var viewModel: BookViewModel
    private lateinit var adapter: BookListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnConfirm: Button
    private lateinit var tvSelectHint: TextView

    private var isSelectMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_list)

        AuthManager.init(this)

        // 游客可进入：本页只做「本地书目读取 + 多选结果回传」，不触碰服务端账号。
        // 与登录相关的后续操作（如云端合成提交）由调用方在拿到选择结果后按需 requireLogin。

        isSelectMode = intent.getBooleanExtra("SELECT_MODE", false)

        // 本 Activity 只服务多选模式（SELECT_MODE）：普通阅读的主入口是
        // MainActivity（底部导航），多选结果返回后才回到这里。
        if (!isSelectMode) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        recyclerView = findViewById(R.id.recyclerView)
        btnConfirm = findViewById(R.id.btnConfirm)
        tvSelectHint = findViewById(R.id.tvSelectHint)

        viewModel = ViewModelProvider(this)[BookViewModel::class.java]

        adapter = BookListAdapter(
            onItemClick = { book ->
                if (isSelectMode) {
                    // 多选模式下点击条目由 Adapter 内部切换选中状态，这里无需处理
                    updateConfirmButton()
                } else {
                    val intent = Intent(this, BookContentActivity::class.java).apply {
                        putExtra("BOOK_TABLE_NAME", book.tableName)
                    }
                    startActivity(intent)
                }
            },
            onSelectionChange = {
                updateConfirmButton()
            }
        )
        adapter.isSelectMode = isSelectMode

        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        if (isSelectMode) {
            // 多选模式：显示提示和确认按钮
            tvSelectHint.visibility = View.VISIBLE
            btnConfirm.visibility = View.VISIBLE

            viewModel.ebooks.observe(this) { list ->
                adapter.submitList(list)
                updateConfirmButton()
            }
            viewModel.loadEbooksForSelection()
        } else {
            viewModel.books.observe(this) { list ->
                adapter.submitList(list)
            }
        }

        btnConfirm.setOnClickListener {
            val selectedBooks = adapter.getSelectedBooks()
            if (selectedBooks.isEmpty()) {
                Toast.makeText(this, "请至少选择一本书", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 用 JSON 把选中的所有书传回调用方
            val jsonArray = JSONArray()
            for (book in selectedBooks) {
                val obj = JSONObject()
                obj.put("tableName", book.tableName)
                obj.put("displayName", book.displayName)
                obj.put("filePath", book.filePath)
                jsonArray.put(obj)
            }

            val resultIntent = Intent().apply {
                putExtra("BOOKS_JSON", jsonArray.toString())
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    /**
     * 根据当前选中数量更新确认按钮的文案和可用状态
     */
    private fun updateConfirmButton() {
        if (!isSelectMode) return
        val count = adapter.getSelectedBooks().size
        btnConfirm.isEnabled = count > 0
        btnConfirm.text = if (count == 0) {
            "✅ 确认选择"
        } else {
            "✅ 确认选择（已选 $count 本）"
        }
    }
}