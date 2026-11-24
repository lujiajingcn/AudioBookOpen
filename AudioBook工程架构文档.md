# AudioBook 工程总体说明文档

> **代码基线**：git 分支 `master`，提交 `b4e95bcccc9ccb371aa79b55313abd92348e1ba7`（简称 `b4e95bc`），提交时间 2026-10-30 16:19:43 +0800，提交说明 `refactor(tts): 删除滚动韵律 prompt 机制，段级包络整形默认关闭`。
> 本文所有结构、类名、方法名、配置项均以该提交为准；该提交之后的改动（详见 `git log`）未逐条复核，读到与代码不符处以代码为准。
> 仓库根（git root）：`E:\study_audiobook\WorkSpace_AudioBook\AudioBook\AudioBook`。文中相对路径若无特别说明，均相对该根目录。
> Android 客户端当前线上版本：`versionCode = 41` / `versionName = "1.41"`。

---

## 1. 项目总览

AudioBook（产品名「有声伴读 / 听书」）是一个**把电子书自动合成为有声书**的完整系统：用户上传或选择一本电子书（TXT / Markdown / EPUB / PDF / MOBI / AZW），系统把正文切分成句子段，逐段调用 TTS 引擎（可克隆音色的云端引擎，或端侧/本机的 MOSS-TTS-Nano）生成语音，再拼接、转码、入库，最终在阅读器里实现「边读边听、点哪句播哪句、句级高亮」的体验，并配套书城、书架、发现社区、积分充值、后台运营管理等能力。

整个仓库是一个**多语言 monorepo**，按子工程划分如下：

| 子工程 | 语言 / 技术栈 | 角色 |
|---|---|---|
| `AudioBookServer` | Java 17 + Spring Boot 3.2 | 后端单体服务，提供全部 REST API、TTS 编排、支付、书城 |
| `AudioBookPlayer` | Kotlin + Android（AGP 8.6.1, compileSdk 34） | C 端 Android 客户端，含端侧 MOSS-TTS-Nano 推理 |
| `AudioBookWeb` | Vue 3 + TS + Vite + Ant Design Vue | C 端网页版（书城 / 阅读 / 听书 / 合成） |
| `AudioBookManager` | Vue 3 + TS + Vite + Ant Design Vue | B 端管理后台（运营 / 用户 / 书城 / 节点 / 版本发布） |
| `AudioBookShared` | TypeScript（纯源码 npm 包） | Web 与 Manager 复用的 API 契约 + 认证 + HTTP 基础设施 |
| `AudioBookLocalTtsTool` | Python + PySide6（Qt） | Windows 桌面工具，本机批量合成 + 直写库发布到书城 |
| `scripts` | Batch / Shell | 一键环境搭建与 Maven/Gradle 构建封装 |

### 1.1 运行时拓扑与数据流

```
                    ┌──────────────────────────────────────────────┐
   C 端用户          │                 AudioBookServer               │
  ┌──────────┐      │   Spring Boot :8080（nginx :80 反代）          │
  │ Player   │◀────▶│  /api/auth /api/tts /api/store /api/shelf ...  │
  │ (Android)│ HTTP │  /api/payment /api/posts /api/app/version ...  │
  └──────────┘      │  ┌────────────┐   ┌──────────────────────┐    │
  ┌──────────┐      │  │  MySQL     │   │  Redis（限流/进度快照）│    │
  │ Web (C端)│◀────▶│  │ audiobook_ │   └──────────────────────┘    │
  └──────────┘      │  │ db         │                               │
  ┌──────────┐ 代理  │  └────────────┘        │ WebClient            │
  │ Manager  │◀────▶│                         ▼                      │
  │ (B端:3001)│/api │        ┌───────────────────────────────┐       │
  └──────────┘      │        │  TTS 引擎节点池（可多机）        │       │
  ┌──────────┐ SSH/ │        │  MOSS-TTS（JSON/base64 WAV）    │       │
  │ LocalTts │ MySQL│        │  Pocket-TTS（裸 WAV，拉丁语种） │       │
  │ Tool     │◀────▶│        └───────────────────────────────┘       │
  └──────────┘      └──────────────────────────────────────────────┘
        │
        └── 本机跑 MOSS-TTS-Nano（conda 解释器子进程），产物结构与 Server 同构，
            经 SSH 文件通道 + -L 隧道直写生产 MySQL，「一键发布到书城」。
```

要点：
- **音频只落磁盘、不入库**（`audio_segments.audio_data` 恒为 NULL，历史遗留列），文件路径形如 `{taskId}/tts_{taskId}_{n}.mp3`。
- **TTS 引擎路由**在 Server 侧决定：中文与未配置语种一律走 MOSS，7 门拉丁语系且配了音色才走 Pocket（详见 §3.3）。
- **端侧合成**（Player `.moss` 包）与**本机合成工具**（LocalTtsTool）都跑同一个 MOSS-TTS-Nano 引擎，与服务端逐段合成、切句、帧预算口径完全对齐，产物可无缝互认。

---

## 2. AudioBookServer —— 后端服务

路径 `AudioBookServer/`，Maven 工程（`pom.xml`），Spring Boot 3.2.0 / Java 17，打包 `audiobookserver-1.0.0.jar`。

### 2.1 技术栈与关键依赖

- Web：`spring-boot-starter-web`（内嵌 Tomcat MVC）+ `spring-boot-starter-webflux`（仅为使用 `WebClient` 异步调用上游 TTS 引擎）。
- 持久层：**Spring Data JPA / Hibernate**（非 MyBatis）+ `mysql-connector-j`；Redis（`spring-boot-starter-data-redis`，Lettuce）用于限流计数与任务进度快照。
- 安全：`spring-boot-starter-security` + `jjwt 0.12.6`（JWT）。
- 音频/电子书：`ws.schild:jave-all-deps 3.3.1`（FFmpeg 封装，WAV→MP3、视频转码）、`org.apache.pdfbox 3.0.2`（PDF 抽取）。
- 校验 `starter-validation`；测试 JUnit（离线仓库约束）。
- DDL 策略：dev `ddl-auto: update`，prod `ddl-auto: validate`（升级前须先跑建表脚本）。

### 2.2 包结构（`src/main/java/com/audiobook/server/`）

| 包 | 文件数 | 职责 |
|---|---|---|
| `controller` | 25 | REST 端点层，按业务域拆分；含 `GlobalExceptionHandler` |
| `service` | 23 | 业务逻辑（认证、积分、书城、社区、TTS 编排、节点调度、恢复等） |
| `service.payment` | 5 | 支付网关抽象与 Alipay/Wechat/Mock 实现 + 加解密 |
| `model` | 26 | JPA 实体 + 枚举（`TaskStatus`/`SegmentStatus`/`TtsEngineType`）+ `Request` DTO |
| `repository` | 22 | Spring Data JPA 仓储接口 |
| `dto` | 11 | 请求/响应对象 |
| `util` | 13 | 音频/文本处理、JWT、Cookie、路径安全、语种识别、打包 |
| `config` | 3 | `SecurityConfig` / `AsyncConfig` / `PaymentProperties` |
| `filter` | 2 | `JwtAuthenticationFilter` / `RateLimitFilter` |
| `exception` | 2 | `InsufficientCreditsException`(→402) / `TtsTimeoutException`(→504) |

> 实体之间**无任何关联注解**，全部是扁平外键列，避免隐式级联与 N+1。

### 2.3 Controller 层（REST 端点）

**`AuthController` (`/api/auth`)**：`POST /register`、`POST /login`（签发 JWT + 下发 HttpOnly Cookie）、`GET /validate`、`POST /logout`、`POST /change-password`（递增 `tokenVersion` 使旧 token 失效）。

