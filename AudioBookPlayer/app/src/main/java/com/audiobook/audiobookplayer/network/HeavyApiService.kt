package com.audiobook.audiobookplayer.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 重型端点集合（差异化超时）。
 *
 * 这些端点要么传输大文件（单文件上限 50MB），要么在服务端有长耗时操作
 * （参考音频上传含最长 120s 的视频转码、整书文本提取、音频流式下载），
 * 与登录/列表等轻接口共用一套超时会顾此失彼：
 * 迁就重接口 → 服务器不可达时登录挂起数分钟；迁就轻接口 → 大文件上传被拦腰截断。
 * 因此单独拆出，挂在 [RetrofitClient] 的长超时客户端上。
 */
interface HeavyApiService {

    @Multipart
    @POST("api/tts/upload-reference")
    suspend fun uploadReferenceAudio(
        @Part referenceAudio: MultipartBody.Part
    ): Response<Map<String, Any>>

    /**
     * 上传电子书+引用已上传的参考音频ID创建TTS任务（批量合成时参考音频只上传一次）
     */
    @Multipart
    @POST("api/tts/upload-book")
    suspend fun uploadBookForTtsWithRefId(
        @Part bookFile: MultipartBody.Part,
        @Part("reference_id") referenceId: RequestBody
    ): Response<Map<String, Any>>

    /**
     * 上传电子书并直接指定预设音色创建 TTS 任务（免参考音频）。
     * 仅传 book_file + voice_id；服务端以预设音色合成，不克隆参考音频。
     * 与 [uploadBookForTtsWithRefId] 互斥：选了参考音频走后者（克隆），选了预设音色走本接口。
     */
    @Multipart
    @POST("api/tts/upload-book")
    suspend fun uploadBookForTtsWithVoice(
        @Part bookFile: MultipartBody.Part,
        @Part("voice_id") voiceId: RequestBody
    ): Response<Map<String, Any>>

    /**
     * 下载单个音频片段。
     *
     * 用 @Streaming：响应体边下边写盘，不把整段音频物化进 Java 堆。
     * 调用方（BookListFragment / TtsFragment）从 body.byteStream() 分块读取，
     * 不会调用 bytes()/string() 这类会把整个响应读进内存的方法。
     */
    @Streaming
    @GET("api/tts/segment/{segmentId}/download")
    suspend fun downloadSegment(
        @Path("segmentId") segmentId: Long
    ): Response<okhttp3.ResponseBody>

    /** 上传电子书文件，提取全文段落和目录（非 TTS 场景） */
    @Multipart
    @POST("api/tts/extract-content")
    suspend fun extractContent(
        @Part bookFile: MultipartBody.Part
    ): Response<Map<String, Any>>

    /**
     * 下载书城书籍文件。
     *
     * 用 @Streaming：整本书可能几十 MB，非流式会让 OkHttp 把响应全部缓冲进堆再返回，
     * 低内存机型直接 OOM。调用方 BookStoreViewModel 支持 [range] 续传：传入
     * "bytes=<已下载字节>-" 时服务端返回 206 + 剩余字节，客户端追加写入即可断点续传。
     */
    @Streaming
    @GET("api/store/book/{id}/download")
    suspend fun downloadStoreBook(
        @Path("id") id: Long,
        @Header("Range") range: String? = null
    ): Response<okhttp3.ResponseBody>

    /**
     * 本书的音频片段元数据（**已按段号升序**，与服务端 `StoreReadingController.segments` 同构）。
     *
     * 书城音频改为**逐段下载**后，这里是唯一的分段真源：
     * 响应 `{ success, segments:[{id, segmentNumber, textContent, status, voice}], total }`。
     * 客户端只取 `status == "completed"` 的段 —— 未合成完成的段没有音频，
     * 请求它的单段接口会拿到 4xx，白白浪费一次往返。
     *
     * 与 [getStoreBookContent] 的差异：那个返回的是**原文段落**（用于阅读页排版），
     * 这个是**合成片段**（用于下载音频），两者的段号在服务端同源、一一对应。
     */
    @GET("api/store/book/{bookId}/segments")
    suspend fun getStoreBookSegments(
        @Path("bookId") bookId: Long
    ): Response<Map<String, Any>>

