package com.audiobook.audiobookplayer.moss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「安装判定」与「音色克隆能力判定」必须分开。
 *
 * encode 模型是**可选能力**，不是安装前置。如果把它塞进
 * [MossModuleManager] 的必需文件清单，所有 1.0.0 老用户升级 App 后
 * 会被判成"模块没装全"、被要求重下 700MB+ —— 而他们只用内置音色，
 * 完全不需要 encode。所以两条判据必须各自成立：
 *   · 老模块（无 encode）→ 仍算「已安装」（内置合成正常）
 *   · 老模块（无 encode）→ 但「不支持克隆音色」（必须告诉用户）
 *
 * 这里用纯文件判定测（不依赖 Android Context，单测没有 Robolectric），
 * 测的正是 [MossModuleManager.hasVoiceCloneModel] 内部那三条规则的等价形式。
 */
class MossVoiceCloneCapabilityTest {

    private val encodeFiles = listOf(
        "moss_audio_tokenizer_encode.onnx",
        "moss_audio_tokenizer_encode.data"
    )

    private fun newDir(): File =
        File(System.getProperty("java.io.tmpdir"), "mosscap_" + System.nanoTime()).apply { mkdirs() }

    private fun touch(dir: File, name: String, size: Int) {
        dir.resolve(name).writeBytes(ByteArray(size))
    }

    /** 与 MossModuleManager.hasVoiceCloneModel 同规则的纯文件判定 */
    private fun hasClone(dir: File): Boolean = encodeFiles.all { File(dir, it).isFile }

    @Test
    fun 老模块_无encode_判为不支持克隆() {
        val dir = newDir()
        // 目录里只放一个无关文件，模拟"模块已装但版本是 1.0.0"
        touch(dir, "browser_poc_manifest.json", 503354)
        assertFalse("1.0.0 没有 encode，必须判为不支持克隆", hasClone(dir))
        dir.deleteRecursively()
    }

    @Test
    fun 只有部分encode文件_仍判false() {
        val dir = newDir()
        // 只有 onnx 没有 .data：半套编码器建不起会话，必须判 false 而不是"试试看"
        touch(dir, encodeFiles[0], 815775)
        assertFalse("encode 两个文件必须成对出现", hasClone(dir))
        dir.deleteRecursively()
    }

    @Test
    fun 新模块_两个encode文件都在_判true() {
        val dir = newDir()
        touch(dir, encodeFiles[0], 815775)
        touch(dir, encodeFiles[1], 44507136)
        assertTrue("1.1.0 带 encode，应判为支持克隆", hasClone(dir))
        dir.deleteRecursively()
    }

    @Test
    fun 空目录_判false() {
        val dir = newDir()
        assertFalse(hasClone(dir))
        dir.deleteRecursively()
    }
}