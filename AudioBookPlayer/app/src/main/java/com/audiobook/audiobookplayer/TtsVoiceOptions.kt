package com.audiobook.audiobookplayer

/**
 * 「预设音色」下拉（TtsFragment 里的 spinnerDefaultVoice）在**两种合成方式**下的
 * 内容与选中态判定。
 *
 * ## 为什么这一个下拉要装两套东西
 *
 * 需求（游客视角）：合成方式选「本机」时，「预设音色」分支里要能选**本机音色**
 * （模块内置音色），而不是让游客面对一个永远无效的云端音色。
 * 于是同一个下拉承担两种角色：
 *
 * | 合成方式 | 下拉里的内容 | 选中后写入 |
 * |---|---|---|
 * | 在线 | 服务端预设音色（name (voiceId)） | selectedDefaultVoiceId（voiceId） |
 * | 本机 | 模块内置音色（音色名，如 Junhao） | localVoiceName（音色名） |
 *
 * ## 为什么必须把判定抽成纯函数
 *
 * 两套内容共用一个 Spinner 控件，最容易出的错是**串味**：
 * 切模式时选中的位置没按各自记忆恢复，于是把 Junhao（音色名）
 * 当成 voiceId 提交给服务端 —— 服务端不认，本机音色也没选上，
 * 用户看到的是「我明明选了 Junhao，怎么没生效」。
 * 这类错不崩溃、只是音色不对，靠肉眼很难发现，必须用单测钉死。
 *
 * ## 互斥性
 *
 * 「我的参考音频库」与「预设音色」是**来源下拉里的两个互斥分支**，
 * 切分支即清掉另一分支的选中（见 TtsFragment 来源下拉监听），
 * 所以本机模式下「用参考音频克隆」与「用内置音色」天然不会同时生效 ——
 * 引擎那边也是「有参考音频就用参考音频，否则用内置音色」。
 */

/** 本机音色下拉的占位项：模块没装时列表里只有这一条，且下拉置灰 */
internal const val LOCAL_VOICE_PLACEHOLDER = "（模块未安装，暂无内置音色）"

/** 在线预设音色下拉的占位项：还没选具体音色（对应空 voiceId） */
internal const val SERVER_VOICE_PLACEHOLDER = "（请选择预设音色）"

/** 这个下拉当前装的是哪一套内容 */
internal enum class PresetVoiceSource {
    /** 服务端预设音色（在线合成用） */
    SERVER,

    /** 模块内置音色（本机合成用） */
    LOCAL
}

/**
 * 「预设音色」下拉的完整状态：装什么、选中哪一项、能不能点。
 *
 * @param source 当前内容来源（在线=服务端音色，本机=模块内置音色）
 * @param items 下拉里的展示项（逐项对应下拉位置）
 * @param selectedIndex 当前应选中的位置
 * @param enabled 下拉是否可点：本机模式没装模块时列表只有占位项 → 置灰
 */
internal data class PresetVoiceOptions(
    val source: PresetVoiceSource,
    val items: List<String>,
    val selectedIndex: Int,
    val enabled: Boolean
)

/**
 * 「参考音频来源」下拉的两个选项文案。
 *
 * 项内带「参考音频来源-」前缀：标题已与下拉同行，项内再点明所属字段，
 * 用户不用回头看标题就知道自己在选哪个字段。
 */
internal const val SOURCE_LABEL_LIBRARY = "参考音频来源-我的参考音频库"
internal const val SOURCE_LABEL_PRESET = "参考音频来源-预设音色"

/**
 * 「参考音频来源」下拉的**固定**选项（两个互斥分支，任何合成方式都一样）。
 *
 * ## 为什么不再按登录状态变化
 *
 * 这里曾经是 `computeReferenceSourceOptions(isLocalMode, isGuest)`：在线 + 游客时
 * 藏掉「预设音色」。理由是当时服务端音色清单匿名访问 401，游客点开那个分支
 * 只会看到一个永远加载不出来的空下拉 —— 与其给空框，不如不给选项。
 *
 * 现在这个前提没了：服务端已把 `api/tts/voices` 改为公开（音色清单是配置项、
 * 不含任何用户数据），客户端另有内置清单兜底（见 [BUILTIN_SERVER_VOICES]），
 * 游客也能看见有哪些音色可选。
 *
 * **「看得见」与「用得了」是两件事**：在线合成建任务要登录、要积分，那道门在
 * `startOnlineSynthesis` 的 `requireLogin`，不必在选项层就把音色藏起来 ——
 * 藏起来只会让游客连「有什么音色可选」都不知道。
 */
internal val SOURCE_OPTIONS = listOf(SOURCE_LABEL_LIBRARY, SOURCE_LABEL_PRESET)

