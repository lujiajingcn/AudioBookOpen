package com.audiobook.audiobookplayer

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.SharedReferenceAudio

/**
 * 「发现 - 参考音频」排行榜适配器。
 *
 * 名次由服务端随列表下发（rank 从 1 开始），客户端不自己数 position——
 * 列表将来若加头部分组或分页，自己数就会与后端口径对不上。
 */
class SharedReferenceAdapter(
    private val onPreviewClick: (SharedReferenceAudio) -> Unit,
    private val onUseClick: (SharedReferenceAudio) -> Unit,
    private val onApplyClick: (SharedReferenceAudio) -> Unit
) : ListAdapter<SharedReferenceAudio, SharedReferenceAdapter.SharedViewHolder>(SharedDiffCallback()) {

    /** 正在试听的条目 id；同一时间只允许一个在播 */
    private var previewingId: Long? = null

    /** 正在处理「使用」的条目 id，按钮置灰防重复点击 */
    private var usingId: Long? = null

    /** 该条目是否正在试听（用于「试听 / 停止」的切换判断） */
    fun isPreviewing(id: Long): Boolean = previewingId == id

    fun setPreviewing(id: Long?) {
        val old = previewingId
        previewingId = id
        notifyItemChangedById(old)
        notifyItemChangedById(id)
    }

    fun setUsing(id: Long?) {
        val old = usingId
        usingId = id
        notifyItemChangedById(old)
        notifyItemChangedById(id)
    }

    private fun notifyItemChangedById(id: Long?) {
        if (id == null) return
        val idx = currentList.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SharedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shared_reference, parent, false)
        return SharedViewHolder(view)
    }

    override fun onBindViewHolder(holder: SharedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SharedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRank: TextView = itemView.findViewById(R.id.tvRank)
        private val tvName: TextView = itemView.findViewById(R.id.tvSharedName)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvSharedMeta)
        private val btnPreview: Button = itemView.findViewById(R.id.btnPreviewShared)
        private val btnUse: Button = itemView.findViewById(R.id.btnUseShared)
        private val btnApply: Button = itemView.findViewById(R.id.btnApplyToBook)

        fun bind(item: SharedReferenceAudio) {
            tvRank.text = item.rank.toString()
            tintRankBadge(item.rank)

            tvName.text = item.name
            val size = if (item.fileSize > 0) " · ${FileUtils.formatSize(item.fileSize)}" else ""
            val desc = item.description?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            tvMeta.text = "by ${item.ownerName} · 使用 ${item.useCount} 次$size$desc"

            // 文案与布局里的初始值（"合成试听"）保持一致：
            // 这里若还写"试听"，按钮在第一次状态复位后会"变回"旧名字。
            val isPreviewing = previewingId == item.id
            btnPreview.text = if (isPreviewing) "停止" else "合成试听"
            btnPreview.setOnClickListener { onPreviewClick(item) }

            // 本人分享的不显示「使用」：自己用自己不算使用量，服务端也会跳过计数
            val owned = item.owned
            when {
                item.mine -> {
                    btnUse.visibility = View.GONE
                    // 必须清掉监听器：View 是复用的，不清会带着上一条 item 的引用
                    btnUse.setOnClickListener(null)
                }
                owned -> {
                    btnUse.visibility = View.VISIBLE
                    btnUse.text = "已使用"
                    btnUse.isEnabled = false
                    btnUse.setOnClickListener(null)
                }
                else -> {
                    btnUse.visibility = View.VISIBLE
                    btnUse.text = if (usingId == item.id) "使用中…" else "使用"
                    btnUse.isEnabled = usingId != item.id
                    btnUse.setOnClickListener { onUseClick(item) }
                }
            }

            // 「用这个声音读我的书」：一键套用（即便已拥有也可用，只是跳过导入/计数直接跳 TTS）
            btnApply.isEnabled = usingId != item.id
            btnApply.setOnClickListener { onApplyClick(item) }
        }

        /** 前三名金/银/铜，其余保持灰底 */
        private fun tintRankBadge(rank: Int) {
            val color = when (rank) {
                1 -> 0xFFF5A623.toInt()
                2 -> 0xFF9E9E9E.toInt()
                3 -> 0xFFC68642.toInt()
                else -> 0xFFBDBDBD.toInt()
            }
            // 背景由 XML inflate，各 item 的 drawable 共享同一个 ConstantState。
            // 不先 mutate() 直接 setColor/setTint，改动会落到共享状态上，
            // 结果是所有徽章一起变成最后一次设置的颜色（名次配色失效）。
            val drawable = tvRank.background?.mutate()
            if (drawable is GradientDrawable) {
                drawable.setColor(color)
            } else {
                drawable?.setTint(color)
            }
        }
    }

    class SharedDiffCallback : DiffUtil.ItemCallback<SharedReferenceAudio>() {
        override fun areItemsTheSame(
            oldItem: SharedReferenceAudio,
            newItem: SharedReferenceAudio
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: SharedReferenceAudio,
            newItem: SharedReferenceAudio
        ): Boolean {
            return oldItem == newItem
        }
    }
}
