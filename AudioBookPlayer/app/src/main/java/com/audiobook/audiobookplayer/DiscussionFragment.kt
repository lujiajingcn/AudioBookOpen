package com.audiobook.audiobookplayer

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.Post
import com.audiobook.audiobookplayer.model.SharedReferenceAudio
import com.audiobook.audiobookplayer.model.isMine
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现页 -> 「讨论区」标签页：社区帖子列表（富文本）。
 *
 * 本类只负责「读」：拉列表、收藏、举报、进详情。
 * 发帖（「写」）整块搬去了 [CreatePostBottomSheet] —— 入口是页面右下角的「＋」，
 * 发完通过 Fragment Result 通知本页刷新，所以这里不再持有任何表单控件。
 *
 * 支持标题、图片、嵌入参考音频、@提及（服务端从正文自动解析）、收藏、举报、评论。
 * 列表按发帖时间倒序（服务端保证，客户端只按返回顺序展示）。
 */
class DiscussionFragment : Fragment() {

    companion object {
        /** 单次拉取条数（服务端上限 200） */
        private const val PAGE_LIMIT = 100

        /** 「更多」菜单项：「删除」（自己的帖子） */
        private const val ACTION_DELETE = 1

        /** 「更多」菜单项：「举报」（他人的帖子） */
        private const val ACTION_REPORT = 2
    }

    private lateinit var adapter: PostAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView

    /** 当前帖子列表（用于收藏后就地更新单条） */
    private var posts: List<Post> = emptyList()