/**
 * 服务端预设音色（id + 中文名）。
 *
 * @param name 中文名（如「新闻女声（标准播音）」）
 * @param id 服务端音色 id（如「demo-1」；必须是引擎 demo.jsonl 里真实存在的 id）
 */
internal data class ServerVoice(val name: String, val id: String)

/**
 * **内置**的预设音色清单（兜底用）。
 *
 * ## 为什么要有它
 *
 * 清单的权威来源是服务端 `api/tts/voices`。服务端已把该接口改为公开
 * （见 AudioBookServer 的 SecurityConfig：音色清单是配置项，不含用户数据），
 * 但**服务端与客户端不可能同一时刻升级** —— 老服务端对游客仍返回 401，
 * 此时游客的「预设音色」下拉又变成一个只有占位项的空框。
 *
 * 所以客户端内置一份与服务端默认配置（`moss.tts.voices`）一致的清单兜底：
 * 服务端返回时以服务端为准，返回失败（401/网络不通）时用内置的。
 * 用户永远看得见有哪些音色可选。
 *
 * ## ⚠️ 清单里的 id 必须与线上引擎的真实音色一致
 *
 * MOSS-TTS-Nano 的预置音色叫 **demo**（`assets/demo.jsonl`，共 demo-1…demo-29），
 * 服务端把 id 原样透传给引擎的 `demo_id` 字段。
 * 原先内置的是 `xiaoyan / xiaoyu / aisjiuxu …` —— 那是**云端 MOSS API** 的音色 id，
 * 引擎侧根本不存在，选中后必然被引擎 400 拒掉
 * （`demo_id is required unless prompt speech is uploaded`）。
 * 这份清单是兜底显示用的，一旦与服务端不一致，用户选到的就是一个必然失败的选项。
 *
 * 与服务端默认值的对应关系：
 * `moss.tts.voices=demo-1,demo-2,demo-3,demo-4,demo-5,demo-6`
 * 服务端改了这个配置时，客户端内置清单会过时 —— 但那只影响「服务端不可达」
 * 时的兜底显示，联网时始终以服务端为准。
 */
internal val BUILTIN_SERVER_VOICES = listOf(
    ServerVoice("新闻女声（标准播音）", "demo-1"),
    ServerVoice("温柔女声（深夜电台）", "demo-2"),
    ServerVoice("台湾腔女声", "demo-3"),
    ServerVoice("京味男声（胡同闲聊）", "demo-4"),
    ServerVoice("儒雅男声（知识讲堂）", "demo-5"),
    ServerVoice("都市女声（独白）", "demo-6")
)

/**
 * 计算「预设音色」下拉在当前状态下该显示什么。
 *
 * @param isLocalMode 合成方式是否为「本机」
 * @param localVoices 模块内置音色清单（MossModuleManager.builtinVoices）
 * @param localVoicesLoaded 内置音色清单是否已读过一轮（区分「还没读」与「读了但为空 = 模块没装」）
 * @param selectedLocalVoice 本机模式当前选中的内置音色名
 * @param serverDisplayList 服务端预设音色的展示名（含首项占位）
 * @param serverVoiceIds 服务端预设音色的 id（与 serverDisplayList 同序，首项为空串表示占位）
 * @param selectedServerVoiceId 在线模式当前选中的服务端音色 id
 */
internal fun computePresetVoiceOptions(
    isLocalMode: Boolean,
    localVoices: List<String>,
    localVoicesLoaded: Boolean,
    selectedLocalVoice: String,
    serverDisplayList: List<String>,
    serverVoiceIds: List<String>,
    selectedServerVoiceId: String?
): PresetVoiceOptions {
    if (isLocalMode) {
        // 模块没装（或清单还没读出来）时，列表里只留一个占位项并置灰：
        // 用户看到的是「有这个位置、但要先装模块」，而不是一个空框。
        val items = if (localVoices.isEmpty()) listOf(LOCAL_VOICE_PLACEHOLDER) else localVoices
        // 选中态按**名字**找回：位置对不上就落到首项（清单顺序变了也不怕）。
        val idx = localVoices.indexOf(selectedLocalVoice).takeIf { it >= 0 } ?: 0
        return PresetVoiceOptions(
            source = PresetVoiceSource.LOCAL,
            items = items,
            selectedIndex = idx,
            enabled = localVoices.isNotEmpty()
        )
    }
    // 在线：服务端预设音色。清单还没回来时先给占位项，
    // 否则游客点开这个下拉会看到空框，不知道是该等还是坏了。
    val items = serverDisplayList.ifEmpty { listOf(SERVER_VOICE_PLACEHOLDER) }
    val idx = serverVoiceIds.indexOf(selectedServerVoiceId).takeIf { it >= 0 } ?: 0
    return PresetVoiceOptions(
        source = PresetVoiceSource.SERVER,
        items = items,
        selectedIndex = idx,
        enabled = true
    )
}
