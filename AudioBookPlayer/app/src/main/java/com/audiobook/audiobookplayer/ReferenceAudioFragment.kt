package com.audiobook.audiobookplayer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.audiobook.audiobookplayer.model.ReferenceAudio
import com.audiobook.audiobookplayer.model.ServerReferenceAudio
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 参考音频管理页（「我的 - 参考音频管理」）。
 *
 * 维护的是**手机本地**的参考音频列表：
 * - 从手机导入：复制一份进 App 私有目录并登记，原文件不动；
 * - 从服务器添加：把管理员共享库的音频下载到手机，再加入列表；
 * - 分享：把音频上传到服务端「我的库」并公开，进入发现页排行榜，他人可使用；
 * - 删除：只移除本条记录与 App 内的副本，**不删除手机上的原始音频文件**。
 *
 * 注意「分享」操作的服务端对象是 user_reference_audio 表（我的服务端库），
 * 与本地 SQLite 这张表是两回事：本地导入的音频必须先上传才能分享。
 */
class ReferenceAudioFragment : Fragment() {

    companion object {
        private const val TAG = "RefAudioFragment"
    }

    private lateinit var repository: ReferenceAudioRepository
    private lateinit var adapter: ReferenceAudioAdapter
    // ---------- 视图引用 ----------
    // replace() + addToBackStack 装载的 Fragment，视图销毁后实例仍留在返回栈里
    // （状态只降到 CREATED，onDestroy 不会调用）。字段若一直指向旧 View，整棵 View 树
    // 连同 Activity 会被一直拽着不放，直到该返回栈条目被弹出。故这里用可空后备字段持有，
    // onDestroyView 统一置空；非空取值器让使用处无需改动，且销毁后误访问会立刻抛错，
    // 而不是静默操作一个已经死掉的视图。
    private var _recyclerView: RecyclerView? = null
    private val recyclerView: RecyclerView get() = _recyclerView ?: error("视图已销毁: recyclerView")
    private var _tvEmpty: TextView? = null
    private val tvEmpty: TextView get() = _tvEmpty ?: error("视图已销毁: tvEmpty")
    private var _btnImportLocal: Button? = null
    private val btnImportLocal: Button get() = _btnImportLocal ?: error("视图已销毁: btnImportLocal")
    private var _btnAddFromServer: Button? = null
    private val btnAddFromServer: Button get() = _btnAddFromServer ?: error("视图已销毁: btnAddFromServer")
    private var _btnRecord: Button? = null
    private val btnRecord: Button get() = _btnRecord ?: error("视图已销毁: btnRecord")

    // ---------- 录制参考音频 ----------
    // 录制逻辑抽在 ReferenceAudioRecorder 里（与语音合成页共用同一份实现）；
    // 本页只负责：权限申请、把状态画到对话框控件上、录完落库进「我的参考音频」。
    private var recorder: ReferenceAudioRecorder? = null

    /** 当前打开的录制对话框（未打开时为 null）。关闭时置空并释放 recorder。 */
    private var recordDialog: AlertDialog? = null