TTS 相关被拆成多个 Controller，共用 `TtsControllerSupport`：
- **`TtsSynthesisController` (`/api/tts`)**：交互式同步合成 `POST /clone`（音色克隆）、`POST /synthesize`（预设音色），走 `sync-timeout-seconds=60`。
- **`TtsTaskController` (`/api/tts`)**：批量任务生命周期——`POST /upload-book`（上传书建任务）、`POST /from-store`（书城书建任务）、`GET /tasks`、`GET /task/{id}`、`GET /voices`（免鉴权）、`GET /task/{id}/segments-list`、`POST /task/{id}/voices`（改分段音色并重合成）、`POST /task/{id}/retry`、`POST /tasks/retry-failed`、`DELETE /task/{id}`、`GET /task/{id}/toc`。
- **`TtsDownloadController`**：`GET /segment/{id}/download`（单段 MP3 流式）、`GET /task/{id}/download-all`（整任务 ZIP）。
- **`BookContentController`**：`POST /extract-content`（上传书即时解析正文/目录，重接口限流）。
- **`ReferenceAudioController`**：参考音频（音色克隆素材）全套——临时上传、用户库 CRUD、视频转码（≤120s）、官方共享库、分享与用量上报、免鉴权试听流。
- **`TaskStreamController` (`/api/tts/stream`)**：`GET /{taskId}`（`text/event-stream` SSE 实时进度），`broadcastProgress` 静态方法被合成线程调用。

其余业务 Controller：
- **`BookStoreController` (`/api/store`)**：书城首页/列表/分类/编辑推荐/详情/正文/封面（多为只读免鉴权），`/book/{id}/download`、`/book/{id}/audio/download`（需登录）。
- **`StoreReadingController` (`/api/store/book/{bookId}`)**：在线阅读与听书——`/segments`、`/toc`、`/segments/{segmentId}/audio`（免鉴权流式）、`GET/PUT /progress`（按用户读写进度）。
- **`ProgressController` (`/api/progress`)**：自传任务段级进度读写。
- **`ShelfController` (`/api/shelf`)**、**`FavoriteController` (`/api/favorites`)**（划词收藏）、**`PostController` (`/api/posts`)**（发现社区，GET 免鉴权、POST 需登录限流）。
- **`PaymentController` (`/api/payment`)**：`/channels`、`/prepay`、`/orders`、`/orders/{no}/cancel`、`/mock/confirm`、`/notify/alipay`、`/notify/wechat`（回调免鉴权，靠渠道验签）。
- **`CreditController` (`/api/credits`)**：余额、流水、套餐。
- **管理端**：`AdminController`（用户/书籍/合成控制/参考音频，`hasRole("ADMIN")`）、`AdminCreditController`（扣费规则/套餐/调分/统计）、`AdminTtsNodeController`（合成节点 CRUD 与启停，运行期生效免重启）、`AdminContentController`（帖子/参考音频审核）、`AdminAppVersionController`（APK 版本上传发布）。
- **`AppVersionController` (`/api/app/version`)**：客户端 `GET /latest`、`GET /latest/apk`（免鉴权流式下载）。
- **`DictController` (`/api/app/dict`)**：ECDICT 离线词典（23.1MB）`/info`、`/download`，独立限流。
- **`ConfigController` (`/api/config`)**：下发前端运行参数（如 `maxFileSizeBytes`）。

### 2.4 Service 层核心方法

**`VoiceCloningTtsService`**（实现 `TtsService`，约 2900 行，整个后端的引擎核心）：
- `byte[] synthesize(Request)` / `synthesizeInteractive(Request)` / `synthesizeInteractiveTo(Request, OutputStream)`：单段合成入口，末者流式写出不占堆。
- `String uploadReferenceAudio(MultipartFile)`：保存参考音频并入库前裁到 `reference-max-seconds`（8s）。
- `Long createTtsTask(...)`：建 `Task` 行、落书籍/参考音频文件、扣积分，事务提交后经 `dispatchAfterCommit` 派发异步合成。
- `void applySegmentVoices(taskId, defaultVoiceId, Map, ...)`：改分段音色、把变更段重置为 PENDING、只重派发变更段。
- `@Async("ttsTaskExecutor") void processTtsTaskAsync(Long taskId)`：**整本书合成流水线**（见 §2.5）。
- `decideEngineType(...)`（纯函数，746–805 行）：**引擎路由**（见 §2.6）。
- `callTtsEngine(...)`（683 行）：MOSS 引擎对接；`callPocketEngine(...)`（870 行）：Pocket 引擎对接。

**`TtsService`**（接口）：定义上述合成/建任务/派发契约。

**`TtsLoadBalancer`**（多节点调度）：`candidates(TtsEngineType)`（按引擎隔离选点，最少在途优先、同负载按 weight）、`onAcquire/onRelease/onSuccess/onFailure`（进程内在途数 + 熔断冷却）、`hasHealthyNode()`、`@Scheduled probeAll()`（每 10s 串行 TCP 建连探活，不依赖引擎健康端点）。

**`TtsNodeRegistry`**（节点真源）：`reload()` 从 `tts_nodes` 表加载快照、`@Scheduled scheduledReload()`（5s 兜底刷新）、CRUD `create/update/delete/findAll`（写后立即刷新）、`normalizeUrl()`。

**`RedisTaskService`**：`recordNewTask`、`updateTaskStatus`（进度写 Redis 供 SSE/列表快读）、`getSystemStats`、`cleanupCompletedTask`；**fail-closed**——Redis 异常时限流整体拒绝。

**`TtsTaskRecoveryRunner`**（`ApplicationRunner`）：启动时把 `pending/queued/processing` 及「有 pending 段的 partial」任务限量重新派发（`recover-max=200`），实现断点续跑。

**`ShutdownSignal`**（监听 `ContextClosedEvent`）：`isShuttingDown()`、`isShutdownInterruption(Throwable)`——让合成线程在关闭时收手、不落终态、不把被中断段标 failed（保留可续跑）。

**`AuthService`**：`register`、`login→LoginResult(token,isAdmin)`、`validateToken`、`changePassword`（BCrypt）。

**`CreditService`**：`getRule/updateRule`、`resolveCost`、`getFreeTrialsRemaining`、`chargeForTask/chargeForRetry`、`refundForTask`（按 `credits_refunded` 幂等）、`preCheckBalance`、`grantRegisterBonus`、`applyRecharge`。

**`CreditOrderService`**：`createOrder`、`handleNotify`（分渠道验签）、`markOrderPaid`（幂等入账）、`cancelOrder`、`@Scheduled closeExpiredOrders()`（5min 关超时单），乐观锁 `@Version`。

**`payment/*Provider`**：`channel()`、`isConfigured()`（缺密钥降级 MOCK）、`prepay(order)→PrepayResult`、`parseNotify(...)→NotifyResult`。Alipay 走 RSA2 表单跳转；Wechat 走 v3 API + `PaymentCryptoUtil.aesGcmDecrypt`；Mock 生产禁用（`allow-mock=false`）。

**`BookTextExtractor`**（约 1600 行）：`extractBookContent(file, ext)→BookContent(paragraphs, toc, ...)`；按扩展名分派 `extractTxt/Markdown/Pdf/Epub/Mobi`；`locateStartOffsets`/`paragraphIndexOfOffset` 维护「段↔原文↔进度」偏移映射。书城书建任务时用它一次性提取正文+目录并落 `BookContentSnapshot`。

**`BookStoreService`**（`ApplicationRunner`）：启动 `scanStoreDirectory()` 扫描 `store_books` 目录对账入库；`listBooksPaged`、`getHot/New/EditorPicks`、`readableContentForReading`（优先快照否则实时提取）、`rebuildContentSnapshot`、`resolveBookFile/CoverFile`、`generateCover`、`streamStoreAudioZip`。

**`AdminService`**（约 1600 行）：用户增删改封（`deleteUserInTx` 级联清任务）、书城书上传/编辑/删除/封面、批量合成 `synthesizeBooksBatch`、`bindSynthTaskToBook`、暂停/继续 `pauseTaskInDb`/`resumeTaskInDb`、参考音频与主题 CRUD。

其余：`AppVersionService`、`DictService`、`BookCoverExtractor`、`BookContentSnapshotService`、`ShelfService`、`FavoriteService`、`PostService`、`AdminContentService`、`RateLimitService`（Redis Lua 固定窗口）。

### 2.5 TTS 合成流水线 `processTtsTaskAsync(taskId)`

