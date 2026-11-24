package com.audiobook.audiobookplayer.moss

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.audiobook.audiobookplayer.BuildConfig
import com.audiobook.audiobookplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「本机语音合成」模块管理页。
 *
 * ## 职责边界
 * 这一页只解决一件事：**让用户自己决定要不要把 725 MB 的模块装到手机上**。
 * 因此：
 *   · 默认不下载任何东西 —— 只想用在线合成的用户完全不受影响；
 *   · 体积、用途、代价（占空间、加载后吃内存）在按下去之前就讲清楚；
 *   · 装完之后可以随时卸载，且卸载会把 725 MB 真正删掉。
 *
 * ## 状态机
 * 页面呈现由 [UiState] 决定，状态之间是单向的，避免出现
 * "显示已安装、点下去却在下载"这类自相矛盾的中间态：
 *   NotInstalled -> Downloading -> Verifying -> Installing -> Installed
 *                        |                                    ^
 *                        +--> Paused --------------------------+
 *   Installed -> Uninstalling -> NotInstalled
 *
 * 下载态的主按钮是「暂停」而不是置灰的「正在下载…」：停传输只需 close 流，
 * 断点已落在 .part 里，点「继续」走 Range 续传（见 [pauseDownload]）。
 *
 * ## 一个必须诚实告知的行为
 * 模型常驻内存对系统压力很大（实测 4 GB 设备上会触发 lowmemorykiller 回收后台应用）。
 * 页面在"已安装"状态下会显示设备可用内存并给出提示，而不是等用户发现
 * "装了之后别的 App 老是被杀"再来找原因。
 */
class MossModuleActivity : Activity() {

    companion object {
        private const val TAG = "MossModuleActivity"
    }

    private enum class UiState { Checking, NotInstalled, Downloading, Paused, Installed, Busy }

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnPrimary: Button
    private lateinit var tvAbout: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 订阅进程级安装状态的收集协程；只在页面处于前台期间存活（见 onCreate 的注册处） */
    private var stateJob: Job? = null

    /**
     * 当前这一轮是不是「本页面」发起的 —— 决定它结束后要不要 [refresh]。
     *
     * 只有本页面发起、且**让它自己跑完**的轮次才刷新：用户点「暂停」停掉的轮次
     * 必须留在 [UiState.Paused]（主按钮=「继续」），被 refresh 覆盖成「未安装」
     * 就丢了"一键继续"的语义 —— 而那轮在 download() 抛出时根本走不到下面的认领，
     * 标志天然是 false。跨页面残留的轮次同理：认领不了，就不刷新。
     */
    private var startedByThisPage = false

    /** 当前正在进行的下载/安装任务，取消按钮要能真的把它停掉 */
    private var runningJob: Job? = null

    /**
     * 本轮是否被用户以「暂停」主动停下（而不是它自己失败/被页面销毁带走）。
     *
     * 协程被取消只有两条路：用户点「暂停」，或 onDestroy 的 scope.cancel()。
     * 前者要回 [UiState.Paused]（主按钮=「继续」，一键恢复）；后者页面已经没了，
     * 什么都不该做。两条路都保留 .part 断点，下次进来能从断点续传。
     * 每轮 [startInstall] 开始时复位。
     */
    private var pauseRequested = false

    /**
     * 忙碌渲染抑制：用户点过「暂停」/「取消」之后置 true，直到下一轮 exitBusy 到达。
     *
     * 为什么需要它：停止动作只是 close 流，而 IO 线程的阻塞 read 不是立刻死的
     * （真机实测：close 之后 read 又活了两秒多才抛异常）。这期间下载循环可能
     * 再发布**最后一轮进度** —— 它排进主线程队列的时机可能晚于我们的
     * `setState(Paused)`，于是「已暂停」被一批陈旧进度闪回成「正在下载」，
     * 而传输早就停了。让用户盯着一个写着"正在下载"却纹丝不动界面，
     * 比置灰按钮更糟。StateFlow 保序：exitBusy 一定排在这批残留之后到达，
     * 届时随 onInstallState 的空闲分支复位本标志。
     */
    private var suppressStaleBusy = false

