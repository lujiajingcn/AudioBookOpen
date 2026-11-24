package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.ShelfEntry

/**
 * 搜索结果列表。
 *
 * 比 ShelfAdapter 简单得多：只展示「书名 + 分类」两行文本，不带任何任务行
 * 的下载/重试/删除入口——搜索页的目标是「找到想读的书并打开」，
 * 管理类操作请回到书架页。
 */
class SearchResultAdapter(
    private val onClick: (ShelfEntry) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val items: MutableList<ShelfEntry> = mutableListOf()

    fun submit(list: List<ShelfEntry>) {
        items.clear()
        items.addAll(list)
        // 搜索结果通常很短（用户筛了几个字），全量刷新即可；不引 DiffUtil 的复杂度
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val book = entry.book
        holder.title.text = book.displayName
        val subtitle = book.category.ifBlank { "" }
        if (subtitle.isEmpty()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = "分类：$subtitle"
        }
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
    }
}
