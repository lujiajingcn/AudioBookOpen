package com.audiobook.audiobookplayer.model

data class Task(
    val id: Long = 0,
    val bookName: String,
    val audioName: String,
    val status: String = "processing",
    val progress: Int = 0,
    val totalSegments: Int = 0,
    val completedSegments: Int = 0,
    /**
     * 当前阶段文案（如「正在加载模型 1/1」「正在解析电子书」）。
     *
     * 存在的理由：本机合成在首段推理之前要先加载模型，实测 13~42 秒，
     * 这期间 completedSegments 恒为 0 —— 只显示「x / y」的话，用户看到的是
     * 一个纹丝不动的「0 / N」，很容易以为点了没反应或卡死了。
     * 为 null = 无阶段信息（在线合成的任务，或 v7 之前的存量记录）。
     */
    val progressText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val audioFilesPath: String? = null,
    val serverTaskId: Long? = null,
    val hasAudio: Boolean = false,
    val downloadStatus: String? = null,
    val downloadProgress: Int = 0,
    val errorMessage: String? = null
) {
    fun getTaskId(): Long = id

    /**
     * 调用服务端 /api/tts/task/{taskId} 系列接口时使用的任务 ID。
     *
     * 服务端 ID 与本地 id 是两个完全不同的 ID 空间：本地 id 是 tasks 表的 rowid，
     * 拿它去查服务端接口必然 404。因此这里只返回 serverTaskId，不再回退到本地 id。
     *
     * 返回 null 表示服务端 ID 尚未回填（例如上传完成后用户立刻切走了页面），
     * 调用方应跳过请求并给出明确提示，而不是发出注定失败的请求。
     */
    fun getQueryId(): Long? = serverTaskId
}