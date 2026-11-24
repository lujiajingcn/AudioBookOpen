package com.audiobook.audiobookplayer

import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.audiobook.audiobookplayer.model.Comment
import com.audiobook.audiobookplayer.model.CreateCommentRequest
import com.audiobook.audiobookplayer.model.CreateReportRequest
import com.audiobook.audiobookplayer.model.Post
import com.audiobook.audiobookplayer.model.SharedReferenceAudio
import com.audiobook.audiobookplayer.model.isMine
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 帖子详情：帖子全文 + 评论（含嵌套回复）+ 收藏 + 举报。
 * 通过 Intent extra "POST_ID" 进入。
 */
class PostDetailActivity : BaseActivity() {

    companion object {
        private const val TAG = "PostDetailActivity"

        /** 「更多」菜单项：「删除」（自己的帖子） */
        private const val ACTION_DELETE = 1

        /** 「更多」菜单项：「举报」（他人的帖子） */
        private const val ACTION_REPORT = 2

        private val FULL_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE_FMT = SimpleDateFormat("MM-dd", Locale.getDefault())
    }

    private var postId: Long = 0
    private var post: Post? = null
    private val comments = mutableListOf<Comment>()
    private var replyingTo: Comment? = null

    /**
     * 登录后要接回的动作（如「收藏」「举报」「发评论」）。
     * 与 [MainActivity] 里的同名字段同一套模式：loginLauncher 是单例，
     * 用一次性闭包保存"登录成功后要做的事"，用完即清；Activity 被系统回收重建时
     * 闭包会丢，此时用户回到详情页重新点一次即可，不会崩。
     */
    private var pendingAfterLogin: (() -> Unit)? = null
    private val loginLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && AuthManager.isAuthenticated) {
            pendingAfterLogin?.invoke()
            // 登录成功后 detail 里的 isFavorited 等状态可能已经变了，重新拉一次
            loadDetail()
        }
        pendingAfterLogin = null
    }

    private lateinit var tvUsername: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvContent: TextView
    private lateinit var llImages: LinearLayout
    private lateinit var llRefAudio: LinearLayout
    private lateinit var tvRefAudio: TextView
    private lateinit var btnFavorite: Button
    private lateinit var btnLike: Button
    private lateinit var tvCommentCount: TextView
    private lateinit var recyclerComments: RecyclerView
    private lateinit var tvReplyHint: TextView
    private lateinit var etComment: EditText
    private lateinit var btnSend: Button

    private lateinit var commentAdapter: CommentAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_detail)

        postId = intent.getLongExtra("POST_ID", 0L)
        if (postId == 0L) {
            Toast.makeText(this, "帖子不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvUsername = findViewById(R.id.tvPostUsername)
        tvTime = findViewById(R.id.tvPostTime)
        tvTitle = findViewById(R.id.tvPostTitle)
        tvContent = findViewById(R.id.tvPostContent)
        llImages = findViewById(R.id.llPostImages)
        llRefAudio = findViewById(R.id.llRefAudio)
        tvRefAudio = findViewById(R.id.tvRefAudio)
        btnFavorite = findViewById(R.id.btnFavorite)
        btnLike = findViewById(R.id.btnLikePost)
        tvCommentCount = findViewById(R.id.tvCommentCount)
        recyclerComments = findViewById(R.id.recyclerComments)
        tvReplyHint = findViewById(R.id.tvReplyHint)
        etComment = findViewById(R.id.etComment)
        btnSend = findViewById(R.id.btnSend)

        commentAdapter = CommentAdapter { comment -> setReply(comment) }
        recyclerComments.adapter = commentAdapter
        recyclerComments.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPostMore).setOnClickListener { showPostActions(it) }
        btnFavorite.setOnClickListener { toggleFavorite() }
        btnLike.setOnClickListener { toggleLike() }
        btnSend.setOnClickListener { sendComment() }

        loadDetail()
    }

    private fun loadDetail() {
        // 帖子详情对游客开放（服务端 GET /api/posts/{id} 已 permitAll）
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { RetrofitClient.apiService.getPost(postId) }
                if (!resp.isSuccessful) {
                    showError("加载失败：HTTP ${resp.code()}")
                    return@launch
                }
                val body = resp.body() ?: return@launch
                if (body["success"] == false) {
                    val msg = body["message"] as? String
                    showError("加载失败：${msg ?: "未知错误"}")
                    return@launch
                }
                @Suppress("UNCHECKED_CAST")
                val postMap = body["post"] as? Map<String, Any> ?: return@launch
                val p = parsePost(postMap) ?: return@launch
                post = p
                withContext(Dispatchers.Main) { renderPost(p) }

                @Suppress("UNCHECKED_CAST")
                val rawComments = body["comments"] as? List<Map<String, Any>> ?: emptyList()
                comments.clear()
                comments.addAll(rawComments.mapNotNull { parseComment(it) })
                withContext(Dispatchers.Main) {
                    tvCommentCount.text = "评论 ${p.commentCount}"
                    commentAdapter.submitList(comments.toList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "加载帖子详情失败", e)
                showError("加载失败：${e.toUserMessage()}")
            }
        }
    }

    private fun renderPost(p: Post) {
        tvUsername.text = p.username
        tvTime.text = formatTime(p.createdAt)
        if (p.title.isNullOrBlank()) {
            tvTitle.visibility = View.GONE
        } else {
            tvTitle.visibility = View.VISIBLE
            tvTitle.text = p.title
        }
        tvContent.text = p.content

        // 图片
        llImages.removeAllViews()
        if (p.imageUrls.isNotEmpty()) {
            llImages.visibility = View.VISIBLE
            val sizePx = (100 * resources.displayMetrics.density).toInt()
            val marginPx = (8 * resources.displayMetrics.density).toInt()
            p.imageUrls.take(3).forEach { rel ->
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = marginPx }
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

        // 嵌入参考音频
        if (p.refAudioId != null) {
            llRefAudio.visibility = View.VISIBLE
            tvRefAudio.text = "参考音频分享 #${p.refAudioId}"
            llRefAudio.setOnClickListener { showRefAudioDialog(p.refAudioId) }
        } else {
            llRefAudio.visibility = View.GONE
            llRefAudio.setOnClickListener(null)
        }

        updateFavoriteButton(p)
        updateLikeButton(p)
    }

    private fun updateLikeButton(p: Post) {
        btnLike.text = if (p.liked) "♥ ${p.likeCount}" else "♡ ${p.likeCount}"
        btnLike.setTextColor(
            ContextCompat.getColor(
                this,
                if (p.liked) R.color.primary else android.R.color.secondary_text_dark
            )
        )
    }

    private fun toggleLike() {
        val p = post ?: return
        requireLogin("点赞需要登录后使用") { doToggleLike(p) }
    }

    private fun doToggleLike(p: Post) {
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { RetrofitClient.apiService.togglePostLike(p.id) }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    showError("操作失败：${resp.errorMessage()}")
                    return@launch
                }
                val liked = resp.body()?.get("liked") as? Boolean ?: !p.liked
                val count = (resp.body()?.get("likeCount") as? Number)?.toInt() ?: p.likeCount
                val updated = p.copy(liked = liked, likeCount = count)
                post = updated
                withContext(Dispatchers.Main) { updateLikeButton(updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("操作失败：${e.toUserMessage()}")
            }
        }
    }

    /**
     * 「更多」菜单：自己的帖子给「删除」，别人的给「举报」。
     *
     * 详见 [DiscussionFragment.showPostActions] 的说明（同一套规则、同一个理由）：
     * 常驻的「举报」不该对自己的帖子出现，也不该占据顶栏最显眼的位置。
     */
    private fun showPostActions(anchor: View) {
        val p = post ?: return
        val popup = PopupMenu(this, anchor)
        if (p.isMine) {
            popup.menu.add(Menu.NONE, ACTION_DELETE, 0, "删除")
        } else {
            popup.menu.add(Menu.NONE, ACTION_REPORT, 0, "举报")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ACTION_DELETE -> {
                    confirmDelete(p)
                    true
                }
                ACTION_REPORT -> {
                    reportPost()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(p: Post) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除帖子")
            .setMessage("删除后这条帖子会立即从讨论区消失，且不可恢复。")
            .setPositiveButton("删除") { _, _ -> doDeletePost(p) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDeletePost(p: Post) {
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { RetrofitClient.apiService.deletePost(p.id) }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    showError("删除失败：${resp.errorMessage()}")
                    return@launch
                }
                Toast.makeText(this@PostDetailActivity, "已删除", Toast.LENGTH_SHORT).show()
                // 帖子没了，详情页继续留着没有任何意义 —— 回列表（列表会在 onResume 重拉）
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("删除失败：${e.toUserMessage()}")
            }
        }
    }

    private fun updateFavoriteButton(p: Post) {
        btnFavorite.text = if (p.isFavorited) "已收藏 ${p.favoriteCount}" else "收藏 ${p.favoriteCount}"
    }

    private fun toggleFavorite() {
        val p = post ?: return
        requireLogin("收藏帖子需要登录后使用") { doToggleFavorite(p) }
    }

    private fun doToggleFavorite(p: Post) {
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { RetrofitClient.apiService.toggleFavorite(p.id) }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    showError("操作失败：${resp.errorMessage()}")
                    return@launch
                }
                val favorited = resp.body()?.get("favorited") as? Boolean ?: !p.isFavorited
                val favCount = (resp.body()?.get("favoriteCount") as? Number)?.toInt() ?: p.favoriteCount
                val updated = p.copy(isFavorited = favorited, favoriteCount = favCount)
                post = updated
                withContext(Dispatchers.Main) {
                    updateFavoriteButton(updated)
                    Toast.makeText(this@PostDetailActivity, if (favorited) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("操作失败：${e.toUserMessage()}")
            }
        }
    }

    private fun reportPost() {
        requireLogin("举报需要登录后使用") { doReportPost() }
    }

    private fun doReportPost() {
        val input = EditText(this).apply {
            hint = "举报理由（必填，≤500字）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("举报帖子")
            .setView(input)
            .setPositiveButton("提交") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.isBlank()) {
                    Toast.makeText(this, "请填写举报理由", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                submitReport("POST", postId, reason)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitReport(type: String, targetId: Long, reason: String) {
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.createReport(CreateReportRequest(type, targetId, reason))
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    showError("举报失败：${resp.errorMessage()}")
                    return@launch
                }
                Toast.makeText(this@PostDetailActivity, "举报已提交，感谢反馈", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("举报失败：${e.toUserMessage()}")
            }
        }
    }

    // ---------- 参考音频嵌入 ----------

    private fun showRefAudioDialog(refAudioId: Long) {
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { RetrofitClient.apiService.getSharedReference(refAudioId) }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    showError("加载参考音频失败")
                    return@launch
                }
                val b = resp.body() ?: return@launch
                val name = b["name"] as? String ?: "未知"
                val desc = b["description"] as? String
                val useCount = (b["useCount"] as? Number)?.toLong() ?: 0L
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(this@PostDetailActivity)
                        .setTitle("参考音频：$name")
                        .setMessage((if (desc.isNullOrBlank()) "" else "$desc\n\n") + "已被使用 $useCount 次")
                        .setPositiveButton("用这个声音读我的书") { _, _ -> applyRefToTts(refAudioId, name) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("加载参考音频失败：${e.toUserMessage()}")
            }
        }
    }

    private fun applyRefToTts(refId: Long, refName: String) {
        requireLogin("使用参考音频需要登录后使用") { doApplyRefToTts(refId, refName) }
    }

    private fun doApplyRefToTts(refId: Long, refName: String) {
        lifecycleScope.launch {
            try {
                val repo = ReferenceAudioRepository(applicationContext)
                val alreadyOwned = withContext(Dispatchers.IO) {
                    repo.listAll().any { it.isFromSharedLibrary && it.serverId == refId }
                }
                if (!alreadyOwned) {
                    val item = SharedReferenceAudio(id = refId, name = refName)
                    val res = withContext(Dispatchers.IO) { repo.importFromShared(item) }
                    res.onFailure { e ->
                        withContext(Dispatchers.Main) { showError(e.toUserMessage()) }
                        return@launch
                    }
                    withContext(Dispatchers.IO) { repo.reportSharedUse(refId) }
                }
                PendingTtsReference.set(PendingTtsReference.Payload(refId, refName))
                withContext(Dispatchers.Main) {
                    finish()
                    startActivity(Intent(this@PostDetailActivity, MainActivity::class.java).apply {
                        putExtra("OPEN_TTS", true)
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("操作失败：${e.toUserMessage()}")
            }
        }
    }

    // ---------- 评论 ----------

    private fun setReply(comment: Comment) {
        replyingTo = comment
        tvReplyHint.visibility = View.VISIBLE
        tvReplyHint.text = "回复 @${comment.username}"
        etComment.hint = "回复 @${comment.username}…"
        etComment.requestFocus()
    }

    private fun clearReply() {
        replyingTo = null
        tvReplyHint.visibility = View.GONE
        tvReplyHint.text = ""
        etComment.hint = "说点什么…"
    }

    private fun sendComment() {
        val p = post ?: return
        requireLogin("发表评论需要登录后使用") { doSendComment(p) }
    }

    private fun doSendComment(p: Post) {
        val content = etComment.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            Toast.makeText(this, "评论内容不能为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (content.length > 500) {
            Toast.makeText(this, "评论不能超过 500 字", Toast.LENGTH_SHORT).show()
            return
        }
        val parentId = replyingTo?.id
        btnSend.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.createComment(
                        p.id, CreateCommentRequest(content, parentId)
                    )
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    withContext(Dispatchers.Main) { showError("评论失败：${resp.errorMessage()}") }
                    return@launch
                }
                @Suppress("UNCHECKED_CAST")
                val commentMap = resp.body()?.get("comment") as? Map<String, Any>
                val newComment = commentMap?.let { parseComment(it) }
                withContext(Dispatchers.Main) {
                    if (newComment != null) {
                        comments.add(newComment)
                        commentAdapter.submitList(comments.toList())
                        recyclerComments.scrollToPosition(comments.size - 1)
                    }
                    val updated = p.copy(commentCount = p.commentCount + 1)
                    post = updated
                    tvCommentCount.text = "评论 ${updated.commentCount}"
                    etComment.text?.clear()
                    clearReply()
                    Toast.makeText(this@PostDetailActivity, "评论成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("评论失败：${e.toUserMessage()}") }
            } finally {
                withContext(Dispatchers.Main) { btnSend.isEnabled = true }
            }
        }
    }

    // ---------- 解析 ----------

    private fun parsePost(m: Map<String, Any>): Post? {
        val id = (m["id"] as? Number)?.toLong() ?: return null
        val imageUrls = (m["imageUrls"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val mentions = (m["mentions"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val refAudioId = (m["refAudioId"] as? Number)?.toLong()
        val commentCount = (m["commentCount"] as? Number)?.toInt() ?: 0
        val favoriteCount = (m["favoriteCount"] as? Number)?.toInt() ?: 0
        val likeCount = (m["likeCount"] as? Number)?.toInt() ?: 0
        val liked = m["liked"] as? Boolean ?: false
        val isFavorited = m["isFavorited"] as? Boolean ?: false
        return Post(
            id = id,
            username = m["username"] as? String ?: "",
            title = m["title"] as? String,
            content = m["content"] as? String ?: "",
            imageUrls = imageUrls,
            refAudioId = refAudioId,
            mentions = mentions,
            commentCount = commentCount,
            favoriteCount = favoriteCount,
            likeCount = likeCount,
            liked = liked,
            createdAt = m["createdAt"] as? String,
            isFavorited = isFavorited
        )
    }

    private fun parseComment(m: Map<String, Any>): Comment? {
        val id = (m["id"] as? Number)?.toLong() ?: return null
        return Comment(
            id = id,
            postId = (m["postId"] as? Number)?.toLong() ?: postId,
            username = m["username"] as? String ?: "",
            content = m["content"] as? String ?: "",
            parentId = (m["parentId"] as? Number)?.toLong(),
            parentUsername = m["parentUsername"] as? String,
            createdAt = m["createdAt"] as? String
        )
    }

    private fun formatTime(raw: String?): String {
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

    /**
     * 游客点击写操作（收藏 / 举报 / 评论 / 拿去朗读）时的登录拦截。
     * 与 [MainActivity.requireLogin] 同一套模式：已登录直接放行；未登录把动作挂到
     * [pendingAfterLogin]，弹「需要登录」对话框，「去登录」用 [loginLauncher] 拉起
     * [LoginActivity] 并带 RETURN_ONLY 标记——登录成功回到本页后 launcher 回调里
     * 会 invoke 该闭包并刷新 detail（isFavorited 等状态可能已变）。
     */
    private fun requireLogin(message: String, after: () -> Unit) {
        if (AuthManager.isAuthenticated) {
            after()
            return
        }
        pendingAfterLogin = after
        MaterialAlertDialogBuilder(this)
            .setTitle("需要登录")
            .setMessage(message)
            .setNegativeButton("稍后再说", null)
            .setPositiveButton("去登录") { _, _ ->
                loginLauncher.launch(
                    Intent(this, LoginActivity::class.java)
                        .putExtra("RETURN_ONLY", true)
                )
            }
            .show()
    }

    inner class CommentAdapter(
        private val onReplyClick: (Comment) -> Unit
    ) : ListAdapter<Comment, CommentAdapter.CommentViewHolder>(CommentDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
            return CommentViewHolder(view)
        }

        override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvUser: TextView = itemView.findViewById(R.id.tvCommentUsername)
            private val tvCtime: TextView = itemView.findViewById(R.id.tvCommentTime)
            private val tvCcontent: TextView = itemView.findViewById(R.id.tvCommentContent)
            private val tvReply: TextView = itemView.findViewById(R.id.tvReply)

            fun bind(c: Comment) {
                tvUser.text = c.username
                tvCtime.text = formatTime(c.createdAt)
                val prefix = if (!c.parentUsername.isNullOrBlank()) "回复 @${c.parentUsername}：" else ""
                tvCcontent.text = prefix + c.content
                tvReply.setOnClickListener { onReplyClick(c) }
            }
        }
    }
}

/**
 * 评论列表的 DiffUtil 回调。
 *
 * 必须放在 Activity 之外、作为**文件级顶层类**：
 * 作为 `inner class CommentAdapter` 的嵌套类时，Kotlin 不允许在 inner class 内部再声明
 * 非 inner 的嵌套类（会报 "Class is not allowed here"）；改成 inner 也不行——它要在
 * CommentAdapter 的父类构造实参里实例化，而 inner class 的构造需要外部实例，此时还拿不到。
 * 顶层类两种约束都绕开了。
 */
private class CommentDiffCallback : DiffUtil.ItemCallback<Comment>() {
    override fun areItemsTheSame(oldItem: Comment, newItem: Comment): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Comment, newItem: Comment): Boolean = oldItem == newItem
}
