package com.audiobook.audiobookplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.WordBookEntry

/**
 * 单词本列表适配器。
 *
 * - 点整行 → 朗读该词（[onSpeakClick]，复用 TextToSpeaker；引擎不可用时由 Fragment 弹 Toast）
 * - 点右侧删除 → [onDeleteClick]
 * - 长按整行 → [onLongClick]「跳转到原书」（无出处时由 Fragment 提示重新收藏）
 *
 * 只显示**首条**释义：ECDICT 里 abandon 这类词有 5+ 条义项，全展开会把每行撑到
 * 好几行高、列表变得难以速览。完整释义在阅读页查词弹窗里看。
 */
class WordBookAdapter(
    private val onSpeakClick: (WordBookEntry) -> Unit,
    private val onDeleteClick: (WordBookEntry) -> Unit,
    private val onLongClick: (WordBookEntry) -> Unit
) : ListAdapter<WordBookEntry, WordBookAdapter.VH>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_word_book, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    /** 出处副标题「书名 · 第 N 段/行」；无出处（旧收藏）返回空串让整块 GONE。 */
    private fun buildSourceLabel(item: WordBookEntry): String {
        val name = item.bookName?.takeIf { it.isNotBlank() } ?: return ""
        val seg = item.segmentNumber?.takeIf { it > 0 }
        val line = item.lineNumber?.takeIf { it > 0 }
        return when {
            seg != null -> "$name · 第 $seg 段"
            line != null -> "$name · 第 $line 行"
            else -> name
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvWord: TextView = itemView.findViewById(R.id.tvWord)
        private val tvPhonetic: TextView = itemView.findViewById(R.id.tvPhonetic)
        private val tvTranslation: TextView = itemView.findViewById(R.id.tvTranslation)
        private val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteWord)

        fun bind(item: WordBookEntry) {
            tvWord.text = item.word
            tvPhonetic.text = item.phonetic
            tvPhonetic.visibility = if (item.phonetic.isBlank()) View.GONE else View.VISIBLE

            val firstTranslation = item.translations.firstOrNull().orEmpty()
            tvTranslation.text = firstTranslation
            tvTranslation.visibility =
                if (firstTranslation.isBlank()) View.GONE else View.VISIBLE

            // 出处副标题：旧收藏 6 列全 null ⇒ 空串 ⇒ GONE，绝不显示空白占位。
            val sourceLabel = buildSourceLabel(item)
            tvSource.text = sourceLabel
            tvSource.visibility = if (sourceLabel.isEmpty()) View.GONE else View.VISIBLE

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSpeakClick(getItem(pos))
            }
            btnDelete.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDeleteClick(getItem(pos))
            }
            // 长按 → 跳转原书。同样用 bindingAdapterPosition 取当前条目：
            // 列表刷新/动画期间 ViewHolder 的 position 可能已错位，用旧 position 会跳错词。
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(getItem(pos))
                true   // 消费事件，避免长按后系统再补一次普通点击去朗读
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<WordBookEntry>() {
        override fun areItemsTheSame(oldItem: WordBookEntry, newItem: WordBookEntry): Boolean =
            oldItem.id == newItem.id

        // 直接比整个 data class（自动生成的 equals 对List 字段是结构化相等）。
        // 不用逐字段手写 —— 那样对 translations（List）写== 会被 lint 判
        // DiffUtilEquals 告警（它无法确定 List 有正确 equals）。这里语义本就
        // 「整个条目是否相同」，比整体最贴切。含id 无妨：areContentsTheSame 只在
        // areItemsTheSame 为 true（同 id）时才被问到。
        override fun areContentsTheSame(oldItem: WordBookEntry, newItem: WordBookEntry): Boolean =
            oldItem == newItem
    }
}