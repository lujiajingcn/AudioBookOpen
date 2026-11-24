package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.Book

class BookListAdapter(
    private val onItemClick: (Book) -> Unit,
    private val onDeleteClick: (Book) -> Unit = {},
    private val onSelectionChange: (() -> Unit)? = null
) : ListAdapter<Book, BookListAdapter.ViewHolder>(BookDiffCallback()) {

    private val selectedBooks = mutableSetOf<String>()  // 按 tableName 去重

    /** 是否处于多选模式（可同时选择多本） */
    var isSelectMode: Boolean = false

    fun getSelectedBooks(): List<Book> =
        selectedBooks.mapNotNull { tableName ->
            currentList.find { it.tableName == tableName }
        }

    fun clearSelection() {
        selectedBooks.clear()
        notifyItemRangeChanged(0, itemCount)
        onSelectionChange?.invoke()
    }

    private fun toggleSelection(book: Book) {
        if (!selectedBooks.add(book.tableName)) {
            selectedBooks.remove(book.tableName)
        }
        val pos = currentList.indexOfFirst { it.tableName == book.tableName }
        if (pos >= 0) notifyItemChanged(pos)
        onSelectionChange?.invoke()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById<TextView>(android.R.id.text1)
        private val ivAudioIndicator: ImageView = itemView.findViewById<ImageView>(R.id.ivAudioIndicator)
        private val deleteButton: ImageButton? = itemView.findViewById<ImageButton>(R.id.btnDelete)
        private val checkBox: CheckBox? = itemView.findViewById<CheckBox>(R.id.cbSelect)

        fun bind(book: Book) {
            textView.text = book.displayName

            ivAudioIndicator.visibility = if (book.hasAudio) View.VISIBLE else View.GONE

            val isSelected = selectedBooks.contains(book.tableName)
            // 多选模式下显示 CheckBox，隐藏删除按钮；否则显示删除按钮
            checkBox?.isChecked = isSelected
            checkBox?.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            deleteButton?.visibility = if (!isSelectMode && book.filePath.isNotEmpty()) View.VISIBLE else View.GONE

            // 多选模式下条目背景高亮选中项。
            // 取 @color/book_selected_highlight 而不是写死色值 —— 配色改暖金后，
            // 原来写死的 0x223F51B5（浅蓝）会在金色界面里冒出一块蓝。
            val backgroundColor = if (isSelectMode && isSelected) {
                ContextCompat.getColor(itemView.context, R.color.book_selected_highlight)
            } else {
                android.graphics.Color.TRANSPARENT
            }
            itemView.setBackgroundColor(backgroundColor)

            itemView.setOnClickListener {
                if (isSelectMode) {
                    toggleSelection(book)
                } else {
                    onItemClick(book)
                }
            }

            checkBox?.setOnClickListener {
                toggleSelection(book)
            }

            deleteButton?.setOnClickListener {
                onDeleteClick(book)
            }
        }
    }

    class BookDiffCallback : DiffUtil.ItemCallback<Book>() {
        override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
            return oldItem.id == newItem.id && oldItem.tableName == newItem.tableName
        }

        override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
            return oldItem == newItem
        }
    }
}