    /**
     * 下载书城书籍的**单个**音频片段（流式）。
     *
     * 越权边界在服务端：片段必须属于本书的 task_id，否则 404。
     * 调用方（[com.audiobook.audiobookplayer.StoreAudioDownloadManager]）按段号升序逐段拉取、
     * 每段落库一次 —— 这样下到第 N 段时前 N 段就已经能在阅读页播放，
     * 不必等整本下完（原先的 zip 整包下载必须等全部解压入库）。
     *
     * 用 @Streaming：边下边写盘，不把整段音频物化进 Java 堆。
     */
    @Streaming
    @GET("api/store/book/{bookId}/segments/{segmentId}/audio")
    suspend fun downloadStoreSegmentAudio(
        @Path("bookId") bookId: Long,
        @Path("segmentId") segmentId: Long
    ): Response<okhttp3.ResponseBody>

    /**
     * 读取书城书籍的正文（段落 + 目录）。
     *
     * 与 [downloadStoreAudio] 是**同源**的：服务端 `BookStoreController.bookContent`
     * 内部走 `BookTextExtractor.extractBookContent`，与 TTS 切段是同一个实现
     * ⇒ 返回的 `paragraphs` 与音频 zip 里的片段**按下标 1:1 对齐**。
     * 「下载合成音频」据此把 `paragraphs[n-1].text` 作为第 n 段的文本一起入库，
     * 阅读页才能同时拿到文字与音频（zip 里只有 mp3、不含任何文本）。
     *
     * 走重型超时：整本书的解析对服务端是长耗时操作（PDF 尤甚）。
     * 响应 { success, title, paragraphs:[{text,charOffset,paragraphIndex}], toc, extension }
     */
    @GET("api/store/book/{id}/content")
    suspend fun getStoreBookContent(
        @Path("id") id: Long
    ): Response<Map<String, Any>>

    /**
     * 下载服务器共享参考音频到手机本地。
     * 用 @Streaming：响应体边下边写盘，不把整段音频物化进 Java 堆。
     */
    @Streaming
    @GET("api/tts/server-references/{id}/audio")
    suspend fun downloadServerReference(
        @Path("id") id: Long
    ): Response<okhttp3.ResponseBody>

    /**
     * 上传一条参考音频到「我的服务端参考音频库」。
     *
     * 与 [uploadReferenceAudio]（/upload-reference，只落盘不入我的库）不同，
     * 这个端点会登记 user_reference_audio 记录，**只有入了我的库才能分享**。
     *
     * @return { success, id, name, fileName, description, fileSize }
     */
    @Multipart
    @POST("api/tts/references")
    suspend fun uploadUserReference(
        @Part referenceAudio: MultipartBody.Part,
        @Part("name") name: RequestBody,
        @Part("description") description: RequestBody? = null
    ): Response<Map<String, Any>>

    /**
     * 下载分享排行榜里的参考音频（试听或存进本地库）。
     * 用 @Streaming：响应体边下边写盘，不把整段音频物化进 Java 堆。
     *
     * 注意：这个端点**不计数**。使用量由 [com.audiobook.audiobookplayer.network.ApiService
     * .reportSharedReferenceUse] 单独上报。
     */
    @Streaming
    @GET("api/tts/shared-references/{id}/audio")
    suspend fun downloadSharedReference(
        @Path("id") id: Long
    ): Response<okhttp3.ResponseBody>

    /**
     * 下载 APK（@Streaming：边下边写盘，几十 MB 的安装包不进堆）。
     *
     * 无需 Authorization：该端点在服务端被 SecurityConfig 放行
     * （客户端在启动页就检查更新，此刻可能尚未登录）。
     *
     * @param versionCode 客户端手上元信息对应的版本号。**必须传**：下载接口会据此
     *   返回该版本的文件，而不是「此刻的最新版」。否则「先查 /latest 拿到 MD5、
     *   再下载」这两步之间若管理员发布了新版本，就会下载到新包却用旧 MD5 校验，
     *   必然失败，而报错信息（下载失败）完全指错了方向。
     */
    @Streaming
    @GET("api/app/version/latest/apk")
    suspend fun downloadApk(
        @Query("platform") platform: String = "android",
        @Query("versionCode") versionCode: Int
    ): Response<okhttp3.ResponseBody>

