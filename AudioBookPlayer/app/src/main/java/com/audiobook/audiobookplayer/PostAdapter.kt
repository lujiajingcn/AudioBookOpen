package com.audiobook.audiobookplayer

import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.audiobook.audiobookplayer.model.Post
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * 发现页帖子列表适配器：头像 / 用户名 / 标题 / 内容（@提及高亮）/ 图片 /
 * 嵌入参考音频 / 点赞 / 收藏 / 评论数 / 更多菜单。
 *
 * @param onItemClick 点击卡片（进入帖子详情）
 * @param onFavoriteClick 点击收藏（列表内快速切换收藏态）
 * @param onLikeClick 点击点赞（列表内快速切换点赞态）
 * @param onRefAudioClick 点击嵌入的参考音频卡片（拉详情并可选「用这个声音读我的书」）
 * @param onMoreClick 点击右上「更多」（由调用方按「是不是我的帖子」弹不同菜单：
 *   自己的帖子给「删除」，别人的给「举报」）
 */
class PostAdapter(
    private val onItemClick: (Post) -> Unit,
    private val onFavoriteClick: (Post) -> Unit,
    private val onLikeClick: (Post) -> Unit,
    private val onRefAudioClick: (Long) -> Unit,
    private val onMoreClick: (Post, View) -> Unit
) : ListAdapter<Post, PostAdapter.PostViewHolder>(PostDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false)
        return PostViewHolder(view)
    }

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PostViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvPostAvatar)
        private val tvUsername: TextView = itemView.findViewById(R.id.tvPostUsername)
        private val tvTime: TextView = itemView.findViewById(R.id.tvPostTime)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvPostTitle)
        private val tvContent: TextView = itemView.findViewById(R.id.tvPostContent)
        private val llImages: LinearLayout = itemView.findViewById(R.id.llPostImages)
        private val llRefAudio: LinearLayout = itemView.findViewById(R.id.llRefAudio)
        private val tvRefAudio: TextView = itemView.findViewById(R.id.tvRefAudio)
        private val btnLike: Button = itemView.findViewById(R.id.btnLikePost)
        private val btnFavorite: Button = itemView.findViewById(R.id.btnFavorite)
        private val tvComment: TextView = itemView.findViewById(R.id.tvCommentCount)
        private val btnMore: TextView = itemView.findViewById(R.id.btnPostMore)

        fun bind(post: Post) {
            tvUsername.text = post.username
            tvTime.text = formatPostTime(post.createdAt)
            bindAvatar(post.username)

            if (post.title.isNullOrBlank()) {
                tvTitle.visibility = View.GONE
            } else {
                tvTitle.visibility = View.VISIBLE
                tvTitle.text = post.title
            }

            tvContent.text = highlightMentions(post.content, post.mentions)

            // 图片：最多 3 张横向排列
            llImages.removeAllViews()
            if (post.imageUrls.isNotEmpty()) {
                llImages.visibility = View.VISIBLE
                val ctx = itemView.context
                val sizePx = (100 * ctx.resources.displayMetrics.density).toInt()
                val marginPx = (8 * ctx.resources.displayMetrics.density).toInt()
                post.imageUrls.take(3).forEach { rel ->
                    val iv = ImageView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                            marginEnd = marginPx
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        adjustViewBounds = true
                    }
                    val url = RetrofitClient.BASE_URL + rel.trimStart('/')
                    val token = AuthManager.token
                    iv.load(url) {
                        if (token != null) addHeader("Authorization", "Bearer $token")
                        crossfade(true)
                    }
                    llImages.addView(iv)
                }
            } else {
                llImages.visibility = View.GONE
            }

            // 嵌入参考音频卡片
            if (post.refAudioId != null) {
                llRefAudio.visibility = View.VISIBLE
                tvRefAudio.text = "参考音频分享 #${post.refAudioId}"
                llRefAudio.setOnClickListener { onRefAudioClick(post.refAudioId) }
            } else {
                llRefAudio.visibility = View.GONE
                llRefAudio.setOnClickListener(null)
            }

            btnFavorite.text = if (post.isFavorited) "已收藏 ${post.favoriteCount}" else "收藏 ${post.favoriteCount}"
            btnFavorite.setOnClickListener { onFavoriteClick(post) }

            // 点赞：实心/空心 + 计数。已赞用主题色，未赞用次要文字色 ——
            // 「我赞过没有」是这一枚按钮上唯一要传达的状态，只靠空心实心在小字号下不好认。
            btnLike.text = if (post.liked) "♥ ${post.likeCount}" else "♡ ${post.likeCount}"
            btnLike.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (post.liked) R.color.primary else android.R.color.secondary_text_dark
                )
            )
            btnLike.setOnClickListener { onLikeClick(post) }

            tvComment.text = "评论 ${post.commentCount}"
            tvComment.setOnClickListener { onItemClick(post) }

            btnMore.setOnClickListener { v -> onMoreClick(post, v) }

            itemView.setOnClickListener { onItemClick(post) }
        }

        /**
         * 头像：用用户名首字生成一枚圆形色块。
         *
         * 工程里没有「用户头像」这个东西（users 表没有 avatar 字段，也没有上传链路），
         * 与其画一个所有人都一样的灰色人像剪影（那和不显示头像差不多），
         * 不如按用户名生成确定性的字母头像 —— 同一个人在任何设备、任何时候
         * 颜色和字都一样，刷列表时能靠颜色认出「又是这个人」。
         */
        private fun bindAvatar(username: String) {
            val name = username.trim()
            tvAvatar.text = name.take(1).uppercase()
            // 色板取自主题的暖色系，饱和度压低，避免 34dp 的小方块在浅色列表里太扎眼
            val palette = intArrayOf(
                0xFFB08968.toInt(), 0xFF8D6E63.toInt(), 0xFFA1887F.toInt(),
                0xFF9C7A5B.toInt(), 0xFF7E8C6A.toInt(), 0xFF6F7C8C.toInt()
            )
            val index = if (name.isEmpty()) 0
            else (name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % palette.size
            tvAvatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette[index])
            }
        }

        /** 把正文中 @用户名 高亮成链接色（仅高亮服务端解析出的 mentions，避免误标普通文本） */
        private fun highlightMentions(text: String, mentions: List<String>): CharSequence {
            if (mentions.isEmpty()) return text
            val spannable = SpannableString(text)
            // 用主题主色（暖金）而不是写死的蓝 #1A73E8 —— 主界面已统一到暖金，
            // 这里写死蓝会在金色正文里冒出蓝色链接。取资源可跟随日夜主题。
            val color = ContextCompat.getColor(itemView.context, R.color.primary)
            mentions.forEach { name ->
                if (name.isBlank()) return@forEach
                val matcher = Pattern.compile("@${Pattern.quote(name)}").matcher(text)
                while (matcher.find()) {
                    spannable.setSpan(
                        ForegroundColorSpan(color),
                        matcher.start(), matcher.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            return spannable
        }

        private fun formatPostTime(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return try {
                val date = FULL_FMT.parse(raw) ?: return raw
                val diff = System.currentTimeMillis() - date.time
                when {
                    diff < 0 -> raw
                    diff < 60_000L -> "刚刚"
                    diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
                    diff < 24 * 3_600_000L -> TIME_FMT.format(date)
                    else -> DATE_FMT.format(date)
                }
            } catch (e: Exception) {
                raw
            }
        }
    }

    companion object {
        private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE_FMT = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    class PostDiffCallback : DiffUtil.ItemCallback<Post>() {
        override fun areItemsTheSame(oldItem: Post, newItem: Post): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Post, newItem: Post): Boolean = oldItem == newItem
    }
}
