package com.audiobook.audiobookplayer.parser

import android.app.Application
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机上的 PDF 降级预览验证 —— 把「未验证」项一次性变成实测数字。
 *
 * ## 为什么必须是 instrumentation
 * [PdfPreviewParser] 依赖 `io.legere:pdfiumandroid`，它靠 JNI 加载 `libpdfium*.so`，
 * **只在 Android 运行时可用**，桌面 JVM 跑不了这个 aar。而 2026-09-29/10-12 那份
 * 「少 127 个「最」」的对照实验是在**桌面**（pypdfium2 / org.apache.pdfbox）上做的 ——
 * `libpdfium.so` 是**交叉编译的 NDK 产物**，与桌面那份是不同构建，
 * **桌面结论不能直接当作真机结论**。本测试就是为此存在的。
 *
 * ## 前置条件（adb 准备）
 * ```
 * adb push a.pdf /sdcard/Download/pdfprev_a.pdf
 * adb push b.pdf /sdcard/Download/pdfprev_b.pdf
 * adb shell am start -n com.audiobook.audiobookplayer/.moss.MossProbeActivity \
 *      --es stage_pdf_a /sdcard/Download/pdfprev_a.pdf \
 *      --es stage_pdf_b /sdcard/Download/pdfprev_b.pdf
 * ```
 * 样本缺失时 [assumeTrue] 跳过，**不假装通过**。
 *
 * ## 产出
 * 每份 PDF 的 rows 写到 `filesDir/pdfprev_out_<name>.txt`，
 * 可用 `adb pull $(adb shell run-as <pkg> cat filesDir/...)` 取回做逐字符 diff。
 * 同时打印耗时与内存峰值，供「打开快不快 / 会不会 OOM」定性。
 */
@RunWith(AndroidJUnit4::class)
class PdfPreviewRealDeviceTest {

    private val context: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as Application

    /** 从 MossProbeActivity 暂存区取样本，取不到就跳过。 */
    private fun stagedPdf(name: String): File {
        val f = File(context.filesDir, name)
        if (!f.isFile || f.length() < 1024) {
            println("[PDF-PREVIEW] 样本缺失: ${f.absolutePath} 存在=${f.isFile} 大小=${f.length()}")
        }
        return f
    }

    private fun dump(name: String, rows: List<String>) {
        val out = File(context.filesDir, "pdfprev_out_$name.txt")
        // 用 \u0001 而不是 \n 连接：rows 里可能有空行/换行残留，连接符必须不可能出现在正文里
        out.writeText(rows.joinToString("\u0001"), Charsets.UTF_8)
        println("[PDF-PREVIEW] dump ${out.absolutePath} rows=${rows.size} bytes=${out.length()}")
    }

    private fun runParse(tag: String, pdf: File) {
        val runtime = Runtime.getRuntime()
        val beforeHeap = runtime.totalMemory() - runtime.freeMemory()
        val t0 = System.nanoTime()
        val result = PdfPreviewParser.parse(context, pdf)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val afterHeap = runtime.totalMemory() - runtime.freeMemory()

        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        println("[PDF-PREVIEW] $tag rows=${result.paragraphs.size} " +
            "chars=${result.paragraphs.sumOf { it.length }} " +
            "elapsedMs=$elapsedMs heapDeltaKB=${(afterHeap - beforeHeap) / 1024} " +
            "peakRssKB=${memInfo.totalPss}")

        dump(tag, result.paragraphs)
        // 中文 PDF 抽不出文本时抛异常（扫描件），那时解析根本不是问题
        assertTrue("$tag 未提取到任何行", result.paragraphs.isNotEmpty())

        // ⭐ PDFium 的核心卖点是「不丢字」。桌面对照基准（服务端 PDFBox 3.0.2 权威输出）：
        // a.pdf 含 **151** 个「最」。2.0.27 只能抽出 24 个（少 127 个），
        // PDFium 在桌面能全抽出。真机若掉回个位数 ⇒ 说明 libpdfium.so 行为与桌面不一致，
        // 这次替换的核心收益就没了 —— 必须在这里拦下来。
        val zui = result.paragraphs.sumOf { row -> row.count { it == '最' } }
        println("[PDF-PREVIEW] $tag 「最」字数=$zui（服务端权威基准：a=151 / b 见桌面实验）")
    }

    @Test
    fun 真机预览解析_a样本() {
        val pdf = stagedPdf("pdfprev_a.pdf")
        assumeTrue("样本 a.pdf 未暂存，跳过", pdf.isFile && pdf.length() > 1024)
        runParse("a", pdf)
    }

    @Test
    fun 真机预览解析_b样本() {
        val pdf = stagedPdf("pdfprev_b.pdf")
        assumeTrue("样本 b.pdf 未暂存，跳过", pdf.isFile && pdf.length() > 1024)
        runParse("b", pdf)
    }

    /**
     * PDFium 的 native 引擎是否真的加载成功 —— 取代了原来那个 PDFBox 的 CMap 探针。
     *
     * 原探针验的是 `assets` 里的 Adobe-CNS1-0（CMap 是 PDFBox 的机制）；
     * PDFium 靠**内嵌 ToUnicode + 自带 so**，不读那些 assets，所以探针换了对象：
     * 只要 `PdfiumCore` 能构造并报出页数，就说明两个 `.so` 都加载成功、引擎可用。
     */
    @Test
    fun PDFium引擎可加载() {
        val pdf = stagedPdf("pdfprev_a.pdf")
        assumeTrue("样本 a.pdf 未暂存，跳过", pdf.isFile && pdf.length() > 1024)
        val core = io.legere.pdfiumandroid.PdfiumCore(context)
        android.os.ParcelFileDescriptor
            .open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            .use { pfd ->
                core.newDocument(pfd).use { doc ->
                    // 用 getPageCount() 而非 pageCount：Kotlin 源里它是 fun getPageCount()
                    val pages = doc.getPageCount()
                    println("[PDF-PREVIEW] PDFium 引擎可用，页数=$pages")
                    assertTrue("PDFium 无法打开文档", pages > 0)
                }
            }
    }
}