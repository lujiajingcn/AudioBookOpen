package com.audiobook.audiobookplayer.moss

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiobook.audiobookplayer.BookViewModel
import com.audiobook.audiobookplayer.VoicePreviewManager
import com.audiobook.audiobookplayer.model.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真机上的本机合成验证 —— 重点是**暂停/恢复**这条从未在真机跑过的链路。
 *
 * ## 为什么必须是 instrumentation 测试
 * 两个限制把它逼成了唯一可行的办法：
 *
 * 1. **模拟器验不了**：4 GB 模拟器上本机合成跑到首段之前就被 lowmemorykiller 杀掉
 *    （实测 RSS 3.1 GB），根本没有机会点暂停。
 * 2. **真机点不了**：实测小米/MIUI 默认禁止 adb 注入输入事件
 *    （`SecurityException: Injecting to another application requires INJECT_EVENTS`），
 *    而 [LocalSynthesisService] 是 `exported=false`（这是正确的安全设置，不该为了测试放开），
 *    所以 `input tap` 与 `am start-foreground-service` 两条路都走不通。
 *
 * instrumentation 测试跑在设备本进程里，可以直接构造 [BookViewModel]、
 * 直接驱动 [MossLocalSynthesizer] 并读写同一个 [PauseController]，
 * 于是**不需要任何点击**就能验证真机行为。
 *
 * ## 前置条件
 * 设备上需已装好模型模块，以及一本多段落的电子书。用 adb 准备：
 *   adb shell am start -n <pkg>/.moss.MossProbeActivity --ez auto_stage true
 *   adb push test.txt /sdcard/Download/test.txt
 * 缺任意一项时用 [assumeTrue] 跳过，而不是假装通过。
 */
