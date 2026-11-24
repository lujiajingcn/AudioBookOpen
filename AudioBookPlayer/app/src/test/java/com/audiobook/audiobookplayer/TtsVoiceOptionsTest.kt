package com.audiobook.audiobookplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「预设音色」下拉**双模式**的判定规则测试。
 *
 * 背景：游客在「语音合成」页选「合成方式=本机」时，原来那个「预设音色」分支里装的是
 * 服务端云端音色（voiceId）—— 而本机引擎压根不读 voiceId，游客选了个寂寞。
 * 现在的结构：**同一个下拉**在两种合成方式里装两套东西
 * （在线=服务端音色，本机=模块内置音色），两套内容各记各的选中态。
 *
 * 为什么必须有测试：两套内容共用一个 Spinner 控件，最容易出的错是**串味** ——
 * 切模式时选中的位置没按各自记忆恢复，于是把 Junhao（音色名）当成 voiceId 提交给服务端。
 * 不崩溃、只是音色不对，真机上手工点一遍很难稳定复现，只能靠单测钉死。
 */
class TtsVoiceOptionsTest {

    /** 模块内置音色清单（真机上是 Junhao 等） */
    private val localVoices = listOf("Junhao", "Xiaoyan")

    /** 服务端预设音色的展示名与 id（首项是占位） */
    private val serverDisplay = listOf("（请选择预设音色）", "云希 (v_001)", "晓晓 (v_002)")
    private val serverIds = listOf("", "v_001", "v_002")

    private fun local(
        selectedLocalVoice: String = "Junhao",
        voices: List<String> = localVoices
    ) = computePresetVoiceOptions(
        isLocalMode = true,
        localVoices = voices,
        localVoicesLoaded = true,
        selectedLocalVoice = selectedLocalVoice,
        serverDisplayList = serverDisplay,
        serverVoiceIds = serverIds,
        selectedServerVoiceId = null
    )

    private fun server(selectedId: String? = null) = computePresetVoiceOptions(
        isLocalMode = false,
        localVoices = localVoices,
        localVoicesLoaded = true,
        selectedLocalVoice = "Junhao",
        serverDisplayList = serverDisplay,
        serverVoiceIds = serverIds,
        selectedServerVoiceId = selectedId
    )

    @Test
    fun `在线模式下拉里装服务端预设音色`() {
        val o = server("v_002")
        assertEquals(PresetVoiceSource.SERVER, o.source)
        assertEquals(serverDisplay, o.items)
        assertEquals("选中项要按 voiceId 找回", 2, o.selectedIndex)
        assertTrue(o.enabled)
    }

    @Test
    fun `本机模式下拉里装模块内置音色`() {
        val o = local(selectedLocalVoice = "Xiaoyan")
        assertEquals(PresetVoiceSource.LOCAL, o.source)
        assertEquals(localVoices, o.items)
        assertEquals("选中项要按音色名找回", 1, o.selectedIndex)
        assertTrue(o.enabled)
    }

    @Test
    fun `切模式不会把本机音色名当成voiceId`() {
        // 回归基线：两套内容共用一个控件时，最典型的错就是选中态串味。
        // 本机模式下选中的必须是**音色名**，它绝不能出现在服务端 voiceId 列表里。
        val l = local(selectedLocalVoice = "Junhao")
        assertFalse(l.items.any { it in serverIds })
        assertEquals(0, l.selectedIndex)

        // 再切回在线：选中的是 voiceId，跟音色名不是一回事。
        val s = server(selectedId = null)
        assertEquals("没选 voiceId 时落到占位项", 0, s.selectedIndex)
        assertEquals(SERVER_VOICE_PLACEHOLDER, s.items[0])
    }

    @Test
    fun `模块没装时本机音色下拉只有占位项且置灰`() {
        val o = local(voices = emptyList())
        assertEquals(listOf(LOCAL_VOICE_PLACEHOLDER), o.items)
        assertFalse("没有音色可选时不能让人点", o.enabled)
    }

    @Test
    fun `在线模式服务端清单还没回来时给占位项`() {
        val o = computePresetVoiceOptions(
            isLocalMode = false,
            localVoices = localVoices,
            localVoicesLoaded = true,
            selectedLocalVoice = "Junhao",
            serverDisplayList = emptyList(),
            serverVoiceIds = emptyList(),
            selectedServerVoiceId = null
        )
        assertEquals(listOf(SERVER_VOICE_PLACEHOLDER), o.items)
        assertEquals(0, o.selectedIndex)
    }

    @Test
    fun `选中的音色不在清单里时落到首项`() {
        val o = local(selectedLocalVoice = "已经被删掉的音色")
        assertEquals(0, o.selectedIndex)
    }