    /** 首次进入时 onResume 不该再拉一次（onViewCreated 已拉过），见 [onResume] */
    private var skipNextResumeReload = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_discussion, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerPosts)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = PostAdapter(
            onItemClick = { openPostDetail(it) },
            onFavoriteClick = { quickFavorite(it) },
            onLikeClick = { quickLike(it) },
            onRefAudioClick = { showRefAudioDialog(it) },
            onMoreClick = { post, anchor -> showPostActions(post, anchor) }
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // 发帖面板发布成功后回传条数，据此刷新列表。
        // 用 viewLifecycleOwner 而不是 this：刷新动作要碰 view，必须与 view 同生命周期。
        parentFragmentManager.setFragmentResultListener(
            CreatePostBottomSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getInt(CreatePostBottomSheet.RESULT_POSTED_COUNT, 0) > 0) loadPosts()
        }

        skipNextResumeReload = true
        loadPosts()
    }

    /**
     * 从帖子详情返回时重拉列表。
     *
     * 详情页可以「删除自己的帖子」，删完那一条服务端已经不再返回；
     * 不重拉的话列表里会留着一条点进去 404 的幽灵条目。
     * 首次进入由 [onViewCreated] 已经拉过，这里跳过那一次，
     * 避免「进页面打两次请求」（onViewCreated 之后紧跟着就会 onResume）。
     */
    override fun onResume() {
        super.onResume()
        if (skipNextResumeReload) {
            skipNextResumeReload = false
            return
        }
        if (::adapter.isInitialized) loadPosts()
    }

    // ---------- 列表 ----------

    private fun loadPosts() {
        // 帖子列表对游客开放（服务端 /api/posts GET 已 permitAll）；
        // 未登录时 isFavorited 恒为 false，收藏按钮走 requireLogin 引导。
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { fetchPosts() }
                posts = list
                adapter.submitList(list)
                tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("加载失败：${e.toUserMessage()}")
            }
        }
    }

    private suspend fun fetchPosts(): List<Post> {
        val resp = RetrofitClient.apiService.getPosts(PAGE_LIMIT)
        // 用 BusinessException 而不是 IllegalStateException：调用方统一走
        // `showError("加载失败：${e.toUserMessage()}")`，而 toUserMessage 对普通异常
        // 只会输出兜底文案「未知错误」—— 服务端返回的「内容已下架」这类原因会被吞掉。
        if (!resp.isSuccessful) throw BusinessException("HTTP ${resp.code()}")
        val body = resp.body() ?: return emptyList()
        if (body["success"] == false) {
            val msg = body["message"] as? String
            throw BusinessException(msg?.takeIf { it.isNotBlank() } ?: "加载帖子失败")
        }
        @Suppress("UNCHECKED_CAST")
        val raw = body["posts"] as? List<Map<String, Any>> ?: return emptyList()
        return raw.mapNotNull { parsePost(it) }
    }

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

    /** 收藏后就地替换单条，避免整表刷新闪烁 */
    private fun updatePost(updated: Post) {
        val newList = posts.map { if (it.id == updated.id) updated else it }
        posts = newList
        adapter.submitList(newList)
    }

    // ---------- 参考音频（点击帖子里的筹码） ----------

    /** 在帖子列表里点击嵌入的参考音频：拉详情，可一键拿去读我的书 */
    private fun showRefAudioDialog(refAudioId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getSharedReference(refAudioId)
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    context?.showError("加载参考音频失败")
                    return@launch
                }
                val b = resp.body() ?: return@launch
                val name = b["name"] as? String ?: "未知"
                val desc = b["description"] as? String
                val useCount = (b["useCount"] as? Number)?.toLong() ?: 0L
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("参考音频：$name")
                        .setMessage((if (desc.isNullOrBlank()) "" else "$desc\n\n") + "已被使用 $useCount 次")
                        .setPositiveButton("用这个声音读我的书") { _, _ -> applyRefToTts(refAudioId, name) }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("加载参考音频失败：${e.toUserMessage()}")
            }
        }
    }

    /** 与参考音频榜单「用这个声音读我的书」同款流程：确保进本地库 + 上报 + 跳 TTS 预选 */
    private fun applyRefToTts(refId: Long, refName: String) {
        // 把分享音频导入本地库 + 跳 TTS，属于写用户状态的动作，未登录先引导登录
        requireLoginThen("使用参考音频需要登录后使用") { doApplyRefToTts(refId, refName) }
    }

    private fun doApplyRefToTts(refId: Long, refName: String) {
        val host = requireActivity() as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = ReferenceAudioRepository(requireContext().applicationContext)
                val alreadyOwned = withContext(Dispatchers.IO) {
                    repo.listAll().any { it.isFromSharedLibrary && it.serverId == refId }
                }
                if (!alreadyOwned) {
                    val item = SharedReferenceAudio(id = refId, name = refName)
                    val res = withContext(Dispatchers.IO) { repo.importFromShared(item) }
                    res.onFailure { e ->
                        withContext(Dispatchers.Main) { context?.showError(e.toUserMessage()) }
                        return@launch
                    }
                    withContext(Dispatchers.IO) { repo.reportSharedUse(refId) }
                }
                PendingTtsReference.set(PendingTtsReference.Payload(refId, refName))
                withContext(Dispatchers.Main) { host.showTts() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("操作失败：${e.toUserMessage()}")
            }
        }
    }

    // ---------- 点赞 / 更多菜单 ----------

    private fun quickLike(post: Post) {
        requireLoginThen("点赞需要登录后使用") { doQuickLike(post) }
    }

    private fun doQuickLike(post: Post) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.togglePostLike(post.id)
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    context?.showError("操作失败：${resp.errorMessage()}")
                    return@launch
                }
                val liked = resp.body()?.get("liked") as? Boolean ?: !post.liked
                val count = (resp.body()?.get("likeCount") as? Number)?.toInt() ?: post.likeCount
                updatePost(post.copy(liked = liked, likeCount = count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("操作失败：${e.toUserMessage()}")
            }
        }
    }

    /**
     * 「更多」菜单：按**是不是我的帖子**给不同项。
     *
     * 之前这里是卡片右上角一枚常驻「举报」按钮，自己的帖子也显示 ——
     * 既抢视觉重心，又等于允许用户举报自己（2026-11-02 UI 走查 P2-15）。
     * 收进溢出菜单后，主视觉回到内容本身；自己的帖子里给的是「删除」（P2-14）。
     */
    private fun showPostActions(post: Post, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        if (post.isMine) {
            popup.menu.add(Menu.NONE, ACTION_DELETE, 0, "删除")
        } else {
            popup.menu.add(Menu.NONE, ACTION_REPORT, 0, "举报")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ACTION_DELETE -> {
                    confirmDelete(post)
                    true
                }
                ACTION_REPORT -> {
                    reportPost(post)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(post: Post) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除帖子")
            .setMessage("删除后这条帖子会立即从讨论区消失，且不可恢复。")
            .setPositiveButton("删除") { _, _ -> doDeletePost(post) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDeletePost(post: Post) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.deletePost(post.id)
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    context?.showError("删除失败：${resp.errorMessage()}")
                    return@launch
                }
                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                // 整表重拉而不是就地删除：服务端是逻辑删除，列表排序与总数都变了，
                // 就地删一条会让「hasMore/分页」与服务端不一致。
                loadPosts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("删除失败：${e.toUserMessage()}")
            }
        }
    }

    // ---------- 收藏 / 举报 / 详情 ----------

    private fun quickFavorite(post: Post) {
        requireLoginThen("收藏帖子需要登录后使用") { doQuickFavorite(post) }
    }

    private fun doQuickFavorite(post: Post) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.toggleFavorite(post.id)
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    context?.showError("操作失败：${resp.errorMessage()}")
                    return@launch
                }
                val favorited = resp.body()?.get("favorited") as? Boolean ?: !post.isFavorited
                val favCount = (resp.body()?.get("favoriteCount") as? Number)?.toInt() ?: post.favoriteCount
                updatePost(post.copy(isFavorited = favorited, favoriteCount = favCount))
                Toast.makeText(
                    requireContext(),
                    if (favorited) "已收藏" else "已取消收藏",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("操作失败：${e.toUserMessage()}")
            }
        }
    }

    private fun reportPost(post: Post) {
        requireLoginThen("举报需要登录后使用") { doReportPost(post) }
    }

    private fun doReportPost(post: Post) {
        val input = EditText(requireContext()).apply {
            hint = "举报理由（必填，≤500字）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("举报帖子")
            .setView(input)
            .setPositiveButton("提交") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.isBlank()) {
                    Toast.makeText(requireContext(), "请填写举报理由", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                submitReport("POST", post.id, reason)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitReport(type: String, targetId: Long, reason: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.createReport(
                        com.audiobook.audiobookplayer.model.CreateReportRequest(type, targetId, reason)
                    )
                }
                if (!resp.isSuccessful || resp.body()?.get("success") != true) {
                    context?.showError("举报失败：${resp.errorMessage()}")
                    return@launch
                }
                Toast.makeText(requireContext(), "举报已提交，感谢反馈", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context?.showError("举报失败：${e.toUserMessage()}")
            }
        }
    }

    private fun openPostDetail(post: Post) {
        val intent = Intent(requireContext(), PostDetailActivity::class.java).apply {
            putExtra("POST_ID", post.id)
        }
        startActivity(intent)
    }

    /**
     * 写操作统一走宿主 MainActivity 的登录引导：未登录弹框 → 去登录 → 回来后自动接回 action。
     * 与非 MainActivity 宿主（理论上不会走到）时直接执行 action 兜底。
     */
    private fun requireLoginThen(message: String, action: () -> Unit) {
        val host = activity as? MainActivity
        if (host != null) host.requireLogin(message, action) else action()
    }
}
