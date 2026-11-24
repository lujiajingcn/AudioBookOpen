package com.audiobook.audiobookplayer

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.SharedReferenceAudio
import com.audiobook.audiobookplayer.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 发现页 -> 「参考音频」标签页：用户分享出来的参考音频，按使用量排名。
 *
 * 三个动作：
 * - 拉榜：GET /api/tts/shared-references，服务端已按 useCount 倒序排好；
 * - 试听：把音频下载到 cache 后用 MediaPlayer 播放，**不计入使用量**；
 * - 使用：下载进本地参考音频库，成功后上报一次 use（使用量 +1）。
 *
 * 「试听」和「使用」刻意走同一条下载流但分开上报：
 * 若把计数放在下载端点上，只看不用的试听会把榜单刷花。
 */
class ReferenceAudioTabFragment : Fragment() {

    companion object {
        private const val TAG = "RefAudioTab"

        /** 排行榜单次拉取条数（服务端上限 200） */
        private const val RANK_LIMIT = 50
    }

    private lateinit var repository: ReferenceAudioRepository
    private lateinit var adapter: SharedReferenceAdapter
    // View 字段可空并在 onDestroyView 置空，避免 ViewPager2 复用 Fragment 实例时
    // 旧视图（及其 Context / 整棵视图树）被长期持有无法被 GC
    private var btnRefresh: Button? = null
    private var recyclerView: RecyclerView? = null
    private var tvEmpty: TextView? = null
    private var tvError: TextView? = null

    /** 试听用的播放器；同一时刻最多一个 */
    private var mediaPlayer: MediaPlayer? = null

    /** 试听下载的临时文件，退出时清理 */
    private var previewFile: File? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_reference_audio_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ReferenceAudioRepository(requireContext().applicationContext)

        btnRefresh = view.findViewById(R.id.btnRefresh)
        recyclerView = view.findViewById(R.id.recyclerSharedRefs)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvError = view.findViewById(R.id.tvError)

        adapter = SharedReferenceAdapter(
            onPreviewClick = { item -> togglePreview(item) },
            onUseClick = { item -> useShared(item) },
            onApplyClick = { item -> applyToMyBook(item) }
        )
        recyclerView?.adapter = adapter
        recyclerView?.layoutManager = LinearLayoutManager(requireContext())

        btnRefresh?.setOnClickListener { loadRanking() }