1. `runningTasks.add` 去重；检查 `isTaskDeleted` / `isTaskPaused`（管理员暂停/删任务即收手）。
2. 取段元数据投影；**若 0 段则现场抽段**（书城书走 `extractBookContent`+落快照，自传书走 `extractText`+`TextSegmenter.sentences`）；顺带做书级语种识别落 `Task.language`。
3. 置 `PROCESSING`（DB + Redis），一次性 `loadReferenceAudio` 缓存、建 `uploads/{taskId}/` 目录。
4. **逐段循环**：每段前检查删除/暂停/`isShuttingDown`/`awaitHealthyNode`（全部节点不可用则等恢复，超时 180s 后以 PARTIAL/FAILED 收尾且**剩余段保持 pending 不标 failed**）；`COMPLETED` 且文件仍在则跳过（断点续跑）；调 `synthesizeSegmentWithRetry`。心跳：`updateRuntimeUnlessPaused` + Redis + SSE 广播。
5. **终态判定**：`completedCount==0 → FAILED`（触发全额退款）；`==total → COMPLETED`；否则 `PARTIAL`。顶层 `catch(Throwable)` 兜底；关闭打断则保持 processing 交给下次启动恢复器。

**段级重试 `synthesizeSegmentWithRetry`**：按 `backoff-seconds=5,15,30` 退避，只重试「重发可能成功」的错误（超时/5xx/429/连接类），4xx/解析失败/关闭打断不重试。`donePieces` 跨重试缓存已成功小片，只重发失败片。

**单段 `doSynthesizeSegment`**：`TextSegmenter.subPieces` 拆 ≤45 字小片 → 丢弃无朗读内容片 → 单片直合成 / 多片 `synthesizePiecesAndConcat`（逐片串行 + `WavClauseJoiner` 归一静音与响度对齐）→ 空段给 `WavConcat.silentWav()` 占位 → `applySegmentEnvelope`（`WavEnvelope`，默认关）→ `convertWavToMp3`（FFmpeg）→ 落 `uploads/{taskId}/tts_{taskId}_{n}.mp3` → 更新段状态 COMPLETED。

### 2.6 引擎路由 `decideEngineType`（判定顺序即契约）

1. `pocket.tts.enabled=false` → **MOSS**（逐字节兼容旧行为）。
2. **中文 `zh` → 短路 MOSS**（写在 voiceId 判据之前，防止给中文书误选 Pocket 音色导致整本发英文模型的静默事故）。
3. 语种属 Pocket 可路由的 7 门拉丁语（`LanguageDetector.isPocketRoutable`：en/fr/de/es/it/pt/nl）**且** `language-voices` 为该语种配了音色 → **POCKET**。
4. `voiceId` 命中 Pocket 音色清单（大小写不敏感的「手动指定逃生门」）→ **POCKET**。
5. 其余（含 `other`、可路由但未配音色的语种）→ **MOSS** 回落。

语种来源：`effectiveLanguage(request)` 优先取书级 `Task.language`（解析时识别一次并落库），交互式预览时就地 `LanguageDetector.detect`。

### 2.7 状态机

- **`TaskStatus`**：`pending → queued → processing → {completed | partial | failed}`；另有 **`paused`**（管理员挂起，非终态、不算未完成、不占线程池/配额、不被启动恢复器捞起）。关键判定：`isTerminal()`、`isUnfinished()`、`isPaused()`、`allowsResynthesis()`（仅 failed/partial 允许重来）、`unfinishedValues()=[pending,queued,processing]`。
- **`SegmentStatus`**：`pending → processing → completed | failed`（失败段可重置回 pending 重试）。
- **`TtsEngineType`**：`MOSS`（默认/向后兼容）/ `POCKET`；`fromString` 对 null/未知一律回落 MOSS。

### 2.8 util 包工具类

- **`WavConcat`**：同格式 WAV 纯字节拼接（超长句多小片合成后拼成一条）。只保留 `fmt `/`data` chunk；**格式不一致直接抛**（不静默重采样）。接缝两侧各做短淡出/淡入（`joint-fade-millis=15`，改副本不改原片以保护 `donePieces` 缓存）。`silentWav()` 生成 300ms 静音占位；`parse` 对 Pocket「流式 WAV 未回填 data size」做截断容错（提交 `9d970d7` 的核心修复）。
- **`WavEnvelope`**：段级包络整形（裁头尾静音→端点淡化→头尾统一补齐），治「段间停顿忽长忽短 + 末帧非零导致『啪』」。`Options` 携带 7 个参数；**默认关闭**（`segment-envelope.enabled=false`）；失败/非 16bit 一律原样返回（fail-open）。
- **`WavClauseJoiner`**：句内子片接缝一致性——`normalize`（归一每片头尾静音）、`join`（按片尾标点插等长停顿 + 有效发声响度对齐 + 片内巨停顿封顶）。**当前默认开启**（`subclause.join.enabled=true`），是主要音质修复项。
- **`TextSegmenter`**：`DEFAULT_MAX_LENGTH=45`（引擎单次上限）。`sentences(text)` 按句末标点切成完整句（=一个 segment/一行 DB/一个 mp3）；`subPieces(sentence)` 把超 45 字句按内部标点拆小片；`hasSpeakableContent` 过滤纯下划线/点线/空白。**书城阅读页与音频段号必须同口径**（与 `BookTextExtractor` 共用 `sentences`）。
- **`LanguageDetector`**：`detect(text)` 用词频/字符特征识别 `zh/en/fr/de/es/it/pt/nl/other`；`isPocketRoutable(lang)` 判定 7 门拉丁语。
- **`PocketVoiceConfig`**：把 `id` 或 `id:显示名` 逗号串解析成有序 Map，被合成服务与 Controller 共用保证口径一致。
- **`AudioVideoUtil`**：基于 FFmpeg 的 `isVideoFormat`、`convertVideoToWav`、`probeDurationSeconds`、`capDurationToWav`、`encodeWithTimeout`（FFmpeg 挂死不拖垮线程）。
- **`CoverRenderer`**：无书封面时用 Java2D 按标题/作者/主色渲染封面图。
- **`ZipAudioPacker`**：`pack(...)` 把任务多段 MP3 流式打成 ZIP。
- **`JwtUtil`**：`generateToken(username,isAdmin,tokenVersion)`、`extractUsername`、`getTokenVersionFromToken`、`validateToken`；无 `JWT_SECRET`（非 dev）启动即失败。
- **`AuthCookieService`**：HttpOnly Cookie 载体，`writeAuthCookie/clearAuthCookie`（含 `X-Client-App` 多 app id 区分）、`resolveAppId`、`readToken`。
- **`PathSafetyUtil`**：目录穿越防护 `resolveBaseDir`、`isWithin`/`assertWithinParent`、`deleteQuietly`。
- **`PageUtil`**：`MAX_PAGE_SIZE=100`、`clampSize`。

### 2.9 关键实体与数据表（`model/`）

- **`Task`（tasks）**：`userId`、`bookFilePath`、`referenceAudioPath`、`storeBookId`（书城书才非空）、`status`、`totalSegments/completedSegments`、`creditsCost/creditsRefunded`（退款幂等）、`defaultVoiceId`、`segmentVoices`(TEXT JSON)、`language`、时间戳。索引 `idx_task_status`、`idx_task_user_created`。
- **`AudioSegment`（audio_segments）**：`taskId`、`segmentNumber`、`textContent`(TEXT)、`audioData`(@Lob，**现恒 NULL**)、`audioFilePath`、`durationSeconds`、`status`、`errorMessage`、`voice`（段级音色覆盖）。
- **`User`（users）**：`username`(unique)、`password`(BCrypt)、`email`、`isAdmin`、`bannedUntil`、`credits`、`tokenVersion`（改密递增令旧 token 失效）。
- **`TtsNode`（tts_nodes）**：`name`、`url`(unique)、`engineType`、`language`、`weight`、`enabled`、`sortOrder`、`remark`（只存配置，运行态在 `TtsLoadBalancer` 内存）。
- **`StoreBook`（store_books）**：书城书元数据 + `taskId`（绑定合成任务）+ `status`。
- **`ReferenceAudio` / `ReferenceAudioTopic` / `UserReferenceAudio` / `TempReferenceAudio`**：官方音色克隆素材、主题分类、用户上传可分享、临时上传。
- **`BookContentSnapshot`（book_content_snapshots）**：书城书正文快照（LONGTEXT），保证阅读页与音频同源。
- **积分/支付**：`CreditOrder`（`@Version` 乐观锁）、`CreditTransaction`（流水）、`CreditPackage`、`CreditRule`（单例扣费规则）。
- **社区/阅读**：`Post`/`Comment`/`PostFavorite`/`Report`、`ShelfBook`、`TextFavorite`、`ReadingProgress`（`segmentNumber`+`offsetMs`）、`AppVersion`。
- **`Request`（非实体 DTO）**：`text`、`voiceId`、`referenceAudio`(byte[])、`referenceAudioFile`(File 流式)、`maxNewFrames`、`language`。

