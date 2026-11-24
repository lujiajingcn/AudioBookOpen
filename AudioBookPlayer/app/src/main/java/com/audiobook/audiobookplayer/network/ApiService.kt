package com.audiobook.audiobookplayer.network

import com.audiobook.audiobookplayer.model.AuthResponse
import com.audiobook.audiobookplayer.model.CreateCommentRequest
import com.audiobook.audiobookplayer.model.CreatePostRequest
import com.audiobook.audiobookplayer.model.CreateReportRequest
import com.audiobook.audiobookplayer.model.FavoriteUploadRequest
import com.audiobook.audiobookplayer.model.LoginRequest
import com.audiobook.audiobookplayer.model.RegisterRequest
import com.audiobook.audiobookplayer.model.RetryFailedTasksRequest
import com.audiobook.audiobookplayer.model.SyncAudioCoverageRequest
import com.audiobook.audiobookplayer.model.SyncBookMetaRequest
import com.audiobook.audiobookplayer.model.SyncFavoritesRequest
import com.audiobook.audiobookplayer.model.SyncUserSettingsRequest
import com.audiobook.audiobookplayer.model.SyncWordBookRequest
import com.audiobook.audiobookplayer.model.WordBookUploadRequest
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.PUT
import retrofit2.http.Query

/** 轻接口（短超时）。重型端点（大文件上传/下载/文本提取）已迁至 [HeavyApiService] */
interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @GET("api/tts/task/{taskId}")
    suspend fun getTaskStatus(
        @Path("taskId") taskId: Long
    ): Response<Map<String, Any>>

    /** 删除服务端任务（排队中任务直接取消；合成中的任务服务端会拒绝并返回 400） */
    @DELETE("api/tts/task/{taskId}")
    suspend fun deleteTask(
        @Path("taskId") taskId: Long
    ): Response<Map<String, Any>>

    /** 重试任务的失败片段（失败已退款的会重新消耗积分） */
    @POST("api/tts/task/{taskId}/retry")
    suspend fun retryTask(
        @Path("taskId") taskId: Long
    ): Response<Map<String, Any>>

    /** 批量重试全部失败任务；返回重试成功数、总补扣积分与跳过明细 */
    @POST("api/tts/tasks/retry-failed")
    suspend fun retryFailedTasks(
        @Body body: RetryFailedTasksRequest
    ): Response<Map<String, Any>>

    /**
     * 当前账号在**服务端**的合成任务列表（按创建时间倒序）。
     *
     * 存在理由：任务历史原本只存在本机 SQLite 的 `tasks` 表里，换机 / 重装 / 清数据后
     * 已扣积分的任务在 App 内彻底消失（服务端其实还在，Web 端也能看到），用户拿不回
     * 已付费的音频。启动时用它把服务端任务补回本地，是跨端恢复的唯一数据来源。
     *
     * 返回 `{ success, total, page?, size?, truncated, tasks: [{taskId, status,
     * totalSegments, completedSegments, createdAt, originalBookName, errorMessage,
     * creditsCost}], queuedCount }`；不传 page/size 时服务端返回最近 500 条（`truncated` 标记是否截断）。
     */
    @GET("api/tts/tasks")
    suspend fun getUserTasks(
        @Query("page") page: Int? = null,
        @Query("size") size: Int? = null
    ): Response<Map<String, Any>>

    @GET("api/tts/task/{taskId}/segments-list")
    suspend fun getTaskSegmentsList(
        @Path("taskId") taskId: Long
    ): Response<Map<String, Any>>

    /**
     * 可选预设音色清单（id + 中文名），供「默认音色 / 逐段音色」下拉选择。
     * 返回 { success, voices: [{id, name}] }
     */
    @GET("api/tts/voices")
    suspend fun getVoices(): Response<Map<String, Any>>

    /**
     * 修改任务的音色分配（任务级默认音色 + 段落级映射）并触发受影响片段重新合成。
     * 请求体：{ "defaultVoiceId": String?, "segmentVoices": { 段号: voiceId|null } }
     * segmentVoices 的 value 传 null 表示清除该段预设、回退到克隆参考音频。
     * 仅当任务处于终态（completed / partial / failed）时才允许；处理中任务服务端会拒绝。
     */
    @POST("api/tts/task/{taskId}/voices")
    suspend fun updateTaskVoices(
        @Path("taskId") taskId: Long,
        // 值可空：defaultVoiceId 与 segmentVoices 的 value 都可能为 null（表示清除该段预设）。
        // @JvmSuppressWildcards 让 JVM 签名落成 Map<String,Object>（而非 Map<String,?>），
        // 否则 Retrofit 反射取不到具体类型；Kotlin 侧仍需 Any? 才能接收 null 值。
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Map<String, Any>>

    /** 获取 TTS 任务的目录（章节映射到 segmentNumber） */
    @GET("api/tts/task/{taskId}/toc")
    suspend fun getTaskToc(
        @Path("taskId") taskId: Long
    ): Response<Map<String, Any>>

    // ---------- 书城 ----------

    /** 书城首页：热门榜 + 新书榜 + 分类 */
    @GET("api/store/home")
    suspend fun getStoreHome(): Response<Map<String, Any>>

    /**
     * 书城书籍列表（可选分类筛选 + 标签筛选 + 关键词搜索）。
     *
     * [tag] 与 [category] 可同时传：分类是单选归属、标签是多选描述，服务端按「与」组合过滤。
     * 不传 tag 时行为与旧版完全一致（兼容旧服务端）。
     */
    @GET("api/store/books")
    suspend fun getStoreBooks(
        @Query("category") category: String? = null,
        @Query("keyword") keyword: String? = null,
        @Query("tag") tag: String? = null
    ): Response<Map<String, Any>>

    /**
     * 编辑推荐书单（editor_pick=true 且在售，按创建时间倒序）。
     * 用于 Discover「推荐」页。返回 { success, books: [StoreBook] }
     */
    @GET("api/store/editor-picks")
    suspend fun getEditorPicks(): Response<Map<String, Any>>

    // ---------- 参考音频 ----------

    /**
     * 服务器共享参考音频库列表（管理员上传，所有登录用户可见）。
     * 返回 { success, references: [{id, name, description, fileName, fileSize, createdAt}], total }
     */
    @GET("api/tts/server-references")
    suspend fun getServerReferences(): Response<Map<String, Any>>

    // ---------- 我的参考音频库（服务端） ----------

    /**
     * 我的服务端参考音频库列表。
     * 返回 { success, references: [{id, name, fileName, description, fileSize,
     * shared, useCount, createdAt}], total }
     *
     * 注意这是**服务端**的「我的库」，与手机本地 SQLite 的参考音频表不是同一份数据：
     * 本地导入的音频从未上传过服务端，因此这里查不到，必须先上传才能分享。
     */
    @GET("api/tts/references")
    suspend fun getMyReferences(): Response<Map<String, Any>>

    /**
     * 开启/关闭分享（仅本人）。分享后进入「发现 - 参考音频」排行榜。
     * 请求体：{ shared: Boolean }
     */
    @POST("api/tts/references/{id}/share")
    suspend fun shareReference(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, Any>>

    // ---------- 分享排行榜（发现页） ----------

    /**
     * 分享排行榜，按使用量倒序。
     * 返回 { success, references: [{rank, id, name, description, fileName, fileSize,
     * useCount, ownerName, sharedAt, createdAt, mine}], total }
     */
    @GET("api/tts/shared-references")
    suspend fun getSharedReferences(
        @Query("limit") limit: Int? = null
    ): Response<Map<String, Any>>

    /**
     * 上报一次使用：把分享音频存进自己的库之后调用，使用量 +1。
     * 与下载端点分开，是为了让「试听」不污染榜单。
     * 返回 { success, counted, useCount }
     */
    @POST("api/tts/shared-references/{id}/use")
    suspend fun reportSharedReferenceUse(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    // ---------- 发现（帖子） ----------

    /**
     * 发现页帖子列表，按发帖时间倒序（最新在前）。
     * 支持分页（page + limit）与按嵌入参考音频过滤（refAudioId）。
     * 返回 { success, posts: [PostItem], total, currentPage, hasMore }
     */
    @GET("api/posts")
    suspend fun getPosts(
        @Query("limit") limit: Int? = null,
        @Query("page") page: Int? = null,
        @Query("refAudioId") refAudioId: Long? = null
    ): Response<Map<String, Any>>

    /** 帖子详情：{ success, post: PostItem, comments: [CommentItem], commentCount } */
    @GET("api/posts/{id}")
    suspend fun getPost(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    /** 发布帖子（支持标题/图片/嵌入参考音频/@提及）。请求体：CreatePostRequest */
    @POST("api/posts")
    suspend fun createPost(
        @Body body: CreatePostRequest
    ): Response<Map<String, Any>>

    /** 评论 / 回复：{ content: String, parentId?: Long } */
    @POST("api/posts/{id}/comments")
    suspend fun createComment(
        @Path("id") id: Long,
        @Body body: CreateCommentRequest
    ): Response<Map<String, Any>>

    /** 收藏 / 取消收藏：{ success, favorited, favoriteCount } */
    @POST("api/posts/{id}/favorite")
    suspend fun toggleFavorite(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    /**
     * 点赞 / 取消点赞：{ success, liked, likeCount }。
     *
     * 与收藏是两条互不影响的链路：收藏进「我的收藏」列表，点赞只表态。
     */
    @POST("api/posts/{id}/like")
    suspend fun togglePostLike(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    /** 删除自己发布的帖子：{ success, message }；非本人 403、不存在 404 */
    @DELETE("api/posts/{id}")
    suspend fun deletePost(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    /** 举报：{ targetType: "POST"|"COMMENT", targetId: Long, reason: String } */
    @POST("api/posts/reports")
    suspend fun createReport(
        @Body body: CreateReportRequest
    ): Response<Map<String, Any>>

    /** 上传帖子图片（≤10MB，jpg/png/webp/gif），返回 { success, url } */
    @Multipart
    @POST("api/posts/upload-image")
    suspend fun uploadPostImage(
        @Part file: MultipartBody.Part
    ): Response<Map<String, Any>>

    /** 单条分享参考音频详情（供帖子内嵌「好声音」卡片）：{ success, id, name, description, useCount, ... } */
    @GET("api/tts/shared-references/{id}")
    suspend fun getSharedReference(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    // ---------- 客户端版本更新 ----------

    /**
     * 查询客户端最新版本。
     *
     * 无需 Authorization：服务端对该路径 permitAll——客户端在启动页就检查更新，
     * 此刻可能尚未登录或本地 token 已过期，要求鉴权会让未登录用户永远收不到
     * （尤其是强制）更新提示。
     *
     * @param versionCode 当前 APK 的 BuildConfig.VERSION_CODE，服务端据此判定是否有更新
     * 返回 { success, hasUpdate, latest:{versionCode, versionName, fileSize, md5,
     * changelog, forceUpdate, releasedAt}, downloadUrl }；无更新时 hasUpdate=false。
     */
    @GET("api/app/version/latest")
    suspend fun getLatestVersion(
        @Query("platform") platform: String = "android",
        @Query("versionCode") versionCode: Int
    ): Response<Map<String, Any>>

    // ---------- 播放进度跨端同步 ----------

    /**
     * 拉取某任务的播放进度（断点续听）。
     * @param taskId 服务端任务 ID（= Android 的 server_task_id）
     * 返回 { success, hasProgress, segment, offsetMs, updatedAt }；尚无进度时 hasProgress=false。
     */
    @GET("api/progress")
    suspend fun getProgress(
        @Query("taskId") taskId: Long
    ): Response<Map<String, Any>>

    /**
     * 上报/覆盖播放进度（断点续听）。服务端只保留最近一次上报。
     * 请求体：{ taskId: Long, segment: Int, offsetMs: Int }
     * 返回 { success, message }。
     */
    @PUT("api/progress")
    suspend fun putProgress(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, Any>>

    // ---------- 跨端阅读进度（bookKey 口径） ----------

    /**
     * 拉取**跨端**阅读进度（断点续读）。
     *
     * 身份用 bookKey（`s:{storeBookId}` / `t:{serverTaskId}` / `n:{书名}`），
     * 与有声覆盖图同一套口径（[CoverageCodec.bookKey]）—— 三端对同一本书算出同一个 key，
     * 进度才能真正互通。
     *
     * 为什么不用上面的任务口径端点：
     * - 它按**任务归属**校验，书城书的音频常由管理员合成 ⇒ 普通用户 403，进度传不上去；
     * - 未合成的书（task_id 为空）压根没有任务 id ⇒ 纯阅读的位置完全无处可存。
     *
     * @param bookKey 跨端书名身份键
     * 返回 { success, hasProgress, segment, offsetMs, updatedAt }；尚无进度时 hasProgress=false。
     */
    @GET("api/book-progress")
    suspend fun getBookProgress(
        @Query("bookKey") bookKey: String
    ): Response<Map<String, Any>>

    /**
     * 上报/覆盖**跨端**阅读进度。服务端只保留最近一次上报。
     * 请求体：{ bookKey: String, segment: Int, offsetMs: Int }
     * 返回 { success, message }。
     */
    @PUT("api/book-progress")
    suspend fun putBookProgress(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): Response<Map<String, Any>>

    // ---------- 听书收藏跨端同步 ----------

    /**
     * 我的听书收藏列表（服务端为准）。
     * 返回 { success, favorites: [{id, bookName, taskId, segmentNumber, content, createdAt}], total }
     */
    @GET("api/favorites")
    suspend fun getFavorites(): Response<Map<String, Any>>

    /**
     * 新增一条收藏（幂等：同书同段已存在则返回既有记录）。
     * 请求体：{ bookName, segmentNumber, content, taskId? }
     */
    @POST("api/favorites")
    suspend fun addFavorite(
        @Body body: FavoriteUploadRequest
    ): Response<Map<String, Any>>

    /**
     * 批量上传合并（首次上云迁移 / 离线补传）：上报本地已有收藏，服务端按
     * (bookName, segmentNumber) 合并去重后返回全量列表。
     * 请求体：{ favorites: [{bookName, segmentNumber, content, taskId?}] }
     */
    @POST("api/favorites/sync")
    suspend fun syncFavorites(
        @Body body: SyncFavoritesRequest
    ): Response<Map<String, Any>>

    /** 取消收藏（按服务端 id）。返回 { success, removed, message } */
    @DELETE("api/favorites/{id}")
    suspend fun deleteFavorite(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    // ---------- 生词本跨端同步（/api/word-book，服务端为真源，身份=sw） ----------

    /**
     * 我的生词本全量列表。
     * 返回 `{ success, words: [{id, sw, word, phonetic, translations, createdAt, updatedAt}], total }`
     * 其中 `translations` 为 LF（真换行符）分隔的义项字符串；Android 本地存字面量 `\n`，
     * 边界互转在 WordBookRepository 的 sync 层。
     */
    @GET("api/word-book")
    suspend fun getWordBook(): Response<Map<String, Any>>

    /** 单条 upsert（幂等，已存在同 sw 返回既有记录） */
    @POST("api/word-book")
    suspend fun addWord(
        @Body body: WordBookUploadRequest
    ): Response<Map<String, Any>>

    /**
     * 批量合并（首次上云 / 离线补传）：上报本地待同步词条，服务端按 (user_id, sw)
     * 合并去重后返回全量列表 + imported 增量。
     */
    @POST("api/word-book/sync")
    suspend fun syncWordBook(
        @Body body: SyncWordBookRequest
    ): Response<Map<String, Any>>

    /** 按 id 删除（幂等）。用于本地已有 server_id 的词条。 */
    @DELETE("api/word-book/{id}")
    suspend fun deleteWord(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    /**
     * 按 sw 删除（幂等）。老版本升级上来的本地词条没有 server_id，
     * 走 by-sw 端点比"先 sync 拿 id 再删 id"省一次网络往返。
     */
    @DELETE("api/word-book/by-sw/{sw}")
    suspend fun deleteWordBySw(
        @Path("sw") sw: String
    ): Response<Map<String, Any>>

    // ---------- 书城书架跨端同步（/api/shelf，服务端为单一真源，身份=storeBookId） ----------

    /**
     * 我的书架列表（服务端为真源，按加入时间倒序，已带书名/作者/是否有音频元信息）。
     * 返回 `{ success, items: [{shelfBookId, storeBookId, title, author, category,
     * hasAudio, missing, offshelf, addedAt}], total }`。
     */
    @GET("api/shelf")
    suspend fun getShelf(): Response<Map<String, Any>>

    /**
     * 加入书架（幂等）。书不存在或已下架时服务端返回 404。
     * 返回 `{ success, shelfItem, added, message }`。
     */
    @POST("api/shelf/{storeBookId}")
    suspend fun addToShelf(
        @Path("storeBookId") storeBookId: Long
    ): Response<Map<String, Any>>

    /**
     * 移出书架（幂等：不在书架也返回成功）。跨设备生效——其它设备下次拉取会将其移除。
     * 返回 `{ success, removed, message }`。
     */
    @DELETE("api/shelf/{storeBookId}")
    suspend fun removeFromShelf(
        @Path("storeBookId") storeBookId: Long
    ): Response<Map<String, Any>>

    // ---------- 有声覆盖图跨端同步（/api/audio-coverage，服务端为真源并集，身份=(bookKey,source)） ----------

    /**
     * 拉取当前用户在服务端的**全量并集**有声覆盖图（不传字节，仅元数据）。
     * 返回 `{ success, items: [{id, bookKey, source, storeBookId, serverTaskId,
     * totalSegments, doneRanges, doneCount, hasAudio, updatedAt}], total }`。
     * 注意响应键名是 **items**（不同于生词本的 words）。
     */
    @GET("api/audio-coverage")
    suspend fun getAudioCoverage(): Response<Map<String, Any>>

    /**
     * 条件 GET（P1 #3：增量同步 / 免流量 304）。
     *
     * 传上次缓存的 ETag 作 [ifNoneMatch]：服务端未变则回 **304 Not Modified**（无 body），
     * 调用方应保留本地缓存、跳过重建；变了则回 200 + 新 ETag + 全量 items。
     * 传 `null` 时 Retrofit 不发 ``If-None-Match`` 头，等价于无条件 GET（首次同步走这条）。
     *
     * 注意 [Response.isSuccessful] 对 304 返回 **false**（它只认 2xx），
     * 调用方须显式判 ``code() == 304``，不能把 304 当失败。
     */
    @GET("api/audio-coverage")
    suspend fun getAudioCoverageConditional(
        @Header("If-None-Match") ifNoneMatch: String?
    ): Response<Map<String, Any>>

    /**
     * 批量上报本机覆盖行，服务端按 (userId, bookKey, source) 做**并集合并**（只增不删）后
     * 返回合并后的全量并集视图（同 [getAudioCoverage] 的结构）。
     *
     * P1 #3：响应带 ``ETag`` 头，调用方应落盘（见 [android.content.SharedPreferences] 或
     * 本地 kv 表），下次本机无音频时用作 [getAudioCoverageConditional] 的 ``If-None-Match``。
     */
    @POST("api/audio-coverage/sync")
    suspend fun syncAudioCoverage(
        @Body body: SyncAudioCoverageRequest
    ): Response<Map<String, Any>>

    // ---------- 跨端单值偏好（/api/user-settings，LWW 逐项合并） ----------

    /**
     * 拉取单值偏好权威全量。
     * 返回 `{success, settings:{playback_speed:"1.5",...}, items:[{key,value,updatedAt}], version}`。
     * 客户端实际同步走 [putUserSettings]（一次 PUT 完成拉+推），此 GET 备用。
     */
    @GET("api/user-settings")
    suspend fun getUserSettings(): Response<Map<String, Any>>

    /**
     * 单值偏好批量同步（一次 PUT 完成「拉 + 推」）。
     * 请求体：{items:[{key, value, updatedAt}]}，服务端按 LWW 逐项合并后返回权威全量。
     * 干净项回传服务端版本、脏项用放大时间戳，规则见
     * [com.audiobook.audiobookplayer.UserSettingsSyncManager]。
     */
    @PUT("api/user-settings")
    suspend fun putUserSettings(
        @Body body: SyncUserSettingsRequest
    ): Response<Map<String, Any>>

    // ---------- 每书元数据（/api/book-meta，LWW 逐项合并，身份=bookKey） ----------

    /**
     * 每书元数据批量同步（全量上报 + 拉全量）。
     * 请求体：{items:[{bookKey, sortOrder, lastReadAt, updatedAt}]}；
     * 返回 `{success, items:[同结构权威全量], total}`。
     *
     * ⛔ 只同步 sortOrder / lastReadAt，绝不带 category（跨端分类语义不同，会互相污染）。
     */
    @POST("api/book-meta/sync")
    suspend fun syncBookMeta(
        @Body body: SyncBookMetaRequest
    ): Response<Map<String, Any>>

}