    /**
     * 交互式音色试听：用参考音频（克隆音色）合成一段固定样例句，返回 WAV。
     * 挂在长超时客户端上——/clone 是同步阻塞重接口（上传参考音频 + 引擎合成）。
     *
     * @param text         试听样例句（服务端校验非空、≤500 字）
     * @param promptAudio  参考音频文件（与批量合成使用的同一份参考音频，格式口径一致）
     * @return audio/wav 响应体；任何失败（含 4xx/5xx）由调用方按 [retrofit2.Response.isSuccessful] 判断
     */
    @Multipart
    @POST("api/tts/clone")
    suspend fun previewVoiceClone(
        @Part("text") text: okhttp3.RequestBody,
        @Part promptAudio: MultipartBody.Part
    ): Response<okhttp3.ResponseBody>

    /**
     * 交互式**预设音色**试听：只给 voiceId，不带参考音频，返回 WAV。
     *
     * 与 [previewVoiceClone] 打同一个端点：服务端的 `prompt_audio` 与 `voiceId` 都是
     * `required=false`，二选一 —— 有参考音频就克隆，只有 voiceId 就用预设音色。
     * 所以「试听预设音色」不需要新端点，只是把第二个 part 换成 voiceId。
     *
     * ⚠️ 不能给两个 part 都传：服务端优先按参考音频走克隆，voiceId 会被静默忽略，
     * 用户听到的是自己音色的克隆结果，却以为在听「新闻女声」。
     *
     * @param text    试听样例句（服务端校验 ≤500 字）
     * @param voiceId 预设音色 id（来自 /api/tts/voices）
     */
    @Multipart
    @POST("api/tts/clone")
    suspend fun previewVoiceById(
        @Part("text") text: okhttp3.RequestBody,
        @Part("voiceId") voiceId: okhttp3.RequestBody
    ): Response<okhttp3.ResponseBody>

    /**
     * 离线词典元信息（版本 / 大小 / MD5 / 词条数）。
     *
     * 无需 Authorization：服务端 SecurityConfig 放行了 app/dict 下的全部端点——
     * 词典是与账号无关的静态资源，而阅读**本地导入的书**不需要登录，
     * 要求鉴权会让离线读书的人永远装不上词典。
     *
     * 响应：`{ success, available, version?, downloadSize?, downloadMd5?,
     * downloadName?, rows?, license?, source?, downloadUrl? }`。
     * 服务端未部署词典时 `available=false`，此时其余字段全部缺失（不是 null）。
     *
     * ⚠️ KDoc 里写路径时**绝不能**带 `**` 通配符（如 `api/app/dict/**`）：
     * 那里的 `*/` 会提前闭合本注释块，余下文本被当代码解析，
     * 报出 `Unclosed comment` / `Missing '}'` 这种完全指不到位置的错。
     */
    @GET("api/app/dict/info")
    suspend fun getDictInfo(): Response<Map<String, Any>>

    /**
     * 下载离线词典（gzip 压缩的 SQLite 库，23.1MB）。
     *
     * @Streaming：边下边写盘，23MB 不进 Java 堆；调用方从 body.byteStream() 分块读取。
     *
     * 支持 [range] 断点续传：传 `"bytes=<已下载字节>-"` 时服务端回 206 + 剩余字节，
     * 客户端追加写入即可。与 [downloadStoreBook] 同一套约定。
     *
     * ⚠️ 服务端刻意**不**返回 `Content-Encoding: gzip`：那会让 OkHttp 自动透明解压，
     * 于是落盘字节与 `downloadMd5` 校验的字节不是同一份，断点续传也无法按原始字节对齐。
     * 解压由客户端在下载完成后显式做（[com.audiobook.audiobookplayer.DictionaryManager]）。
     */
    @Streaming
    @GET("api/app/dict/download")
    suspend fun downloadDict(
        @Header("Range") range: String? = null
    ): Response<okhttp3.ResponseBody>
}