### 2.10 配置关键开关（`src/main/resources/application*.yml`）

- **MOSS**：`moss.tts.url`（仅首部署种子）、`voice-param=demo_id`、`voices=demo-1..6`、`reference-max-seconds=8`、`frames.{per-char=4.0,base=30,min=30,max=375}`、`timeout-seconds=180`（批量）、`sync-timeout-seconds=60`、`nodes.{refresh-millis=5000, failure-cooldown-seconds=30, max-consecutive-failures=2, health-check-interval=10000, wait-for-healthy-max-seconds=180}`。
- **Pocket**：`pocket.tts.enabled=${POCKET_TTS_ENABLED:false}`（总开关）、`voices`、`language-voices`（`lang:voiceId` 映射，决定 `voice_url`）。
- **`app.tts.*`**：`concurrency=${APP_TTS_CONCURRENCY:2}`（全局在途上限=线程池 core=max，与节点数无关）、`queue-capacity=200`、`joint-fade-millis=15`、`segment-envelope.enabled=false`、`subclause.join.enabled=true`、`recover-on-startup=true`、`recover-max=200`、`segment-retry.backoff-seconds=5,15,30`。
- **限流 `app.rate-limit.*`**：`limited-paths`（正则全锚定）；login 10/60s、register 3/60s（失败次数语义）、tts 10/60s、dict 20/60s；Redis 异常 fail-closed。三处（代码默认 + 两个 profile yml）必须同步，由 `RateLimitPathsConfigTest` 断言。
- 其他：`jwt.secret/expiration`、`app.cors.allowed-origins`（禁止 `*`）、`app.auth.cookie.{name, secure, same-site=Lax}`、`app.quota.max-unfinished-tasks-per-user=10`、`app.payment.*`（密钥全走环境变量）。

### 2.11 启动 / 后台线程 / 鉴权 / 异常

- **启动类 `ServerApplication`**：`@SpringBootApplication` + `@EnableAsync`（提供 `ttsTaskExecutor`）+ `@EnableScheduling`。
- **线程池 `AsyncConfig`**：core=max=`app.tts.concurrency`、queue=200、**`AbortPolicy`**（队列满即拒，绝不用 CallerRuns 以免 HTTP 线程跑整本书）、优雅关闭 `awaitTermination=60s`。
- **`@Scheduled`（3 个，共用 size=2 调度池）**：`closeExpiredOrders`（5min）、`scheduledReload`（5s）、`probeAll`（10s）。
- **鉴权链 `SecurityConfig`**：`RateLimitFilter → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter`；CSRF 关闭（靠 SameSite=Lax + 精确 CORS）；未认证/权限不足统一回 JSON 401/403。免鉴权：`/api/auth/**`（除改密）、`/api/payment/notify/**`、`/api/app/version/**`、`/api/app/dict/**`、书城/发现只读 GET、`/api/tts/voices`；`/api/admin/**` 需 `ROLE_ADMIN`。
- **`JwtAuthenticationFilter`**：凭据优先 `Authorization: Bearer`（Android/API），回退 HttpOnly Cookie（Web）；短 TTL 进程内缓存用户快照；每请求比对 `tokenVersion` 与 `bannedUntil`。
- **`GlobalExceptionHandler`（`@RestControllerAdvice`）**：`InsufficientCreditsException`→402（带 required/balance/shortage）、`TtsTimeoutException`→504、`MaxUploadSize`→413、校验/参数→400、乐观锁→409、兜底→500（堆栈仅进日志）。

---

## 3. AudioBookPlayer —— Android 客户端

路径 `AudioBookPlayer/`，Kotlin 1.9.20 / AGP 8.6.1，`namespace = com.audiobook.audiobookplayer`，minSdk 24 / target & compile 34，Java/Kotlin target 17，当前 `versionCode=41 / versionName="1.41"`。

### 3.1 技术栈与依赖

- 端侧推理：**ONNX Runtime Android 1.24.3**（≥1.20 以支持 16KB 内存页）。
- PDF 降级预览：**`io.legere:pdfiumandroid:1.0.31`**（版本必须钉死 1.0.31，否则 Kotlin metadata 与编译器 1.9.20 冲突；替换旧 pdfbox-android 甩开约 11MB）。
- 播放：系统 `androidx.media:1.7.0` + `MediaPlayer` + `MediaSessionCompat`（**非** ExoPlayer/Media3）。
- 网络：Retrofit 2.9.0 + OkHttp 4.12.0（含 logging、okhttp-sse）+ Gson。
- 协程 kotlinx-coroutines 1.7.3；Material 1.12.0、Coil 2.5.0（封面）、ZXing 3.5.2（支付二维码）、`security-crypto`（加密 SharedPreferences）。
- ABI：release 仅 `arm64-v8a`；debug 加 `x86_64`（供模拟器验证端侧推理）。
- `buildConfigField` 注入 `BASE_URL`（后端 API，默认 `http://api.example.com/`）与 `MOSS_MODULE_BASE`（端侧模型静态托管，默认 `${prodBaseUrl}moss/`）；release 构建有回环地址/明文 http 防呆守卫。

### 3.2 源码包结构

根包 `com.audiobook.audiobookplayer`（扁平放 UI/Service/仓储/工具）+ 子包 `.model`、`.moss`、`.network`、`.parser`、`.update`。

### 3.3 主要 Activity / Fragment

- **`SplashActivity`**（LAUNCHER 入口，全屏启动图 ~1.5s → BookListActivity，衔接更新检查）、**`BookListActivity`**（游客可进入，本地书目读取与多选回传）、**`MainActivity`**（底部导航宿主：发现/书架/书城/我的；实现**游客门禁 `requireLogin(message, after)`**）、**`LoginActivity`**（登录/注册唯一收口）、**`BookContentActivity`**（阅读器核心，见 §3.7）、**`RechargeActivity`**（ZXing 充值）、**`PostDetailActivity`**、**`CreditLedgerActivity`**。
- Fragment：`BookListFragment`（书架：书籍+任务合并行、长按拖放排序、本机合成暂停/继续、清理音频）、`DiscoverFragment`（社区，游客开放）、`BookStoreFragment`（书城：浏览/搜索/在线阅读/按需下载）、`ProfileFragment`（个人中心）、`TtsFragment`（合成页，在线/本机分流）、`FavoritesFragment`、`SearchFragment`、`ReferenceAudioFragment`、`WordBookFragment`、`DiscussionFragment`；BottomSheet：`CreatePostBottomSheet`、`DictLookupSheet`、`ChinDictSheet` 等。

### 3.4 ViewModel 层