    /** 最近一次拿到的清单；决定按钮文案与体积显示 */
    private var manifest: MossManifest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_moss_module)
        title = "本机语音合成"

        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)
        progressBar = findViewById(R.id.progressBar)
        btnPrimary = findViewById(R.id.btnPrimary)
        tvAbout = findViewById(R.id.tvAbout)

        btnPrimary.setOnClickListener { onPrimaryClicked() }

        // 调试用：长按标题（或点状态行）可临时改写模块托管地址，只在 debug 包生效。
        // 为什么需要它：725 MB 的包很可能迁到 CDN/对象存储，或排查时想指到备用源；
        // 若地址一变就必须发 App 版本、否则老用户永远下不到，这个约束没必要。
        // 只给 debug 是防呆：release 用户误改成无效地址后没有任何恢复入口。
        //
        // 两个入口都挂上是因为长按对自动化测试不友好（uiautomator 命中不稳定），
        // 而"点状态行"能稳定点到 —— 我自己验证这个功能时就吃了这个亏。
        if (BuildConfig.DEBUG) {
            findViewById<TextView>(R.id.tvTitle).setOnLongClickListener {
                showBaseUrlDialog(); true
            }
            tvStatus.setOnClickListener { showBaseUrlDialog() }
        }

        tvAbout.text = buildString {
            append("模型：MOSS-TTS-Nano（0.1B 参数，Apache-2.0 许可）\n")
            append("来源：OpenMOSS/MOSS-TTS-Nano\n\n")
            append("模块包含模型权重，安装后可在本机离线合成语音，不消耗积分、不依赖网络。\n\n")
            append("需要注意：\n")
            // 静态兜底体积（[MossModuleManager.FALLBACK_*]，对齐线上 manifest 真值）。
            // 上面状态区在能拉到清单时用的是清单里的真值，这里是"还没联网/拉不到"
            // 也要先把代价说清楚的地方，所以只能取兜底常量。
            append("· 安装包${MossModuleManager.approxDownloadMb()}，" +
                "安装后占用${MossModuleManager.approxInstalledMb()}\n")
            // 耗时只给实测区间，不编一个具体数字。
            //
            // 实测数据：同一段文本（94 帧 / 7.5 秒音频）
            //   · 独立测试页（其它业务线程空闲）耗时约 8 秒 → 实时率约 0.5
            //   · 走应用完整链路（并发 UI、数据库、GC）耗时约 68 秒 → 实时率约 0.11
            // 也就是说"同样一台设备，负载不同能差 5 倍以上"。
            // 当初写成"一段 10 分钟有声书约需 21 分钟"是按 0.5 算的，属于乐观值；
            // 与其换成另一个同样不可靠的数字，不如给区间并说明它会波动 ——
            // 真实的剩余时间由合成页按已完成的段动态估算，那个比这里的静态文案准。
            append("· 合成较慢：实测实时率约 0.1~0.5（随设备与负载波动很大），\n")
            append("  即 10 分钟音频大约要跑 20~100 分钟；合成页会动态显示剩余时间\n")
            append("· 合成过程中可以随时中止\n")
            append("· 模型加载后会占用大量内存，可能影响其它后台应用\n")
            append("· 可随时卸载，卸载会清空全部模块文件")
        }

        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // 必须清掉 Handler 队列：download() 跑在 IO 线程上，而 onDestroy 的
        // cancel() 停不掉那个阻塞读循环（它只在循环里检查中断标志），
        // 于是下载还会继续几十秒到几十分钟。这期间每 500 ms 就往队列里追加一个
        // 携带 Activity 强引用的 Runnable，全部在视图销毁后执行 ——
        // 不断操作已销毁的 View 树，并把整棵 View 树持有到下载结束才释放。
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 前台期间订阅进程级安装状态。
     *
     * ## 为什么用 onStart/onStop 而不是 `repeatOnLifecycle`
     *
     * 本类继承的是**裸 `android.app.Activity`**（不是 `ComponentActivity`），
     * 所以既没有 `lifecycleScope` 扩展、也不能作为 `repeatOnLifecycle` 的接收者 ——
     * 项目里能用那套的 `BookContentActivity` 走的是 `BaseActivity : AppCompatActivity`。
     * 为订阅一个状态流而改继承会牵动主题与全部控件行为，代价远大于收益。
     * `onStart`/`onStop` 对任何 Activity 都成立，语义与 `STARTED` 完全等价。
     *
     * ## 为什么只在 STARTED 期间收集
     *
     * 用户离开页面时立刻停掉收集。下载本身**不会**因此停下（见 [MossDownloader]
     * 的进程级状态说明），所以回到页面时重新收集，StateFlow 会**先发一次当前值**
     * —— 进度与按钮状态瞬间回到真实值，不需要额外的手动查询。这就是记忆里
     * 「事件驱动 + onResume 重查两条路」在本场景的形态：真值本身就在进程里，
     * 只需要一条；写两条反而多一个会漂移的口径。
     */
    override fun onStart() {
        super.onStart()
        stateJob?.cancel()
        stateJob = scope.launch {
            MossDownloader.installState.collect { st -> onInstallState(st) }
        }
    }

    override fun onStop() {
        super.onStop()
        stateJob?.cancel()
        stateJob = null
    }

    // ------------------------------------------------------------ 状态刷新

    /**
     * 订阅进程级安装状态。
     *
     * 这个回调解决两件事，都是用户实际遇到的：
     *
     * 1. **进度实时更新**（原本的 bug）：退出下载页后 `.part` 仍在后台增长，
     *    但重新进来时 `partialBytes()` 只被读一次就写进文案，于是
     *    「上次已下载 50.3MB」会一直停在 50.3。现在数字直接来自后台循环发布的
     *    进程级状态，每 500 ms 变一次。
     *
     * 2. **下载中按钮不可用**：判据从「本地有没有 .part」换成「进程里是否真有
     *    一轮下载在跑」。前者只能告诉你*下过*，后者才知道*正在下* ——
     *    这正是原先下载中重新进页面、按钮仍可点、一点就撞 tryLock 失败的根因。
     */
    private fun onInstallState(st: MossDownloader.InstallState) {
        // 忙碌态：不论是谁发起的，进度与按钮都按真实运行态渲染（下载阶段=「暂停」，
        // 校验/解压/安装阶段=置灰），用户看到的必须与进程里正在发生的一致。
        if (st.busy) {
            // 停止动作后、exitBusy 到达前：这批忙碌发布属于**已被停掉的那一轮**，
            // 是残留而非现状。渲染它会把「已暂停 / 可续传」闪回成「正在下载」。
            if (suppressStaleBusy) return
            progressBar.visibility = View.VISIBLE
            progressBar.isIndeterminate = st.downloaded <= 0L || st.total <= 0L
            if (st.total > 0L) {
                progressBar.progress =
                    (st.downloaded * 1000L / st.total).coerceIn(0L, 1000L).toInt()
            }
            tvStatus.text = if (st.stage.isNotEmpty()) st.stage else "正在下载"
            tvDetail.text = buildString {
                if (st.total > 0L) {
                    append("${mb(st.downloaded)} / ${mb(st.total)}")
                } else if (st.downloaded > 0L) {
                    append(mb(st.downloaded))
                }
                if (st.bytesPerSecond > 0L) {
                    append("　${mb(st.bytesPerSecond)}/s")
                    val remain = (st.total - st.downloaded) / st.bytesPerSecond.coerceAtLeast(1)
                    append("　剩余约 ${remain / 60} 分 ${remain % 60} 秒")
                }
            }
            // 只有"正在从网络读字节"的阶段能暂停：主按钮给真的能停的「暂停」。
            // 校验/解压/安装阶段（pausable=false）停不下来，保持置灰 ——
            // 给一个按了没反应的按钮比不给更糟。
            if (st.pausable) {
                btnPrimary.isEnabled = true
                btnPrimary.text = "暂停"
            } else {
                btnPrimary.isEnabled = false
                btnPrimary.text = "正在下载…"
            }
            return
        }

        // 从忙碌回到空闲：这一轮结束（成功 / 失败 / 取消 / 暂停），
        // 让真实结果（已安装 / 可续传）重新渲染出来。
        // 同时解除忙碌渲染抑制 —— exitBusy 是残留进度之后到达的最后一个事件，
        // 从这里起，新的忙碌发布就是真新一轮了。
        suppressStaleBusy = false
        if (startedByThisPage) {
            startedByThisPage = false
            refresh()
        }
    }

    private fun refresh() {
        val installed = MossModuleManager.isInstalled(this)
        if (installed) {
            setState(UiState.Installed)
            return
        }
        // ⚠️ 已有下载在跑时**必须停在这里**。
        // 少这一句，页面一进来就会走 NotInstalled 分支，用 `partialBytes()` 的一次性
        // 快照渲染出「上次已下载 50.3 MB，点下面按钮会从断点继续」——而那个下载此刻
        // 正在后台把 50.3 变成 51.2，而这个按钮点了必然撞 tryLock 失败。
        // StateFlow 的订阅会立刻把真实进度刷上来（onInstallState），这里只要别去覆盖它。
        if (MossDownloader.installState.value.busy) return
        // 未安装：去服务端问一下包多大、什么版本，以便把体积讲清楚
        setState(UiState.Checking)
        scope.launch {
            val m = withContext(Dispatchers.IO) {
                runCatching { MossDownloader.fetchManifest(this@MossModuleActivity) }
                    .onFailure { Log.w(TAG, "拉取清单失败: ${it.message}") }
                    .getOrNull()
            }
            manifest = m
            if (m == null) {
                setState(UiState.NotInstalled)
                tvStatus.text = "无法连接模块服务器"
                tvDetail.text = "请检查网络后重试。只想用在线合成的话，可以不安装这个模块。"
            } else {
                setState(UiState.NotInstalled)
            }
        }
    }

    private fun setState(state: UiState) {
        when (state) {
            UiState.Checking -> {
                tvStatus.text = "正在检查…"
                tvDetail.text = ""
                progressBar.visibility = View.GONE
                btnPrimary.isEnabled = false
                btnPrimary.text = "安装模块"
            }
            UiState.NotInstalled -> {
                val m = manifest
                tvStatus.text = "未安装"
                // 覆盖了托管地址时明确标出来：否则"为什么下的不是官方源"没人说得清，
                // 排查线上问题时这一点尤其重要。
                val sourceNote = if (MossModuleSource.isOverridden(this)) {
                    "\n数据源：${MossModuleSource.baseUrl(this)}（自定义）"
                } else {
                    ""
                }
                tvDetail.text = if (m != null) {
                    val free = MossDownloader.availableBytes(this)
                    // 上次没下完的分片：必须让用户看见，否则"为什么按钮写着继续"
                    // 和"这 300 MB 是什么"都无从解释。
                    val partMb = MossDownloader.partialBytes(this, m)
                    // 空间是否够用与按钮的可用态同口径（hasEnoughSpace）：
                    // 续传时缺的是峰值减去已下载的那部分，写在这里就取同一个判据，
                    // 免得文案与按钮打架（之前正是执行侧漏减 .part 导致误报「空间不足」）
                    val spaceOk = MossDownloader.hasEnoughSpace(this, m)
                    buildString {
                        if (partMb > 0) {
                            append("上次已下载 ${mb(partMb)}，点下面按钮会从断点继续。\n")
                        } else {
                            append("需要下载 ${mb(m.downloadBytes)}，安装后占用 ${mb(m.installedBytes)}。")
                            append("\n设备可用空间 ${mb(free)}。")
                        }
                        append(sourceNote)
                        // 与按钮的可用态同口径（hasEnoughSpace）：续传时缺的是
                        // 峰值减去已下载的那部分，否则文案会误报「空间不足」
                        if (!spaceOk) {
                            append("\n⚠️ 空间不足（本次还需约 " +
                                "${mb(MossDownloader.requiredPeakBytes(m) - partMb)}），请先清理。")
                        }
                    }
                } else {                    // 拉不到清单（断网 / 服务端 502）时**必须留一个能点的按钮**：
                    // 原先这里 btnPrimary.isEnabled = (m != null && …)，页面上一个可点控件都没有，
                    // 「请检查网络后重试」这句提示在 UI 上根本无法执行，
                    // 用户只能杀掉页面重进（从本机合成入口进来要先退两层）。
                    "安装后可在本机离线合成语音。$sourceNote\n\n" +
                        "当前无法获取模块信息（网络不通或服务器暂时不可用），点下面按钮重试。"
                }
                progressBar.visibility = View.GONE
                // 空间判据必须与 startInstall() 里的 MossDownloader.hasEnoughSpace 同口径。
                // 直接调它，不再在这里重算一遍（`available >= peak - part` 与
                // `available + part >= peak` 是同一个式子，但写成两处就一定会漂移：
                // 之前执行侧漏减了已下载的 .part，续传时按钮放行、点下去却报空间不足）。
                btnPrimary.isEnabled = m == null || MossDownloader.hasEnoughSpace(this, m)
                // 有断点时按钮文案要说清是"继续"，而不是让用户以为又要重下 725 MB
                btnPrimary.text = when {
                    m == null -> "重新检查"
                    MossDownloader.partialBytes(this, m) > 0 -> "继续下载并安装"
                    else -> "下载并安装模块"
                }
            }
            UiState.Downloading -> {
                progressBar.visibility = View.VISIBLE
                // 必须复位成确定进度：isIndeterminate 一旦被置 true，
                // ProgressBar 会完全忽略 setProgress。整个安装流程一定会先经过
                // onStage("校验安装包完整性")把它置上，而原先没有任何地方置回 false ——
                // 于是「安装失败 → 点重试」之后的整段 725 MB 下载，
                // 进度条都是一个永远转圈的动画（文字在变、条不动），
                // 而 Downloading 分支只把 visibility 设回 VISIBLE，漏了这个状态位。
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                // 准备期（拉清单/查空间）就给「暂停」：这时点它只是放弃本轮尝试，
                // 一个字节都还没下，不存在"停到一半"的状态。
                btnPrimary.isEnabled = true
                btnPrimary.text = "暂停"
            }
            UiState.Paused -> {
                // 用户点了「暂停」：传输已停、断点已留在 .part 里。
                // 主按钮变「继续」，点下去经 [startInstall] → download() 的 Range 续传。
                val m = manifest
                val part = if (m != null) MossDownloader.partialBytes(this, m) else 0L
                val total = m?.downloadBytes ?: 0L
                tvStatus.text = "已暂停"
                tvDetail.text = if (part > 0L && total > 0L) {
                    "已下载 ${mb(part)} / ${mb(total)}，点「继续」恢复下载（断点续传）。"
                } else {
                    "点「继续」恢复下载。"
                }
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress =
                    if (total > 0L) (part * 1000L / total).coerceIn(0L, 1000L).toInt() else 0
                btnPrimary.isEnabled = true
                btnPrimary.text = "继续"
            }
            UiState.Busy -> {
                progressBar.visibility = View.VISIBLE
                // 这一态只出现在下载完成后的解压/校验/安装阶段：不可中断，
                // 按钮置灰并说清在干什么，别留着上一个态的「暂停」文案。
                btnPrimary.isEnabled = false
                btnPrimary.text = "正在安装…"
            }
            UiState.Installed -> {
                tvStatus.text = "已安装"
                val used = MossModuleManager.installedBytes(this)
                val free = MossDownloader.availableBytes(this)
                val byVersion = manifest?.version?.let { "模块版本 $it\n" } ?: ""
                tvDetail.text = buildString {
                    append(byVersion)
                    append("占用空间 ${mb(used)}，设备可用 ${mb(free)}。")
                    append("\n\n在语音合成页选择「本机合成」即可离线合成；")
                    append("不选则仍走在线合成。")
                }
                // 已装的模块可能是 1.0.0（不含 encode 模型）——
                // 它完全能合成内置音色，所以 [MossModuleManager.isInstalled] 判为"已安装"，
                // 用户也就没有任何"该升级"的提示。而"用参考音频克隆音色"依赖 encode，
                // 缺了它用户会以为功能坏了。**缺能力要说出来**，别让他自己撞。
                if (!MossModuleManager.hasVoiceCloneModel(this)) {
                    tvDetail.append(
                        "\n\n当前模块版本不支持「用参考音频克隆音色」（缺少 encode 模型）。" +
                            "卸载后重新下载即可获得该能力。"
                    )
                }
                progressBar.visibility = View.GONE
                btnPrimary.isEnabled = true
                btnPrimary.text = "卸载模块"
            }
        }
    }

    /** 折算口径统一收在 [MossModuleManager.formatMb]：全App 只有这里在显示体积。 */
    private fun mb(bytes: Long): String = MossModuleManager.formatMb(bytes)

    /**
     * 改写模块托管地址（仅 debug）。留空即恢复构建期的默认地址。
     * 改完立刻重新拉清单，让用户能马上看出新地址通不通。
     */
    private fun showBaseUrlDialog() {
        val current = MossModuleSource.baseUrl(this)
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = "https://…/moss/"
        }
        AlertDialog.Builder(this)
            .setTitle("模块托管地址（调试）")
            .setMessage(
                "当前：$current\n" +
                    "默认：${MossModuleSource.defaultBaseUrl}\n\n" +
                    "必须以 / 结尾；留空表示恢复默认。"
            )
            .setView(input)
            .setPositiveButton("保存并重试") { _, _ ->
                MossModuleSource.setBaseUrl(this, input.text.toString())
                manifest = null
                refresh()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                MossModuleSource.setBaseUrl(this, null)
                manifest = null
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 动作

    private fun onPrimaryClicked() {
        when {
            MossModuleManager.isInstalled(this) -> confirmUninstall()
            // 下载进行中：主按钮是「暂停」，不再是置灰的「正在下载…」
            isDownloadInProgress() -> pauseDownload()
            else -> startInstall()
        }
    }

    /**
     * 是否有一轮下载正在进行。
     *
     * 两个判据都要，各自覆盖另一种判据看不到的窗口：
     *   · [runningJob] 覆盖"拉清单 / 查空间"的准备期 —— 那时进程级 busy 还没置上，
     *     但这一轮确实已经开始了，点主按钮应当是暂停而不是再发起一轮；
     *   · [MossDownloader.installState] 覆盖跨页残留（用户退出又进来，而那轮下载
     *     属于已销毁的 Activity）：本页 runningJob 是空的，但传输真的在跑，
     *     暂停照样生效（[MossDownloader.cancelActiveTransfer] 是进程级的）。
     */
    private fun isDownloadInProgress(): Boolean =
        runningJob?.isActive == true || MossDownloader.installState.value.busy

    private fun confirmUninstall() {
        val used = MossModuleManager.installedBytes(this)
        AlertDialog.Builder(this)
            .setTitle("卸载本机合成模块？")
            .setMessage(
                "将删除模块文件，释放约 ${mb(used)} 空间。\n" +
                    "卸载后：已用本机合成生成的有声书仍可正常播放，但无法再新建本机合成任务。\n" +
                    "随时可以重新安装。"
            )
            .setPositiveButton("卸载") { _, _ -> doUninstall() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doUninstall() {
        setState(UiState.Busy)
        tvStatus.text = "正在卸载…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                MossModuleManager.clearCaches()
                MossDownloader.clearDownloads(this@MossModuleActivity)
                MossModuleManager.uninstall(this@MossModuleActivity)
            }
            if (ok) {
                toast("已卸载模块")
                manifest = null
                refresh()
            } else {
                setState(UiState.Installed)
                toast("卸载失败，可能有文件被占用，请重试")
            }
        }
    }

    /**
     * 开始安装。
     *
     * ⚠️ **每次安装都重新拉一次清单**，不复用进页面时缓存的那份。
     * 实测踩过：页面打开时拿到清单 A，期间服务端把包换成了 B（同为 1.0.0 版本号），
     * 于是下载到 B 的 zip、却拿 A 的 sha256 去校验，报
     * 「解压结果校验失败：NOTICE.txt」—— 用户看到的是一句像是"文件坏了"的话，
     * 真实原因却是"清单过期"。重拉一次清单的代价只有几 KB，却能避免整类误报。
     */
    private fun startInstall() {
        pauseRequested = false
        // 新的一轮：上一轮停止动作的残留抑制到此为止（见 suppressStaleBusy 注释）
        suppressStaleBusy = false
        setState(UiState.Downloading)
        tvStatus.text = "正在获取模块信息"
        // 声明在 launch 之前：协程的 finally 要拿它跟 runningJob 比对，
        // 以确认「结束的确实是这一次的任务」。若把 val job 直接写在 launch 的
        // 初始化式里，Kotlin 不允许在变量自身初始化中引用它（编译期 unresolved）。
        var thisJob: Job? = null
        thisJob = scope.launch {
            val m = runCatching {
                withContext(Dispatchers.IO) { MossDownloader.fetchManifest(this@MossModuleActivity) }
            }.getOrElse { e ->
                // 准备期（还没 enterBusy）就被停掉：这一轮什么都还没下。
                // 两个分支都不能走默认路径 —— 那句 toast 是给网络故障的，
                // 用户点暂停（或页面被销毁）不该看到"无法获取模块信息"。
                if (pauseRequested) {
                    setState(UiState.Paused)
                    return@launch
                }
                if (e is CancellationException) {
                    setState(UiState.NotInstalled)
                    return@launch
                }
                setState(UiState.NotInstalled)
                toast("无法获取模块信息：${e.message}")
                return@launch
            }
            manifest = m

            if (!MossDownloader.hasEnoughSpace(this@MossModuleActivity, m)) {
                setState(UiState.NotInstalled)
                AlertDialog.Builder(this@MossModuleActivity)
                    .setTitle("空间不足")
                    .setMessage(
                        "本次下载与安装峰值需要约 ${mb(MossDownloader.requiredPeakBytes(m))}，" +
                            "当前可用 ${mb(MossDownloader.availableBytes(this@MossModuleActivity))}。\n请先清理空间。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
                return@launch
            }

            // 空间也过了才宣布忙碌：拉清单失败/空间不足这两种情况下页面是**可重试**的，
            // 若此时 enterBusy，按钮会被禁用而用户什么也做不了。
            MossDownloader.enterBusy("准备下载")
            try {
                val archive = withContext(Dispatchers.IO) {
                    MossDownloader.download(this@MossModuleActivity, m, listener)
                }
                // 走到这里才认领"这一轮是本页面发起的"（决定 exitBusy 后要不要
                // refresh，见 [startedByThisPage] 注释）。必须放在 download() **之后**：
                // tryLock 失败时它立刻抛出，根本走不到这里 —— 那时认领了，
                // 等于替别人（已销毁页面）的那一轮刷新界面。
                startedByThisPage = true
                setState(UiState.Busy)
                tvStatus.text = "正在安装"
                withContext(Dispatchers.IO) {
                    MossDownloader.install(this@MossModuleActivity, archive, m, listener)
                }
                toast("模块安装完成")
                refresh()
            } catch (e: CancellationException) {
                // 走到这里只有两种可能：用户点了「暂停」，或 onDestroy 的
                // scope.cancel()（页面销毁）。两者都**保留已下载的分片**，下次续传。
                //
                // 早先这里调 clearDownloads() 把 .part 删掉了，与续传能力直接矛盾：
                // 725 MB 在移动网络下一旦中断，用户就得从 0 重来 ——
                // 而 MossDownloader.download() 在断流时明明特意保留了 .part
                // 并在错误信息里写着"已保留断点，可重试续传"。
                // 同一个功能里两处对"半成品怎么处理"给出相反答案，是设计缺陷不是小瑕疵。
                //
                // 代价：用户暂停后不回到这个页面，那部分空间会一直占着。
                // 下次进来会看到「继续下载（已完成 N MB）」，卸载模块时一并清理
                // （doUninstall 里仍然调 clearDownloads）。
                if (pauseRequested) {
                    // 暂停：回暂停态，主按钮变「继续」。
                    // 不弹 toast —— 界面本身的变化就是反馈，再弹一句是噪音。
                    Log.i(TAG, "已暂停下载，保留断点以便续传")
                    if (!isDestroyed) setState(UiState.Paused)
                    throw e
                }
                // 页面销毁：不弹 toast、不碰视图。跨页暂停时这一幕一定发生 ——
                // 传输是被新页面停掉的，旧页面的协程此时才收尾，
                // 任何提示都会和新页面的「已暂停」打架。
                Log.i(TAG, "页面已退出，保留断点以便续传")
                if (!isDestroyed) setState(UiState.NotInstalled)
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "安装失败", t)
                setState(UiState.NotInstalled)
                AlertDialog.Builder(this@MossModuleActivity)
                    .setTitle("安装失败")
                    .setMessage(t.message ?: t.javaClass.simpleName)
                    .setPositiveButton("重试") { _, _ -> startInstall() }
                    .setNegativeButton("关闭", null)
                    .show()
            } finally {
                // ⚠️ 这里**不能** exitBusy。
                // 用户退出页面时 scope.cancel() 会让本协程立刻走到 finally，
                // 而 download() 跑在 IO 线程的阻塞读循环里 —— 协程取消**不会**中断
                // 那个线程（它只查 Thread.interrupted），所以后台下载仍在跑。
                // 若在这里复位 busy，页面就会显示成可点的未安装态，用户一点就撞
                // tryLock 失败 —— 正是本次要修的那类"按钮状态与真实运行态不符"。
                // 收尾交给 MossDownloader 在锁真正释放的那一刻做。
                //
                // 协程真正结束时才置空，这才是「本页面这轮确实结束了」的时点。
                // 提前置空会让「暂停」少掉一道协程取消（关流仍能停传输，
                // 但协程要等 IO 自然收尾才结束，isDownloadInProgress 也会误判）。
                if (runningJob === thisJob) runningJob = null
            }
        }
        runningJob = thisJob
    }

    /**
     * 暂停下载：停掉传输、**保留断点**，主按钮变「继续」。
     *
     * 这就是本页唯一的"停止"入口 —— 停传输只需 close 流，断点已落在 .part 里，
     * 点「继续」走 Range 续传即可恢复，不需要第二个"放弃"按钮：两者机制完全相同
     * （都 close 流 + cancel 协程、都保留 .part），而暂停还能一键继续。
     * （曾经有一个「取消」按钮，但它在准备期会随 setState(Downloading) 闪现、
     * 安装期又随 stage 发布复现且停不下阻塞 IO，属于"按了没反应的按钮"，
     * 已按 v5 的原则移除。）
     *
     * 为什么不弹确认框：暂停是可逆的（断点还在，点「继续」就续传）。
     */
    private fun pauseDownload() {
        pauseRequested = true
        // 抑制这一轮残留的进度发布：read 不是立刻死的，最后一轮进度可能
        // 晚于下面的 setState(Paused) 投递到主线程（见 suppressStaleBusy 注释）。
        suppressStaleBusy = true
        // 关流是唯一能让阻塞中的 read 立刻返回的办法（协程 cancel 停不下 IO 线程）。
        // 跨页残留的下载没有本页 Job 可 cancel，正是靠这一步才停得下来。
        MossDownloader.cancelActiveTransfer()
        runningJob?.cancel()
        // 立即切暂停态，不等 StateFlow 的 exitBusy 回调：
        // 那个回调要等 IO 线程抛出、协程收尾后才到，用户会看到界面慢半拍。
        setState(UiState.Paused)
    }

    /** 下载/安装过程中的进度回调（后台线程） */
    private val listener = object : MossDownloader.ProgressListener {
        override fun onProgress(downloaded: Long, total: Long, bytesPerSecond: Long) {
            mainHandler.post {
                // ⚠️ onDestroy 里的 removeCallbacksAndMessages(null) **只能清当时已入队的**，
                // 而 725 MB 的下载循环停不下来（设计如此：退出页面仍继续下载），
                // 它会每 500ms 再 post 一次 —— 没有这道守卫，Activity 连同整棵 View 树
                // 会被强制持有着直到下载结束（移动网络下可能半小时）。
                if (isDestroyed) return@post
                if (total > 0) {
                    progressBar.progress = (downloaded * 1000 / total).toInt()
                    tvStatus.text = "正在下载 ${downloaded * 100 / total}%"
                    tvDetail.text = buildString {
                        append("${mb(downloaded)} / ${mb(total)}")
                        if (bytesPerSecond > 0) {
                            append("　${mb(bytesPerSecond)}/s")
                            val remain = (total - downloaded) / bytesPerSecond.coerceAtLeast(1)
                            append("　剩余约 ${remain / 60} 分 ${remain % 60} 秒")
                        }
                    }
                } else {
                    tvStatus.text = "正在下载"
                    tvDetail.text = mb(downloaded)
                }
            }
        }

        override fun onStage(stage: String) {
            mainHandler.post {
                if (isDestroyed) return@post
                tvStatus.text = stage
                if (stage.contains("校验") || stage.contains("解压") || stage.contains("安装")) {
                    // 校验/解压阶段没有精确进度，用不确定进度条表达"在干活"
                    progressBar.isIndeterminate = true
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
