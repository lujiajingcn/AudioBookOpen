package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.TocEntry

class TocListAdapter(
    private val onItemClick: (TocEntry) -> Unit
) : ListAdapter<TocEntry, TocListAdapter.TocViewHolder>(TocDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_toc_entry, parent, false)
        return TocViewHolder(view)
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TocViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTocTitle)

        // 右边距取布局里声明的原始值。bind 会被复用调用，若直接读当前 paddingRight，
        // 每次复用都会在上一次的基础上继续累加缩进。
        private val basePaddingEnd = tvTitle.paddingEnd

        fun bind(entry: TocEntry) {
            // 根据层级设置缩进，level 每增 1 增加 16dp
            val indentPx = (entry.level * 32 * itemView.context.resources.displayMetrics.density).toInt()
            // 修复：第三个参数原先误传 paddingBottom，导致右边距被写成底部边距的值
            tvTitle.setPaddingRelative(indentPx, tvTitle.paddingTop, basePaddingEnd, tvTitle.paddingBottom)
            tvTitle.text = entry.title

            itemView.setOnClickListener { onItemClick(entry) }
        }
    }

    class TocDiffCallback : DiffUtil.ItemCallback<TocEntry>() {
        override fun areItemsTheSame(oldItem: TocEntry, newItem: TocEntry): Boolean {
            return oldItem.title == newItem.title &&
                   oldItem.segmentNumber == newItem.segmentNumber &&
                   oldItem.paragraphIndex == newItem.paragraphIndex
        }

        override fun areContentsTheSame(oldItem: TocEntry, newItem: TocEntry): Boolean {
            return oldItem == newItem
        }
    }
}