**`BookViewModel`**（核心，AndroidViewModel）：
- LiveData：`books/ebooks/tasks/shelfEntries/lines/toc/currentPlayingPosition/currentPlayingSpans/isPlaying/playbackSpeed/sleepTimer/phase/parsePageProgress/onlineAudioAvailable/storeAudioArrived/favorites/bookOpenError`。
- `mergeShelf()` 合并书籍+任务成书架条目；`loadBooks/loadEbooks/reorderShelfBooks`。
- 导入：`importEbook(uri)`、`importFromUrl(url)`、`validateEbookFile`（按扩展名+魔数校验）。
- 打开书：`openBook(book)` → `readServerRowsWithCache(...)`（服务端权威正文）；`requestScrollToLine/rowIndexByAnchor` 供跳转定位。
- 音频入库：`saveAudioSegmentStreaming(...)`（在线，攒批写）与本机合成专用**立即落盘**写入。
- 播放控制：`playFromFirstSegment()`、`playFromProgress(item, segmentNumber, offsetMs)`、`resolveResumeProgress()`（本地与服务端进度合并续听）、`savePlaybackProgress/getSavedProgress`。
- 本机合成孤儿任务对账 `reconcile...`（进程被杀后把 `本机合成 ·` 前缀任务改 partial）、`registerLocalTask`。

`BookStoreViewModel`：书城首页/列表/搜索（游客 permitAll）、在线阅读正文与按需音频下载编排。

### 3.5 `.moss` —— 端侧 MOSS-TTS-Nano 引擎

- **`MossLocalEngine`**（约 914 行，核心）：改写自官方 `android_onnx_runtime` 示例的 ONNX/CPU 推理引擎，0.1B 自回归模型，四张图（prefill / decode_step / local_fixed_sampled_frame + codec decode_full）。关键方法：`synthesize(...)`（文本→token→KV 前向→逐帧 decode→音频 token→codec 解码→48kHz PCM）、`runPrefill/runDecode`（每 8 帧内存探针，危险水位提前收尾）、`encodeReferenceAudio(wav48kStereo)`（参考音频克隆，整本只编码一次）、`selectBuiltinVoicePromptCodes(voice)`（默认 `Junhao`）、`interleaveToPlanar`、`buildInputRows/buildTextRows`。
- **`MossLocalSynthesizer`**（约 1513 行，编排）：`synthesizeBook(...)`（suspend，逐段并行、断点续跑、判重）、`planParallelism(context)`（**按可用内存反推引擎数**而非核数）、`estimateMaxFrames`（与服务端同公式）、内存护栏 `isMemoryCritical/availableBytes`、`pcmToWav`。**与在线合成落到同一入库方法**，下游播放/定位零改动。
- **`MossDownloader`**：模块下载/安装（`fetchManifest`、断点续传 `.part`、`install` 解压校验、可暂停）。
- **`MossManifest` / `MossModuleManager`**：模块清单与目录管理，`isInstalled/missingFiles/verifyAll/sha256`、`builtinVoices`、`hasVoiceCloneModel`（是否有 codec encoder，决定能否克隆）。
- **`LocalSynthesisService`**（前台服务，`foregroundServiceType=dataSync`）：让几十分钟推理在后台继续；`start(context, books, voice, referenceAudioPath)`、`cancel/pause/resume`、`AUDIO_NAME_PREFIX = "本机合成 · "`（区分在线/本机任务）、WakeLock、`Progress` LiveData。
- **`PauseController`**（暂停/恢复闸门）、**`SentencePieceTokenizer`**（纯 Kotlin 分词，弥补官方 Android 未做分词）、**`MossTextSplitter`**（超长段落→小片，控峰值内存防 LMK 杀）、**`ReferenceWavLoader`**（参考音频解析、重采样 48kHz、限 2~10s）、**`PcmJoiner`**（段间 PCM 拼接与淡入淡出）。

### 3.6 `.parser` —— 电子书本地解析

> 本地解析**仅作降级预览**（尤其 PDF），权威解析仍走服务端；解析口径必须与阅读页一致，否则高亮/段号错位。

- `LocalEbookParser`（统一入口 `parse(file)`，按扩展名分派；TXT 编码回退、Markdown 剥离）、`EpubParser`（ZIP/container.xml/OPF spine/NCX/NAV 目录）、`MobiParser`（PDB 容器 + PalmDOC LZ77 解压 + 目录扫描）、`PdfPreviewParser`（**PDFium** 文本提取，去页眉页脚/点线前导，字符级与服务端 PDFBox 对齐）、`ParagraphSplitter`（流式段落/句子切分，与阅读页同口径）、`TextPositions`、`EbookSegmentMap`（约 437 行，**核心风险点，有独立 JVM 单测**：「原文行↔音频段」定位映射，`build/locate/segmentAtOffset/rowsOfSegmentNumber/spansInRowsOfSegment`，供点读与高亮按行按字符区间定位）。

### 3.7 阅读器核心（`BookContentActivity` 2000+ 行 + `LineAdapter`）

- `LineAdapter`（`ListAdapter<LineItem>` + DiffUtil）：`onItemClick(item, charOffset, position)`——一行可含多音频段，靠字符下标做「点哪句播哪句」；`playingPosition` + `playingSpans` 驱动整行/局部高亮。
- 高亮/跳转：`refreshPlayHighlight()`、`highlightReadLine`、`jumpToPosition`、`backToReadAloudPosition`。
- 点击跳段朗读：`readFromHere(item, selStart)` → `playFromProgress`。
- 播放栏：`showReadAloudBar()`、`applyPlaybackBarForBookType()`、可拖拽手柄 `setupChromeHandleDrag`。
- 系统 TTS 实时朗读：`toggleReadAloud/startReadAloud/jumpReadAloud/endReadAloud`（配合 `ReadAloudSpeaker` 窗口式入队 + epoch 过滤陈旧回调）；在线音频连播由 `PlaybackService` 承担。
- 点读/查词：英文走 `DictionaryManager`+`DictLookupSheet`，中文走 `ChinDictManager`+`ChinDictSheet`，单词朗读走 `TextToSpeaker`。
- 长按选区/翻译/收藏：`TextActionModeProvider`、`FallbackSelectionHandles`（MIUI 选词器失效接管）。

### 3.8 `.network` —— API / 鉴权 / SSE

- `ServerConfig`（地址即 `BuildConfig.BASE_URL`）、`RetrofitClient`（单例，**占位 baseUrl + `DynamicBaseUrlInterceptor`** 每请求替换 host；超时分级：轻 10/30/30s、重 15/300/300s、SSE 10/600s；拦截器链 `AuthInterceptor`（注入 Bearer，`/api/auth/*`、`/api/app/version/` 除外）→ `UnauthorizedInterceptor`（401→`AuthManager.handleUnauthorized`）→ `InsufficientCreditsInterceptor`（402→前台弹去充值）→ logging 脱敏）。
- `ApiService`（轻接口：鉴权/任务/书城/社区/收藏/进度/版本）、`HeavyApiService`（上传/下载/长耗时：`uploadBookForTtsWith*` 建任务、`downloadSegment`、`extractContent`、书城内容/音频、`downloadApk`、词典）、`CreditApiService`（积分/支付）。
- `AuthManager`：`EncryptedSharedPreferences`（AES256_GCM）存 token；`login/register/logout`；`handleUnauthorized`（仅会话过期清凭证跳登录，**游客无 token 的 401 直接忽略，绝不强制登录**）。
- `TaskProgressListener` + `SseClient.connectToTaskStream`（连 `/api/tts/stream/{taskId}`，处理 heartbeat/progress/complete/error/init）。

### 3.9 `.update` —— APK 自更新

`UpdateManager`：`checkForUpdate()`（走 `getLatestVersion`，判据 `latest>当前`）、`downloadApk(...)`（下载到私有目录 + MD5 校验）、`installApk(file)`（经 `FileProvider content://` 起安装）、`canInstallPackages`（REQUEST_INSTALL_PACKAGES 授权引导）。`UpdateDialog`：升级弹窗、`checkManually`、`startDownload`（用全局 scope 避免下载中断）。

### 3.10 在线 vs 本机合成 / 游客模式