    @Test
    fun `两套内容的选中态各自独立_切来切去不丢选择`() {
        // 场景：在线选了 v_002 → 切本机选 Xiaoyan → 切回在线。
        // 期望：切回在线时仍是 v_002（各记各的），切回本机时仍是 Xiaoyan。
        val online = server("v_002")
        assertEquals(2, online.selectedIndex)
        val localAgain = local(selectedLocalVoice = "Xiaoyan")
        assertEquals(1, localAgain.selectedIndex)
        val onlineAgain = server("v_002")
        assertEquals(2, onlineAgain.selectedIndex)
    }

    @Test
    fun `来源下拉固定给两个分支_与合成方式和登录状态无关`() {
        // 回归基线：曾经按「在线 + 游客」藏掉「预设音色」，如今音色清单已公开
        // （服务端 SecurityConfig + 客户端内置兜底），游客也该看得见有哪些音色可选。
        // 「用不了」由 startOnlineSynthesis 的 requireLogin 把关，不在选项层藏。
        assertEquals(
            listOf(SOURCE_LABEL_LIBRARY, SOURCE_LABEL_PRESET),
            SOURCE_OPTIONS
        )
    }

    @Test
    fun `内置兜底音色清单与服务端默认配置一致`() {
        // 与服务端 TtsTaskController 里
        // @Value("${moss.tts.voices:demo-1,demo-2,demo-3,demo-4,demo-5,demo-6}")
        // 的默认值逐项对齐：老服务端对游客 401 时的兜底显示靠它，
        // id 对不上会让游客选了一个服务端不认的音色。
        //
        // ⚠️ 这些 id 必须与线上引擎的真实 demo 一致：MOSS-TTS-Nano 的预置音色
        // 叫 demo（demo-1…demo-29），服务端原值 xiaoyan 等是云端 API 的音色 id，
        // 引擎侧不存在，选中后必然被引擎 400 拒掉。
        assertEquals(
            listOf("demo-1", "demo-2", "demo-3", "demo-4", "demo-5", "demo-6"),
            BUILTIN_SERVER_VOICES.map { it.id }
        )
    }

    @Test
    fun `内置兜底清单的展示名非空且id不重复`() {
        // 兜底清单是硬编码常量，写错不会有编译错误、只会在真机上表现为
        // 「下拉里出现空白项 / 选中后提交了同一个 id 两次」。
        assertTrue(BUILTIN_SERVER_VOICES.isNotEmpty())
        assertTrue(BUILTIN_SERVER_VOICES.all { it.name.isNotBlank() && it.id.isNotBlank() })
        assertEquals(BUILTIN_SERVER_VOICES.size, BUILTIN_SERVER_VOICES.map { it.id }.distinct().size)
    }

    @Test
    fun `在线模式的占位项是首项_游客也不会看到空下拉`() {
        // 游客进入在线模式时 voiceDisplayList 至少是「占位 + 内置兜底清单」，
        // 不再是「只有占位项」的空下拉。
        val display = buildList {
            add(SERVER_VOICE_PLACEHOLDER)
            BUILTIN_SERVER_VOICES.forEach { add("${it.name} (${it.id})") }
        }
        val ids = buildList {
            add("")
            BUILTIN_SERVER_VOICES.forEach { add(it.id) }
        }
        // 选中一个**不在清单里**的旧音色 id：模拟「服务端换了音色清单，
        // 用户上次选的音色已经没了」（如旧的 xiaoyan → 现在的 demo-1）。
        // 此时应回落到占位项，而不是停在某个不存在的音色上。
        val o = computePresetVoiceOptions(
            isLocalMode = false,
            localVoices = localVoices,
            localVoicesLoaded = true,
            selectedLocalVoice = "Junhao",
            serverDisplayList = display,
            serverVoiceIds = ids,
            selectedServerVoiceId = "__not_in_list__"
        )
        assertEquals(1 + BUILTIN_SERVER_VOICES.size, o.items.size)
        assertEquals("占位项必须在首项", "", ids[0])
        assertEquals(SERVER_VOICE_PLACEHOLDER, o.items[0])
        assertEquals("选中的音色不在清单里时落到占位项", 0, o.selectedIndex)

        // 清单内的 id 能按 id 找回选中位置（换清单后仍要成立）。
        val firstRealId = BUILTIN_SERVER_VOICES.first().id
        val picked = computePresetVoiceOptions(
            isLocalMode = false,
            localVoices = localVoices,
            localVoicesLoaded = true,
            selectedLocalVoice = "Junhao",
            serverDisplayList = display,
            serverVoiceIds = ids,
            selectedServerVoiceId = firstRealId
        )
        assertEquals("选中项要按 id 找回", firstRealId, ids[picked.selectedIndex])
    }
}
