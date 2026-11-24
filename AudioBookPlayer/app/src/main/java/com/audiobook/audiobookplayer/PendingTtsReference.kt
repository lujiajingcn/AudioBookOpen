package com.audiobook.audiobookplayer

/**
 * 跨 Fragment 传递「想用哪条参考音频朗读」的一次性意图。
 *
 * 「发现 - 参考音频」榜单上的「用这个声音读我的书」会把选中的分享音频
 * （先确保已导入本地库、并上报一次使用量）暂存到这里，再跳到 TTS 页；
 * TTS 页在 onViewCreated 里 [consume] 取出并预选该音色。
 *
 * 用静态单例而非 Bundle：TTS 与发现页分处不同的 Fragment 容器，且以 replace 装载、
 * 跨返回栈保留，Bundle 传递不便；一次性意图用完即清，避免回看历史时重复预选。
 */
object PendingTtsReference {
    data class Payload(val serverId: Long, val name: String)

    @Volatile
    private var pending: Payload? = null

    fun set(payload: Payload) {
        pending = payload
    }

    /** 取出并清空（一次性意图） */
    fun consume(): Payload? {
        val p = pending
        pending = null
        return p
    }
}