- **分流在 `TtsFragment` 决策**：在线（`uploadBookForTts*`，需登录耗积分，音色来自服务端预设 `getVoices`，1.41 起预设 id 改用 `demo_id`）；本机（先判 `MossModuleManager.isInstalled`，走 `LocalSynthesisService.start` → `MossLocalSynthesizer` 端侧推理，不耗积分、离线，音色可选模块内置音色或参考音频克隆）。两者落到同一 `audio_segments` 表；任务靠 `Task.audioName` 是否带 `"本机合成 · "` 前缀区分（影响书架按钮），本机任务 `server_task_id` 恒 null。
- **游客模式**：无独立实体，以 `AuthManager.isAuthenticated`（是否有 token）为唯一判据；读类接口服务端 permitAll，写类操作 UI 层 `requireLogin(msg, after)` 拦截、登录后自动接回原操作；退出登录原地刷回游客态不 finish 宿主。

### 3.11 Manifest 与构建

- **Application `AudioBookApplication`**：`allowBackup=false`（加密主密钥不备份）、`networkSecurityConfig`（正式包禁明文）、维护「当前前台 Activity」（供 402 弹窗）；`AuthManager.init` 不在 onCreate（避免拖慢冷启动）。
- **权限**：INTERNET、ACCESS_NETWORK_STATE、FOREGROUND_SERVICE(_MEDIA_PLAYBACK/_DATA_SYNC)、RECORD_AUDIO、POST_NOTIFICATIONS、WAKE_LOCK、REQUEST_INSTALL_PACKAGES；`<queries>` 声明 `TTS_SERVICE`。
- **组件**：`SplashActivity`(exported)、`MossProbeActivity`(exported，供 adb 拉起验证)、`PlaybackService`(mediaPlayback)、`moss.LocalSynthesisService`(dataSync)、`MediaButtonReceiver`、`FileProvider`。
- **签名**：`local.properties`（不入库）键 `AUDIOBOOK_STORE_FILE/_STORE_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD`；未配则 release 不签名并告警。1.29 起用 `release.jks`（证书 `<cert-fingerprint>…`）。release `isMinifyEnabled=true`+`isShrinkResources=true`；debug 若存在 release 签名则复用（避免覆盖安装失败）。无 productFlavors。

---

## 4. AudioBookWeb —— C 端网页

路径 `AudioBookWeb/`，Vue 3.4（`<script setup>`）+ TypeScript 5.3 + Vite 5 + Ant Design Vue 4.1（按需 import）+ Pinia 2 + vue-router 4 + axios；dev 端口 3000，`/api` 代理到 `http://localhost:8080`。强依赖 `@audiobook/shared`（`file:../AudioBookShared`，`preserveSymlinks:true`）。

### 4.1 目录结构

`src/`：`main.ts`（启动顺序「先 `bootstrapAuth` 校验登录态 → 装 router → 挂载」）、`App.vue`（按 nightMode 切 antd darkAlgorithm）、`router/index.ts`（路由表 + `createAuthGuard`）、`layouts/MainLayout.vue`（侧栏/顶栏/移动端抽屉、余额胶囊）、`views/`、`composables/`、`services/`、`stores/`、`utils/`。

### 4.2 关键页面（`views/`）

`Login`（含扫码下载 APK）、`Register`、`BookStore`（书城）、`Shelf`（书架，服务端真源，正常/失效/下架三态）、`Reader`（在线阅读，边读边播句级高亮）、`Player`（听书，逐段播放）、`TtsSynthesis`（上传书+参考音频/预设音色建任务）、`ReferenceLibrary`（我的参考音频）、`TaskList`（任务进度 SSE）、`Discover`（帖子流 + 共享参考音频榜）、`Credits`（积分充值）、`Profile`（个人中心）、`NotFound`。

### 4.3 组合式与状态

- `composables/useAudioEngine.ts`（Web Audio 逐段解码/预调度/淡入淡出/睡眠定时/进度）、`useAudioPreview.ts`（试听统一封装）、`usePlayProgress.ts`（服务端 `/api/progress` + localStorage 双写，服务端优先）。
- `services/`：`apiClient.ts`（axios 工厂 + 401 统一处理 + `X-Client-App:'web'`）、`api.ts`（auth/tts/store/shelf/config/credit/appVersion 全套后端调用）、`discovery.ts`、`favorites.ts`（服务端真源 + 本地乐观缓存 + 跨端去重）、`playbackControl.ts`（全局暂停总线）、`sse.ts`（fetch+ReadableStream 的 SSE 客户端）。
- `stores/`：`auth`（基于 shared `defineAuthStoreBase`，JWT 走 HttpOnly Cookie 不落前端）、`config`、`credits`、`settings`（夜间/字号/缩放）。

### 4.4 路由

公开：`/login`、`/register`、404；`/`→`/store`；书城 `/store`、`/shelf`、`/discover`；阅读听书 `/reader/:bookId`、`/player/:taskId`；创作 `/tts`、`/references`、`/tasks`；用户 `/profile`、`/credits`。守卫 `createAuthGuard`（未登录跳 `/login?redirect=`）。

登录凭据走服务端下发的 HttpOnly Cookie（`AUTH_TOKEN_WEB`，与 Manager 的 `AUTH_TOKEN_MANAGER` 隔离）。与 Android 端并列共享同一后端与数据（收藏/书架/进度跨端共享）。

---

## 5. AudioBookManager —— B 端管理后台

路径 `AudioBookManager/`，技术栈与 Web 相同（Vue 3 + TS + Vite + Ant Design Vue + Pinia + `@audiobook/shared`），端口 `:3001`，与用户端登录态隔离。

### 5.1 入口与鉴权

`main.ts` **先 `bootstrapAuth` 向 `/auth/validate` 校验后才 `app.use(router)` + mount**（避免刷新误踢）；onError 区分 401（清登录态）与 5xx/网络错误（保留登录态不误杀）。`stores/auth` 用 `defineAuthStoreBase({admin:true})`，只持久化 `admin_username/admin_is_admin`。`services/apiClient` 用 `createApiClient({appId:'manager', onUnauthorized})`，401 弹「重新登录」Modal。

### 5.2 菜单与页面（`views/`）

7 项：`AdminUsers`（用户管理）、`AdminBooks`（书城书籍 + 合成控制）、`AdminReference`（参考音频）、`AdminTtsNodes`（TTS 合成节点调度）、`AdminCredits`（积分/套餐/流水）、`AdminAppVersion`（客户端版本发布）、`Discover`（发现内容审核）、`Login`。

### 5.3 调用的后端管理接口（`services/api.ts`）

- `adminApi`（`/api/admin`）：用户 CRUD/设管理员/封禁/重置密码；书籍 CRUD/上传（300s）；合成 `POST /books/{id}/synthesize`(+`/pause`、`/resume`)、`DELETE /books/{id}/synthesize`、`/books/batch-synthesize`；参考音频与主题 CRUD；`GET /api/tts/voices`。
- 节点：`/api/admin/tts/nodes` CRUD + `PUT /{id}/enabled`（含在途数/熔断/探活/schedulableCount/ttsConcurrency 实时指标，即时生效免重启）。
- 版本发布：`GET /api/admin/app-version`、`POST /upload`（APK，300s）、`POST /{id}/release|/unrelease`、`DELETE /{id}`。
- `creditAdminApi`（`/api/admin/credits`）：调分、套餐 CRUD、流水/订单。
- `discovery.ts`：`adminContentApi`（帖子/参考音频审核）+ 只读试听。

---

## 6. AudioBookShared —— 前端共享库

路径 `AudioBookShared/`，`@audiobook/shared`，**纯 TS 源码包**（`main/module/types` 直接指向 `src/index.ts`，`sideEffects:false`），依赖全部为 peerDependencies（axios/dayjs/pinia/vue/vue-router 由宿主提供）。仅被 Manager 与 Web 引用（Server 是 Java、Player 是 Kotlin，不用它）。

模块：
- `types.ts`：**API 契约单一可信源**（认证、`ApiResult<T>`、`StoreBook`、积分、发现、后台审核、跨端 `ReadingProgress/SaveProgress`），与后端 JSON 对齐。
- `http.ts`：`createApiClient`（axios 骨架：baseURL `/api`、`withCredentials`、`X-Client-App` 头、401 拦截骨架，差异化行为由 `onUnauthorized` 注入）。
- `auth-api.ts`（`createAuthApi`）、`auth-store.ts`（`defineAuthStoreBase`，verified 语义、清 legacy 明文 token）、`bootstrap.ts`（`bootstrapAuth`）、`guard.ts`（`createAuthGuard`，含 adminMode）、`logout.ts`（`performLogout`）、`format.ts`（时间/大小/积分/时长格式化）、`index.ts`（统一 re-export）。

