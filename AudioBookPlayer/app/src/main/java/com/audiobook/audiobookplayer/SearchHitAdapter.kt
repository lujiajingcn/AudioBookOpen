package com.audiobook.audiobookplayer

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 书内搜索的一条命中。
 *
 * [rowIndex] 是**正文行下标**（可直接喂给 BookContentActivity.jumpToPosition）；
 * [lineNumber] 只是行号，用于展示 —— 两者不是一回事：阅读页显示的是原文分行，
 * 而行号可能在切分粒度变化后漂移。
 *
 * [matchEnd] 左闭右开。
 *
 * [chapter] 是命中处**所属章节标题**（来自目录，取「行下标 ≤ 本行」的最近一条）。
 * 为空串表示这本书没有目录（TXT 常见）或当前行在第一个章节标题之前。
 * 加它是因为只显示「第 1234 行」对一本几百页的书毫无定位意义 —— 用户既不知道
 * 离自己刚读到的地方多远，也不知道翻过去是哪个部分。
 */
data class SearchHit(
    val rowIndex: Int,
    val lineNumber: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int,
    val chapter: String = ""
) {
    /** 去重键：同一行 + 同一区间即同一条命中 */
    val key: String get() = "$rowIndex:$matchStart:$matchEnd"
}

/**
 * 搜索结果的列表适配器。
 *
 * 每条只显示**命中处前后的上下文**（不是整行）—— 电子书一行可能很长，
 * 整行铺出来用户根本看不到关键词在哪；截取半径 18 个字符，
 * 两端被截断时补省略号，命中词本身加粗 + 底色。
 */
class SearchHitAdapter(
    private val onClick: (SearchHit) -> Unit
) : ListAdapter<SearchHit, SearchHitAdapter.ViewHolder>(HitDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_hit, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val hit = getItem(position)
        holder.bind(hit, onClick)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvContext: TextView = itemView.findViewById(R.id.tvHitContext)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvHitLocation)

        fun bind(hit: SearchHit, onClick: (SearchHit) -> Unit) {
            tvContext.text = snippet(hit)
            // 章节名在前、行号在后：用户是靠章节认位置的，行号只是补充。
            // 没有目录的书（TXT）退化为原来的「第 N 行」，不留一个空的「· 」。
            tvLocation.text = if (hit.chapter.isBlank()) {
                "第 ${hit.lineNumber} 行"
            } else {
                "${hit.chapter} · 第 ${hit.lineNumber} 行"
            }
            itemView.setOnClickListener { onClick(hit) }
        }

        /** 命中词前后各 18 个字符 + 两端省略号；命中区间在裁出的片段里重新换算 */
        private fun snippet(hit: SearchHit): CharSequence {
            val radius = SNIPPET_RADIUS
            val from = (hit.matchStart - radius).coerceAtLeast(0)
            val to = (hit.matchEnd + radius).coerceAtMost(hit.lineText.length)
            val prefix = if (from > 0) "…" else ""
            val suffix = if (to < hit.lineText.length) "…" else ""
            val body = hit.lineText.substring(from, to)
            val start = prefix.length + (hit.matchStart - from)
            val end = prefix.length + (hit.matchEnd - from)

            return SpannableString(prefix + body + suffix).apply {
                setSpan(
                    StyleSpan(Typeface.BOLD),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    BackgroundColorSpan(
                        ContextCompat.getColor(itemView.context, R.color.highlight_search)
                    ),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    class HitDiffCallback : DiffUtil.ItemCallback<SearchHit>() {
        override fun areItemsTheSame(oldItem: SearchHit, newItem: SearchHit): Boolean =
            oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: SearchHit, newItem: SearchHit): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val SNIPPET_RADIUS = 18
    }
}