        loadRanking()
    }

    // ---------- 拉榜 ----------

    private fun loadRanking() {
        // 榜单对游客开放（服务端 GET /api/tts/shared-references 已 permitAll）；
        // 「已使用」标记本地库查得到就用，游客本地库为空即全部未拥有，无副作用。
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { fetchRanking() }
                // 先把「本地已持有」标记烘焙进列表项，再一次性 submitList，
                // 避免 submitList 之后再单独 setOwnedIds 触发整表 notifyDataSetChanged 造成双重刷新/闪烁
                val owned = withContext(Dispatchers.IO) { ownedServerIds() }
                val decorated = items.map { if (it.id in owned) it.copy(owned = true) else it }
                adapter.submitList(decorated)
                tvEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                tvError?.visibility = View.GONE
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "加载排行榜失败: ${e.message}")
                // 必须走 toUserMessage() 而不是 e.message：
                // fetchRanking 抛的是 BusinessException("HTTP xxx")，而网络类异常的 message
                // 是 OkHttp 英文原文（可能带内网地址），无 message 时还会把「null」上屏。
                showError("加载失败：${e.toUserMessage()}")
            } finally {
                // 协程由 viewLifecycleOwner 管理，view 销毁时会被取消并走到这里，
                // 此时控件已不可用，必须先判空
                if (view != null) setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        btnRefresh?.isEnabled = !loading
        btnRefresh?.text = if (loading) "加载中…" else "刷新"
    }

    private suspend fun fetchRanking(): List<SharedReferenceAudio> {
        val resp = RetrofitClient.apiService.getSharedReferences(RANK_LIMIT)
        if (!resp.isSuccessful) {
            // BusinessException：调用方用 `showError("...${e.toUserMessage()}")` 展示，
            // 普通异常会被兜底成「未知错误」，状态码就丢了。
            throw BusinessException("HTTP ${resp.code()}")
        }
        val body = resp.body() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val raw = body["references"] as? List<Map<String, Any>> ?: return emptyList()
        return raw.mapNotNull { m ->
            val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            SharedReferenceAudio(
                id = id,
                name = name,
                rank = (m["rank"] as? Number)?.toInt() ?: 0,
                fileName = m["fileName"] as? String,
                description = m["description"] as? String,
                fileSize = (m["fileSize"] as? Number)?.toLong() ?: 0L,
                useCount = (m["useCount"] as? Number)?.toLong() ?: 0L,
                ownerName = m["ownerName"] as? String ?: "",
                mine = m["mine"] as? Boolean ?: false
            )
        }
    }

    /** 本地库里已持有的分享条目（source=shared 的 serverId），用于把按钮置为「已使用」 */
    private suspend fun ownedServerIds(): Set<Long> =
        repository.listAll()
            .filter { it.isFromSharedLibrary }
            .mapNotNull { it.serverId }
            .toSet()

    // ---------- 试听 ----------

    private fun togglePreview(item: SharedReferenceAudio) {
        // 再点一次同一个 = 停止
        if (adapter.isPreviewing(item.id)) {
            stopPreview()
            return
        }
        // 试听对游客开放（服务端 GET /api/tts/shared-references/{id}/audio 已 permitAll）；
        // 试听不计数，使用量由「使用」/「拿去朗读」上报，避免刷花榜单。
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.setPreviewing(item.id)
            val file = withContext(Dispatchers.IO) { downloadToCache(item) }
            if (file == null) {
                adapter.setPreviewing(null)
                context?.let { Toast.makeText(it, "试听失败", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            try {
                startPlayback(file)
            } catch (e: Exception) {
                Log.w(TAG, "播放失败: ${e.message}")
                adapter.setPreviewing(null)
                context?.showError("播放失败：${e.toUserMessage()}")
            }
        }
    }

    private suspend fun downloadToCache(item: SharedReferenceAudio): File? {
        return try {
            val resp = RetrofitClient.heavyApi.downloadSharedReference(item.id)
            if (!resp.isSuccessful) {
                // 必须判 isSuccessful：404/500/401 时 resp.body() 为 null，不判会把
                // 真实失败原因完全掩盖。显式按 code 给出差异化提示
                Log.w(TAG, "下载试听文件失败: HTTP ${resp.code()}")
                return null
            }
            val body = resp.body() ?: return null
            val ext = item.fileName
                ?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                ?: "wav"
            // 视图销毁的窄窗口内 requireContext() 会抛异常，改用 context? 判空返回
            val dir = context?.cacheDir?.let { File(it, "shared_preview") }?.apply { mkdirs() }
                ?: return@downloadToCache null
            val file = File(dir, "preview_${item.id}.$ext")
            body.saveTo(file)
            file
        } catch (e: CancellationException) {
            // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "下载试听文件失败: ${e.message}")
            null
        }
    }

    /**
     * 用 prepareAsync 而不是 prepare：prepare() 是同步解码，放在主线程上
     * 遇到大文件会卡界面。回调里要判断 Fragment 还活着——异步期间用户可能已经切走了。
     */
    private fun startPlayback(file: File) {
        stopPlaybackOnly()
        previewFile = file
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener { player ->
                if (view == null) {
                    player.release()
                    return@setOnPreparedListener
                }
                player.start()
            }
            setOnCompletionListener { stopPreview() }
            setOnErrorListener { _, what, _ ->
                Log.w(TAG, "MediaPlayer 错误: what=$what")
                if (view != null) {
                    stopPreview()
                    Toast.makeText(requireContext(), "播放失败", Toast.LENGTH_SHORT).show()
                }
                true
            }
            prepareAsync()
        }
        mediaPlayer = mp
    }

    private fun stopPlaybackOnly() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放播放器失败: ${e.message}")
        }
        mediaPlayer = null
        previewFile?.let { runCatching { it.delete() } }
        previewFile = null
    }

    private fun stopPreview() {
        stopPlaybackOnly()
        if (view != null) adapter.setPreviewing(null)
    }

    // ---------- 使用 ----------

    private fun useShared(item: SharedReferenceAudio) {
        // 导入本地库 + 上报使用量：写用户状态，未登录先引导登录
        requireLoginThen("使用参考音频需要登录后使用") { doUseShared(item) }
    }

    private fun doUseShared(item: SharedReferenceAudio) {
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.setUsing(item.id)
            val result = repository.importFromShared(item)
            result.fold(
                onSuccess = { added ->
                    // 上报失败不影响本地副本：已经在库里了，只提示计数可能没加上
                    val counted = repository.reportSharedUse(item.id)
                    if (!counted) {
                        Log.w(TAG, "使用量上报失败，本地已保存: id=${item.id}")
                    }
                    context?.let {
                        Toast.makeText(
                            it, "已添加《${added.name}》到我的参考音频", Toast.LENGTH_SHORT
                        ).show()
                    }
                    // 刷新榜单：使用量变了，名次可能也要重排
                    loadRanking()
                },
                onFailure = { e ->
                    context?.showError(e.toUserMessage())
                }
            )
            adapter.setUsing(null)
        }
    }

    private fun showError(message: String) {
        tvError?.text = message
        tvError?.visibility = View.VISIBLE
    }

    // ---------- 一键套用：用这个声音读我的书 ----------

    /**
     * 「用这个声音读我的书」：把榜单上的分享音频一键拿去朗读。
     *
     * 流程与「使用」一致（未拥有则导入本地库 + 上报一次使用量），区别在于最后一步
     * 不再留在榜单，而是带着这条音色的标识跳到 TTS 页并预选它
     * （通过 [PendingTtsReference] 一次性传递给 TtsFragment）。
     *
     * 复用 [MainActivity.showTts] 进入 TTS——它本身就是从书架页跳转 TTS 的同一入口，
     * 返回键行为与别处一致（回到发现页）。
     */
    private fun applyToMyBook(item: SharedReferenceAudio) {
        // 导入本地库 + 跳 TTS：写用户状态，未登录先引导登录
        requireLoginThen("用这个声音读你的书需要登录后使用") { doApplyToMyBook(item) }
    }

    private fun doApplyToMyBook(item: SharedReferenceAudio) {
        val host = requireActivity() as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.setUsing(item.id)
            try {
                val alreadyOwned = withContext(Dispatchers.IO) {
                    repository.listAll().any { it.isFromSharedLibrary && it.serverId == item.id }
                }
                if (!alreadyOwned) {
                    val res = withContext(Dispatchers.IO) { repository.importFromShared(item) }
                    res.onFailure { e ->
                        withContext(Dispatchers.Main) { context?.showError(e.toUserMessage()) }
                        return@launch
                    }
                    // 上报失败不影响本地副本，仅提示计数可能没加上（与 useShared 同口径）
                    withContext(Dispatchers.IO) { repository.reportSharedUse(item.id) }
                }
                PendingTtsReference.set(PendingTtsReference.Payload(item.id, item.name))
                withContext(Dispatchers.Main) { host.showTts() }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                context?.showError("操作失败：${e.toUserMessage()}")
            } finally {
                adapter.setUsing(null)
            }
        }
    }

    /**
     * 写操作统一走宿主 MainActivity 的登录引导：未登录弹框 → 去登录 → 回来后自动接回 action。
     */
    private fun requireLoginThen(message: String, action: () -> Unit) {
        val host = activity as? MainActivity
        if (host != null) host.requireLogin(message, action) else action()
    }

    override fun onDestroyView() {
        stopPlaybackOnly()
        recyclerView?.adapter = null
        // 清空 View 引用，让旧视图树（含 RecyclerView / Adapter / 各 LineItem）可被回收
        btnRefresh = null
        recyclerView = null
        tvEmpty = null
        tvError = null
        // 试听文件按 id 命名、仅删当前一个，cacheDir/shared_preview 会持续累积。
        // 退出页面时整体清空该目录（均为一次性试听产物，无持久价值）。
        context?.let { File(it.cacheDir, "shared_preview").deleteRecursively() }
        super.onDestroyView()
    }
}