@RunWith(AndroidJUnit4::class)
class LocalSynthesisRealDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as Application

    /**
     * 测试用电子书（3 段约 6 个合成段，够长以便观察暂停）。
     *
     * ⚠️ 写进**应用私有目录**而不是 /sdcard/Download：
     * 第一版把书推到外部存储，结果 instrumentation 进程读它时报
     * `EACCES (Permission denied)` —— Android 10+ 的分区存储下，
     * 测试进程并不自动拥有该路径的读权限（应用本体有，但测试进程的上下文不同）。
     * 私有目录由测试自己写、应用自己读，不依赖任何存储权限。
     */
    private fun prepareBook(): File {
        val f = File(context.filesDir, "rdtest.txt")
        if (!f.isFile || f.length() < 100) {
            val base = listOf(
                "清晨的阳光透过窗帘的缝隙洒进房间，在地板上投下一道细长的光带，他睁开眼先听见远处传来的鸟鸣。",
                "街角的旧书店已经开了三十年，木质招牌被风雨磨得发白，店主是个沉默的老人每天准时九点开门。",
                "雨下了一整夜，屋檐的水滴声到天亮才渐渐停下，推开窗空气里全是泥土和青草混在一起的味道。",
                "图书馆的下午总是很安静，只听得见翻书的声音和偶尔的脚步声，她坐在靠窗的位置看了三个小时。"
            )
            val paras = (0 until 3).map { base.joinToString("") }
            f.writeText(paras.joinToString("\n\n") + "\n", Charsets.UTF_8)
        }
        return f
    }

    @Test
    fun 暂停期间不再推进_恢复后跑完且不丢段() = runBlocking {
        // 把前置条件打出来：用例被跳过时最需要知道的就是"到底哪个条件不满足"，
        // 否则只会看到一个 SKIP，看不出是模型没装、书没推、还是路径写错。
        val installed = MossModuleManager.isInstalled(context)
        println("[RD-TEST] isInstalled=$installed 缺失=${MossModuleManager.missingFiles(context)}")
        val file = prepareBook()
        println("[RD-TEST] 测试书 ${file.absolutePath} 存在=${file.isFile} 大小=${file.length()}")
        assumeTrue("模块未安装，跳过", installed)
        assumeTrue("测试书准备失败，跳过", file.isFile)
        val bookPath = file.absolutePath

        val controller = PauseController()
        val done = AtomicInteger(0)
        val finished = CountDownLatch(1)
        val failed = java.util.concurrent.atomic.AtomicReference<String?>(null)

        // synthesizeBook 现在直接接 BookStorage（不再经 ViewModel）——
        // 入库职责被抽到了 storage 层，ViewModel 只是 UI 侧的薄封装。
        val book = Book(id = 0, tableName = "rdtest.txt", displayName = "rdtest.txt", filePath = bookPath)

        val worker = Thread {
            // 这个 lambda 不是 suspend 的，普通 Thread 里没有协程上下文，
            // 因此要再套一层 runBlocking 才能调用 suspend 的 synthesizeBook。
            runBlocking {
                try {
                    val result = MossLocalSynthesizer.synthesizeBook(
                    context = context,
                    book = book,
                    storage = com.audiobook.audiobookplayer.BookStorage(context),
                    voice = MossLocalEngine.DEFAULT_VOICE,
                    parallelism = 1,          // 固定 1：本测试验证的是暂停语义，不是并行
                    pauseController = controller,
                    listener = object : MossLocalSynthesizer.ProgressListener {
                        override fun onParsed(totalSegments: Int, alreadyDone: Int) {
                            // alreadyDone 是续跑起点：首次为 0，续跑时为本地已有段数。
                            // 把它打出来才能在真机日志里确认「续跑没把进度归零」。
                            println("[RD-TEST] 解析完成，共 $totalSegments 段，本地已有 $alreadyDone 段")
                        }

                        override fun onProgress(done_: Int, total: Int, etaMs: Long) {
                            done.set(done_)
                            println("[RD-TEST] 进度 $done_/$total")
                        }

                        override fun onStage(stage: String) {
                            println("[RD-TEST] 阶段: $stage")
                        }
                    },
                    isCancelled = { false }
                )
                if (result is MossLocalSynthesizer.Result.Failed) failed.set(result.message)
            } catch (t: Throwable) {
                failed.set(t.message ?: t.javaClass.simpleName)
            } finally {
                finished.countDown()
            }
            }
        }
        worker.isDaemon = true
        worker.start()

        // ---- 尽快暂停：此时通常还在加载模型（真机实测约 4 秒），
        //      加载完成前不会有任何段产出，因此这是"暂停生效"的最干净观察点。
        Thread.sleep(500)
        controller.pause()
        println("[RD-TEST] 已暂停，等待观察窗口…")

        // 观察窗口：给足时间让引擎加载完（真机约 4 秒）并尝试取段。
        // 若闸门失效，这几秒内必然会有段被合成完 —— 因为单段合成只要约 1 秒。
        Thread.sleep(20_000)
        val atPause = done.get()
        println("[RD-TEST] 暂停 20 秒后已完成 $atPause 段（期望 0）")
        assertEquals("暂停期间不应有任何段完成", 0, atPause)

        // ---- 恢复：应当把剩余的段全部跑完
        controller.resume()
        println("[RD-TEST] 已恢复")
        val ok = finished.await(10, TimeUnit.MINUTES)
        assertTrue("恢复后应在 10 分钟内跑完", ok)

        val total = done.get()
        println("[RD-TEST] 最终完成 $total 段，失败=${failed.get()}")
        assertEquals("不应有失败", null, failed.get())
        assertTrue("应至少合成出 1 段", total >= 1)
    }

    /**
     * 「试听原声」绝不能删掉用户的参考音频文件。
     *
     * ## 为什么专门测这一条
     * [com.audiobook.audiobookplayer.VoicePreviewManager.playPreview] 的原始语义是
     * "播放试听合成的临时 WAV"，因此它会在播放结束/出错时**删除所播文件** ——
     * 对 cacheDir 里的临时产物这是对的。
     * 而「试听原声」播的是 filesDir/referenceAudio 下的**正式数据**：
     * 若照搬那段删除逻辑，用户听一次自己的录音文件就没了，
     * 且现象是"第二次点提示文件已丢失"，很难第一时间联想到播放器。
     *
     * 这条测试用真实 MediaPlayer 跑完整生命周期
     * （prepare → play → 自然播完 → onCompletion），断言文件仍然存在；
     * 比"点 UI 看有没有报错"可靠得多 —— 文件被删掉时界面**没有任何提示**。
     */
    @Test
    fun 试听原声不能删除用户的参考音频文件() {
        val ctx = context
        // 造一个真实可解码的 WAV（静音亦可，MediaPlayer 只要求格式合法）
        val refFile = File(ctx.filesDir, "ref_original_guard_test.wav")
        writeSilentWav(refFile, seconds = 1)
        val sizeBefore = refFile.length()
        println("[RD-TEST] 参考音频测试文件: ${refFile.absolutePath} ($sizeBefore 字节)")

        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VoicePreviewManager.playPreview(
                refFile,
                token = "original:guardtest",
                deleteOnFinish = false          // ← 被测的关键参数
            ) { playing ->
                if (playing) started.countDown() else finished.countDown()
            }
        }

        assertTrue("播放应能开始", started.await(15, TimeUnit.SECONDS))
        assertTrue("1 秒音频应在 30 秒内播完", finished.await(30, TimeUnit.SECONDS))
        Thread.sleep(500)   // 给 onCompletion 里的清理留时间

        assertTrue("播放结束后文件必须仍然存在", refFile.exists())
        assertEquals("文件大小不应变化", sizeBefore, refFile.length())
        println("[RD-TEST] 自然播完后文件仍在，大小 ${refFile.length()} 字节 ✅")

        // 「用户主动停止」是另一个会误删的入口：stopInternal 原先也有一行无条件删除。
        val refFile2 = File(ctx.filesDir, "ref_original_guard_test2.wav")
        writeSilentWav(refFile2, seconds = 60)   // 够长，保证还没播完就停
        val started2 = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            VoicePreviewManager.playPreview(
                refFile2, token = "original:guardtest2", deleteOnFinish = false
            ) { if (it) started2.countDown() }
        }
        assertTrue("第二次播放应能开始", started2.await(15, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { VoicePreviewManager.stop() }
        Thread.sleep(500)
        assertTrue("停止播放后文件也必须仍然存在", refFile2.exists())
        println("[RD-TEST] 主动停止后文件仍在 ✅")

        refFile.delete()
        refFile2.delete()
    }

    /** 写一个 16bit 单声道静音 WAV，供 MediaPlayer 解码（避免依赖任何外部素材） */
    private fun writeSilentWav(file: File, seconds: Int, sampleRate: Int = 16000) {
        val dataSize = sampleRate * seconds * 2
        java.io.DataOutputStream(java.io.BufferedOutputStream(file.outputStream())).use { out ->
            fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            fun le32(v: Int) { le16(v and 0xFFFF); le16((v shr 16) and 0xFFFF) }
            out.write("RIFF".toByteArray(Charsets.US_ASCII)); le32(36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII)); le32(16)
            le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            out.write("data".toByteArray(Charsets.US_ASCII)); le32(dataSize)
            out.write(ByteArray(dataSize))
        }
    }

    /**
     * 暂停中「中止」必须能把 worker 放出来。
     *
     * 这是暂停功能里最容易写错、后果又最严重的一处：若 [PauseController.awaitResume]
     * 只等"恢复"信号而不看中止标志，那么"暂停状态下点中止"会让 worker
     * **永远挂起** —— 服务既不结束也不释放内存，通知栏一直挂着，
     * 用户以为还在跑。比崩溃更难排查。
     */
    @Test
    fun 暂停中中止必须能结束_不能永久挂起() {
        val controller = PauseController()
        controller.pause()

        val released = CountDownLatch(1)
        val got = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = Thread {
            got.set(controller.awaitResume { true })   // isCancelled = true
            released.countDown()
        }
        t.isDaemon = true
        t.start()

        assertTrue("暂停中调用 releaseAll 后必须放行", released.await(5, TimeUnit.SECONDS))
        assertEquals("被中止时应返回 false", false, got.get())
    }

    /**
     * 孤儿任务对账：进程被杀后留下的「合成中」必须在下次 loadTasks 时落定为 partial。
     *
     * 这条来自真机上实际观察到的现象：书架里挂着 `本机合成 · Junhao | 合成中 | 4 / 31`，
     * 但模块根本没装 —— 说明 App 在合成途中被杀，而任务状态永远停在「合成中」。
     */
    @Test
    fun 孤儿任务应被对账为部分完成() = runBlocking {
        val viewModel = BookViewModel(context)
        val repo = com.audiobook.audiobookplayer.TaskRepository(context)

        // 书名带上时间戳：saveTask 每次都插新行，用固定书名会在重复运行时
        // 攒下多条同名记录，按书名取「第一条」就未必是本次造的那条。
        val bookName = "孤儿任务测试书-${System.currentTimeMillis()}"

        // 造一条"进程被杀时留下的"本机合成任务：processing + 本机合成前缀
        val taskId = System.currentTimeMillis()
        viewModel.saveTask(bookName, LocalSynthesisService.audioName("Junhao"), taskId)
        viewModel.updateTaskStatus(taskId, "processing", 4, 31)

        // 关键：从仓库把这条任务**读回来**再交给对账，而不是手工构造 Task。
        //
        // Task.id 是 tasks 表的**自增主键**，而 saveTask 传进去的那个是
        // **task_id 列**（业务 id，毫秒时间戳）—— 两个不同的字段。
        // 手工构造 Task 时若图省事把业务 id 填进 id 字段，对账就会按 rowid
        // 去 `WHERE id = ?`，命中 0 行且不报错；而断言因为「构造值刚好等于
        // 写入值」照样通过 —— 于是「UPDATE 全部落空、孤儿任务永远卡在合成中」
        // 这个真正的 bug 被测试放行了。必须让被测对象来自真实的数据形态。
        val orphan = repo.getTasks(emptySet()).first { it.bookName == bookName }
        println("[RD-TEST] 读回任务：rowid=${orphan.id} status=${orphan.status}")

        // 直接调对账逻辑（内部可见），不等 LiveData 的异步投递 ——
        // 上一版靠 sleep + tasks.value 断言，读到 null 而偶发失败，
        // 那是在测"协程调度快不快"，不是在测对账本身。
        val changed = viewModel.reconcileOrphanLocalTasks(listOf(orphan))
        println("[RD-TEST] 对账是否发生改动=$changed")

        // 用仓库直读，绕开 LiveData 时序。
        // 断言仍按 bookName 定位：rowid 只在本次断言里有意义，
        // 写死它等于把「用了哪个 ID 空间」又变成测试的前提。
        val after = repo.getTasks(emptySet()).firstOrNull { it.bookName == bookName }
        println("[RD-TEST] 本条 status=${after?.status} 进度=${after?.completedSegments}/${after?.totalSegments} 阶段=${after?.progressText}")

        assertTrue("应识别出孤儿任务并改写", changed)
        assertEquals("孤儿任务应被改为 partial", "partial", after?.status)
        assertEquals("已完成的段数应保留", 4, after?.completedSegments)
        assertEquals("过期的阶段文案应被清掉", null, after?.progressText)
    }
}