两端仅通过注入 options（appId、storageKeys、adminMode、onUnauthorized/onError）体现差异，逻辑复用最大化。

---

## 7. AudioBookLocalTtsTool —— 本机合成与发布工具

路径 `AudioBookLocalTtsTool/`。**注意：GUI 框架是 PySide6（Qt Widgets），不是 Tkinter。**

### 7.1 定位与技术栈

Windows 桌面工具：在**本机**用 MOSS-TTS-Nano 引擎把电子书合成语音，按与 AudioBookServer **完全同构**的口径写入本地 MySQL，再「一键发布到书城」。核心设计：表名/字段/段号规则/音频命名 `{taskId}/tts_{taskId}_{n}.mp3` 与 `AudioBookServer/init_database.sql` 逐字一致，本地结果可原样推服务器、零转换。

依赖（`requirements.txt`）：`PySide6>=6.6`、`PyMySQL>=1.1`（本地库与服务器库都直连）、`pypdf>=4.0`。**关键**：MOSS 引擎依赖（onnxruntime/sentencepiece/numpy）不装在这里——引擎跑在它自己的 conda 解释器里，主进程与引擎进程通过**子进程 + JSON 行协议**通信。

启动：`run.py`（`QApplication`+`MainWindow`）、`start.bat`（双击）。配置持久化在 `%APPDATA%/AudioBookLocalTtsTool/config.json`（可用环境变量 `AUDIOBOOK_LOCAL_TTS_CONFIG` 覆盖）。可 PyInstaller 打包（MOSS 工程本体不打包）。

### 7.2 目录结构

`app/config.py`（`AppConfig/MySqlTarget/SshTarget/MossSettings` + 读写持久化）；`app/core/`：`segmenter.py`（切句，与服务端 `TextSegmenter` 同口径）、`booktext.py`（正文提取入口 `extract_text`）、`mobitext.py`（MOBI/AZW，PalmDOC LZ77）、`moss_bridge.py`（跑在 MOSS 解释器里的引擎桥）、`moss_client.py`（主进程侧 `MossEngine`）、`db.py`（MySQL 存取，**非 sqlite**）、`schema_align.py`（本地库结构对齐服务器 DDL）、`audio.py`（WAV→MP3/时长/静音占位/拼接）、`synthesizer.py`（合成编排主循环）、`uploader.py`（一键发布）、`ssh_upload.py`（`SshClient` + `read_server_info`）、`api_client.py`（本地签发管理员 token 调 content-snapshot）；`app/ui/main_window.py`（主窗口 + `SynthWorker`/`UploadWorker`）；`tools/`（若干 selftest 与命令行入口）。

### 7.3 关键类与函数

- **`MainWindow(QMainWindow)`**（`app/ui/main_window.py`）：左配置 Tab（MOSS 引擎/本地库/服务器/其他）+ 右干活区（合成输入、进度、任务表、上传按钮、日志）。`_selected_books()`（按 `;` 拆多本、去重、过滤不存在）、`_collect_config()`、`_start_synthesis()`（组 `SynthesisRequest[]` → `QThread`+`SynthWorker`）、`_stop_synthesis()`（协作式置 `_stop_flag`）、`_do_upload()`、`_resolve_book_files()`（主线程为待传任务解析原书文件）。线程模型：所有长任务放子线程，worker 只 emit 信号。
- **`synthesize_books(...)`（批量主循环，`synthesizer.py` 736–814）**：建一个 `EngineSession`，`for request in requests` 逐本调 `synthesize_book(session=...)`；单本异常记 `BookResult.error` 不中断整批；`should_stop()` 时剩余本记 skipped；`finally` 关 session。
- **`EngineSession`（201–245）**：`get()` 惰性加载并**整批共用一个模型实例**（省掉每次 ~28s 重复加载）；`_failure` 粘滞；`close()` 停引擎。
- **`synthesize_book(...)`（单本主流程，329–733）**：`extract_text`→`sentences` 切段（一个都不能丢）→ `ensure_schema/connect/ensure_user` → **断点续跑** `find_resumable_task`（判据=路径+总段数+参考音频文件名+音色，状态限 paused/processing/partial/failed）→ 工作目录 `task_{id}/` 留原书副本 → **长段预警**（≥500 字段开跑前打印）→ `prepare_reference`/`find_ffmpeg`/`session.get()` → **段级 for 循环**（逐段 `sub_pieces` 拆 ≤45 字片，逐片 `engine.synthesize(seed=1234+index*100+piece_index, max_new_frames=resolve_max_new_frames)`，片间响应停止，`concat_wavs`→`convert_to_mp3`→`insert_segment` 落库，每 10 段落进度）→ 收尾 stopped→paused / completed / partial。
- **`resolve_max_new_frames(text)`**：`clamp(30 + len*4.0, [30,375])`，与服务端口径一致。
- **心跳**：`HEARTBEAT_SECONDS=5.0`（顶栏）、`HEARTBEAT_LOG_SECONDS=30.0`（日志）、`HEARTBEAT_MIN_PIECES=3`——仅当 `len(pieces)>=3` 且不在最后一片、每片完成后距上次≥5s 才报。
- **`MossEngine.synthesize(...)`（`moss_client.py` 220–281）**：`self._lock` 内写请求行到子进程 stdin，然后每 0.1s 轮询 `self._result`；超时抛「合成超时（900 秒未返回）」。桥接侧 `moss_bridge.py` 把 `sys.stdout` 挪到 stderr、协议数据独占 fd1 写，加载 `OnnxTtsRuntime` 调 `runtime.synthesize(..., streaming=True, voice_clone_max_text_tokens=75)`。
- **`db.py`（MySQL/PyMySQL）**：`DDL_USERS/TASKS/AUDIO_SEGMENTS/TOOL_UPLOAD_LOG`；`audio_data LONGBLOB`（本地自包含，服务器侧恒 NULL）。`connect`（`read_timeout=600/write_timeout=600`）、`find_resumable_task`、`completed_segment_numbers`、`insert_segment(ON DUPLICATE KEY)`、`find/create/update_store_book`、`record_upload/uploaded_task_ids`、`adapt_values`（老库 NOT NULL 自适应）。
- **`uploader.upload_tasks(...)`（201–328）**：`plan_upload` 选任务 → `SshClient` → `read_server_info` 读 env → `ssh.tunnel` 起 `-L` 隧道连服务器库 `127.0.0.1:port` → 逐任务 `_upload_one_to_store`（书城）或 `_upload_one`（书架）。`_push_audio_and_meta`：`_collect_audio_files`（优先工作目录 `seg_*.mp3`，缺则从 BLOB 导出）→ `ssh.pipe_tar` 流式推 → `ssh.list_dir` 核对（缺则抛触发回滚）→ `batch_insert_segments`+`update_task`。
- **管理员 token 本地签发**：`api_client.sign_admin_token`（`key=SHA-512(secret)`、HS512、claims `sub/isAdmin/tokenVersion/iat/exp`、TTL 600s）；`rebuild_content_snapshot(store_book_id)` POST `/api/admin/books/{id}/content-snapshot`（**失败只告警不拦发布**）。

### 7.4 与 Server 的交互（两条通道，不走业务 HTTP）

1. **文件通道 = SSH**：生产 MySQL 只绑 `127.0.0.1:3306`、公网只开 22/80；原书与 mp3 用 scp/tar 流推到 `uploads/`、`store_books/` 目录。
2. **元数据 = SSH `-L` 隧道直连服务器 MySQL**：写 `tasks`（归管理员）+ `audio_segments`（`audio_data=NULL`）+ `store_books`（`status=onshelf`、`task_id` 指向那条任务）。
- **书城 vs 书架**：书城建一条归 `audiobook_admin` 的任务 + `store_books`（普通用户书架看不到）；书架只建任务挂到某用户名下。唯一一次 HTTP 是发布收尾补正文快照（本地库直写导致服务端「合成收尾写快照」从未发生，缺快照首开实测 9.2s，有快照 95ms）。
- 幂等/回滚：同名书默认跳过，勾「强制重发」复用同一条 `store_books`；远端文件数/名校验不过则回滚库行 + 删已传目录与原书。

