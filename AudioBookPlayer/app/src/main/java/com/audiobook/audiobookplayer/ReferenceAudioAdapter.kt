package com.audiobook.audiobookplayer

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.ReferenceAudio

/** 我的参考音频列表适配器 */
class ReferenceAudioAdapter(
    private val onDeleteClick: (ReferenceAudio) -> Unit,
    private val onShareClick: (ReferenceAudio) -> Unit,
    private val onPreviewClick: (ReferenceAudio, Boolean) -> Unit,
    /** 「试听原声」：直接播放这条参考音频本身（[Boolean] 为 true 表示正在播放原声，点击意为停止） */
    private val onPlayOriginalClick: (ReferenceAudio, Boolean) -> Unit = { _, _ -> }
) : ListAdapter<ReferenceAudio, ReferenceAudioAdapter.RefViewHolder>(RefDiffCallback()) {

    /**
     * 服务端已处于「分享中」的 id 集合。
     * 分享状态存在服务端而非本地库，所以由 Fragment 拉一次列表后塞进来；
     * 它不参与 DiffUtil 比较（本地条目本身没变），需要单独刷新。
     */
    private var sharedIds: Set<Long> = emptySet()

    /** 正在上传/切换中的 id，用于把按钮置灰防重复点击 */
    private var pendingIds: Set<Long> = emptySet()

    /** 正在「试听合成中」的 id（网络请求阶段，按钮置灰显示"试听中…"） */
    private var previewLoadingId: Long? = null

    /** 正在「播放试听」的 id（播放阶段，按钮显示"停止"） */
    private var previewPlayingId: Long? = null

    /** 正在「播放原声」的 id（按钮显示"停止原声"） */
    private var originalPlayingId: Long? = null

    /**
     * RecyclerView 局部刷新用的 payload。
     *
     * ## 为什么必须用 payload，而不是只缩小 notify 范围
     * 上一版把"整表重绑"改成了"只重绑变化的那一行"，但**闪烁依旧**：
     * `notifyItemChanged(pos)`（不带 payload）会让**整行**重新 bind，
     * 于是同一行里的「合成试听」也跟着被重置重绘 —— 而它其实压根没变。
     * 用户看到的就是"同一行两个按钮一起闪"。
     *
     * payload 机制允许"只更新行内的一部分"：
     * 带 payload 时 [onBindViewHolder] 走局部更新分支，**不碰**另一个按钮，
     * 于是它的视觉状态与触摸反馈都保持原样，不会闪。
     */
    private companion object {
        const val TAG = "RefAudioAdapter"
        const val PAYLOAD_PREVIEW = "payload_preview"
        const val PAYLOAD_ORIGINAL = "payload_original"
    }

    /**
     * 只刷新**显示状态确实变了**的行，并把"行内哪个按钮变了"作为 payload 传下去。
     *
     * ## 为什么不能再用 notifyItemRangeChanged(0, itemCount)
     * 原实现每次状态变化都通知整个列表重绑。这在播放结束时会造成肉眼可见的问题：
     * 用户点「试听原声」→ 播完 → 回调把状态清空 → 整个列表重绑 →
     * **所有记录的按钮同时闪一下**。
     *
     * ## 为什么缩小范围还不够，必须带 payload
     * 只把范围缩到"变化的那一行"仍然会闪：`notifyItemChanged(pos)` 不带 payload 时
     * 会重绑**整行**，同一行里没变化的「合成试听」也被重置重绘，
     * 用户看到的就是"同一行两个按钮一起闪"。
     * 带上 payload 后 [onBindViewHolder] 只更新那一个按钮，
     * 另一个的视觉状态与触摸反馈都原样保留。
     *
     * @param oldId 变化前处于该状态的条目 id
     * @param newId 变化后处于该状态的条目 id
     * @param payload 传给 [onBindViewHolder] 的标记，指明行内到底哪个控件需要更新
     */
    private fun notifyStateChanged(oldId: Long?, newId: Long?, payload: String) {
        if (oldId == newId) return
        listOfNotNull(oldId, newId).distinct().forEach { id ->
            val pos = positionOf(id)
            // pos < 0：该条目已不在当前列表里（比如刚被删除），无需刷新。
            // 必须判负 —— notifyItemChanged(负数) 会直接抛 IndexOutOfBoundsException。
            if (pos >= 0) notifyItemChanged(pos, payload)
        }
    }

    /** 按条目 id 找当前位置；找不到返回 -1（ListAdapter 的 currentList 是只读的） */
    private fun positionOf(id: Long): Int =
        currentList.indexOfFirst { it.id == id }

    fun setSharedIds(ids: Set<Long>) {
        sharedIds = ids
        notifyItemRangeChanged(0, itemCount)
    }

    fun setPendingIds(ids: Set<Long>) {
        pendingIds = ids
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * 同步试听状态：任一参数为 null 表示清空对应状态。
     * 只刷新受影响的行，且只更新行内的「合成试听」按钮（见 [notifyStateChanged]）。
     */
    fun setPreviewStates(loadingId: Long?, playingId: Long?) {
        val oldLoading = previewLoadingId
        val oldPlaying = previewPlayingId
        previewLoadingId = loadingId
        previewPlayingId = playingId
        notifyStateChanged(oldLoading, loadingId, PAYLOAD_PREVIEW)
        notifyStateChanged(oldPlaying, playingId, PAYLOAD_PREVIEW)
    }

    /**
     * 同步「原声播放」状态。
     *
     * 单独一个方法而不是并进 setPreviewStates：两者是**互斥**的
     * （同一个 MediaPlayer），如果放在一起，调用方每次都要同时想清楚四个 id，
     * 很容易出现"原声在播、试听按钮却还显示停止"这种不一致的界面。
     * 两者各带各的 payload，也正因如此「试听原声」的状态变化不会去动「合成试听」。
     */
    fun setOriginalPlaying(id: Long?) {
        val old = originalPlayingId
        originalPlayingId = id
        notifyStateChanged(old, id, PAYLOAD_ORIGINAL)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RefViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reference_audio, parent, false)
        return RefViewHolder(view)
    }

    /**
     * 带 payload 时走**局部更新**：只改那一个按钮，不碰整行。
     *
     * 这是消除闪烁的关键 —— 没有 payload 时 RecyclerView 会把整行重新 bind，
     * 行内所有按钮都被重置重绘；带 payload 则只更新指定的那个控件。
     */
    override fun onBindViewHolder(holder: RefViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = getItem(position)
        // 走的是哪条路径值得记下来：闪烁的根因是"整行重绑"，
        // 而闪烁本身没法用界面的 dump 观测；有这条日志，
        // "播放结束时到底重绑了整行还是只更新了一个按钮"就是可核对的事实。
        // 用 v 级：正常滚动/刷新也会走到这里，d 级在真机上刷得太快。
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            val path = when {
                payloads.contains(PAYLOAD_PREVIEW) -> "局部:合成试听"
                payloads.contains(PAYLOAD_ORIGINAL) -> "局部:试听原声"
                else -> "整行重绑"
            }
            Log.v(TAG, "bind[$position] '$path' payloads=$payloads")
        }
        when {
            payloads.contains(PAYLOAD_PREVIEW) -> holder.updatePreviewButton(item)
            payloads.contains(PAYLOAD_ORIGINAL) -> holder.updateOriginalButton(item)
            else -> holder.bind(item)
        }
    }

    override fun onBindViewHolder(holder: RefViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RefViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvRefName)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvRefMeta)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDeleteRef)
        private val btnShare: Button = itemView.findViewById(R.id.btnShareRef)
        private val btnPreview: Button = itemView.findViewById(R.id.btnPreviewRef)
        private val btnPlayOriginal: Button = itemView.findViewById(R.id.btnPlayOriginal)

        /**
         * 更新「合成试听」按钮的文案、可用性与点击行为。
         *
         * ⚠️ 三处文案必须与布局里的初始文案（"合成试听"）保持同一套。
         * 曾经布局写"试听"、这里复位也写"试听"，改名时若只改布局，
         * 按钮在第一次状态变化后就会"变回"旧名字 —— 看起来像改名没生效。
         */
        fun updatePreviewButton(item: ReferenceAudio) {
            // 网络合成中显示"合成中…"并置灰；播放中显示"停止"（点击即停）
            val isPreviewLoading = item.id == previewLoadingId
            val isPreviewPlaying = item.id == previewPlayingId
            when {
                isPreviewLoading -> {
                    btnPreview.text = "合成中…"
                    btnPreview.isEnabled = false
                }
                isPreviewPlaying -> {
                    btnPreview.text = "停止"
                    btnPreview.isEnabled = true
                }
                else -> {
                    btnPreview.text = "合成试听"
                    btnPreview.isEnabled = true
                }
            }
            btnPreview.setOnClickListener { onPreviewClick(item, isPreviewPlaying) }
        }

        /** 更新「试听原声」按钮。纯本地播放，没有网络阶段，只有"播放中/未播放"两态。 */
        fun updateOriginalButton(item: ReferenceAudio) {
            val isOriginalPlaying = item.id == originalPlayingId
            btnPlayOriginal.text = if (isOriginalPlaying) "停止原声" else "试听原声"
            btnPlayOriginal.setOnClickListener { onPlayOriginalClick(item, isOriginalPlaying) }
        }

        fun bind(item: ReferenceAudio) {
            tvName.text = item.name
            val source = when (item.source) {
                ReferenceAudio.SOURCE_SERVER -> "来自服务器"
                ReferenceAudio.SOURCE_SHARED -> "来自分享榜"
                ReferenceAudio.SOURCE_MINE -> "我分享的"
                else -> "本地导入"
            }
            tvMeta.text = "$source · ${FileUtils.formatSize(item.fileSize)}"
            btnDelete.setOnClickListener { onDeleteClick(item) }

            // 两个按钮的文案与监听器抽成独立方法：
            // 局部更新（payload 路径）要复用同一份逻辑，避免两处各写一遍而漂移。
            updatePreviewButton(item)
            updateOriginalButton(item)

            // 从管理员库/分享榜下载的都不是本人的音频，服务端会拒绝分享，直接不给入口。
            //
            // ⚠️ 这里刻意用 if/else 而不是「GONE + return」：
            // 早退会让后面所有控件的初始化被跳过。原先只有"分享"在后面，所以没暴露问题；
            // 本次新增的「试听原声」若不小心放到 return 之后，对不可分享的条目就会
            // **点了没反应**（按钮在、监听器没挂上），而且只在这一类条目上复现，
            // 极容易漏测。结构上避免这种坑，比记住"别忘了放在 return 前面"可靠。
            if (!item.canShare) {
                btnShare.visibility = View.GONE
            } else {
                btnShare.visibility = View.VISIBLE
                val isShared = item.serverId != null && item.serverId in sharedIds
                val isPending = item.id in pendingIds
                btnShare.isEnabled = !isPending
                btnShare.text = when {
                    isPending -> "处理中…"
                    isShared -> "已分享"
                    else -> "分享"
                }
                btnShare.setOnClickListener { onShareClick(item) }
            }
        }
    }

    class RefDiffCallback : DiffUtil.ItemCallback<ReferenceAudio>() {
        override fun areItemsTheSame(oldItem: ReferenceAudio, newItem: ReferenceAudio): Boolean {
            return oldItem.id == newItem.id
        }

        // ReferenceAudio 是 data class，equals 由编译器生成，可直接比较
        override fun areContentsTheSame(oldItem: ReferenceAudio, newItem: ReferenceAudio): Boolean {
            return oldItem == newItem
        }
    }
}
