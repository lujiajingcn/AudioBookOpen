package com.audiobook.audiobookplayer.model

/**
 * 本地参考音频（音色素材）记录。
 *
 * 存放的是 App 私有目录下的一份**副本**（filesDir/reference_audio/），
 * 因此删除记录时会连带清理该副本，但**绝不会删除用户手机上的原始文件**——
 * 这条记录只代表"我的参考音频列表"里的一项，并不拥有原文件。
 *
 * @param filePath App 私有目录内的副本路径（不是用户选的原始文件 URI）
 * @param source   来源：[SOURCE_LOCAL] 从手机导入，[SOURCE_SERVER] 从管理员共享库下载，
 *                  [SOURCE_SHARED] 从「发现 - 参考音频」排行榜下载
 * @param serverId 非本地来源时对应的服务端 id，避免重复下载同一项。
 *                 注意 id 的**归属表由 source 决定**：
 *                 [SOURCE_SERVER] → 服务端 reference_audio 表（管理员共享库）
 *                 [SOURCE_SHARED] → 服务端 user_reference_audio 表（用户分享库）
 *                 两张表的 id 会撞号，所以必须连着 source 一起判断，不能只看 serverId。
 */
data class ReferenceAudio(
    val id: Long = 0,
    val name: String,
    val filePath: String,
    val source: String,
    val serverId: Long? = null,
    val description: String? = null,
    val fileSize: Long = 0,
    val createdAt: Long = 0
) {
    companion object {
        /** 从手机本地导入 */
        const val SOURCE_LOCAL = "local"

        /** 从管理员共享参考音频库下载 */
        const val SOURCE_SERVER = "server"

        /** 从「发现 - 参考音频」排行榜下载（**别人**分享的，本人无权分享/取消分享） */
        const val SOURCE_SHARED = "shared"

        /**
         * 本人上传到服务端并分享的（serverId 指向**自己**的 user_reference_audio 条目）。
         *
         * 与 [SOURCE_SHARED] 的区别：两者 serverId 都属于 user_reference_audio 表，
         * 但归属不同——服务端对非本人条目会拒绝分享操作，客户端必须能提前区分，
         * 否则用户会点出一个永远失败的按钮。
         */
        const val SOURCE_MINE = "mine"
    }

    /** 是否从服务端下载得来（管理员库或用户分享库都算） */
    val isFromServer: Boolean get() = source != SOURCE_LOCAL

    /** 是否来自用户分享排行榜（别人的分享） */
    val isFromSharedLibrary: Boolean get() = source == SOURCE_SHARED

    /**
     * 是否可以分享：本地导入（需先上传）或本人已上传的条目。
     * 从管理员库/排行榜下载的都不是本人的，服务端会拒绝，所以入口不给。
     */
    val canShare: Boolean get() = source == SOURCE_LOCAL || source == SOURCE_MINE
}