### 7.5 配置项含义

`MossSettings`：`project_path`（MOSS 工程目录）、`python_exe`（引擎解释器，留空=工具自身解释器）、`model_dir`（留空=工程 `models/`）、`cpu_threads=16`、`execution_provider="cpu"`（cpu/cuda）、`sample_mode="fixed"`（fixed=用导出烘进图的采样常量）、`enable_wetext=False`（WeTextProcessing 文本正则化，依赖 pynini 多数环境装不上故默认关）。`MySqlTarget`（local_db/server_db）、`SshTarget`（host/port/user/key_path/remote_uploads/remote_store/server_env/local_tunnel_port=0 自动挑空闲端口）、发布参数（`publish_target="store"`、`store_owner_username="audiobook_admin"`、`store_category`、`server_api_base`、`local_username="local_tts"`）、`ffmpeg_path`/`keep_wav`/`work_dir`/`server_ddl_path`。

### 7.6 「选 3 本合成，第 1 本成功后卡住」的可疑代码位置

批量与单本的**唯一结构差异**是 `EngineSession` 被跨书复用（`synthesize_books` 736–814），且每本新建一次 MySQL 连接、重跑 `prepare_reference`。按可能性：

- **A. 引擎进程存活但不回话 → 每片各等满 900 秒**（`moss_client.py:220–281`）：进程 `poll()` 返回 None 但桥接侧不返回/不写 done，轮询无整体上限；第 2 本首段若是超长段（几十片）即「开始后长时间一动不动」。书与书之间缺少引擎重置/健康探测。
- **B. `EngineSession.get()` 不校验引擎是否已死 → 第 2 本静默秒失败**（`synthesizer.py:224–237/506`）：若引擎在第 1 本末崩溃，`_engine` 仍返回旧对象，第 2 本每片命中「引擎进程未运行」被当片级失败吞掉，海量 failed 段秒刷完，用户观感即「停在第 2 本」。缺「poll() 非 None 提前熔断」。
- **C. 长段心跳的第一个空窗（看似卡死）**（`synthesizer.py:583–596`）：心跳只在 `len(pieces)>=3` 且非最后一片、每片返回后才发；首片本身 ~20s（或叠加 A 的 900s）期间无回调；单片段更只靠段末进度。与主观「卡住」高度吻合。
- **D. 书间新建 DB 连接 / 参考音频转码**：每本 `database.connect()`（`read/write_timeout=600`），单段大 BLOB `insert_segment` 可阻塞至多 10 分钟；`prepare_reference`（`synthesizer.py:304`）的 `subprocess.run(ffmpeg)` **未设 timeout**（对比 `audio.py:66–69` 有 `timeout=300`），ffmpeg 卡住会无限期阻塞 worker——最直接的「真死锁」点。
- **E. 若卡在发布**：`ssh_upload.py:258–300` `pipe_tar` 的 `proc.wait()` **无超时**；`upload_tasks` 在隧道内串行处理所有任务，单任务网络挂起拖住整批。
- **F. 停止/关窗后残留 MOSS 进程**：`main_window.py:1119–1124` `closeEvent` 只 `quit()+wait(3000)`，worker 若阻塞在 C/D 则 `MossEngine.stop()` 不执行，桥接子进程（占 16 线程 CPU）成孤儿继续跑，与后续批次抢 CPU。

**排查顺序**：先看日志有没有第 2 本「开始合成」那行（`synthesize_books:778`）——有开始无心跳无失败 → 查 A/C/D；有开始随后海量「引擎进程未运行」→ 查 B；停在「正在上传 X 个音频文件」→ 查 E。

---

## 8. scripts —— 构建与环境

- **`setup_env.bat`**：Windows 一键环境搭建。① JDK（优先复用 Android Studio 自带 JBR，缺失则 `winget install Microsoft.OpenJDK.21`）；② Maven；③ Android SDK（API 34 + build-tools 34 + platform-tools，回写 `AudioBookPlayer/local.properties` 的 `sdk.dir`）；④ 运行期依赖提示（MySQL→`init_database.sql`、Redis `localhost:6379`、MOSS-TTS `localhost:18083`）。
- **`mvn.sh`**：Maven 包装脚本（绕过 Git Bash 下 classpath 被空格/反斜杠打坏的问题），**JDK 固定 17**（Spring Boot 3.2 / Java 17；用 21+ 会因 Lombok+旧 ASM 报大量「找不到符号」）。用于构建 Server。
- **`gradle.sh`**：Gradle 包装脚本（绕过 `bin/gradle` 对 `cygpath` 的依赖），用 Studio JBR 起 Gradle 8.10。用于构建 Player。
- **`SDK`**：0 字节空占位文件，当前不承载逻辑。

> 补充：AudioBookPlayer 仓库里**没有 `gradlew` 脚本**（只有 `gradle/wrapper/gradle-wrapper.properties` 指向 gradle-8.10）。实际构建/发布时可直接用缓存的 Gradle 发行版，例如 `.../gradle-8.10/bin/gradle.bat :app:assembleRelease`。APK 发布走 `AdminAppVersionController`（`POST /api/admin/app-version/upload`，multipart，`release=true`），服务端强制 `versionCode` 严格大于库内最大值并去重。

---

## 9. 关键跨模块机制小结

1. **切句口径三端统一**：Server `TextSegmenter.sentences`、Player `ParagraphSplitter`、LocalTtsTool `segmenter.py` 必须同口径（一个句子=一个 segment=一行 DB=一个 mp3），否则阅读页高亮/段号/进度错位。
2. **帧预算公式统一**：`max_new_frames = clamp(30 + 字数×4.0, [30, 375])`，Server / Player / LocalTtsTool 一致。
3. **音频接缝与包络**：Server 侧 `WavConcat`（拼接，格式不一致抛、流式 WAV data size 截断容错）+ `WavClauseJoiner`（默认开，句内子片一致性）+ `WavEnvelope`（默认关，段级整形）；Player 侧对应 `PcmJoiner`。
4. **引擎路由与多节点**：`decideEngineType`（中文/未配置走 MOSS，拉丁语系配置走 Pocket）+ `TtsLoadBalancer`/`TtsNodeRegistry`（按引擎隔离选点、熔断冷却、探活、运行期热更新）。
5. **断点续跑**：Server 靠 `COMPLETED && 文件存在` 跳过 + `TtsTaskRecoveryRunner` 启动重派 + `ShutdownSignal` 关闭不落终态；LocalTtsTool 靠 `find_resumable_task` 复用 task_id 跳过已完成段。
6. **在线 vs 本机合成**：Player 端 `TtsFragment` 分流，产物落同一 `audio_segments` 表，靠 `Task.audioName` 前缀 `"本机合成 · "` 区分；本机不耗积分、离线。
7. **游客模式**：以「是否有 token」为唯一判据，读类接口服务端 permitAll、写类操作客户端 `requireLogin` 拦截、登录后接回原操作。
8. **APK 自更新**：Player `UpdateManager` 查 `/api/app/version/latest` → 下载校验 MD5 → `FileProvider` 拉起安装；Manager 版本页上传发布；服务端强制 versionCode 递增。
9. **前端复用**：Web 与 Manager 共用 `@audiobook/shared`（契约/认证/HTTP/守卫），仅靠 `X-Client-App`（web/manager）区隔 Cookie（`AUTH_TOKEN_WEB`/`AUTH_TOKEN_MANAGER`），登录态互不顶掉。

---

*文档结束。如需对某个类/方法进一步展开到逐行级别（例如 `VoiceCloningTtsService.callPocketEngine` 的完整故障切换、`MossLocalSynthesizer.synthesizeBook` 的段循环、或 `EbookSegmentMap` 的定位算法），可在此基线上继续细化。*