    /**
     * 录音权限申请结果。
     *
     * ⚠️ 必须先 [showRecordDialog] 再 [startRecordingInDialog]，顺序不能颠倒：
     * 只调 startRecordingInDialog 会在**没有对话框**的情况下把 recorder 建起来并开始录音，
     * 界面上什么都看不到，而且会一直录到页面销毁 —— 用户完全无法停止。
     */
    private val requestRecordPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (view == null) return@registerForActivityResult
        if (granted) {
            showRecordDialog()
            startRecordingInDialog()
        } else {
            toast("需要麦克风权限才能录制参考音频")
        }
    }

    /** 服务端「已分享」的参考音频 id（user_reference_audio.id），用于按钮文案 */
    private var sharedIds: Set<Long> = emptySet()

    /** 正在上传/切换分享的本地 id，防重复点击 */
    private val pendingIds = mutableSetOf<Long>()

    /** 试听状态：当前正在网络合成 / 正在播放的本地条目 id */
    private var previewLoadingId: Long? = null
    private var previewPlayingId: Long? = null

    // ---------- 「服务器共享参考音频」弹窗的试听态 ----------
    /** 弹窗里正在播放的共享音频 id；null = 未在播 */
    private var serverPreviewPlayingId: Long? = null
    /** 弹窗里各行的试听按钮（id → 按钮），用于「试听 / 停止 / 加载中…」三态切换 */
    private val serverPreviewButtons = mutableMapOf<Long, TextView>()
    private var serverPickerDialog: AlertDialog? = null

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 回调挂的是 Fragment 生命周期而非视图生命周期：本 Fragment 由 replace() +
        // addToBackStack 装载，视图销毁后实例仍留在返回栈里，回调照样会触发；
        // 此时 importLocal 里的 viewLifecycleOwner 会直接抛 IllegalStateException
        if (view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri == null) {
                context?.let { Toast.makeText(it, "未选择文件", Toast.LENGTH_SHORT).show() }
                return@registerForActivityResult
            }
            importLocal(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_reference_audio, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repository = ReferenceAudioRepository(requireContext().applicationContext)

        _recyclerView = view.findViewById(R.id.recyclerRefs)
        _tvEmpty = view.findViewById(R.id.tvEmpty)
        _btnImportLocal = view.findViewById(R.id.btnImportLocal)
        _btnAddFromServer = view.findViewById(R.id.btnAddFromServer)
        _btnRecord = view.findViewById(R.id.btnRecord)

        adapter = ReferenceAudioAdapter(
            onDeleteClick = { item -> confirmDelete(item) },
            onShareClick = { item -> toggleShare(item) },
            onPreviewClick = { item, isPlaying -> previewReference(item, isPlaying) },
            onPlayOriginalClick = { item, isPlaying -> playOriginal(item, isPlaying) }
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        btnImportLocal.setOnClickListener { openFilePicker() }
        btnAddFromServer.setOnClickListener { loadServerReferences() }
        btnRecord.setOnClickListener { onRecordButtonClick() }

        refresh()
        refreshSharedIds()
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = repository.listAll()
            adapter.submitList(list)
            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * 拉一次服务端「我的库」，把处于分享中的 id 同步下来。
     * 分享状态存在服务端，本地 SQLite 不冗余这一列，避免两处状态不一致。
     */
    private fun refreshSharedIds() {
        AuthManager.token ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = try {
                withContext(Dispatchers.IO) { fetchSharedIds() }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "同步分享状态失败: ${e.message}")
                return@launch
            }
            sharedIds = ids
            adapter.setSharedIds(ids)
        }
    }

    private suspend fun fetchSharedIds(): Set<Long> {
        val resp = RetrofitClient.apiService.getMyReferences()
        if (!resp.isSuccessful) return emptySet()
        val body = resp.body() ?: return emptySet()
        @Suppress("UNCHECKED_CAST")
        val raw = body["references"] as? List<Map<String, Any>> ?: return emptySet()
        return raw.mapNotNull { m ->
            val shared = m["shared"] as? Boolean ?: false
            if (!shared) return@mapNotNull null
            (m["id"] as? Number)?.toLong()
        }.toSet()
    }

    // ---------- 分享 ----------

    /**
     * 点击「分享」：
     * - 已上传过的（source=mine）→ 直接切换分享状态；
     * - 仅本地导入的 → 先上传到服务端我的库，拿到 id 后再开启分享。
     *
     * 本地导入的音频服务端根本没有记录，不上传无从分享。
     */
    private fun toggleShare(item: ReferenceAudio) {
        if (item.id in pendingIds) return
        if (AuthManager.token == null) {
            context?.let { Toast.makeText(it, "请先登录", Toast.LENGTH_SHORT).show() }
            return
        }

        // 取局部变量：item.serverId 是 Long?，在 withContext 的 lambda 里
        // 靠智能转换不保险，显式收窄一次
        val serverId = item.serverId
        val isShared = serverId != null && serverId in sharedIds

        // 已上传：直接切换
        if (item.source == ReferenceAudio.SOURCE_MINE && serverId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                setPending(item.id, true)
                val ok = withContext(Dispatchers.IO) {
                    callShare(serverId, !isShared)
                }
                setPending(item.id, false)
                context?.let {
                    Toast.makeText(
                        it,
                        if (ok) (if (isShared) "已取消分享" else "已分享，他人可在发现页使用")
                        else "操作失败，请重试",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshSharedIds()
            }
            return
        }

        // 未上传：先上传再分享（两步对用户是一次点击）
        viewLifecycleOwner.lifecycleScope.launch {
            setPending(item.id, true)
            context?.let {
                Toast.makeText(it, "正在上传《${item.name}》…", Toast.LENGTH_SHORT).show()
            }
            val newServerId = withContext(Dispatchers.IO) {
                repository.uploadToMyLibrary(item).getOrNull()
            }
            if (newServerId == null) {
                setPending(item.id, false)
                context?.let { Toast.makeText(it, "上传失败，无法分享", Toast.LENGTH_LONG).show() }
                return@launch
            }
            val shared = withContext(Dispatchers.IO) { callShare(newServerId, true) }
            // 上传已成功（上方 newServerId == null 已提前 return）就绑定到「我的库」，
            // 与分享是否成功无关：否则分享失败时用户重试会整份文件再传一次、服务端产生孤儿记录。
            repository.markAsMine(item.id, newServerId)
            setPending(item.id, false)
            context?.let {
                Toast.makeText(
                    it,
                    if (shared) "已分享，他人可在发现页使用" else "上传成功但分享失败，请重试",
                    Toast.LENGTH_SHORT
                ).show()
            }
            refresh()
            refreshSharedIds()
        }
    }

    /** 调服务端切换分享；返回是否成功 */
    private suspend fun callShare(serverId: Long, shared: Boolean): Boolean {
        return try {
            val resp = RetrofitClient.apiService.shareReference(
                serverId, mapOf("shared" to shared)
            )
            if (!resp.isSuccessful) {
                Log.w(TAG, "切换分享失败: HTTP ${resp.code()}")
                return false
            }
            resp.body()?.get("success") == true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "切换分享异常: ${e.message}")
            false
        }
    }

    private fun setPending(localId: Long, pending: Boolean) {
        if (pending) pendingIds.add(localId) else pendingIds.remove(localId)
        adapter.setPendingIds(pendingIds.toSet())
    }

    // ---------- 音色试听 ----------

    /**
     * 「试听原声」：直接播放这条参考音频本身。
     *
     * ## 与「试听」的区别（这是本按钮存在的理由）
     * [previewReference] 走的是**服务端克隆合成**：拿这条参考音频当音色提示，
     * 让 /api/tts/clone 把固定样例句合成一遍再播 —— 听到的是"克隆结果"，
     * 需要联网，且听到的内容与用户录的那段话无关。
     * 而本方法播的就是用户自己那份录音（本地副本），纯离线、内容就是原话。
     *
     * 用户真正想判断的是"克隆出来的音色像不像我"，只有原声与克隆结果都能听到才比得出来；
     * 在此之前**没有任何途径回听自己录的素材**。
     *
     * ## 两个刻意的实现细节
     * 1. `deleteOnFinish = false` —— 播放器不能删这个文件。它是
     *    filesDir/referenceAudio 下的正式数据，不是临时合成产物；
     *    删掉等于把用户的音色素材弄丢（详见 VoicePreviewManager.playPreview 的说明）。
     * 2. 与试听共用同一个 MediaPlayer（都走 [VoicePreviewManager]），因此两者天然互斥：
     *    播原声会先停掉试听，反之亦然 —— 避免两路声音叠在一起。
     */
    private fun playOriginal(item: ReferenceAudio, isPlaying: Boolean) {
        if (isPlaying) {
            VoicePreviewManager.stop()
            adapter.setOriginalPlaying(null)
            return
        }
        val file = File(item.filePath)
        if (!file.exists()) {
            context?.let { Toast.makeText(it, "音频文件已丢失，请重新添加", Toast.LENGTH_SHORT).show() }
            return
        }
        // 先停掉可能正在进行的"试听合成"，并清掉它的加载态 ——
        // 否则那条目的按钮会永远卡在"试听中…"（它的协程回来时视图还在，
        // 但用户已经转去听原声了，界面上就留下一个不会恢复的灰按钮）。
        VoicePreviewManager.stop()
        previewLoadingId = null
        adapter.setPreviewStates(null, null)

        // token 用 "original:$id" 前缀与试听的 item.id 区分开：
        // 两者若用同一个 token，播放器内部的 playingToken 比较会把它们当同一次播放，
        // 导致"播原声时试听按钮也显示停止"这类状态串台。
        val token = "original:${item.id}"
        adapter.setOriginalPlaying(item.id)
        VoicePreviewManager.playPreview(file, token, deleteOnFinish = false) { playing ->
            if (view == null) return@playPreview
            if (!playing) adapter.setOriginalPlaying(null)
        }
    }

    /**
     * 试听某个参考音频（克隆音色）：用固定样例句让服务端 /api/tts/clone 合成并播放。
     * [isPlaying] 为 true 表示当前条目正在播放，点击意为「停止」。
     */
    private fun previewReference(item: ReferenceAudio, isPlaying: Boolean) {
        if (isPlaying) {
            // 停止当前试听
            VoicePreviewManager.stop()
            previewPlayingId = null
            adapter.setPreviewStates(previewLoadingId, null)
            return
        }
        val file = File(item.filePath)
        if (!file.exists()) {
            context?.let { Toast.makeText(it, "音频文件已丢失，请重新添加", Toast.LENGTH_SHORT).show() }
            return
        }
        // 点击发生在主线程、视图必然存活，先安全地取 ApplicationContext，
        // 避免协程切到 IO 之后再调 requireContext() 在视图销毁时抛异常
        // （异常会让 previewLoadingId 永远停在该 id、对应按钮卡死在"试听中…"）。TtsFragment 同此做法。
        val appCtx = requireContext().applicationContext
        // 进入「网络合成中」：按钮显示"试听中…"并置灰，防止重复触发。
        // 同时清掉原声的播放态：两者共用同一个 MediaPlayer，播试听必然打断原声，
        // 若不清，"停止原声"会一直挂在那条上而实际上没有声音。
        adapter.setOriginalPlaying(null)
        previewLoadingId = item.id
        adapter.setPreviewStates(previewLoadingId, null)
        Log.d(TAG, "合成试听开始: id=${item.id} name=${item.name} previewLoadingId=$previewLoadingId")

        viewLifecycleOwner.lifecycleScope.launch {
            val wav = withContext(Dispatchers.IO) {
                VoicePreviewManager.fetchPreviewWav(appCtx, file)
            }
            // 视图已被销毁（Fragment 仍在返回栈）：直接放弃，避免操作消亡视图
            if (view == null) {
                // 这里必须记日志：视图没了就不复位 loading 态，若之后视图重建，
                // 按钮会带着"合成中…"回来而实际早已结束 —— 看起来就是卡死。
                Log.w(TAG, "合成试听返回但视图已销毁，跳过复位（loading 态可能残留）")
                return@launch
            }
            previewLoadingId = null
            if (wav == null) {
                Log.w(TAG, "合成试听失败：wav == null（网络或服务端问题）")
                adapter.setPreviewStates(null, null)
                Toast.makeText(appCtx, "试听合成失败，请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Log.d(TAG, "合成试听拿到音频: ${wav.absolutePath} (${wav.length()} 字节)，开始播放")
            previewPlayingId = item.id
            adapter.setPreviewStates(null, previewPlayingId)
            VoicePreviewManager.playPreview(
                wav, item.id
            ) { playing ->
                if (view == null) return@playPreview
                Log.d(TAG, "合成试听播放状态: playing=$playing")
                if (!playing) {
                    previewPlayingId = null
                    adapter.setPreviewStates(null, null)
                }
            }
        }
    }

    // ---------- 录制参考音频 ----------

    /**
     * 点「录制」：先确认麦克风权限，再打开录制对话框。
     *
     * 权限在**打开对话框之前**要，而不是等用户点了「开始」再弹系统框：
     * 否则用户点开始 → 系统权限框盖住对话框 → 体验上是「点了没反应」。
     */
    private fun onRecordButtonClick() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            showRecordDialog()
        } else {
            requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showRecordDialog() {
        if (recordDialog?.isShowing == true) return
        val ctx = context ?: return
        val view = layoutInflater.inflate(R.layout.dialog_record_reference, null)

        val tvStatus = view.findViewById<TextView>(R.id.tvRecordStatus)
        val btnStart = view.findViewById<Button>(R.id.btnRecordStart)
        val btnStop = view.findViewById<Button>(R.id.btnRecordStop)
        val btnDiscard = view.findViewById<Button>(R.id.btnRecordDiscard)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("录制参考音频")
            .setView(view)
            .setPositiveButton("保存", null)   // 点击后在 onShow 里接管，避免录到一半就关窗
            .setNegativeButton("取消", null)
            .create()

        btnStart.setOnClickListener { startRecordingInDialog() }
        btnStop.setOnClickListener { stopRecordingAndOfferSave() }
        btnDiscard.setOnClickListener {
            recorder?.discardRecording()
            updateRecordButtons(btnStart, btnStop, btnDiscard)
        }

        // 「保存」在没录到东西时应当不可点：AlertDialog 的按钮要 onShow 之后才拿得到，
        // 且必须用 setOnClickListener(null) 之外的方式覆盖默认的「点完即关」
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (recorder?.hasRecording == true) stopRecordingAndOfferSave()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
            updateRecordButtons(btnStart, btnStop, btnDiscard)
        }
        dialog.setOnDismissListener {
            // 关窗即释放：未保存的录音（cacheDir 里的 .m4a）由 recorder 负责删掉，
            // 否则用户反复打开录制会留下一堆没人认领的缓存文件
            recorder?.release()
            recorder = null
            recordDialog = null
        }

        recordDialog = dialog
        dialog.show()
    }

    /** 开始录制（对话框已打开）。录音启动失败时把原因告诉用户，而不是静默失败。 */
    private fun startRecordingInDialog() {
        // 防御：对话框没开就开录，会出现「在录音但界面上没有停止按钮」的失控状态
        // （权限回调里就踩过这个坑）。这里直接挡掉，而不是寄望调用方顺序正确。
        if (recordDialog == null) {
            Log.w(TAG, "录制对话框未打开，忽略录音请求")
            return
        }
        val ctx = context ?: return
        val r = recorder ?: ReferenceAudioRecorder(ctx, recorderListener).also { recorder = it }
        try {
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败: ${e.message}", e)
            toast("录音启动失败，请检查麦克风是否被其它应用占用")
        }
    }

    private fun stopRecordingAndOfferSave() {
        val r = recorder ?: return
        val ok = r.stop()
        if (!ok) {
            toast("没有录到声音，请重试")
            return
        }
        askRecordingName()
    }

    /** 录完后让用户确认名称（默认带时间戳，避免多条录音重名） */
    private fun askRecordingName() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            setText(defaultRecordingName())
            setSelection(text.length)
            hint = "参考音频名称"
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("保存录音")
            .setMessage("保存后会作为一条记录加入「我的参考音频」，可在语音合成页选用。")
            .setView(input)
            .setPositiveButton("保存") { _, _ -> saveRecording(input.text.toString()) }
            // 取消保存就把这段录音丢掉，避免用户以为"取消"只是跳过命名、音频还在
            .setNegativeButton("不保存") { _, _ ->
                recorder?.discardRecording()
                updateRecordButtonsFromDialog()
            }
            .show()
    }

    private fun saveRecording(name: String) {
        val r = recorder ?: return
        val source = r.recordedFile ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.importFromRecording(source, name)
            result.fold(
                onSuccess = { item ->
                    // 落库成功后交出版权：此后这份缓存文件归用户素材，不再被当临时文件删掉
                    r.markConsumed()
                    recordDialog?.dismiss()
                    toast("已保存《${item.name}》")
                    refresh()
                },
                onFailure = { e ->
                    // 失败时**不动**录音：用户还能重试保存，不然录了半天的音频就白录了
                    ctxShowError(e.toUserMessage())
                }
            )
        }
    }

    private fun defaultRecordingName(): String =
        "录音 " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    /** recorder 的状态回调：只碰对话框控件，不碰列表 */
    private val recorderListener = object : ReferenceAudioRecorder.Listener {
        override fun onRecorderStateChanged(recording: Boolean, hasRecording: Boolean, elapsedSeconds: Int) {
            val dialog = recordDialog ?: return
            val v = dialog.findViewById<View>(R.id.tvRecordStatus) ?: return
            val tv = v as? TextView ?: return
            tv.text = when {
                recording -> "🔴 录制中 %02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
                hasRecording -> "🎵 已录制，可保存或删除"
                else -> "尚未开始"
            }
            tv.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    when {
                        recording -> android.R.color.holo_red_dark
                        hasRecording -> android.R.color.holo_green_dark
                        else -> R.color.secondary_text
                    }
                )
            )
            updateRecordButtonsFromDialog()
        }

        override fun onRecorderMessage(message: String) {
            // 状态文案已经表达了同样的事，这里不额外弹 Toast，避免对话框上叠提示
        }

        override fun onPlaybackChanged(playing: Boolean) {
            // 本对话框没有独立试听按钮（录完保存后可在列表中试听），故无需更新控件
        }
    }

    /** 按当前 recorder 状态刷新对话框按钮（对话框未打开时什么都不做） */
    private fun updateRecordButtonsFromDialog() {
        val dialog = recordDialog ?: return
        val start = dialog.findViewById<Button>(R.id.btnRecordStart) ?: return
        val stop = dialog.findViewById<Button>(R.id.btnRecordStop) ?: return
        val discard = dialog.findViewById<Button>(R.id.btnRecordDiscard) ?: return
        updateRecordButtons(start, stop, discard)
    }

    private fun updateRecordButtons(start: Button, stop: Button, discard: Button) {
        val recording = recorder?.isRecording == true
        val has = recorder?.hasRecording == true
        start.isEnabled = !recording
        stop.isEnabled = recording
        discard.isEnabled = has && !recording
    }

    private fun toast(msg: String) {
        context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun ctxShowError(msg: String) {
        context?.showError(msg)
    }

    // ---------- 从手机导入 ----------

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // 音频与视频都允许（视频由服务端转 WAV）；具体扩展名在导入时按白名单校验，
            // 与服务端 isAllowedReferenceExt 保持同口径
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        }
        pickAudioLauncher.launch(intent)
    }

    private fun importLocal(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.importFromUri(uri)
            context?.let { ctx ->
                result.fold(
                    onSuccess = { item ->
                        Toast.makeText(ctx, "已添加《${item.name}》", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        ctx.showError(e.toUserMessage())
                    }
                )
            }
            refresh()
        }
    }

    // ---------- 从服务器共享库添加 ----------

    private fun loadServerReferences() {
        if (AuthManager.token == null) {
            context?.let { Toast.makeText(it, "请先登录", Toast.LENGTH_SHORT).show() }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { fetchServerReferences() }
                if (items.isEmpty()) {
                    context?.let {
                        Toast.makeText(it, "服务器暂无共享参考音频", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                showServerPicker(items)
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                context?.showError("获取服务器列表失败：${e.toUserMessage()}")
            }
        }
    }

    private suspend fun fetchServerReferences(): List<ServerReferenceAudio> {
        val resp = RetrofitClient.apiService.getServerReferences()
        if (!resp.isSuccessful) {
            // BusinessException：让调用方的 `showError("获取服务器列表失败：${e.toUserMessage()}")`
            // 显示真实状态码，而不是「未知错误」。
            throw BusinessException("HTTP ${resp.code()}")
        }
        val body = resp.body() ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val raw = body["references"] as? List<Map<String, Any>> ?: return emptyList()
        return raw.mapNotNull { m ->
            val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
            val name = m["name"] as? String ?: return@mapNotNull null
            ServerReferenceAudio(
                id = id,
                name = name,
                fileName = m["fileName"] as? String,
                description = m["description"] as? String,
                fileSize = (m["fileSize"] as? Number)?.toLong() ?: 0L
            )
        }
    }

    /**
     * 服务器共享参考音频的挑选弹窗：每行带「试听」。
     *
     * 原来这里是 `setItems(labels)` —— 只有一行文字，用户唯一能依据的只有名字，
     * 而名字恰恰是这个库里最不可靠的信息（好几条都叫「中文女性」）。
     * 现在改成自定义视图：左侧名称 + 大小/描述，右侧「试听」；点击行本身仍是添加。
     */
    private fun showServerPicker(items: List<ServerReferenceAudio>) {
        val ctx = context ?: return
        val content = layoutInflater.inflate(R.layout.dialog_server_reference, null)
        val container: LinearLayout = content.findViewById(R.id.llServerRefItems)
        serverPreviewButtons.clear()
        items.forEach { item ->
            val row = layoutInflater.inflate(R.layout.item_server_reference, container, false)
            row.findViewById<TextView>(R.id.tvServerRefName).text = item.name
            val meta = row.findViewById<TextView>(R.id.tvServerRefMeta)
            val parts = buildList {
                if (item.fileSize > 0) add(FileUtils.formatSize(item.fileSize))
                item.description?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            meta.text = parts.joinToString(" · ")
            meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
            val btn = row.findViewById<TextView>(R.id.btnPreviewServerRef)
            btn.text = "试听"
            btn.setOnClickListener { toggleServerPreview(item, btn) }
            row.setOnClickListener {
                serverPickerDialog?.dismiss()
                downloadFromServer(item)
            }
            serverPreviewButtons[item.id] = btn
            container.addView(row)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("服务器共享参考音频")
            .setView(content)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener {
            // 关弹窗必须停声：声音继续从"已经关掉的界面"里出来，用户手上却没有停止按钮
            // （列表行的按钮此刻并不显示"停止"），只能等它自己播完。
            if (serverPreviewPlayingId != null) {
                VoicePreviewManager.stop()
                serverPreviewPlayingId = null
            }
            serverPreviewButtons.clear()
            serverPickerDialog = null
        }
        serverPickerDialog = dialog
        dialog.show()
    }

    /**
     * 弹窗内试听某条共享参考音频：点第一次=下载并播放，再点=停止。
     *
     * 与列表里的「试听原声」共用同一个 [VoicePreviewManager]（同一时刻只有一路声音），
     * 但 token 前缀不同（`serverref:`），两处状态不会互相串台。
     */
    private fun toggleServerPreview(item: ServerReferenceAudio, btn: TextView) {
        if (serverPreviewPlayingId == item.id) {
            VoicePreviewManager.stop()
            serverPreviewPlayingId = null
            btn.text = "试听"
            return
        }
        // 先停掉所有可能正在发声的路径：本弹窗其它行、列表里的合成试听与原声试听
        VoicePreviewManager.stop()
        serverPreviewPlayingId = null
        resetServerPreviewLabels()
        previewLoadingId = null
        previewPlayingId = null
        adapter.setPreviewStates(null, null)
        adapter.setOriginalPlaying(null)

        // 下载是 IO，context 在切换线程前取好：视图销毁后再找 requireContext() 会抛
        val appCtx = requireContext().applicationContext
        val token = "serverref:${item.id}"
        btn.text = "加载中…"
        btn.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                VoicePreviewManager.fetchRemoteForPreview(appCtx, item.fileName) {
                    RetrofitClient.heavyApi.downloadServerReference(item.id)
                }
            }
            btn.isEnabled = true
            if (file == null) {
                btn.text = "试听"
                Toast.makeText(appCtx, "试听失败：网络或服务端异常", Toast.LENGTH_SHORT).show()
                return@launch
            }
            serverPreviewPlayingId = item.id
            btn.text = "停止"
            VoicePreviewManager.playPreview(file, token) { playing ->
                if (playing) return@playPreview
                if (serverPreviewPlayingId == item.id) {
                    serverPreviewPlayingId = null
                    btn.text = "试听"
                }
            }
        }
    }

    /** 把所有行的试听按钮复位（发起新一轮试听前调，避免两行同时显示"停止"） */
    private fun resetServerPreviewLabels() {
        serverPreviewButtons.values.forEach {
            it.text = "试听"
            it.isEnabled = true
        }
    }

    private fun downloadFromServer(item: ServerReferenceAudio) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.importFromServer(item)
            context?.let { ctx ->
                result.fold(
                    onSuccess = { added ->
                        Toast.makeText(ctx, "已下载《${added.name}》到本地", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        ctx.showError(e.toUserMessage())
                    }
                )
            }
            refresh()
        }
    }

    // ---------- 删除 ----------

    private fun confirmDelete(item: ReferenceAudio) {
        val ctx = context ?: return
        MaterialAlertDialogBuilder(ctx)
            .setTitle("删除参考音频")
            .setMessage(
                "将从「我的参考音频」中移除《${item.name}》。\n\n" +
                    "仅删除本条记录与 App 内的副本，不会删除手机上的原始音频文件。"
            )
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = repository.delete(item)
                    context?.let {
                        Toast.makeText(it, if (ok) "已移除" else "删除失败", Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        // 允许试听：MediaPlayer 的 prepared 回调会检查这个标志，
        // 避免"点完试听马上切后台"时音频在后台响起来
        recorder?.isStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    override fun onStop() {
        super.onStop()
        // 停掉可能出现的声音，但**不打断录音** —— 录到一半切后台应当继续录。
        // v5/A8：这里原先在结尾又调了一次 super.onStop()（同一次回调里调两遍），
        // 属于复制粘贴残留；系统回调重复触发可能导致后续 onStart/onResume 的配对计数错乱。
        recorder?.isStarted = false
        recorder?.pausePlayback()
    }

    override fun onDestroyView() {
        // 该 Fragment 由 replace() + addToBackStack 装载，视图销毁后实例仍留在返回栈里。
        // 必须先解绑 adapter，再断开视图引用——否则整棵 View 树会被一直拽着不放，
        // 直到该返回栈条目被弹出
        // 停止可能仍在播放的试听，避免后台继续发声
        VoicePreviewManager.stop()

        // 录制中的对话框随视图一起销毁：先关对话框（它会 dismiss → 释放 recorder），
        // 再兜底 release 一次，保证 MediaRecorder/MediaPlayer 一定被释放
        recordDialog?.dismiss()
        recordDialog = null
        recorder?.release()
        recorder = null

        recyclerView.adapter = null

        // 断开视图引用：Fragment 实例在返回栈里还活着，
        // 不能让它继续拽着已经销毁的 View 树
        _recyclerView = null
        _tvEmpty = null
        _btnImportLocal = null
        _btnAddFromServer = null
        _btnRecord = null
        super.onDestroyView()
    }
}
