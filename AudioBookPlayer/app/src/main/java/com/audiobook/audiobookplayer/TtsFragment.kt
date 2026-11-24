package com.audiobook.audiobookplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.audiobook.audiobookplayer.model.Book
import com.audiobook.audiobookplayer.model.CreditBalanceResponse
import com.audiobook.audiobookplayer.model.ReferenceAudio
import com.audiobook.audiobookplayer.moss.LocalSynthesisService
import com.audiobook.audiobookplayer.moss.MossLocalEngine
import com.audiobook.audiobookplayer.moss.MossModuleManager
import com.audiobook.audiobookplayer.moss.MossModuleActivity
import com.audiobook.audiobookplayer.moss.showSynthesisOutcome
import com.audiobook.audiobookplayer.network.AuthManager
import com.audiobook.audiobookplayer.network.RetrofitClient
import com.audiobook.audiobookplayer.network.SseClient
import com.audiobook.audiobookplayer.network.TaskProgressListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import java.io.File

private const val TAG = "TtsFragment"

class TtsFragment : Fragment() {

    companion object {
        /**
         * 本机合成的线程数与并行度**不在这里写死**：由
         * [com.audiobook.audiobookplayer.moss.MossLocalSynthesizer.planParallelism]
         * 按「可用内存 + CPU 核数」自动决定。
         *
         * 原先是「固定 4 线程 + 逐段串行」—— 在 8 核机器上等于只用了一个引擎的算力，
         * 其余核心全程闲着。现在并行的是**多个独立引擎**：ONNX Runtime 的
         * OrtSession 不是线程安全的，不能多线程共用一个引擎（并发调用属未定义行为，
         * 可能悄悄返回错数据，比崩溃更难查）。
         */
        /** 单段音频下载体积上限（正常单段远小于此；超限视为服务端异常响应，弃段保进程） */
        private const val MAX_SEGMENT_BYTES = 50L * 1024 * 1024
        // 原先还有一个 MAX_REFERENCE_BYTES（参考音频 50MB 上限），
        // 用于限制「从手机选文件」时的流式拷贝；该入口已移除，
        // 体积校验现在由「参考音频管理」页的 ReferenceAudioRepository.MAX_BYTES 负责。

        /** 「合成方式」下拉的选项位置：在线（默认，消耗积分、需登录） */
        private const val MODE_ONLINE = 0
        /** 「合成方式」下拉的选项位置：本机（离线、不耗积分，需先下载 MOSS-TTS-Nano 模块） */
        private const val MODE_LOCAL = 1

        /**
         * 「选择参考音频来源」下拉的选项位置：我的参考音频库（**默认**）。
         * 选中时下方列出参考音频库里的具体参考音频，选哪条就用哪条克隆音色。
         */
        private const val SOURCE_LIBRARY = 0
        /**
         * 「选择参考音频来源」下拉的选项位置：预设音色。
         * 选中时下方列出服务端预设音色的具体值，免参考音频直接合成。
         */
        private const val SOURCE_PRESET = 1
    }

    // ---------- 视图引用 ----------
    // replace() + addToBackStack 装载的 Fragment，视图销毁后实例仍留在返回栈里
    // （状态只降到 CREATED，onDestroy 不会调用）。字段若一直指向旧 View，整棵 View 树
    // 连同 Activity 会被一直拽着不放，直到该返回栈条目被弹出。故这里用可空后备字段持有，
    // onDestroyView 统一置空；非空取值器让使用处无需改动，且销毁后误访问会立刻抛错，
    // 而不是静默操作一个已经死掉的视图。
    private var _btnSelectBook: Button? = null
    private val btnSelectBook: Button get() = _btnSelectBook ?: error("视图已销毁: btnSelectBook")
    private var _tvSelectedBook: TextView? = null
    private val tvSelectedBook: TextView get() = _tvSelectedBook ?: error("视图已销毁: tvSelectedBook")
    private var _tvSelectedFile: TextView? = null
    private val tvSelectedFile: TextView get() = _tvSelectedFile ?: error("视图已销毁: tvSelectedFile")
    private var _btnUploadAndSynthesize: Button? = null
    private val btnUploadAndSynthesize: Button get() = _btnUploadAndSynthesize ?: error("视图已销毁: btnUploadAndSynthesize")
    private var _progressBar: ProgressBar? = null
    private val progressBar: ProgressBar get() = _progressBar ?: error("视图已销毁: progressBar")
    private var _tvProgress: TextView? = null
    private val tvProgress: TextView get() = _tvProgress ?: error("视图已销毁: tvProgress")
    private var _btnPlayResult: Button? = null
    private val btnPlayResult: Button get() = _btnPlayResult ?: error("视图已销毁: btnPlayResult")
    private var _tvCreditHint: TextView? = null
    private val tvCreditHint: TextView get() = _tvCreditHint ?: error("视图已销毁: tvCreditHint")
    private var _tvCostEstimate: TextView? = null
    private val tvCostEstimate: TextView get() = _tvCostEstimate ?: error("视图已销毁: tvCostEstimate")
    // 录制参考音频的控件/状态已整体移到 ReferenceAudioFragment（我的 - 参考音频管理）。
    // 本页不再持有 btnStartRecord/btnStopRecord/btnPlayRecord/btnDeleteRecord/tvRecordStatus。
    private var _btnPreviewVoice: Button? = null
    private val btnPreviewVoice: Button get() = _btnPreviewVoice ?: error("视图已销毁: btnPreviewVoice")

    // 默认预设音色下拉（上传时指定，免参考音频）
    private var _spinnerDefaultVoice: Spinner? = null
    private val spinnerDefaultVoice: Spinner get() = _spinnerDefaultVoice ?: error("视图已销毁: spinnerDefaultVoice")
    private var _tvVoiceHint: TextView? = null
    private val tvVoiceHint: TextView get() = _tvVoiceHint ?: error("视图已销毁: tvVoiceHint")

    // ---------- 参考音频来源（「我的参考音频库 / 预设音色」下拉 + 两个分支） ----------
    private var _spinnerReferenceSource: Spinner? = null
    private val spinnerReferenceSource: Spinner get() = _spinnerReferenceSource ?: error("视图已销毁: spinnerReferenceSource")
    private var _tvSourceHint: TextView? = null
    private val tvSourceHint: TextView get() = _tvSourceHint ?: error("视图已销毁: tvSourceHint")
    /** 分支一「我的参考音频库」：库内具体参考音频列表 + 选中状态 + 试听 */
    private var _layoutSourceLibrary: LinearLayout? = null
    private val layoutSourceLibrary: LinearLayout get() = _layoutSourceLibrary ?: error("视图已销毁: layoutSourceLibrary")
    private var _spinnerReferenceLibrary: Spinner? = null
    private val spinnerReferenceLibrary: Spinner get() = _spinnerReferenceLibrary ?: error("视图已销毁: spinnerReferenceLibrary")
    /** 分支二「预设音色」：预设音色具体值列表 */
    private var _layoutSourcePreset: LinearLayout? = null
    private val layoutSourcePreset: LinearLayout get() = _layoutSourcePreset ?: error("视图已销毁: layoutSourcePreset")

    // ---------- 合成方式（页面最上方的「在线 / 本机」下拉） ----------
    private var _spinnerSynthesisMode: Spinner? = null
    private val spinnerSynthesisMode: Spinner get() = _spinnerSynthesisMode ?: error("视图已销毁: spinnerSynthesisMode")
    private var _tvModeHint: TextView? = null
    private val tvModeHint: TextView get() = _tvModeHint ?: error("视图已销毁: tvModeHint")

    // ---------- 本机音色的状态（音色本身在「预设音色」分支里选，见 [applyPresetSpinnerForMode]） ----------
    /**
     * 当前合成方式是否为「本机」。
     *
     * 字段（而非每次读下拉位置）当唯一事实来源：onItemSelected 的去重、按钮可用态
     * 判据、发起时的路由都要读它；视图重建（旋屏）后下拉位置由它恢复。
     * 注意 Fragment 实例本身不跨页面存活（MainActivity.showTts 每次 new 一个），
     * 所以每次从入口进合成本页都是默认的「在线」，符合需求里的"默认在线"。
     */
    private var synthesisModeLocal = false

    private var selectedBooks: MutableList<Book> = mutableListOf()
    // 参考音频来源已收敛为「从我的参考音频库选择」一种（见 libraryReferenceFile），
    // 原先还有一个 selectedAudioUri（系统文件选择器选来的 URI），已随入口一并移除。
    // 注：这里的「来源」指**文件从哪来**；页面上的「我的参考音频库 / 预设音色」
    // 来源下拉是另一层（referenceSourceMode），管的是用库内音频还是预设音色。
    private var currentTaskId: Long? = null

    /**
     * 本机合成是否处于忙碌态（由 [LocalSynthesisService.progress] 驱动）。
     *
     * 它的唯一用途是防止重复发起：内存放不下第二份模型，
     * 真跑起来只会两个任务互相拖垮。**也不再持有 Job 与中止标志** ——
     * 推理已经搬到前台服务里，生命周期与中止都由服务负责，
     * 页面这边只观察进度、只发指令。
     */
    private var localSynthesisUiBusy = false

    /** 中止按钮的视图引用（可空：视图销毁后返回回调仍可能触发） */
    private var _btnCancelSynthesis: Button? = null
    private val btnCancelSynthesis: Button
        get() = _btnCancelSynthesis ?: error("视图已销毁: btnCancelSynthesis")

    /** 暂停/继续按钮（同一按钮按状态切换文案与动作） */
    private var _btnPauseSynthesis: Button? = null
    private val btnPauseSynthesis: Button
        get() = _btnPauseSynthesis ?: error("视图已销毁: btnPauseSynthesis")

    /** 本机合成在 IO 线程推理，进度要切回主线程刷 UI */
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 每次任务完成时刷新，供「下载音频」按钮统一使用，避免每次完成都重设监听器 */
    private var pendingDownloadTaskId: Long? = null

    // 批量合成时会同时存在多个任务，每个任务一条 SSE 连接。
    // 按 taskId 分别保存连接：共用一个变量会让后一本书覆盖前一本的引用，
    // 旧连接不会被 cancel（泄漏），界面也只能看到最后一个任务的进度。
    private val sseEventSources = java.util.concurrent.ConcurrentHashMap<Long, okhttp3.sse.EventSource>()

    // 本批次仍在合成中的任务，用来在界面上汇总显示 "已完成 x/y 本"
    private val activeTaskIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private var batchTotal: Int = 0

    // taskId -> 书名，下载时用来区分每本书的落盘目录。
    // 不能统一取 selectedBooks.firstOrNull()：它在提交完成后就被清空，
    // 多本书的音频会全部混进同一个 "unknown_book" 目录。
    private val taskBookNames = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private var selectedReferenceId: String? = null

    // 可选预设音色清单（id → 中文名显示），供「默认音色」下拉
    private val voiceDisplayList = mutableListOf<String>()
    private val voiceIdList = mutableListOf<String>()
    private var voiceAdapter: ArrayAdapter<String>? = null
    /** 当前选中的默认预设音色（空表示使用参考音频克隆）；选中有值即可免参考音频合成 */
    private var selectedDefaultVoiceId: String? = null
    /**
     * 「预设音色」下拉是否已装上内容（内置兜底一装上就置位）。
     * 提示行据此区分「还在加载」与「加载后为空」。
     */
    private var voicesLoaded = false

    /**
     * 是否已成功拿到过**服务端**音色清单。
     *
     * 与 [voicesLoaded] 分开是因为两者的失败含义不同：内置兜底永远装得上，
     * 而服务端清单拉不到（断网 / 老服务端匿名 401）时，下拉里那份可能只是过时的
     * 内置数据。onStart 靠它决定要不要补拉一次。
     */
    private var serverVoicesLoaded = false

    // ---------- 本机音色的状态 ----------
    /**
     * 本机合成当前选中的内置音色名。
     *
     * 跨视图重建保留（同 [referenceSourceMode]）：返回栈里的 Fragment 实例在
     * onViewCreated 时按它恢复下拉位置，用户不必每次回来都重选。
     *
     * 落库后会作为任务 `audio_name` 的一部分（[LocalSynthesisService.audioName]），
     * 于是书架上一眼能看出这条是哪个音色合成的。
     */
    private var localVoiceName: String = MossLocalEngine.DEFAULT_VOICE

    /** 本机音色下拉的适配器（模块内置音色清单；持有 Activity Context，视图销毁时必须置空） */
    private var localVoiceAdapter: ArrayAdapter<String>? = null
    /** 内置音色清单是否已读过一轮（区分「还没读」与「读了但为空 = 模块没装」） */
    private var localVoicesLoaded = false

    /**
     * 模块清单里的内置音色（读一次即缓存）。
     *
     * 必须是字段而不是每次现读：切合成方式 / 切来源时都要用它重装下拉，
     * 而清单是 500KB 级的 JSON —— 现读等于每次切下拉都在主线程解析一次。
     */
    private var localVoices: List<String> = emptyList()

    // ---------- 参考音频来源的状态 ----------
    /**
     * 当前参考音频来源：[SOURCE_LIBRARY]（默认，我的参考音频库）或 [SOURCE_PRESET]（预设音色）。
     *
     * 与 synthesisModeLocal 一样属于**跨视图重建保留**的字段：Fragment 实例在返回栈里
     * 存活（replace + addToBackStack），onViewCreated 时按它恢复下拉位置。
     */
    private var referenceSourceMode: Int = SOURCE_LIBRARY
    /** 参考音频库当前清单（spinner 位置 → 条目的映射依据，随 loadReferenceLibrary 刷新） */
    private var libraryItems: List<ReferenceAudio> = emptyList()
    /** 库清单的展示名（与 [libraryItems] 同序，供 spinner 适配器） */
    private val libraryDisplayList = mutableListOf<String>()
    private var libraryAdapter: ArrayAdapter<String>? = null

    /** 当前用户的积分概况（余额 + 计费规则），用于提交前校验 */
    private var creditBalance: CreditBalanceResponse? = null

    /**
     * 批量提交中途收到 402（余额不足）时置位：
     * 余额已耗尽，继续上传后面的书只会白传几十 MB 流量再被服务端打回，
     * 循环里检测到该标记立即中断，并在收尾时弹充值引导。
     */
    @Volatile private var abortBatchForCredits = false

