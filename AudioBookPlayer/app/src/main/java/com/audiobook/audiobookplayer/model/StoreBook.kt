package com.audiobook.audiobookplayer.model

import com.google.gson.annotations.SerializedName

/** 书城书籍（与服务端 StoreBook 实体对应） */
data class StoreBook(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("author") val author: String?,
    @SerializedName("category") val category: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("fileExtension") val fileExtension: String?,
    @SerializedName("fileSize") val fileSize: Long?,
    @SerializedName("coverColor") val coverColor: String?,
    @SerializedName("downloadCount") val downloadCount: Int?,
    /**
     * 关联的合成任务 id：管理员在「书城管理」合成语音后写入；NULL 表示尚未合成音频。
     * 用户端（消费端）下载音频时据此决定是否展示「下载音频」入口。
     */
    @SerializedName("taskId") val taskId: Long? = null,
    /**
     * 标签（一本书可有多个）。服务端保证不返回 null、没标签时是空数组；
     * 旧版服务端没有这个字段，Gson 会给 null，所以声明成可空并在展示侧兜底。
     *
     * 与 [category] 的区别：分类单选、决定书籍在服务器上的目录；标签多选、只用于展示与筛选。
     */
    @SerializedName("tags") val tags: List<String>? = null
)

/** 书城分类（含书籍数量） */
data class StoreCategory(
    @SerializedName("name") val name: String,
    @SerializedName("count") val count: Long
)

/** 书城标签（含该标签下的在售书籍数），结构与 [StoreCategory] 对齐 */
data class StoreTag(
    @SerializedName("name") val name: String,
    @SerializedName("count") val count: Long
)