    /**
     * 充值后返回刷新余额。
     *
     * 注意：registerForActivityResult 的回调挂的是【Fragment 生命周期】而不是视图生命周期，
     * 所以视图已经销毁（Fragment 还留在返回栈里）时回调照样会触发。下面这些回调都先挡一道
     * [isViewCreated]：否则 loadCreditBalance() 里的 viewLifecycleOwner 会直接抛
     * IllegalStateException（"Can't access the Fragments View's LifecycleOwner ... after
     * onDestroyView()"），其余回调则会去操作一张已经死掉的视图。
     */
    private val rechargeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isViewCreated) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            loadCreditBalance()
            Toast.makeText(requireContext(), "充值成功，积分已更新", Toast.LENGTH_SHORT).show()
        }
    }

    private lateinit var viewModel: BookViewModel
    private var _isViewCreated = false
    private val isViewCreated: Boolean
        get() = _isViewCreated

    // 跟踪轮询协程：每个任务独立槽位（key=服务端 taskId），互不覆盖；销毁时全部取消
    private val pollingJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    // 批次内各任务实耗积分累计，用于合成结束后标注「本次消耗 X 积分」
    private val settledTaskIds = mutableSetOf<Long>()
    @Volatile private var batchConsumedCredits = 0
    @Volatile private var pendingCostFetches = 0

    /**
     * 从书架（已导入的电子书列表）多选，返回结果通过 JSON 传入。
     */
    private val selectBookLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isViewCreated) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val booksJson = data.getStringExtra("BOOKS_JSON")
            if (booksJson.isNullOrEmpty()) return@registerForActivityResult

            try {
                val jsonArray = org.json.JSONArray(booksJson)
                val picked = mutableListOf<Book>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val tableName = obj.getString("tableName")
                    val displayName = obj.getString("displayName")
                    val filePath = obj.optString("filePath", "")
                    picked.add(
                        Book(tableName = tableName, displayName = displayName, filePath = filePath)
                    )
                }
                // 以**回传结果**为准整体替换，而不是并集新增。
                // 原先只 add 不 remove：用户二次进选书框取消勾选后，
                // 确认页仍显示旧列表（按钮还写着「批量合成 (2本)」而实际只选了 1 本），
                // 于是会拿早已取消的书去合成 —— 提交了用户明确不要的内容。
                // 选书页回传的就是「本次勾选的完整集合」，直接覆盖是唯一自洽的语义。
                val removed = selectedBooks.size - picked.count { new ->
                    selectedBooks.any { it.tableName == new.tableName }
                }
                selectedBooks.clear()
                selectedBooks.addAll(picked)
                updateSelectedBooksDisplay()
                updateSynthesizeButton()
                if (picked.isNotEmpty()) {
                    val msg = StringBuilder("已选择 ${picked.size} 本书")
                    if (removed > 0) msg.append("，取消 $removed 本")
                    Toast.makeText(requireContext(), msg.toString(), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "解析书籍JSON失败: ${e.message}")
                Toast.makeText(requireContext(), "解析选书结果失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 参考音频的来源 ----------
    // 来源由页面上的「我的参考音频库 / 预设音色」下拉决定（referenceSourceMode）：
    //   · 我的参考音频库（默认）：下方列出库内具体参考音频，选中哪条就用哪条克隆音色；
    //   · 预设音色：下方列出服务端预设音色，免参考音频直接合成。
    // 两者互斥（切换即清掉另一分支的选中），合成逻辑按 libraryReferenceFile /
    // selectedDefaultVoiceId 哪个有值走哪条路。
    // 原先这里还有一个系统文件选择器入口（pickAudio / selectedAudioUri /
    // rememberReferenceUri / restoreReferenceUri，把选中的 URI 记进 tts_prefs 以便下次恢复），
    // 已按需求连同「📂 选择音频文件」按钮一起移除。
    // 想用手机上的音频，先在「我的 - 参考音频管理」用「从手机导入」收进库里，再在这里选。

    /**
     * 加载参考音频库清单，填充来源下拉下方的具体参考音频列表，并恢复选中。
     *
     * 选中优先级：
     *   1. 「发现 - 参考音频」一键套用传来的意图（[PendingTtsReference]，用完即清）；
     *   2. 视图重建前已选中的库文件（libraryReferenceFile 还在，按路径对回条目）；
     *   3. 库非空时默认选第一项（清单按创建时间倒序，即最新加入的那条）。
     *
     * 库为空时列表只显示一条占位文案，并提示去「我的 - 参考音频管理」添加 ——
     * 与旧版弹窗选择器的空库提示同一句，行为不降级。
     */
    private fun loadReferenceLibrary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { referenceRepository.listAll() }
            if (!isViewCreated) return@launch
            libraryItems = items
            libraryDisplayList.clear()
            if (items.isEmpty()) {
                libraryDisplayList.add("（参考音频库为空）")
            } else {
                libraryDisplayList.addAll(
                    items.map { "${it.name}（${if (it.isFromServer) "来自服务器" else "本地导入"}）" }
                )
            }
            libraryAdapter?.notifyDataSetChanged()

            // 一键套用意图：在库里找到那条分享音频并预选（找不到/文件丢失都降级为手动选）
            val payload = PendingTtsReference.consume()
            val pendingItem = payload?.let { p ->
                items.firstOrNull { it.isFromSharedLibrary && it.serverId == p.serverId }
                    ?: items.firstOrNull { it.name == p.name && it.isFromSharedLibrary }
            }
            if (pendingItem != null && referenceSourceMode != SOURCE_LIBRARY) {
                // 意图明确要用某条参考音频：来源必须是「我的参考音频库」。
                // 先改字段再同步下拉视觉（监听发现无变化会提前返回，显式补 visibility/hint）。
                referenceSourceMode = SOURCE_LIBRARY
                selectedDefaultVoiceId = null
                AppPrefs.setDefaultVoiceId(requireContext(), "") // 重置点同步清跨端偏好
                spinnerReferenceSource.setSelection(SOURCE_LIBRARY)
                applyReferenceSourceVisibility()
                updateSourceHint()
            }
            if (referenceSourceMode != SOURCE_LIBRARY) {
                // 来源是「预设音色」：清单照常加载（用户切回去时立即可用），但**不预选** ——
                // 预选会给 libraryReferenceFile 赋值，而在线合成里参考音频优先级高于
                // 预设音色，于是「来源=预设音色」实际却用参考音频克隆，错误音色可复现。
                return@launch
            }
            when {
                pendingItem != null -> {
                    val idx = items.indexOf(pendingItem)
                    spinnerReferenceLibrary.setSelection(idx)
                    selectLibraryItem(idx)
                    Toast.makeText(
                        requireContext(),
                        "已为你选好音色《${pendingItem.name}》，请选择要朗读的书",
                        Toast.LENGTH_LONG
                    ).show()
                }
                payload != null -> {
                    // 意图带了但库里找不到：明说，再按默认规则选，别静默吞掉
                    Toast.makeText(requireContext(), "未找到对应参考音频，请手动选择", Toast.LENGTH_LONG).show()
                    selectDefaultLibraryItem()
                }
                else -> selectDefaultLibraryItem()
            }
        }
    }

    /** 无待恢复意图时的默认选中：视图重建前选过的条目 > 第一项（库非空） */
    private fun selectDefaultLibraryItem() {
        val prevPath = libraryReferenceFile?.absolutePath
        val idx = if (prevPath != null) {
            libraryItems.indexOfFirst { it.filePath == prevPath }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        if (libraryItems.isNotEmpty()) {
            spinnerReferenceLibrary.setSelection(idx)
            selectLibraryItem(idx)
        } else {
            libraryReferenceFile = null
            updateReferenceStatusText()
            updateSynthesizeButton()
        }
    }

    /**
     * 选中库里第 [pos] 条参考音频（pos 超出清单时视为取消选择，如空库占位项）。
     * 文件丢失不静默跳过：提示重新添加，否则用户会以为选好了、合成却用旧音色。
     */
    private fun selectLibraryItem(pos: Int) {
        val item = libraryItems.getOrNull(pos)
        if (item == null) {
            libraryReferenceFile = null
            updateReferenceStatusText()
            updateSynthesizeButton()
            return
        }
        val file = File(item.filePath)
        if (!file.exists()) {
            libraryReferenceFile = null
            updateReferenceStatusText()
            Toast.makeText(
                requireContext(),
                "音频文件已丢失，请到「参考音频管理」重新添加",
                Toast.LENGTH_LONG
            ).show()
            updateSynthesizeButton()
            return
        }
        libraryReferenceFile = file
        updateReferenceStatusText()
        updateSynthesizeButton()
    }

    /**
     * 库分支的状态行：**只在「没有可用音色」时**给一行引导（空库 / 未选中）。
     *
     * 已选中时整行隐藏：原先这里显示「参考音频库：xxx」，与上方下拉里已选中的那一项
     * 完全是同一句话，纯冗余 —— 下拉已经把选择结果摆在脸面上了，再抄一遍只会让人
     * 误以为下面还另有信息。空库/未选中的引导仍要保留：这两种状态下下拉里分别是
     * 占位项和「未选中」外观，单靠它说不清下一步该去哪做。
     */
    private fun updateReferenceStatusText() {
        val hint = when {
            libraryItems.isEmpty() -> "参考音频库为空，可在「我的 - 参考音频管理」中添加"
            libraryReferenceFile == null -> "请从上方列表选择参考音频"
            else -> null
        }
        if (hint == null) {
            tvSelectedFile.setText("")
            tvSelectedFile.visibility = View.GONE
        } else {
            tvSelectedFile.text = hint
            tvSelectedFile.visibility = View.VISIBLE
        }
    }

    /** 按当前来源切换两个分支的可见性 */
    private fun applyReferenceSourceVisibility() {
        val library = referenceSourceMode == SOURCE_LIBRARY
        layoutSourceLibrary.visibility = if (library) View.VISIBLE else View.GONE
        layoutSourcePreset.visibility = if (library) View.GONE else View.VISIBLE
    }

    /** 来源下拉下方的说明行：两个分支各一句话 */
    private fun updateSourceHint() {
        tvSourceHint.text = when {
            // 本机 + 预设音色：这一分支里列的是**模块内置音色**（不是云端音色）。
            // 原先这里写的是「预设音色是云端音色、只对在线有效」，那是本机音色
            // 单独摆在上方时的旧结构 —— 现在音色统一由这个分支选，提示必须跟着改，
            // 否则用户会以为「本机合成用不了这个下拉」。
            referenceSourceMode == SOURCE_PRESET && synthesisModeLocal ->
                "本机合成：选模块内置音色（无需参考音频、无需登录、不消耗积分）；" +
                    "想用自己的声音，请改用「我的参考音频库」。"
            referenceSourceMode == SOURCE_PRESET ->
                "预设音色：无需参考音频，直接以所选音色合成整本。"
            else ->
                "我的参考音频库：用库里已保存的音色（克隆）合成，可在「我的 - 参考音频管理」中增删。"
        }
    }

    // ---------- 本机音色（模块内置音色：装在「预设音色」分支的那个下拉里） ----------

    /**
     * 读取模块内置音色清单。
     *
     * 清单来自模块包（[MossModuleManager.builtinVoices] 读 `browser_poc_manifest.json`
     * 的 `builtin_voices`），**读一次几百 KB 的 JSON 即可**，不必构造 [MossLocalEngine]
     * —— 那会建 ORT 会话、加载几百 MB 模型，是「真正开始合成」才该付的代价。
     * 所以这里显式放到 IO 线程，读完回主线程。
     *
     * 失败/未装模块：清单为空 → 「预设音色」分支的下拉只留一个占位项并置灰，
     * 让用户看到「有这个位置、但要先装模块」，而不是一个空框。
     */
    private fun loadLocalVoices() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) { MossModuleManager.builtinVoices(ctx) }
            if (!isViewCreated) return@launch
            localVoicesLoaded = true
            localVoices = voices
            // 选中的音色名要落在清单里：不在（模块换了/清单变了）就回落到首项。
            if (voices.isNotEmpty() && voices.indexOf(localVoiceName) < 0) {
                localVoiceName = voices[0]
            }
            // 清单是异步读到的：本机模式时下拉里要换成它（在线模式不受影响）。
            if (synthesisModeLocal) applyPresetSpinnerForMode()
            updateVoiceHint()
        }
    }

    /**
     * 把「预设音色」分支的那个下拉切换到**当前合成方式**该显示的内容。
     *
     * 这一个下拉有两种角色（见 [computePresetVoiceOptions]）：
     *  · 在线模式：服务端预设音色（`name (voiceId)`），选中写 [selectedDefaultVoiceId]；
     *  · 本机模式：模块内置音色（音色名），选中写 [localVoiceName]。
     *
     * 两套内容**各记各的选中态**：切模式时按各自记忆恢复位置，
     * 不会把「Junhao」这种音色名当成 voiceId 提交给服务端。
     *
     * 每次都重建适配器而不是复用：两套内容的项数与类型都不同，
     * 复用同一个 ArrayAdapter 必然要 replaceAll —— 那还不如直接换一个新的，
     * 旧的那个随 GC 走（onDestroyView 里已显式置空，不泄漏 Activity Context）。
     */
    private fun applyPresetSpinnerForMode() {
        if (!isViewCreated) return
        // 恢复持久化的默认音色：仅「在线模式 + 内存未选中 + 该 id 已在当前清单里」时补回。
        // 音色清单分两段到位（内置→服务端），放在这里可在 id 真正可用时补回，
        // 选中位置经 :580 的 selectedServerVoiceId 落到 setSelection。
        // 用户主动清空 / 重置点会同步清 AppPrefs，故不会被反复改回去；
        // 程序化回调到占位项(pos=0)时 onItemSelected 只在「非空」时落盘，不会抹掉待恢复值。
        if (!synthesisModeLocal && selectedDefaultVoiceId == null) {
            val saved = AppPrefs.getDefaultVoiceId(requireContext())
            if (saved.isNotEmpty() && saved in voiceIdList) {
                selectedDefaultVoiceId = saved
            }
        }
        val opts = computePresetVoiceOptions(
            isLocalMode = synthesisModeLocal,
            localVoices = localVoices,
            localVoicesLoaded = localVoicesLoaded,
            selectedLocalVoice = localVoiceName,
            serverDisplayList = voiceDisplayList,
            serverVoiceIds = voiceIdList,
            selectedServerVoiceId = selectedDefaultVoiceId
        )
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            opts.items
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        if (opts.source == PresetVoiceSource.LOCAL) {
            localVoiceAdapter = adapter
        } else {
            voiceAdapter = adapter
        }
        spinnerDefaultVoice.adapter = adapter
        spinnerDefaultVoice.isEnabled = opts.enabled
        spinnerDefaultVoice.setSelection(opts.selectedIndex)
        // 清单异步加载完可能落在**同一个下标**上，此时 setSelection 不触发
        // onItemSelected，试听按钮的可用态就会停在旧值（表现出来就是
        // 「音色列表已经出来了，试听按钮还是灰的」）。这里显式补一次刷新。
        if (_btnPreviewVoice != null) updatePreviewVoiceButton()
    }

    /**
     * 「预设音色」分支的说明行：两种合成方式各说各的。
     *
     * 本机模式说的是**模块内置音色**（不是云端音色）：它来自模块自带的参考音频
     * （预编码为音频码），无需参考音频、无需登录、不消耗积分。
     */
    private fun updateVoiceHint() {
        if (synthesisModeLocal) {
            tvVoiceHint.text = when {
                !localVoicesLoaded -> "正在读取模块内置音色…"
                localVoices.isEmpty() ->
                    "内置音色随 MOSS-TTS-Nano 模块一同提供，请先到「我的 - 本机语音合成」下载安装。"
                else ->
                    "内置音色来自模块自带参考音频（预编码为音频码），无需参考音频、无需登录、不消耗积分。"
            }
            return
        }
        tvVoiceHint.text = when {
            // 加载窗口期（内置兜底已装上、服务端清单还在路上）：别说「无可选值」
            !voicesLoaded ->
                "正在加载预设音色…"
            voiceIdList.none { it.isNotEmpty() } ->
                "预设音色暂无可选值（加载失败或服务端未配置），可重进本页或使用「我的参考音频库」。"
            else ->
                "选中预设音色后，整本将直接以该音色合成，可免参考音频；" +
                    "选好可点下方「试听该音色」先听一段样音；逐段音色可在听书页调整。"
        }
    }

    // ---------- 任务完成通知权限（Android 13+） ----------

    private val requestNotifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 拒绝也不阻断合成，只是任务完成时收不到系统通知
        }

    // ---- 录音相关状态已整体移出 ----
    // 原先这里有 requestRecordPermission / mediaRecorder / mediaPlayer / isRecording /
    // recordedFile / recordTimer / recordTickRunnable / recordSeconds。
    // 录制参考音频现由「我的 - 参考音频管理」页负责（ReferenceAudioRecorder），
    // 录完会作为一条记录进「我的参考音频库」，本页通过「从我的参考音频库选择」使用它。

    /** 试听克隆音色状态：是否正在合成中 / 正在播放中 */
    private var voicePreviewLoading = false
    private var voicePreviewPlaying = false
    /** 试听播放标记，与参考音频库的条目 id 区分开 */
    private val VOICE_PREVIEW_TOKEN = "tts_fragment_voice_preview"

    /**
     * 从「我的参考音频库」选中的音频文件（App 私有目录下的持久副本）。
     * 这是库里的正式文件，绝不能删，否则用户的音色素材会丢失。
     */
    private var libraryReferenceFile: File? = null

    /** 参考音频库仓库（懒加载，用于「从我的参考音频库选择」） */
    private val referenceRepository: ReferenceAudioRepository
        get() = ReferenceAudioRepository(requireContext().applicationContext)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _isViewCreated = true

        viewModel = ViewModelProvider(requireActivity())[BookViewModel::class.java]

        _btnSelectBook = view.findViewById(R.id.btnSelectBook)
        _tvSelectedBook = view.findViewById(R.id.tvSelectedBook)
        _tvSelectedFile = view.findViewById(R.id.tvSelectedFile)
        _btnUploadAndSynthesize = view.findViewById(R.id.btnSynthesize)
        _progressBar = view.findViewById(R.id.progressBar)
        _tvProgress = view.findViewById(R.id.tvProgress)
        _btnPlayResult = view.findViewById(R.id.btnPlayResult)
        _tvCreditHint = view.findViewById(R.id.tvCreditHint)
        _tvCostEstimate = view.findViewById(R.id.tvCostEstimate)

        // 「下载音频」按钮的监听器只设置一次（AND-17）：点击时下载最近一次完成的任务
        btnPlayResult.setOnClickListener {
            val id = pendingDownloadTaskId
            if (id != null) {
                fetchAndDownloadAllSegments(id)
            } else {
                Toast.makeText(requireContext(), "请先完成语音合成", Toast.LENGTH_SHORT).show()
            }
        }

        _btnPreviewVoice = view.findViewById(R.id.btnPreviewVoice)
        _spinnerDefaultVoice = view.findViewById(R.id.spinnerDefaultVoice)
        _tvVoiceHint = view.findViewById(R.id.tvVoiceHint)
        _spinnerReferenceSource = view.findViewById(R.id.spinnerReferenceSource)
        _tvSourceHint = view.findViewById(R.id.tvSourceHint)
        _layoutSourceLibrary = view.findViewById(R.id.layoutSourceLibrary)
        _spinnerReferenceLibrary = view.findViewById(R.id.spinnerReferenceLibrary)
        _layoutSourcePreset = view.findViewById(R.id.layoutSourcePreset)

        // 「预设音色」分支的下拉：**两种合成方式共用这一个控件**。
        // 先放一个占位项：loadVoices 对**游客**会整体跳过（服务端清单需登录），
        // 没有占位项的话预设分支的下拉会是空的 —— 用户选完「预设音色」来源
        // 面对一个空框，不知道是该等还是坏了。
        if (voiceDisplayList.isEmpty()) {
            voiceDisplayList.add(SERVER_VOICE_PLACEHOLDER)
            voiceIdList.add("")
        }
        voiceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, voiceDisplayList)
        voiceAdapter!!.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDefaultVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                // 同一个下拉在两种模式里装的是**不同的东西**，这里按当前模式分派。
                if (synthesisModeLocal) {
                    // 本机模式：列表是模块内置音色（音色名，不是 voiceId）。
                    val picked = localVoices.getOrNull(pos) ?: return
                    if (picked != localVoiceName) localVoiceName = picked
                    // 不调 updateSynthesizeButton：内置音色**不是**开始合成的门槛
                    //（本机模式下按钮只看模块就绪与否，见 updateSynthesizeButton 的注释），
                    // 换音色不改变任何控件的可用态。
                    updateVoiceHint()
                } else {
                    // 在线模式：首项是占位「（请选择预设音色）」，对应空 id
                    selectedDefaultVoiceId =
                        if (pos in voiceIdList.indices) voiceIdList[pos].ifEmpty { null } else null
                    // 跨端单值偏好落盘：只在「选中了真实音色」时写 AppPrefs。
                    // 初始化 / 清单加载会程序化回调到占位项(pos=0 → null)，若在那里写会把
                    // 「清单还没回来、尚未恢复」的持久化值误清空；清空统一交给各重置点。
                    selectedDefaultVoiceId?.let { AppPrefs.setDefaultVoiceId(requireContext(), it) }
                    updateSynthesizeButton()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // 按当前合成方式装内容（在线=服务端音色，本机=模块内置音色）并恢复选中位置。
        applyPresetSpinnerForMode()
        // 提示行常显（不再等音色清单加载完才显示）：文案由 updateVoiceHint 按
        // 「有没有可选音色 / 登没登录」自适应，空清单时给的是解释而不是空白。
        updateVoiceHint()

        // 「合成方式」下拉：在线（默认）/ 本机。
        // 下拉项自带「合成方式-」前缀：标题已与下拉同行，项内再点明所属字段，
        // 下拉面板脱离标题单看时（如长按展开的系统面板）也不会失去上下文。
        // 先按当前模式恢复位置，再挂监听 —— 恢复动作触发的 onItemSelected
        // 会被监听里的「与当前模式相同就直接返回」挡掉，不会误弹下载提示。
        _spinnerSynthesisMode = view.findViewById(R.id.spinnerSynthesisMode)
        _tvModeHint = view.findViewById(R.id.tvModeHint)
        spinnerSynthesisMode.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("合成方式-在线", "合成方式-本机")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSynthesisMode.setSelection(if (synthesisModeLocal) MODE_LOCAL else MODE_ONLINE)
        spinnerSynthesisMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val local = pos == MODE_LOCAL
                // 无变化（初始化恢复 / 重复选中同一项）不反应：
                // 否则每次视图重建都会因为 setSelection 再走一遍切换逻辑。
                if (local == synthesisModeLocal) return
                synthesisModeLocal = local
                updateModeHint()
                // 积分提示行必须跟着换口径：本机模式不能再写「每次合成消耗 N 积分」
                // （P1-3：那一行与上面的「不消耗积分」直接冲突）。
                renderCreditHint()
                // 切模式 = 换掉「预设音色」下拉里装的内容（在线=服务端音色，
                // 本机=模块内置音色），并按**各自记忆**恢复选中位置：
                // 两套内容各记各的，切来切去不会把「Junhao」当成 voiceId。
                applyPresetSpinnerForMode()
                // 两处说明行都随模式变：来源说明（这个来源在当前模式下是什么）
                // 与音色说明（列表里装的是什么）。
                updateSourceHint()
                updateVoiceHint()
                updateSynthesizeButton()
                // 选「本机」但模块没就绪：提示去下载。模块装好后同一位置再选不会弹
                // （local 已等于 synthesisModeLocal，直接返回）。
                if (local && MossModuleManager.isInstalled(requireContext()).not()) {
                    promptDownloadMossModule()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        updateModeHint()
        loadLocalVoices()

        // 「选择参考音频来源」下拉：我的参考音频库（默认）/ 预设音色。
        // 与合成方式下拉同一套范式：项内带「参考音频来源-」前缀（标题已与下拉同行，
        // 项内再点明所属字段），先按当前来源恢复位置，再挂监听，
        // 恢复触发的 onItemSelected 被「无变化就返回」挡掉。
        // 选项是**固定的两个分支**（SOURCE_OPTIONS），不再按登录状态变化：
        // 音色清单已公开（服务端 SecurityConfig + 客户端内置兜底），
        // 游客也看得见「有什么音色可选」；用不了的那道门在 startOnlineSynthesis。
        spinnerReferenceSource.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            SOURCE_OPTIONS
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerReferenceSource.setSelection(referenceSourceMode)
        spinnerReferenceSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val preset = pos == SOURCE_PRESET
                if (preset == (referenceSourceMode == SOURCE_PRESET)) return
                referenceSourceMode = if (preset) SOURCE_PRESET else SOURCE_LIBRARY
                applyReferenceSourceVisibility()
                updateSourceHint()
                // 互斥清理：离开某分支就清掉它的选中。否则合成逻辑会拿到与来源下拉
                // 不一致的音色 —— 参考音频的优先级高于内置音色，
                // 「来源=预设音色」却还在用旧的参考音频克隆，是能复现的错误音色。
                //
                // 两个分支在**任何模式下**都是二选一，所以本机模式下
                // 「用参考音频克隆」与「用内置音色」天然不会同时生效。
                if (preset) {
                    libraryReferenceFile = null
                    if (synthesisModeLocal) {
                        // 本机模式：这一分支列的是模块内置音色，**记住上次选的那个**
                        // （清了就变成「每次切来源都要重选音色」）。
                        applyPresetSpinnerForMode()
                    } else {
                        // 在线模式：预设下拉拨回占位项：视觉与 selectedDefaultVoiceId 对齐
                        selectedDefaultVoiceId = null
                        AppPrefs.setDefaultVoiceId(requireContext(), "") // 重置点同步清跨端偏好
                        spinnerDefaultVoice.setSelection(0)
                    }
                } else {
                    selectedDefaultVoiceId = null
                    AppPrefs.setDefaultVoiceId(requireContext(), "") // 重置点同步清跨端偏好
                    if (libraryItems.isNotEmpty()) {
                        spinnerReferenceLibrary.setSelection(0)
                        // ⚠ 位置可能已经是 0（进页面时自动预选的就是第一项）——
                        // setSelection 对同位置不触发 onItemSelected，必须显式补一次，
                        // 否则会出现「列表显示选中了、libraryReferenceFile 却是 null」的分叉。
                        selectLibraryItem(0)
                    } else {
                        libraryReferenceFile = null
                        updateReferenceStatusText()
                    }
                }
                stopVoicePreview()
                updateSynthesizeButton()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        applyReferenceSourceVisibility()
        updateSourceHint()

        // 库内具体参考音频列表（来源=我的参考音频库时显示）。
        // 清单异步加载（Room 查询走 IO），加载完在 loadReferenceLibrary 里恢复选中。
        libraryAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, libraryDisplayList)
        libraryAdapter!!.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerReferenceLibrary.adapter = libraryAdapter
        spinnerReferenceLibrary.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, v: View?, pos: Int, id: Long) {
                selectLibraryItem(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSelectBook.setOnClickListener {
            openBookSelector()
        }

        // 试听克隆音色：用固定样例句合成当前选择的参考音频并播放
        btnPreviewVoice.setOnClickListener {
            if (voicePreviewLoading || voicePreviewPlaying) {
                stopVoicePreview()
            } else {
                previewMyVoice()
            }
        }

        btnUploadAndSynthesize.setOnClickListener {
            if (selectedBooks.isNotEmpty()) {
                // 合成方式已在页面最上方的下拉里选好（默认在线），这里直接按所选模式走。
                // 不再像原先那样弹「本机 / 在线」对话框：模式是合成前的全局设定，
                // 每次合成都重复问一遍只会让用户烦躁；两种模式的代价已写在下拉下方的
                // 提示行里（updateModeHint）。
                if (synthesisModeLocal) {
                    // 选完「本机」之后模块仍可能被卸载、或当初的下载没完成：
                    // 发起前再确认一次，缺模块就引导去下载，而不是静默掉线。
                    if (!MossModuleManager.isInstalled(requireContext())) {
                        promptDownloadMossModule()
                        return@setOnClickListener
                    }
                    startLocalSynthesis()
                } else {
                    startOnlineSynthesis()
                }
            }
        }

        _btnCancelSynthesis = view.findViewById(R.id.btnCancelSynthesis)
        btnCancelSynthesis.setOnClickListener {
            // 中止指令发给服务，**不在页面里 cancel 协程**。
            //
            // 推理正卡在 ONNX 的一次 session.run 里，无论谁 cancel 都中断不了那次
            // native 调用；而服务会把它记成一个中止标志，由推理循环在**段边界**
            // 自行退出 —— 那时才是真的停了。
            // 代价是点了之后要等当前段跑完（一段最多约 30 秒音频 → 可能等几分钟），
            // 所以按钮文案如实写「等当前段合成结束后停止」。
            LocalSynthesisService.cancel(requireContext().applicationContext)
            btnCancelSynthesis.isEnabled = false
            btnPauseSynthesis.isEnabled = false
            // 文案写在**按钮上**而不是只写 tvProgress：进度文案会被服务的下一条
            // progress 覆盖掉（几秒一次），用户回头只看按钮 —— 按钮变灰又不带
            // 说明，看起来就像卡死/无响应。
            // 「等当前段结束」这句必须一直挂着，直到会话真的结束。
            btnCancelSynthesis.text = "中止中（等当前段结束）"
            showProgress("正在中止：等当前段合成结束后停止…")
            // 这里**刻意不弹 Toast**：上面的 showProgress 已经把同一句话写在
            // 进度区，而系统 Toast 是排队的 —— 这条 2 秒的提示会把随后到来的
            // 终态提示（「已中止：已完成 N/M 段」）顶到 3.5 秒之后才显示，
            // 那时用户多半已经移开视线。真机实测过一次：终态 Toast 确实弹了，
            // 但被这条挡在后面，观感跟"没提示"一样。
        }

        _btnPauseSynthesis = view.findViewById(R.id.btnPauseSynthesis)
        btnPauseSynthesis.setOnClickListener {
            val ctx = requireContext().applicationContext
            // 与中止同理：暂停也只能在**段边界**生效，所以按钮文案说的是
            // 「暂停合成」而不是「立即暂停」。点击后立刻禁用，等服务的状态流
            // 回来再按真实状态重新设置，避免连点发出两个相反的指令。
            if (LocalSynthesisService.paused.value) {
                LocalSynthesisService.resume(ctx)
                showProgress("正在恢复…")
            } else {
                LocalSynthesisService.pause(ctx)
                showProgress("正在暂停：等当前段合成结束后暂停…")
            }
            btnPauseSynthesis.isEnabled = false
            Toast.makeText(
                requireContext(),
                if (LocalSynthesisService.paused.value) "已恢复合成" else "将在当前段合成结束后暂停",
                Toast.LENGTH_SHORT
            ).show()
        }

        updateSelectedBooksDisplay()
        loadCreditBalance()
        // 订阅本机合成进度：**重新进入本页会立刻接上正在跑的任务**（服务是唯一事实来源），
        // 这正是"后台进行"该有的样子 —— 用户回来看到的是进度，而不是一个空页面。
        observeLocalSynthesisProgress()
        // 加载参考音频库清单填充来源下拉下方的列表；「发现 - 参考音频」一键套用
        // 传来的音色意图也在这里消费（PendingTtsReference），省得用户回列表找。
        loadReferenceLibrary()

        // 拉取可选预设音色清单，填充「默认音色」下拉
        loadVoices()
    }

    /**
     * 拉取服务端预设音色清单（id + 中文名），填充「预设音色」下拉。
     *
     * ## 两段式加载
     *
     * ① 先用**内置**清单兜底（同步，主线程）：服务端与客户端不可能同一时刻升级，
     *    老服务端对游客返回 401 时，下拉不能是一个只有占位项的空框。
     * ② 再拉服务端清单**覆盖**：以服务端为权威（它可能配了自定义音色）。
     *
     * 失败不影响主流程：拉不到就沿用内置清单（参考音频路径仍可合成）。
     */
    private fun loadVoices() {
        applyServerVoices(BUILTIN_SERVER_VOICES)
        // 内置清单已就位：提示行不必停在「正在加载预设音色…」
        voicesLoaded = true
        updateVoiceHint()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val voices = try {
                val resp = RetrofitClient.apiService.getVoices()
                if (resp.isSuccessful) {
                    // JSON 反序列化后只有 Any?，逐级做安全转换（避免 unchecked cast 告警）
                    val list = (resp.body()?.get("voices") as? List<*>)?.mapNotNull { it as? Map<*, *> }
                    list?.mapNotNull { m ->
                        val id = m["id"] as? String ?: return@mapNotNull null
                        ServerVoice((m["name"] as? String) ?: id, id)
                    }.orEmpty()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "拉取预设音色清单失败，沿用内置清单: ${e.message}")
                emptyList()
            }
            if (voices.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                serverVoicesLoaded = true
                if (isViewCreated) applyServerVoices(voices)
            }
        }
    }

    /**
     * 用一份音色清单填充「预设音色」下拉，并重建适配器。
     *
     * 重建而不是 notifyDataSetChanged：notify 依赖「adapter 持有的 List 与
     * voiceDisplayList 是同一个对象」这个隐含前提 —— 中途只要 adapter 被重建过
     * （切模式、切来源），通知的就是一个已经不挂在 spinner 上的 adapter，
     * 表现为「服务端明明返回了音色，下拉却还是空的」。
     * 重建时按 selectedDefaultVoiceId 恢复选中位置，不会丢选择。
     */
    private fun applyServerVoices(voices: List<ServerVoice>) {
        voiceDisplayList.clear()
        voiceIdList.clear()
        // 占位项：来源下拉选「预设音色」但还没选具体音色
        voiceDisplayList.add(SERVER_VOICE_PLACEHOLDER)
        voiceIdList.add("")
        for (v in voices) {
            voiceDisplayList.add(voiceLabel(v.name, v.id))
            voiceIdList.add(v.id)
        }
        if (isViewCreated) applyPresetSpinnerForMode()
    }

    /**
     * 预设音色的**显示名**：只留人话，不拼技术 id。
     *
     * 原来这里写的是 `"${name} (${id})"`，屏幕上就是
     * 「新闻女声（标准播音） (demo-1)」「Alba (alba)」—— `demo-1` 是服务端
     * 造数据时留下的占位 id，`(alba)` 更是把 id 与名字重复了一遍。
     * 用户既看不懂也不需要看懂 id：它只在提交合成时用，而那是从 [voiceIdList]
     * 按下标取的，与显示文本无关（全文件没有从显示名反解 id 的地方）。
     *
     * 唯一例外：服务端没给名字（name 为空）时只能退回 id，
     * 否则下拉里会出现一个空条目，看着像 bug。
     */
    private fun voiceLabel(name: String?, id: String): String {
        val n = name?.trim().orEmpty()
        return n.ifEmpty { id }
    }

    /** Android 13+ 需要运行时授权才能发任务完成通知；无权限时静默降级（不阻断合成） */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openBookSelector() {
        // 打开已导入电子书列表，支持多选
        val intent = Intent(requireContext(), BookListActivity::class.java).apply {
            putExtra("SELECT_MODE", true)
        }
        selectBookLauncher.launch(intent)
    }

    /** 在线合成：原有的上传 + 服务端排队路径（云端消耗积分，需登录） */
    private fun startOnlineSynthesis() {
        // 云端合成扣积分、依赖服务端账号：游客先弹「需要登录」，登录后自动接回本次合成。
        // 本机离线合成不走这里，游客仍可在装有端侧模型时免登录使用。
        val host = activity as? MainActivity
        if (host != null) {
            host.requireLogin("在线（云端）合成需要登录后使用（消耗积分）") { doStartOnlineSynthesis() }
        } else {
            doStartOnlineSynthesis()
        }
    }

    private fun doStartOnlineSynthesis() {
        if (!ensureEnoughCredits()) return
        requestNotificationPermissionIfNeeded()
        uploadAndSynthesizeBatch(selectedBooks)
    }

    /**
     * 刷新「合成方式」下拉下方的提示行：把当前模式最关键的代价讲清楚。
     *
     * 这行文字取代了原先「点开始合成才弹的选择对话框」—— 知情选择的时机从
     * "最后一刻"提前到"动手之前"，且不打断操作流程。
     */
    private fun updateModeHint() {
        tvModeHint.text = if (synthesisModeLocal) {
            if (MossModuleManager.isInstalled(requireContext())) {
                "本机合成：不消耗积分、离线运行；音色在下方「预设音色」（模块内置）里选，" +
                    "或用「我的参考音频库」克隆自己的声音；" +
                    "较慢（10 分钟音频约需 20~100 分钟），模型加载后占用较多内存"
            } else {
                // 体积取[MossModuleManager] 的兜底常量（对齐线上 manifest 的真值，
                // 见那里的说明）：这里没有清单可读，只能用静态文案。
                "本机合成需要先下载 MOSS-TTS-Nano 模块" +
                    "（v${MossModuleManager.FALLBACK_VERSION}，" +
                    "${MossModuleManager.approxDownloadMb()}，一次性下载安装）"
            }
        } else {
            if (AuthManager.token.isNullOrEmpty()) {
                "在线合成需登录后使用（消耗积分、速度快）；未登录可用「本机」合成（离线、不耗积分）"
            } else {
                "在线合成：消耗积分，速度快"
            }
        }
    }

    /**
     * 选了「本机」但模块没就绪时，提示用户去下载。
     *
     * 两个发起点：下拉切到「本机」的那一刻（onItemSelected），以及点
     * 「开始合成」前的兜底复查（模块可能在选择后被卸载、下载可能没完成）。
     *
     * 「取消」会把下拉拨回在线：模块没就绪时「本机」是个用不了的选项，
     * 留它在上面只会让用户再选一次、再被弹一次。真正想要本机合成的用户
     * 走「去下载」，装完回来重新选即可。
     */
    private fun promptDownloadMossModule() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("需要先下载本机合成模块")
            .setMessage(
                "本机合成依赖 MOSS-TTS-Nano 端侧模型" +
                    "（v${MossModuleManager.FALLBACK_VERSION}，" +
                    "${MossModuleManager.approxDownloadMb()}，一次性下载安装；" +
                    "安装后占用 ${MossModuleManager.approxInstalledMb()}）。\n\n" +
                    "安装后即可离线合成，不消耗积分；本机合成较慢" +
                    "（10 分钟音频约需 20~100 分钟），模型加载后会占用较多内存。\n\n" +
                    "现在去下载？"
            )
            .setPositiveButton("去下载") { _, _ ->
                startActivity(Intent(requireContext(), MossModuleActivity::class.java))
            }
            // 「取消」只关弹窗，**不把下拉弹回在线**：用户明确选了「本机」，
            // 静默改回去会让「选好书回来发现模式被翻了」的困惑出现。
            // 保留本机态没有死路 —— 顶部提示行说明模块要求，「开始合成」可点，
            // 点了照样出这个引导（见 updateSynthesizeButton 与点击回调）。
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 本机（离线）合成：**交给前台服务在后台跑**。
     *
     * 这里不再自己起协程做推理，原因有两个，都是实测出来的：
     *
     * 1. **生命周期**。原先绑在 viewLifecycleOwner.lifecycleScope 上，用户一离开页面
     *    协程就被取消 —— 而本机合成一段 10 分钟音频要跑 20~100 分钟，
     *    "点完必须盯着屏幕"是不可接受的。改由 [LocalSynthesisService] 承载，
     *    切页面、切后台、锁屏都继续跑，通知栏有进度与「中止」入口。
     * 2. **进程存活**。推理要占 1 GB 以上内存，正是 LMK 的首选目标；
     *    普通后台进程随时可能被回收。前台服务 + dataSync 类型 + PARTIAL_WAKE_LOCK
     *    才能保证它真的跑得完。
     *
     * 进度不再只显示在合成页：服务会把每段进度写进任务表，书架里这本书下方
     * 会直接出现「x / y」进度条（复用已有的任务渲染，没有新增 UI）。
     */
    private fun startLocalSynthesis() {
        val books = selectedBooks.toList()
        if (books.isEmpty()) return
        // 同发起前复查：判重必须用 isBusy（isBusy 覆盖「尚无进度」的阶段，
        // isRunning 在解析与加载模型期间恒为 false，会漏判）
        if (LocalSynthesisService.isBusy) {
            Toast.makeText(requireContext(), "本机合成正在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = context?.applicationContext ?: return
        // 参考音频要一并传给本机合成：过去这里写死 DEFAULT_VOICE，
        // 于是"选了郭德纲.wav 却合成出默认音色"—— 本机侧压根没拿到参考音频。
        // 音色优先级与在线一致：选了参考音频就用参考音频，否则用**用户选中的内置音色**。
        // localVoiceName 由「本机音色」下拉维护（读模块包的 builtin_voices），
        // 不再是写死的 DEFAULT_VOICE —— 书架上的任务名也会随之带上音色名。
        val refFile = libraryReferenceFile
        LocalSynthesisService.start(
            ctx,
            books,
            localVoiceName.ifEmpty { MossLocalEngine.DEFAULT_VOICE },
            referenceAudioPath = refFile?.absolutePath
        )
        // UI 由 observeLocalSynthesisProgress() 统一驱动：服务是唯一事实来源，
        // 这里只负责把按钮切到"进行中"的样子，避免"本地以为在跑、服务其实没起来"
        // 这类状态分叉。
        progressBar.visibility = View.VISIBLE
        btnCancelSynthesis.visibility = View.VISIBLE
        btnCancelSynthesis.isEnabled = true
        btnPauseSynthesis.visibility = View.VISIBLE
        btnPauseSynthesis.isEnabled = true
        btnPauseSynthesis.text = "暂停合成"
        btnUploadAndSynthesize.isEnabled = false
        showProgress("已提交本机合成，正在准备…")
        Toast.makeText(requireContext(), "已开始，可离开此页面（通知栏可暂停/中止）", Toast.LENGTH_LONG).show()
        // 必须刷任务：服务在**后台进程**里建 task 行（saveTask 在解析前就 INSERT），
        // 而本 Fragment 从头到尾没碰过 tasks 数据源。不刷的话 _tasks 保持旧值，
        // mergeShelf 拿不到这条任务 ⇒ 书架上整条任务行不渲染，
        // 用户看到的是「合成跑完了、书也能读了，但书架上没有任何记录」。
        // 实测（debug 包 + run-as 直查库）：tasks 表里 status=completed/4-of-4 好好躺着，
        // 书架上却只有书名 + 分类角标。
        viewModel.loadTasks()
    }

    /**
     * 订阅本机合成进度。
     *
     * 在 onViewCreated 里调用，因此**重新进入合成页也会自动接上**正在跑的任务 ——
     * 这正是"后台进行"该有的样子：用户回来能看到进度，而不是一个空页面。
     */
    private fun observeLocalSynthesisProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalSynthesisService.progress.collect { p ->
                    if (!isViewCreated) return@collect
                    if (p == null) {
                        // 没有在跑：把控件收回默认态（例如服务已结束、进程刚起来）
                        if (localSynthesisUiBusy) setLocalSynthesisUiRunning(false)
                        return@collect
                    }
                    if (p.running) {
                        setLocalSynthesisUiRunning(true)
                        val eta = if (p.etaMs >= 0) {
                            "，剩余约 ${p.etaMs / 60000} 分 ${p.etaMs % 60000 / 1000} 秒"
                        } else ""
                        showProgress("${p.bookName}：${p.done}/${p.total} 段$eta")
                    } else {
                        setLocalSynthesisUiRunning(false)
                        // 收掉进度条与上一轮的过程文案。
                        endSynthesizeUi()
                        // ⚠️ 结束反馈**不在这里弹**：改由 observeSynthesisOutcome()
                        // 统一消费 LocalSynthesisService.outcome。
                        //
                        // 之前这里直接 Toast(p.message)，看着没问题，实际漏掉了
                        // 最常见的那条路径 —— 从书架「继续合成」发起、用户不在合成页时
                        // 这个收集器根本没订阅，结束时零反馈（走查 N1/N2）。
                        // 而且「已开始，可离开此页面」那条 Toast 会先把这里排队的
                        // 失败提示顶到 3.5 秒之后，失败又发生得极快，用户早就移开视线了。
                        // 结束态（completed / partial / failed / cancelled）一律刷任务。
                        // 服务是在结束时才把最终 status 与 completed_segments 写库的，
                        // 不刷的话书架会一直停在「processing」甚至什么都没有 ——
                        // 中止后那句「继续合成剩余 N 段」也就无从谈起。
                        viewModel.loadTasks()
                    }
                }
            }
        }
        // 暂停状态单独订阅：它的变化与进度无关（暂停时进度不动），
        // 合并进上面那个流会让「暂停中」在进度没变时收不到通知。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalSynthesisService.paused.collect { paused ->
                    if (!isViewCreated) return@collect
                    btnPauseSynthesis.text = if (paused) "继续合成" else "暂停合成"
                    // 只有在合成进行中才让暂停按钮可点：
                    // 服务结束后留着可点的按钮会让用户对着一个没反应的控件点。
                    btnPauseSynthesis.isEnabled = LocalSynthesisService.isRunning
                    if (paused) showProgress("已暂停（当前段尚未跑完）")
                }
            }
        }
        observeSynthesisOutcome()
    }

    /**
     * 订阅本机合成的**终态结论**（失败/中止/部分完成/全部完成）。
     *
     * 与 [observeLocalSynthesisProgress] 分开订阅，是因为它是「一次性事件」：
     * 进度流表达的是「此刻在跑什么」，终态那条发完即过期；而这里要保证
     * 用户**一定看到**本次合成的结果 —— 包括他从书架发起、整个过程都没进过
     * 合成页的情况（那时进度流压根没有订阅者）。
     *
     * 认领用 [LocalSynthesisService.consumeOutcome] 的 CAS：书架页可能同时
     * 处于 STARTED，两个收集器都会收到同一条，只有一个能拿到。
     * **必须先确认视图可用再认领**，否则会出现「这个页面认领了但弹不出来，
     * 另一个页面又被告知已被领走」，两边都不显示。
     */
    private fun observeSynthesisOutcome() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LocalSynthesisService.outcome.collect { incoming ->
                    if (incoming == null) return@collect
                    if (!isViewCreated) return@collect
                    val outcome = LocalSynthesisService.consumeOutcome(incoming) ?: return@collect
                    val ctx = context ?: return@collect
                    showSynthesisOutcome(ctx, outcome)
                    // P2 #7：本机合成落库改变了覆盖图（source=local），静默推一次服务端，
                    // 让别的设备尽快亮起橙色「另一设备已合成·本机待合成」徽章，不必等下次进页。
                    // FAILED 通常一段都没落，跳过省一次网络。两个 Fragment 靠 consumeOutcome 的
                    // CAS 去重，恰好只有一个会走到这里 → 全局只推一次（与书架页的同一处对齐）。
                    if (outcome.level != LocalSynthesisService.Outcome.Level.FAILED) {
                        viewModel.syncAudioCoverageFromServer()
                    }
                }
            }
        }
    }

    /**
     * 切换本机合成的 UI 忙碌态。
     *
     * 集中在一处改，是因为"进度条可见 / 中止按钮可见 / 合成按钮可用"这三者
     * 必须一起变；分散在各分支里改，迟早出现"进度条还在转但按钮已经能再点一次"
     * 这种能开出第二个合成任务的漏洞（内存根本放不下第二份模型）。
     */
    private fun setLocalSynthesisUiRunning(running: Boolean) {
        localSynthesisUiBusy = running
        if (!isViewCreated) return
        progressBar.visibility = if (running) View.VISIBLE else View.GONE
        btnCancelSynthesis.visibility = if (running) View.VISIBLE else View.GONE
        btnCancelSynthesis.isEnabled = running
        // 必须复位文案：中止时改成了「中止中（等当前段结束）」，
        // 而 btnCancelSynthesis.text 全工程只在这一处被赋值。若不在这里还原，
        // 下一次合成开始后按钮上仍挂着「中止中」—— 用户看到的是一个已经结束的
        // 状态，却还挂在可以点的按钮上。
        btnCancelSynthesis.text = "中止合成"
        btnPauseSynthesis.visibility = if (running) View.VISIBLE else View.GONE
        btnPauseSynthesis.isEnabled = running
        btnPauseSynthesis.text = "暂停合成"
        // 合成按钮的可用性只由 updateSynthesizeButton 决定（它还要求有参考音频或音色）。
        // 原先这里多写了一句 `if (!running) btnUploadAndSynthesize.isEnabled = selectedBooks.isNotEmpty()`，
        // 把上面算出的结果覆盖掉了：没选参考音频、也没选音色时，
        // 本机合成一结束（或被中止）就会把「开始合成」点亮，
        // 用户一点就是一次注定失败的在线合成。判断已收敛到一处，删掉即可。
        updateSynthesizeButton()
    }

    private fun updateSelectedBooksDisplay() {
        if (selectedBooks.isEmpty()) {
            tvSelectedBook.text = "未选择书籍"
        } else if (selectedBooks.size == 1) {
            tvSelectedBook.text = "已选择：${selectedBooks[0].displayName}"
        } else {
            tvSelectedBook.text = "已选择 ${selectedBooks.size} 本书：\n" +
                    selectedBooks.joinToString("\n") { "• ${it.displayName}" }
        }
    }

    private fun updateSynthesizeButton() {
        val hasReference = libraryReferenceFile != null
        val hasVoice = !selectedDefaultVoiceId.isNullOrEmpty()
        // 可用态判据随合成方式（页面最上方下拉所选）切换：
        //   · 本机：只要模块就绪就能合成 —— MossLocalEngine.synthesize 在
        //     referencePromptCodes 为空时会回落到内置 prompt codes（DEFAULT_VOICE），
        //     零参考 / 零预设音色也可用，游客与纯离线用户都不被挡；
        //   · 在线：必须有参考音频或预设音色 —— 云端合成没有音色素材必被服务端打回，
        //     与其让用户传完几十 MB 再报错，不如这里就置灰。
        val modeReady = if (synthesisModeLocal) {
            // 本机模式：模块没装也**不置灰**。置灰会让用户除了页面顶部一行小字外
            // 没有任何推进路径 —— 死按钮比没按钮更糟。保持可点，点击时由
            // promptDownloadMossModule 给出明确引导（见 onPrimaryClicked 回调），
            // 模块就绪后才真正放行 startLocalSynthesis。
            true
        } else {
            hasReference || hasVoice
        }
        btnUploadAndSynthesize.isEnabled = selectedBooks.isNotEmpty() && modeReady
        if (selectedBooks.isNotEmpty()) {
            btnUploadAndSynthesize.text = if (selectedBooks.size == 1) "开始合成"
            else "批量合成 (${selectedBooks.size}本)"
        } else {
            btnUploadAndSynthesize.text = "开始合成"
        }
        // 试听按钮：有参考音频即可用（合成中/播放中由各自状态决定文案，见 updatePreviewVoiceButton）
        updatePreviewVoiceButton(hasReference)
    }

    /**
     * 更新试听按钮的文案与可用态。
     * 网络合成中 → "试听合成中…"（置灰）；播放中 → "停止"；否则按**试听对象**取文案：
     *  · 有参考音频 → "🎧 试听我的音色"（克隆结果）；
     *  · 只选了预设音色 → "🎧 试听该音色"（预设音色本身的音色）。
     *
     * 预设音色这条路径是 2026-11-02 UI 走查 P2-5 补的：在此之前按钮只认参考音频，
     * 选了预设音色的用户**没有任何办法听到它**，只能看名字盲选。
     *
     * @param hasReference 默认**按当前是否已选参考音频实时求值**，而不是写死 false：
     *   试听结束回调里调的是无参版本，若默认值写死 false，播完一次按钮就被置灰，
     *   用户想再听一遍得去动别的控件 —— 表现为「试听只能用一次」。
     */
    private fun updatePreviewVoiceButton(hasReference: Boolean = libraryReferenceFile != null) {
        if (voicePreviewLoading) {
            btnPreviewVoice.text = "试听合成中…"
            btnPreviewVoice.isEnabled = false
            return
        }
        if (voicePreviewPlaying) {
            btnPreviewVoice.text = "停止"
            btnPreviewVoice.isEnabled = true
            return
        }
        val hasVoice = !selectedDefaultVoiceId.isNullOrEmpty()
        // 文案按**来源分支**而不是 hasReference 判断：
        // 来源=我的参考音频库时哪怕库是空的，按钮说的也该是「试听我的音色」，
        // 写成「试听该音色」会让用户以为切到预设音色去了。
        btnPreviewVoice.text =
            if (referenceSourceMode == SOURCE_LIBRARY) "🎧 试听我的音色" else "🎧 试听该音色"
        // 本机模式没有「服务端音色」可试听（那走模块内置音色，试听要另起一次本地推理，
        // 代价是几十秒的模型加载），所以只按「在线 + 有音色」放行。
        btnPreviewVoice.isEnabled = hasReference || (!synthesisModeLocal && hasVoice)
    }

    /** 停止正在进行的克隆音色试听并复位按钮（视图已销毁时不碰 UI） */
    private fun stopVoicePreview() {
        VoicePreviewManager.stop()
        voicePreviewLoading = false
        voicePreviewPlaying = false
        if (isViewCreated) updatePreviewVoiceButton()
    }

    // 参考音频来源已收敛为一种（libraryReferenceFile = 库里的持久副本），
    // 原先的 resolveReferenceFileForPreview()（在「库文件 / URI 来源」之间挑选，
    // 并对 URI 先拷贝到缓存）已随文件选择器入口一起删除；使用处直接取 libraryReferenceFile。

    /** 试听我的音色：用固定样例句合成当前参考音频（克隆音色）并播放 */
    private fun previewMyVoice() {
        // 试听走的是云端 clone 接口，需账号：游客先弹「需要登录」，登录后自动接回试听。
        val host = activity as? MainActivity
        if (host != null && !AuthManager.isAuthenticated) {
            host.requireLogin("试听音色需要登录后使用") { previewMyVoice() }
            return
        }
        // 点击在主线程序发，先安全地拿到 ApplicationContext，避免协程内 requireContext() 在
        // 视图销毁后与 isViewCreated 检查之间存在竞态（return@withContext 不会退出 launch）
        val appCtx = try { requireContext().applicationContext } catch (_: Exception) { return }
        // 重入防护：点击即同步置 loading，避免快速双击在协程置 loading 前发起两次并行合成
        if (voicePreviewLoading || voicePreviewPlaying) return
        // 试听对象按「有参考音频 → 克隆；无参考音频但有预设音色 → 预设音色」分派。
        // 参考音频优先，因为它是用户显式挑的、更具体；预设音色只是"没参考音频时的替代"。
        val refFile = libraryReferenceFile
        val voiceId = selectedDefaultVoiceId
        val previewVoiceOnly = refFile == null && !voiceId.isNullOrEmpty()
        if (refFile == null && !previewVoiceOnly) {
            Toast.makeText(appCtx, "请先选择参考音频或预设音色", Toast.LENGTH_SHORT).show()
            return
        }
        voicePreviewLoading = true
        updatePreviewVoiceButton()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val wav = if (previewVoiceOnly) {
                VoicePreviewManager.fetchPreviewWavForVoice(appCtx, voiceId!!)
            } else {
                // 来源只剩库文件（已被复制进 App 私有目录），直接取用，无需再拷贝到缓存
                VoicePreviewManager.fetchPreviewWav(appCtx, refFile!!)
            }
            withContext(Dispatchers.Main) {
                if (!isViewCreated) return@withContext
                voicePreviewLoading = false
                if (wav == null) {
                    updatePreviewVoiceButton()
                    Toast.makeText(appCtx, "试听合成失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                voicePreviewPlaying = true
                updatePreviewVoiceButton()
                VoicePreviewManager.playPreview(wav, VOICE_PREVIEW_TOKEN) { playing ->
                    if (!isViewCreated) return@playPreview
                    voicePreviewPlaying = playing
                    updatePreviewVoiceButton()
                }
            }
        }
    }

    /**
     * 任务完成后露出「下载音频」按钮并记住任务 id。
     *
     * **必须显式设 VISIBLE**：布局里 `btnPlayResult` 是 `android:visibility="gone"`，
     * 只改 isEnabled / text 的话按钮根本不会显示。这里曾经漏掉这一行，
     * 结果是合成完成后界面提示「任务完成！点击下载音频」，用户却找不到按钮、
     * 拿不到花了积分合成出来的音频（同布局的 TtsActivity 版本是有这一行的）。
     */
    private fun revealDownloadButton(taskId: Long) {
        btnPlayResult.visibility = View.VISIBLE
        btnPlayResult.isEnabled = true
        btnPlayResult.text = "下载音频"
        pendingDownloadTaskId = taskId
    }

    /**
     * 展示过程态文案，并保证控件可见。
     *
     * 全工程所有「进度文案」都必须走这里，不要直接写 `tvProgress.text` ——
     * 因为 [endSynthesizeUi] 会把 visibility 置成 GONE，而布局里它初始也是
     * `gone`：直接赋值只改文字不改可见性，于是「结束收起」之后再来的那条进度
     * （典型的是 SSE 的第一条「正在合成: 1/31」）文案写进去了、用户看不见，
     * 界面表现为「合成开始了但什么都不显示」。
     */
    private fun showProgress(text: String) {
        if (!isViewCreated) return
        tvProgress.visibility = View.VISIBLE
        tvProgress.text = text
    }

    /**
     * 合成结束（或失败）后收掉进度条与状态文案。
     *
     * 「任务完成！点击下载音频」这类文案过去是**永久驻留**的：它只在
     * onComplete 里被赋一次值，之后没有任何地方把它清掉或收起来。下一次
     * 合成失败、或用户只点了「参考音频」什么都不干，这行字还挂着上一轮的结论，
     * 而它旁边那条进度条也还在转 —— 用户会以为还在跑。
     *
     * 结束信息不该只靠这一行文字承载：`revealDownloadButton` 露出的「下载音频」
     * 按钮、书架里的任务条目、以及 Toast，三处都已经表达了结果。所以这里
     * 只负责「不留过程残留」，不负责「告知结果」。
     *
     * ⚠️ 清 text 而不只是设 GONE：这两行文案一旦设 VISIBLE 就不会自己回到
     * gone（见 [beginSynthesizeUi]），下次合成开始时如果某条路径忘了重设，
     * 用户会先看到上一次的结论再被覆盖。置空能让这种遗漏一眼看出来。
     */
    private fun endSynthesizeUi() {
        if (!isViewCreated) return
        progressBar.visibility = View.GONE
        tvProgress.visibility = View.GONE
        tvProgress.text = ""
    }

    /** 拉取积分概况并在界面提示「当前积分 / 每次消耗」 */
    private fun loadCreditBalance() {
        // 游客不必查积分：/api/credits/my 需鉴权，无 token 时清空提示直接返回，
        // 免得进入合成页就发一次注定 401 的往返（在线合成本身已单独做登录门禁）。
        if (AuthManager.token.isNullOrEmpty()) {
            creditBalance = null
            renderCreditHint()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // token 已由 AuthInterceptor 统一注入，不再手传
                val resp = RetrofitClient.creditApi.getMyCredits()
                if (resp.isSuccessful) {
                    creditBalance = resp.body()
                    withContext(Dispatchers.Main) { renderCreditHint() }
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "加载积分概况失败: ${e.message}")
            }
        }
    }

    /**
     * 把 [creditBalance] 渲染成积分提示行 —— **按当前合成方式说不同的话**。
     *
     * 这一行原本只认积分规则，不看合成方式，于是本机模式下同屏出现两句互相打脸的话：
     * 上面 [tvModeHint] 写着「本机合成：不消耗积分、离线运行…」，
     * 下面这一行写着「每次合成消耗 10 积分」（2026-11-02 UI 走查 P1-3）。
     *
     * 本机模式并非「隐藏整行」：余额本身对用户仍然有用（他可能在两种方式间来回切），
     * 所以保留「当前积分：N」，只把**计费口径**换成「本机合成不消耗积分」。
     * 免费试用次数、积分不足这类**只对在线计费有意义**的信息一并收掉 ——
     * 留着会让用户以为本机合成也会被扣，或者以为积分不够就没法本机合成。
     */
    private fun renderCreditHint() {
        if (!isViewCreated) return
        val b = creditBalance
        if (synthesisModeLocal) {
            tvCreditHint.text = if (b != null && b.enabled) {
                "当前积分：${b.balance}　本机合成不消耗积分"
            } else {
                // 未开启积分或游客：本机模式没什么可说的，别留一行空壳红字
                ""
            }
            return
        }
        if (b == null || !b.enabled) {
            tvCreditHint.text = ""
            return
        }
        val per = b.creditsPerTask
        val sb = StringBuilder("当前积分：${b.balance}　每次合成消耗 $per 积分")
        // 展示当日剩余免费试用次数，引导新用户先尝鲜
        if (b.freeTrialsRemaining > 0) {
            sb.append("　今日可免费合成 ${b.freeTrialsRemaining} 次")
        }
        if (!b.enoughForOneTask) {
            sb.append("（积分不足，请先充值）")
        }
        tvCreditHint.text = sb.toString()
    }

    /**
     * 提交合成前校验积分：积分功能未开启则放行；开启但余额不足则弹窗引导充值。
     * 返回 false 表示应当中止本次提交。
     */
    private fun ensureEnoughCredits(): Boolean {
        val b = creditBalance
        // 未拿到规则（网络异常等）时，为不阻断老用户，按「放行」处理；
        // 服务端仍会做最终扣费校验，不会造成资损。
        if (b == null || b.enabled != true) return true
        // 批量场景按「本数 × 单价」预检：只校验「够一次」会让批量提交在中途
        // 因余额耗尽被服务端 402 打断（前面的书已扣费，后面的书白传流量）
        val count = maxOf(1, selectedBooks.size)
        val needed = b.creditsPerTask * count
        // 当日仍有免费试用额度时，本次合成可走试用通道，不在此处拦截（服务端会精确计费）
        if (b.balance < needed && b.freeTrialsRemaining <= 0) {
            val scopeText = if (count > 1) {
                "本次批量合成 $count 本共需 $needed 积分"
            } else {
                "每次合成需要 ${b.creditsPerTask} 积分"
            }
            AlertDialog.Builder(requireContext())
                .setTitle("积分不足")
                .setMessage("$scopeText，当前余额 ${b.balance}，不足以完成本次合成。是否前往充值？")
                .setPositiveButton("去充值") { _, _ ->
                    val intent = Intent(requireContext(), RechargeActivity::class.java)
                    rechargeLauncher.launch(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return false
        }
        return true
    }

    private fun uploadAndSynthesizeBatch(books: List<Book>) {
        // selectedDefaultVoiceId 由主线程的下拉框写入，而下面整批上传跑在 IO 线程，
        // 直接读这个非 @Volatile 字段既没有跨线程可见性保证，也会让「同一批书用上不同音色」。
        // 这里是点击「开始合成」的主线程入口，先把它拷成局部 val，一路传到底。
        val voiceId = selectedDefaultVoiceId
        // 同理把 ApplicationContext 提前取好传进协程，
        // 避免 IO 线程里的 requireContext() 在 Fragment 已 detach 时抛 IllegalStateException。
        val appCtx = context?.applicationContext ?: return

        // 参考音频与预设音色二选一即可。
        // 选了预设音色且没选参考音频时，走「免参考音频」的合成路径。
        val referenceSource: Any? = libraryReferenceFile
        val voiceOnly = !voiceId.isNullOrEmpty() && referenceSource == null
        if (referenceSource == null && !voiceOnly) {
            Toast.makeText(requireContext(), "请先选择参考音频或预设音色", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        // 这里仍需显式显示：布局里 tvProgress 是 gone，而它是上一轮 [endSynthesizeUi]
        // 收起来的（新一轮合成开始前它一直是 GONE）。之后所有过程文案都走
        // [showProgress]（它自己会置 VISIBLE），只有这一处是「还没产生文案、
        // 先把控件亮出来」的起手式。
        tvProgress.visibility = View.VISIBLE
        btnUploadAndSynthesize.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // 重置本批次状态与实耗统计。
            // 注意：上面是普通函数（非 suspend），withContext 只能在协程内调用，
            // 因此这段与预估文案必须一并放进 launch 块内。
            batchTotal = books.size
            activeTaskIds.clear()
            abortBatchForCredits = false
            settledTaskIds.clear()
            batchConsumedCredits = 0
            pendingCostFetches = 0

            // 合成前预估积分消耗，明确告诉用户本次要花多少
            val per = creditBalance?.creditsPerTask ?: 0
            val freeN = creditBalance?.freeTrialsRemaining ?: 0
            val estimate = buildString {
                append("本次合成 ${books.size} 本，预计消耗 ${per * books.size} 积分")
                if (freeN > 0) append("（今日还可免费 $freeN 次）")
            }
            withContext(Dispatchers.Main) {
                if (isViewCreated) {
                    tvCostEstimate.visibility = View.VISIBLE
                    tvCostEstimate.text = estimate
                }
            }

            var successCount = 0
            var failCount = 0

            // ========== Step 1: 仅当选择了参考音频时才上传一次，获取 referenceId ==========
            // 只选预设音色（voiceOnly）时无需参考音频，直接进入 Step 2
            var referenceId: String? = null
            if (referenceSource != null) {
                withContext(Dispatchers.Main) {
                    if (isViewCreated) {
                        showProgress("正在上传参考音频...")
                    }
                }

                referenceId = uploadReferenceAudioOnce(referenceSource)
                if (referenceId == null) {
                    withContext(Dispatchers.Main) {
                        if (isViewCreated) {
                            progressBar.visibility = View.GONE
                            btnUploadAndSynthesize.isEnabled = true
                            showProgress("参考音频上传失败")
                            Toast.makeText(requireContext(), "参考音频上传失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                selectedReferenceId = referenceId
                Log.d(TAG, "参考音频上传完成，referenceId: $referenceId，开始批量处理 ${books.size} 本书")
            } else {
                Log.d(TAG, "使用预设音色合成（无参考音频），voiceId=$voiceId，开始批量处理 ${books.size} 本书")
            }

            // ========== Step 2: 对每本书创建任务 ==========
            for ((index, book) in books.withIndex()) {
                withContext(Dispatchers.Main) {
                    if (isViewCreated) {
                        showProgress("正在提交第 ${index + 1}/${books.size} 本：${book.displayName}")
                    }
                }

                try {
                    val result = uploadSingleBook(book, referenceId, voiceId, appCtx)
                    if (result) {
                        successCount++
                    } else {
                        failCount++
                        // 402 = 余额不足：中断剩余书籍的提交，避免无谓的上传流量
                        if (abortBatchForCredits) {
                            Log.w(TAG, "积分不足，中断剩余 ${books.size - index - 1} 本书的提交")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理书籍失败: ${book.displayName}", e)
                    failCount++
                }

                if (index < books.size - 1) {
                    kotlinx.coroutines.delay(1000)
                }
            }

            // 注意：这里只代表「任务已全部提交到服务端」，不是合成完成。
            // 服务端按 app.tts.concurrency 并发执行，真正的完成进度由 SSE 回调刷新。
            batchTotal = successCount

            withContext(Dispatchers.Main) {
                if (isViewCreated) {
                    progressBar.visibility = View.GONE
                    btnUploadAndSynthesize.isEnabled = true

                    val message = "已提交 $successCount 个合成任务" +
                            (if (failCount > 0) "，$failCount 个提交失败" else "") +
                            (if (abortBatchForCredits) "；余额不足，已中止剩余书籍" else "") +
                            "，服务端正在后台排队合成"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    showProgress(message)

                    // 余额不足被打断：明确引导充值，而不是让用户自己猜「失败」原因
                    if (abortBatchForCredits) {
                        AlertDialog.Builder(requireContext())
                            .setTitle("积分不足")
                            .setMessage("当前积分余额不足以继续合成剩余书籍，充值后可重新提交。是否前往充值？")
                            .setPositiveButton("去充值") { _, _ ->
                                rechargeLauncher.launch(Intent(requireContext(), RechargeActivity::class.java))
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }

                    selectedBooks.clear()
                    updateSelectedBooksDisplay()

                    // 任务已提交到服务端，清理本次的音色选择，避免后续误用过期数据。
                    // 注意：libraryReferenceFile 只是解除引用，绝不 delete——那是库里的持久文件。
                    //
                    // 库分支**不能简单置空**：列表还显示着选中项，状态清成 null 会出现
                    // 「界面显示选中了、实际没选」的分叉（用户回本页直接点合成，用的却不是
                    // 列表里那条）。重置到第一项 —— 与每次进页面的默认选中同一条规则；
                    // 空库时列表只有占位项，selectLibraryItem 自然走到「无选中」分支。
                    selectedDefaultVoiceId = null
                    AppPrefs.setDefaultVoiceId(requireContext(), "") // 重置点同步清跨端偏好
                    if (_spinnerDefaultVoice != null) spinnerDefaultVoice.setSelection(0)
                    if (referenceSourceMode == SOURCE_LIBRARY && _spinnerReferenceLibrary != null) {
                        spinnerReferenceLibrary.setSelection(0)
                        selectLibraryItem(0)
                    } else {
                        libraryReferenceFile = null
                        updateReferenceStatusText()
                    }
                    updateSynthesizeButton()

                    // 有任务成功提交时，回到书架查看任务进度（任务挂在对应书籍下）
                    if (successCount > 0) {
                        val activity = activity
                        if (activity is MainActivity) {
                            activity.navigateToBooks()
                        }
                    }
                }
            }
        }
    }

    /**
     * 上传参考音频一次，返回服务端分配的 referenceId（即保存的文件名）。
     *
     * 现在传进来的必定是「我的参考音频库」里的持久副本（File），
     * 因此不存在临时文件：原先的 Uri 分支（先拷到缓存、上传后删临时文件）已随
     * 文件选择器入口一起删除。
     */
    private suspend fun uploadReferenceAudioOnce(source: Any): String? {
        return withContext(Dispatchers.IO) {
            try {
                val referenceFile = source as? File
                if (referenceFile == null) {
                    Log.w(TAG, "无法读取参考音频文件")
                    return@withContext null
                }

                if (AuthManager.token == null) {
                    Log.w(TAG, "用户未登录")
                    return@withContext null
                }

                val audioPart = MultipartBody.Part.createFormData(
                    "reference_audio",
                    referenceFile.name,
                    referenceFile.asRequestBody("audio/*".toMediaType())
                )

                Log.d(TAG, "📤 上传参考音频: ${referenceFile.name}, 大小: ${referenceFile.length()} bytes")
                val response = RetrofitClient.heavyApi.uploadReferenceAudio(
                    audioPart
                )

                // 清理临时缓存文件（录制文件保留）
                if (response.isSuccessful) {
                    val result = response.body()
                    val referenceId = result?.get("referenceId") as? String
                    if (referenceId != null) {
                        Log.d(TAG, "✅ 参考音频上传成功，referenceId: $referenceId")
                        return@withContext referenceId
                    } else {
                        Log.e(TAG, "⚠️ 服务端未返回 referenceId")
                        return@withContext null
                    }
                } else {
                    Log.e(TAG, "❌ 参考音频上传失败: ${response.code()} ${response.message()}")
                    return@withContext null
                }
            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ 参考音频上传异常: ${e.message}", e)
                return@withContext null
            }
        }
    }

    /**
     * 上传单本电子书 + 已上传的参考音频ID（不再重复上传参考音频文件）
     * [appCtx] 由调用方在主线程传入 ApplicationContext：本函数在 IO 线程执行，
     * 取文件名要查 ContentResolver，用不了 requireContext()。
     */
    private suspend fun uploadSingleBook(
        book: Book,
        referenceId: String?,
        voiceId: String?,
        appCtx: Context
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var tempTaskId: Long? = null
            try {
                val bookFile = prepareBookFile(book)
                if (bookFile == null) {
                    Log.w(TAG, "无法读取书籍文件: ${book.displayName}")
                    return@withContext false
                }

                if (AuthManager.token == null) {
                    Log.w(TAG, "用户未登录")
                    return@withContext false
                }

                val bookName = book.displayName
                val audioName = if (!voiceId.isNullOrEmpty()) {
                    "预设音色($voiceId)"
                } else {
                    libraryReferenceFile?.name ?: "参考音频"
                }

                tempTaskId = System.currentTimeMillis()

                // 落库是纯数据操作，不受页面可见性影响：若套 withContext(Main) +
                // if (isViewCreated)，用户提交后切走 Tab（视图销毁、isViewCreated=false）
                // 时整行都不会写入，但服务端已经建好任务并扣了积分 —— 用户回到列表页
                // 看不到任何记录，合成结果永远取不回。
                // 保留 suspend + await：后面的 updateServerTaskId 必须命中这条已插入的行，
                // 不能退回 fire-and-forget 赌时序。
                //
                // 用 upsert 而非 saveTask：同一本书 + 同一种合成方式重复提交时
                // 复用旧行，否则书架上会堆出多条同名任务、进度各不相同。
                // 后面 updateServerTaskId / 轮询都按 tempTaskId 定位，
                // 所以这里必须接住 upsert 返回的**实际** id（复用时是旧行的 task_id）。
                tempTaskId = viewModel.upsertLocalTask(bookName, audioName, tempTaskId)

                val bookPart = MultipartBody.Part.createFormData(
                    "book_file",
                    bookFile.name,
                    bookFile.asRequestBody("text/plain".toMediaType())
                )

                Log.d(TAG, "📤 上传书籍: ${book.displayName}, referenceId=$referenceId, voiceId=$voiceId")

                // 只选预设音色（无参考音频）走 voice 路径；否则走参考音频（克隆）路径。
                // 二者都存在时服务端以参考音频为准（克隆），这里仍走 refId 路径。
                val response = if (!voiceId.isNullOrEmpty() && referenceId == null) {
                    val voiceIdBody = "text/plain".toMediaType().let { mediaType ->
                        voiceId.toRequestBody(mediaType)
                    }
                    RetrofitClient.heavyApi.uploadBookForTtsWithVoice(bookPart, voiceIdBody)
                } else {
                    val referenceIdBody = "text/plain".toMediaType().let { mediaType ->
                        (referenceId ?: "").toRequestBody(mediaType)
                    }
                    RetrofitClient.heavyApi.uploadBookForTtsWithRefId(bookPart, referenceIdBody)
                }

                if (response.isSuccessful) {
                    val result = response.body()
                    val success = result?.get("success") as? Boolean

                    if (success == true) {
                        val serverTaskIdRaw = result["taskId"]
                        val serverTaskId = when (serverTaskIdRaw) {
                            is Number -> serverTaskIdRaw.toLong()
                            is String -> serverTaskIdRaw.toLongOrNull()
                            else -> null
                        }

                        if (serverTaskId != null) {
                            activeTaskIds.add(serverTaskId)
                            taskBookNames[serverTaskId] = book.displayName
                        }

                        if (serverTaskId != null) {
                            // 回填 server_task_id 同样与页面可见性无关。
                            // 漏写会让这一行永远匹配不上 SSE/轮询回来的服务端结果，
                            // 任务卡在 processing 直到进程重启。
                            viewModel.updateServerTaskId(tempTaskId, serverTaskId)
                            withContext(Dispatchers.Main) {
                                if (isViewCreated) {
                                    currentTaskId = serverTaskId
                                    startStreamingTaskStatus(serverTaskId, tempTaskId)
                                }
                            }
                        }

                        Log.d(TAG, "✅ 书籍上传成功: ${book.displayName}")
                        true
                    } else {
                        val message = result?.get("message") as? String ?: "未知错误"
                        Log.e(TAG, "上传失败: $message")
                        // 失败态也必须落库（原因同上），否则任务永远停在
                        // processing，用户既看不到失败原因也无法重试。
                        tempTaskId.let {
                            viewModel.updateTaskStatus(it, "failed", 0, 0)
                            viewModel.updateTaskErrorMessage(it, message)
                        }
                        false
                    }
                } else {
                    var errorMsg = try {
                        val errorBody = response.errorBody()?.string()
                        if (!errorBody.isNullOrEmpty()) {
                            // ⚠️ 必须解析出 message，不能把整坨 JSON 原文存进任务失败原因。
                            // 服务端所有错误都返回 {"success":false,"message":"..."}，
                            // 原文直接落库会在书架上显示成
                            // `{"success":false,"message":"服务器错误，请稍后重试"}` 这种
                            // 给开发者看的裸 JSON —— 用户看不懂「服务器错误」到底指什么，
                            // 更看不出真正的原因（曾经把「当前节点要求参考音频」这个
                            // 可操作提示压成了 500，客户端再原样显示就更没救了）。
                        val parsed = extractServerMessage(errorBody)
                        if (!parsed.isNullOrBlank()) {
                            parsed
                        } else {
                            // 服务端没给 message（老版本或非 JSON 响应体）：退回原始文本，
                            // 至少不丢信息。
                            errorBody
                        }
                        } else {
                            "HTTP错误: ${response.code()} ${response.message()}"
                        }
                    } catch (e: Exception) {
                        "HTTP错误: ${response.code()} ${response.message()}"
                    }
                    // 402 = 积分不足：置中断标记，让批量循环停止上传后续书籍。
                    // 文案已在上面统一解析过，这里只补兜底（服务端漏 message 时）。
                    if (response.code() == 402) {
                        abortBatchForCredits = true
                        if (errorMsg.isBlank() || errorMsg.startsWith("{")) {
                            errorMsg = "积分不足，请先充值"
                        }
                    }
                    Log.e(TAG, "上传失败: $errorMsg")
                    // 同上，失败态落库不受页面可见性影响
                    tempTaskId.let {
                        viewModel.updateTaskStatus(it, "failed", 0, 0)
                        viewModel.updateTaskErrorMessage(it, errorMsg)
                    }
                    false
                }

            } catch (e: CancellationException) {
                // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                throw e
            } catch (e: Exception) {
                val errorMsg = "上传异常: ${e.message}"
                Log.e(TAG, "上传异常: ${e.message}", e)
                // 同上，异常路径也要落库
                tempTaskId?.let {
                    viewModel.updateTaskStatus(it, "failed", 0, 0)
                    viewModel.updateTaskErrorMessage(it, errorMsg)
                }
                false
            }
        }
    }

    private fun prepareBookFile(book: Book): File? {
        return try {
            if (book.filePath.isNotEmpty()) {
                File(book.filePath)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取书籍文件失败: ${e.message}", e)
            null
        }
    }

    private fun startStreamingTaskStatus(taskId: Long, localTaskId: Long) {
        val baseUrl = RetrofitClient.BASE_URL.removeSuffix("/")
        if (AuthManager.token == null) {
            Log.w(TAG, "⚠️ Token为空，无法建立SSE连接，改用轮询")
            startPollingTaskStatus(taskId, localTaskId)
            return
        }

        // UI 更新统一走 viewLifecycleOwner 的作用域，
        // Fragment 视图销毁后作用域自动取消，避免 runOnUiThread 把回调 post 到消亡视图导致 NPE/崩溃
        val uiScope = viewLifecycleOwner.lifecycleScope

        val listener = TaskProgressListener(
            onProgressUpdate = { completed, total, status ->
                Log.d(TAG, "📡 SSE收到进度: taskId=$taskId, $completed/$total, status=$status")
                viewModel.updateTaskStatus(localTaskId, status, completed, total)

                if (isViewCreated) {
                    if (batchTotal > 1) {
                        // 多任务并行时，单本进度写在任务列表里，顶部只显示批次汇总，
                        // 避免多个任务互相覆盖同一个 TextView
                        updateBatchProgressText()
                    } else {
                        uiScope.launch(Dispatchers.Main) {
                            showProgress("正在合成: $completed/$total")
                        }
                    }
                }
            },
            onComplete = {
                Log.d(TAG, "✅ SSE通知任务完成: taskId=$taskId")
                onTaskFinished(taskId)
                collectTaskCost(taskId)
                uiScope.launch(Dispatchers.Main) {
                    if (batchTotal <= 1) {
                        // 结束提示由下面那句 Toast 承载，这里只负责收起过程态
                        // （进度条 + 上一轮驻留的文案）。
                        endSynthesizeUi()
                    }
                    revealDownloadButton(taskId)

                    Toast.makeText(requireContext(), "语音合成完成，点击下载音频", Toast.LENGTH_LONG).show()
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ SSE连接错误: taskId=$taskId, $error")
                sseEventSources.remove(taskId)?.cancel()
                uiScope.launch(Dispatchers.Main) {
                    requireContext().showError("实时连接失败：$error")
                    startPollingTaskStatus(taskId, localTaskId)
                }
            }
        )

        // 按 taskId 保存，互不覆盖；同一任务重复连接时先关掉旧的
        sseEventSources.remove(taskId)?.cancel()
        sseEventSources[taskId] = SseClient.connectToTaskStream(baseUrl, taskId, listener)

        Log.d(TAG, "🔗 已建立SSE连接到任务 $taskId，当前活跃连接数: ${sseEventSources.size}")
    }

    /**
     * 一个任务收尾：关闭它的 SSE 连接并刷新批次汇总文案
     */
    private fun onTaskFinished(taskId: Long) {
        sseEventSources.remove(taskId)?.cancel()
        // 任务结束（SSE 或轮询收尾）时一并取消其轮询，避免残留独立槽位。
        // 按引用条件删除：cancel 与 remove 之间若有新轮询入槽（SSE error 重入），
        // 无条件 remove 会误删新 Job 的槽位
        val job = pollingJobs[taskId]
        job?.cancel()
        if (job != null) {
            pollingJobs.remove(taskId, job)
        }
        activeTaskIds.remove(taskId)
        updateBatchProgressText()
    }

    /**
     * 任务完成后补齐「本次消耗 X 积分」：拉取该任务最新状态，读取服务端下发的
     * creditsCost（免费试用时为 0），累计到批次实耗；用 settledTaskIds 去重，
     * 避免 SSE 与轮询两条链路重复计入。所有任务的实耗都取回后（pendingCostFetches 归零）
     * 且本批次任务已全部结束，才统一展示，避免提前显示「消耗 0」。
     */
    private fun collectTaskCost(taskId: Long) {
        if (!settledTaskIds.add(taskId)) return
        if (AuthManager.token == null) return
        pendingCostFetches++
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = RetrofitClient.apiService.getTaskStatus(taskId)
                if (resp.isSuccessful) {
                    val task = resp.body()?.get("task") as? Map<*, *>
                    val cost = (task?.get("creditsCost") as? Number)?.toInt() ?: 0
                    batchConsumedCredits += cost
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 取不到成本不影响主流程，最坏情况只是不展示「本次消耗」
            } finally {
                pendingCostFetches--
                if (isViewCreated && pendingCostFetches <= 0 && activeTaskIds.isEmpty() && batchTotal > 0) {
                    withContext(Dispatchers.Main) { showBatchCostSummary() }
                }
            }
        }
    }

    /** 合成结束后展示本批次实耗积分（0 表示全程使用免费试用额度） */
    private fun showBatchCostSummary() {
        if (!isViewCreated) return
        val free = batchConsumedCredits == 0
        tvCostEstimate.visibility = View.VISIBLE
        tvCostEstimate.text = if (free) {
            "本次合成完成，已使用免费试用额度（未扣积分）"
        } else {
            "本次合成完成，共消耗 $batchConsumedCredits 积分"
        }
    }

    private fun updateBatchProgressText() {
        if (!isViewCreated || batchTotal <= 0 || !isAdded || isDetached) return
        // 走 viewLifecycleOwner 作用域，视图销毁后不再 post 到消亡视图
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val remaining = activeTaskIds.size
            if (remaining > 0) {
                showProgress("合成中：已完成 ${batchTotal - remaining}/$batchTotal 本，剩余 $remaining 本")
            } else {
                // 整批收工：与单本完成同一个口径（endSynthesizeUi）。
                // 批次结果已由各任务的 Toast + 书架条目 + tvCostEstimate 承载。
                endSynthesizeUi()
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "UNUSED_VALUE")
    private fun startPollingTaskStatus(taskId: Long, localTaskId: Long) {
        // 同一任务重复发起时先取消旧轮询，再开独立槽位，不覆盖其他任务的轮询
        pollingJobs[taskId]?.cancel()
        val job = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
            var isProcessing = true
            var pollCount = 0
            // 指数退避，2s 起，最长 15s；并设尝试上限，避免无限轮询
            var pollInterval = 2000L
            val maxInterval = 15000L
            val maxAttempts = 600

            while (isProcessing) {
                pollCount++
                if (pollCount > maxAttempts) {
                    Log.w(TAG, "轮询超时（已达 $maxAttempts 次），停止: taskId=$taskId")
                    isProcessing = false
                    break
                }
                try {
                    if (AuthManager.token != null) {
                        Log.d(TAG, "🔄 第${pollCount}次轮询: serverId=$taskId, localId=$localTaskId")

                        val response = RetrofitClient.apiService.getTaskStatus(taskId)

                        if (response.isSuccessful) {
                            val result = response.body()

                            val task = result?.get("task") as? Map<*, *>
                            if (task != null) {
                                val status = task["status"] as? String
                                val completed = (task["completedSegments"] as? Number)?.toInt() ?: 0
                                val total = (task["totalSegments"] as? Number)?.toInt() ?: 0

                                Log.d(TAG, "📊 任务状态: status=$status, progress=$completed/$total")

                                withContext(Dispatchers.Main) {
                                    viewModel.updateTaskStatus(localTaskId, status ?: "processing", completed, total)
                                }

                                if (status == "completed" || (status == "partial" && completed > 0)) {
                                    isProcessing = false
                                    Log.d(TAG, "✅ 任务完成！准备下载音频... taskId=$taskId")

                                    withContext(Dispatchers.Main) {
                                        onTaskFinished(taskId)
                                        collectTaskCost(taskId)
                                        if (isViewCreated) {
                                            if (batchTotal <= 1) {
                                                // 收起过程态；结束提示交给下一行 Toast
                                                endSynthesizeUi()
                                            }
                                            revealDownloadButton(taskId)

                                            // partial 走到这里说明「成了几段但没全成」，
                                            // 说「合成完成」会盖住缺段的事实（书架与通知都写「部分完成」）。
                                            val doneText = if (status == "partial") {
                                                "部分段落未合成成功，可先下载已完成的音频"
                                            } else {
                                                "语音合成完成，点击下载音频"
                                            }
                                            Toast.makeText(requireContext(), doneText, Toast.LENGTH_LONG).show()

                                            Log.d(TAG, "🔄 任务状态已通过 updateTaskStatus 更新，LiveData将自动触发UI刷新")
                                        }
                                    }
                                } else if (status == "failed" || (status == "partial" && completed == 0)) {
                                    // 空音频/超时等导致全部段落失败时，服务端落 FAILED（或部分完成数为 0 的 PARTIAL），
                                    // 统一展示友好文案而非技术堆栈；并取服务端 errorMessage 写进本地任务，书架条目可见原因。
                                    isProcessing = false
                                    val serverMsg = (task["errorMessage"] as? String)?.takeIf { it.isNotBlank() }
                                    val friendly = "合成失败，请重试或换音色"
                                    Log.e(TAG, "❌ 任务失败: taskId=$taskId, ${serverMsg ?: friendly}")
                                    withContext(Dispatchers.Main) {
                                        onTaskFinished(taskId)
                                        viewModel.updateTaskErrorMessage(localTaskId, serverMsg ?: friendly)
                                        if (isViewCreated && batchTotal <= 1) {
                                            // 失败也要收起过程态：失败的用户同样不该
                                            // 对着一条还在转的进度条和上一轮的
                                            // 「正在合成: 8/12」找原因。
                                            endSynthesizeUi()
                                        }
                                        if (isViewCreated) {
                                            Toast.makeText(requireContext(), friendly, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "⏳ 任务进行中，继续轮询...")
                                }
                            } else {
                                Log.w(TAG, "⚠️ 响应体中没有task字段")
                            }
                        } else {
                            Log.w(TAG, "❌ 查询任务状态失败: HTTP ${response.code()}")
                        }
                    } else {
                        Log.w(TAG, "⚠️ Token为空，无法轮询")
                    }

                    if (isProcessing) {
                        kotlinx.coroutines.delay(pollInterval)
                        pollInterval = minOf(pollInterval * 2, maxInterval)
                    }

                } catch (e: CancellationException) {
                    // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 轮询异常: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        if (isViewCreated) {
                            requireContext().showError("查询状态失败：${e.toUserMessage()}")
                        }
                    }
                    break
                }
            }

            Log.d(TAG, "🛑 停止轮询，共轮询${pollCount}次")
            } finally {
                // 按引用条件删除：旧 Job 的取消是异步的，其 finally 可能在新 Job 入槽之后才执行，
                // 无条件 remove(taskId) 会把新 Job 的槽位误删，导致新轮询失去追踪、
                // 任务完成后仍空轮询直至上限
                pollingJobs.remove(taskId, coroutineContext[Job])
            }
        }
        pollingJobs[taskId] = job
    }

    private fun fetchAndDownloadAllSegments(taskId: Long) {
        // 下载是用户发起的一次性后台作业，生命期跟 ViewModel 而非视图：
        // 用 viewLifecycleOwner.lifecycleScope 会在视图销毁（切走 Tab / 旋转屏幕）时取消协程，
        // 刚合成好的整本书音频下到一半就断了，tvProgress 永远停在「正在下载: 37/212」。
        //
        // 协程跑在 viewModelScope 里不能再抓 Activity Context，用 Application Context 弹提示。
        val appContext = viewModel.getApplication<android.app.Application>().applicationContext
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            try {
                if (AuthManager.token == null) {
                    Log.w(TAG, "⚠️ Token为空，无法下载")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "请先登录", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                Log.d(TAG, "🎵 开始下载所有音频片段，taskId=$taskId")

                withContext(Dispatchers.Main) {
                    if (isViewCreated) {
                        showProgress("正在下载音频...")
                        btnPlayResult.isEnabled = false
                    }
                }

                // Step 1: 获取片段列表
                val segmentsResponse = RetrofitClient.apiService.getTaskSegmentsList(taskId)
                if (!segmentsResponse.isSuccessful) {
                    Log.e(TAG, "❌ 获取片段列表失败: HTTP ${segmentsResponse.code()}")
                    withContext(Dispatchers.Main) {
                        if (isViewCreated) {
                            endSynthesizeUi()
                            btnPlayResult.isEnabled = true
                            Toast.makeText(appContext, "获取片段列表失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val segmentsBody = segmentsResponse.body()
                @Suppress("UNCHECKED_CAST")
                val segments = segmentsBody?.get("segments") as? List<Map<String, Any>>
                if (segments.isNullOrEmpty()) {
                    Log.w(TAG, "⚠️ 没有音频片段")
                    withContext(Dispatchers.Main) {
                        if (isViewCreated) {
                            endSynthesizeUi()
                            btnPlayResult.isEnabled = true
                            Toast.makeText(appContext, "未找到音频片段", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@launch
                }

                val totalSegments = segments.size
                Log.d(TAG, "✅ 共 $totalSegments 个片段，开始逐个下载")

                val bookName = taskBookNames[taskId]
                    ?: selectedBooks.firstOrNull()?.displayName
                    ?: "unknown_book"

                // Step 2: 逐个下载每个片段，下完一段立刻入库一段（流式保存）：
                // 内存中任何时刻只驻留一段音频。攒完整本书再一次性写库，
                // 大书会先在堆里堆出整本音频的体积，直接 OOM。
                var savedCount = 0
                for ((index, segment) in segments.withIndex()) {
                    val segmentId = (segment["id"] as? Number)?.toLong() ?: continue
                    val segmentNumber = (segment["segmentNumber"] as? Number)?.toInt() ?: (index + 1)
                    val textContent = segment["textContent"] as? String ?: "段落 $segmentNumber"

                    try {
                        val downloadResponse = RetrofitClient.heavyApi.downloadSegment(segmentId)
                        if (downloadResponse.isSuccessful) {
                            // 单段限流读取：正常单段音频远小于 50MB，超限视为服务端异常响应
                            val audioBytes = downloadResponse.body()?.let { body ->
                                val out = java.io.ByteArrayOutputStream()
                                val buf = ByteArray(16 * 1024)
                                var total = 0L
                                body.byteStream().use { input ->
                                    var read: Int
                                    while (input.read(buf).also { read = it } != -1) {
                                        total += read
                                        if (total > MAX_SEGMENT_BYTES) {
                                            throw java.io.IOException("单段音频超过 ${MAX_SEGMENT_BYTES / 1024 / 1024}MB 上限，放弃该段")
                                        }
                                        out.write(buf, 0, read)
                                    }
                                }
                                out.toByteArray()
                            }
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                if (viewModel.saveAudioSegmentStreaming(bookName, segmentId, segmentNumber, textContent, audioBytes)) {
                                    savedCount++
                                    Log.d(TAG, "✅ 片段 $segmentNumber 下载并入库成功 (${audioBytes.size} bytes)")
                                } else {
                                    Log.w(TAG, "⚠️ 片段 $segmentNumber 入库失败")
                                }
                            }
                        } else {
                            Log.w(TAG, "⚠️ 片段 $segmentNumber 下载失败: HTTP ${downloadResponse.code()}")
                        }
                    } catch (e: CancellationException) {
                        // 协程取消不是失败：必须原样抛出，不能被下面的 catch (e: Exception) 吞掉
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 片段 $segmentNumber 下载异常: ${e.message}")
                    }

                    withContext(Dispatchers.Main) {
                        if (isViewCreated) {
                            showProgress("正在下载: ${index + 1}/$totalSegments")
                        }
                    }
                }

                Log.d(TAG, "📊 下载完成: $savedCount/$totalSegments 个片段")

                // 强制落盘最后凑不满一批的片段，否则「下载完成」但最后几段没声音
                viewModel.flushPendingAudioSegments()
                // 批量提交失败过的片段数（不查就静默丢掉一整批）
                val droppedSegments = viewModel.consumeFlushFailures()
                if (droppedSegments > 0) {
                    Log.e(TAG, "❌ 有 $droppedSegments 段音频写入本地失败")
                }
                val lostSegments = droppedSegments > 0

                // Step 3: 片段已在下载过程中逐段入库，这里只做列表状态统一刷新
                if (savedCount > 0) {
                    viewModel.refreshLibraryAfterDownload()
                }

                withContext(Dispatchers.Main) {
                    if (isViewCreated) {
                        if (lostSegments) {
                            endSynthesizeUi()
                            btnPlayResult.isEnabled = true
                            Toast.makeText(appContext, "有 $droppedSegments 段音频写入本地失败，请重新下载补齐", Toast.LENGTH_LONG).show()
                        } else if (savedCount > 0) {
                            // 「下载完成」按钮本身就是结果指示器，不需要再留一行字。
                            endSynthesizeUi()
                            btnPlayResult.text = "下载完成"
                            btnPlayResult.isEnabled = false
                            Toast.makeText(appContext, "已下载 $savedCount/$totalSegments 个片段", Toast.LENGTH_LONG).show()
                        } else {
                            endSynthesizeUi()
                            btnPlayResult.isEnabled = true
                            Toast.makeText(appContext, "音频下载失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // 下载成功后自动登记电子书到书架
                    if (savedCount > 0) {
                        viewModel.ensureEbookRegistered(bookName)
                    }
                }
            } catch (e: CancellationException) {
                // 取消不是失败，但「卡在下载中」是必须收拾的残局：
                // 只 throw e 会让 tvProgress 停在「正在下载: x/y」且按钮不可点，只能杀进程
                withContext(Dispatchers.Main) {
                    if (isViewCreated) {
                        endSynthesizeUi()
                        btnPlayResult.isEnabled = true
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ 下载异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (isViewCreated) {
                        endSynthesizeUi()
                        btnPlayResult.isEnabled = true
                    }
                    appContext.showError("下载失败：${e.toUserMessage()}")
                }
            } finally {
                // 异常/取消路径也要落盘，否则已下成功的几十段跟着一起丢
                withContext(NonCancellable) {
                    viewModel.flushPendingAudioSegments()
                    val dropped = viewModel.consumeFlushFailures()
                    if (dropped > 0) {
                        Log.e(TAG, "❌ 收尾落盘时有 $dropped 段音频写入失败")
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _isViewCreated = false
        pollingJobs.values.forEach { it.cancel() }
        pollingJobs.clear()
        sseEventSources.values.forEach { it.cancel() }
        sseEventSources.clear()

        // 停止可能仍在播放的克隆音色试听，避免后台继续发声
        // （录音相关的 MediaRecorder/MediaPlayer/缓存文件清理已随录制功能整体移走，
        //   现由 ReferenceAudioFragment 的 onDestroyView 负责）
        VoicePreviewManager.stop()

        // 断开视图引用：Fragment 实例在返回栈里还活着，
        // 不能让它继续拽着已经销毁的 View 树
        _btnSelectBook = null
        _tvSelectedBook = null
        _tvSelectedFile = null
        _btnUploadAndSynthesize = null
        _progressBar = null
        _tvProgress = null
        _btnPlayResult = null
        _tvCreditHint = null
        _tvCostEstimate = null
        _btnPreviewVoice = null
        _spinnerDefaultVoice = null
        _tvVoiceHint = null
        // 合成方式 / 参考音频来源两组下拉与分支容器：Fragment 实例在返回栈里存活，
        // 这些字段同样必须置空（漏掉任何一处，旧 View 树就会被返回栈条目一直拽着）
        _spinnerSynthesisMode = null
        _tvModeHint = null
        _spinnerReferenceSource = null
        _tvSourceHint = null
        _layoutSourceLibrary = null
        _spinnerReferenceLibrary = null
        _layoutSourcePreset = null
        // 适配器持有 Activity Context（simple_spinner_item 的 View 来自它），
        // 与 libraryAdapter 同样必须断开，否则返回栈条目会一直拽着旧 Activity。
        localVoiceAdapter = null
        voiceAdapter = null
        // 来源下拉的适配器在这个方法里直接创建、不留字段，视图销毁即随之回收。
        _btnPauseSynthesis = null
        _btnCancelSynthesis = null
    }

    override fun onStop() {
        super.onStop()
        // 录音播放的暂停/计时器逻辑已随录制功能移走（见 onDestroyView 的说明）
    }

    override fun onStart() {
        super.onStart()
        // 从「我的 - 本机语音合成」装完模块回到本页时，本页视图**并没有重建**
        // （那是另一个 Activity，onViewCreated 不会重跑），于是模式提示仍写着
        // 「需要先下载模块」、音色下拉也停在占位项上。这里各补一次刷新。
        if (!isViewCreated) return
        if (synthesisModeLocal && localVoices.isEmpty()) {
            loadLocalVoices()
            updateModeHint()
        }
        // 退出本页去做别的事（登录、装模块、甚至服务端刚部署完）时，本页视图不重建，
        // onViewCreated 里那次 loadVoices 的结果就留在原处：那时若拉取失败
        // （断网 / 老服务端对匿名请求 401），回到本页看到的仍只有内置兜底清单。
        // 这里在「还没成功拿到过服务端清单」时补拉一次 —— loadVoices 两段式
        // （先内置兜底再服务端覆盖），重复调用无副作用。
        if (!serverVoicesLoaded) {
            loadVoices()
        }
    }
}

