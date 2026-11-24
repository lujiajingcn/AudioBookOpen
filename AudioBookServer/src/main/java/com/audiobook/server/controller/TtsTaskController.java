package com.audiobook.server.controller;

import com.audiobook.server.dto.TtsTaskResponse;
import com.audiobook.server.exception.InsufficientCreditsException;
import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.TaskStatus;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.StoreBookRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.service.BookTextExtractor;
import com.audiobook.server.service.CreditService;
import com.audiobook.server.service.RedisTaskService;
import com.audiobook.server.service.TtsService;
import com.audiobook.server.service.VoiceCloningTtsService;
import com.audiobook.server.util.PageUtil;
import com.audiobook.server.util.PocketVoiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS 任务生命周期：创建（上传/书城）、列表查询、状态、重试、删除、目录。
 * 由原 Controller.java（God Object）拆分而来，路径保持不变。
 */
@RestController
@RequestMapping("/api/tts")
public class TtsTaskController {

    private static final Logger log = LoggerFactory.getLogger(TtsTaskController.class);

    /**
     * 任务列表分页 size 上限：分页参数来自前端，不设限时一次拉全表
     * （含每行 DTO 转换）足以打爆堆内存。复用 {@link PageUtil#MAX_PAGE_SIZE} 的统一规范。
     */
    private static final int MAX_TASKS_PAGE_SIZE = PageUtil.MAX_PAGE_SIZE;

    /**
     * 无分页参数时的兜底上限：对不传 page/size 的调用方（Web 任务页/个人中心的
     * 活跃任务跟踪）无 LIMIT 全量返回时，任务历史多的用户一次拉全部行 + DTO 转换，
     * 可被当放大攻击面。
     * 列表按 created_at 倒序，500 条远大于活跃任务规模（单用户未完成配额
     * 默认 10），截掉的只是最老的历史完成任务；超限时响应携带
     * truncated 标志，total 仍为真实总数，客户端语义不被破坏。
     */
    private static final int MAX_TASKS_BULK_LIMIT = 500;

    private final TtsService service;
    private final TaskRepository taskRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final StoreBookRepository storeBookRepository;
    private final RedisTaskService redisTaskService;
    private final BookTextExtractor bookTextExtractor;
    private final CreditService creditService;
    private final TtsControllerSupport support;

    @Value("${app.store.dir:store_books}")
    private String storeDir;

    /**
     * 可选预设音色清单（逗号分隔的 voiceId），覆盖于 application.yml 的
     * moss.tts.voices；缺省给一组常用音色。前端「按段/按角色绑定音色」时拉取本清单。
     *
     * <p><b>⚠️ 取值必须与线上引擎的 demo_id 一致</b>：MOSS-TTS-Nano 的
     * {@code /api/generate} 只认 {@code demo_id} 字段，且 demo_id 只能是它自带的
     * {@code assets/demo.jsonl} 里那批（demo-1 … demo-29）。原先这里配的
     * {@code xiaoyan / xiaoyu / aisjiuxu …} 是**云端 MOSS API 的音色 id**，
     * 引擎侧根本不存在 —— 建任务时会被引擎 400 拒掉
     * （{@code demo_id is required unless prompt speech is uploaded}），
     * 而服务端当时又把该错误吞成了 500「服务器错误」，用户完全看不懂。
     */
    @Value("${moss.tts.voices:demo-1,demo-2,demo-3,demo-4,demo-5,demo-6}")
    private String voicesConfig;

    /**
     * Pocket-TTS 引擎开关，与 {@code VoiceCloningTtsService} 同源配置。
     * 关闭时下拉不列出 Pocket 音色——列出来也只会让运营选到「选了必失败」的音色。
     */
    @Value("${pocket.tts.enabled:false}")
    private boolean pocketEnabled;

    /** Pocket 预设音色清单（{@code id} 或 {@code id:显示名}，逗号分隔），解析口径见 {@link PocketVoiceConfig}。 */
    @Value("${pocket.tts.voices:}")
    private String pocketVoicesConfig;

    /**
     * 预设音色 id → 中文展示名（前端下拉用，未列者回退为 id 本身）。
     *
     * <p>用 {@code Map.ofEntries} 而非 {@code Map.of}：后者最多只接受 10 对键值，
     * 而引擎的 demo 清单有 29 个 —— 用 {@code Map.of} 会直接编译不过。
     */
    private static final java.util.Map<String, String> VOICE_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("demo-1", "新闻女声（标准播音）"),
            java.util.Map.entry("demo-2", "温柔女声（深夜电台）"),
            java.util.Map.entry("demo-3", "台湾腔女声"),
            java.util.Map.entry("demo-4", "京味男声（胡同闲聊）"),
            java.util.Map.entry("demo-5", "儒雅男声（知识讲堂）"),
            java.util.Map.entry("demo-6", "都市女声（独白）"),
            java.util.Map.entry("demo-7", "English - OpenMOSS"),
            java.util.Map.entry("demo-8", "English - The Bitter Lesson"),
            java.util.Map.entry("demo-9", "English - News"),
            java.util.Map.entry("demo-10", "English - A Gentle Reminder"),
            java.util.Map.entry("demo-11", "English - Taylor Swift"),
            java.util.Map.entry("demo-12", "English - The Quiet Motion"),
            java.util.Map.entry("demo-13", "日本語 - ニュース"),
            java.util.Map.entry("demo-14", "한국어 - 뉴스"),
            java.util.Map.entry("demo-15", "Español - Noticiero"),
            java.util.Map.entry("demo-16", "Français - Journal"),
            java.util.Map.entry("demo-17", "Deutsch - Nachrichten"),
            java.util.Map.entry("demo-18", "Italiano - Telegiornale"),
            java.util.Map.entry("demo-19", "Magyar - Híradó"),
            java.util.Map.entry("demo-20", "Русский - Новости"),
            java.util.Map.entry("demo-21", "فارسی - اخبار"),
            java.util.Map.entry("demo-22", "العربية - النشرة الإخبارية"),
            java.util.Map.entry("demo-23", "Polski - Wiadomości"),
            java.util.Map.entry("demo-24", "Português - Noticiário"),
            java.util.Map.entry("demo-25", "Čeština - Zprávy"),
            java.util.Map.entry("demo-26", "Dansk - Nyhederne"),
            java.util.Map.entry("demo-27", "Svenska - Nyheterna"),
            java.util.Map.entry("demo-28", "Ελληνικά - Ειδήσεις"),
            java.util.Map.entry("demo-29", "Türkçe - Haber Bülteni")
    );

    /**
     * 相对路径的 app.store.dir 统一以进程工作目录（user.dir）解析为绝对路径，
     * 与 BookStoreService / AdminService / TtsControllerSupport 的目录基准保持一致，
     * 杜绝「从书城建任务」读到与扫描入库不同的那份 store_books。
     */
    @PostConstruct
    void normalizeStoreDir() {
        java.io.File dir = new java.io.File(storeDir);
        if (!dir.isAbsolute()) {
            storeDir = new java.io.File(System.getProperty("user.dir"), storeDir).getPath();
        }
    }

    /**
     * TTS 任务队列容量，与 AsyncConfig 的线程池队列保持同源。
     * 建任务前先校验未完成任务数，队列将满时直接返回 429，
     * 避免 AbortPolicy 在事务提交后才拒绝、任务卡在中间态。
     */
    @Value("${app.tts.queue-capacity:200}")
    private int ttsQueueCapacity;

    /** 未完成任务是否已占满队列（含在跑+排队），占满则拒绝新任务 */
    private boolean isTtsQueueFull() {
        long unfinished = taskRepository.countByStatusIn(TaskStatus.unfinishedValues());
        return unfinished >= ttsQueueCapacity;
    }

    /**
     * 磁盘配额：单用户未完成任务数上限。
     * 每个任务都会落盘电子书源文件 + uploads/{taskId}/ 音频目录，不限制的话
     * 一个账号就能把磁盘写满。<=0 表示不限制。
     */
    @Value("${app.quota.max-unfinished-tasks-per-user:10}")
    private int maxUnfinishedTasksPerUser;

    /** 该用户未完成任务数是否已达个人配额上限 */
    private boolean isUserTaskQuotaReached(User user) {
        if (user == null || maxUnfinishedTasksPerUser <= 0) {
            return false;
        }
        long unfinished = taskRepository.countByUserIdAndStatusIn(
                user.getId(), TaskStatus.unfinishedValues());
        return unfinished >= maxUnfinishedTasksPerUser;
    }

    public TtsTaskController(TtsService service,
                             TaskRepository taskRepository,
                             AudioSegmentRepository audioSegmentRepository,
                             StoreBookRepository storeBookRepository,
                             RedisTaskService redisTaskService,
                             BookTextExtractor bookTextExtractor,
                             CreditService creditService,
                             TtsControllerSupport support) {
        this.service = service;
        this.taskRepository = taskRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.storeBookRepository = storeBookRepository;
        this.redisTaskService = redisTaskService;
        this.bookTextExtractor = bookTextExtractor;
        this.creditService = creditService;
        this.support = support;
    }

    /**
     * 上传电子书和参考音频，创建TTS任务
     * reference_audio 和 reference_id 二选一：
     * - reference_audio: 直接上传参考音频文件（兼容旧版）
     * - reference_id: 引用已通过 /upload-reference 上传的参考音频（批量合成时避免重复上传）
     */
    @PostMapping(value = "/upload-book", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBookForTts(
            @RequestParam("book_file") MultipartFile bookFile,
            @RequestParam(value = "reference_audio", required = false) MultipartFile referenceAudio,
            @RequestParam(value = "reference_id", required = false) String referenceId,
            @RequestParam(value = "reference_source", required = false) String referenceSource,
            @RequestParam(value = "voice_id", required = false) String voiceId,
            @RequestParam(value = "segment_voices", required = false) String segmentVoicesJson,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();

        log.info("========== 收到上传请求 ==========");
        log.info("认证信息: {}", authentication != null ? authentication.getName() : "null");
        log.info("电子书文件名: {}, 大小: {} bytes", bookFile.getOriginalFilename(), bookFile.getSize());
        if (referenceAudio != null && !referenceAudio.isEmpty()) {
            log.info("参考音频（直接上传）: {}, 大小: {} bytes", referenceAudio.getOriginalFilename(), referenceAudio.getSize());
        } else if (referenceId != null && !referenceId.isEmpty()) {
            log.info("参考音频（引用ID）: {}", referenceId);
        } else {
            log.warn("未提供参考音频");
        }

        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                log.warn("用户未认证，authentication: {}", authentication);
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            String userId = authentication.getName();
            log.info("用户ID: {}", userId);

            if (bookFile.isEmpty()) {
                log.warn("电子书文件为空");
                response.put("success", false);
                response.put("message", "电子书文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            // 文件大小限制：与 multipart.max-file-size 统一（前端 /api/config 同源）
            if (bookFile.getSize() > support.maxFileSizeBytes()) {
                log.warn("电子书文件过大: {} bytes，上限 {} bytes", bookFile.getSize(), support.maxFileSizeBytes());
                response.put("success", false);
                response.put("message", "电子书文件大小不能超过 " + TtsControllerSupport.formatMB(support.maxFileSizeBytes()) + "MB");
                return ResponseEntity.badRequest().body(response);
            }

            // 验证至少提供了参考音频（直接上传或引用ID）或预设音色
            boolean hasDirectAudio = referenceAudio != null && !referenceAudio.isEmpty();
            boolean hasReferenceId = referenceId != null && !referenceId.isEmpty();
            boolean hasVoice = voiceId != null && !voiceId.isBlank();
            if (!hasDirectAudio && !hasReferenceId && !hasVoice) {
                log.warn("未提供参考音频或预设音色");
                response.put("success", false);
                response.put("message", "必须提供参考音频文件、reference_id 或预设音色 voice_id");
                return ResponseEntity.badRequest().body(response);
            }

            // 引用库文件时校验合法性：防止越权引用他人/不存在的文件，同时杜绝把任意字符串当路径的穿越风险
            if (hasReferenceId) {
                String resolved = support.resolveReferenceFileName(referenceId, referenceSource, userId);
                if (resolved == null) {
                    response.put("success", false);
                    response.put("message", "参考音频不存在或无权限引用");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                }
                referenceId = resolved;
            }

            String bookFilename = bookFile.getOriginalFilename();
            String extension = support.getFileExtension(bookFilename);
            if (!support.isSupportedEbookFormat(extension)) {
                log.warn("文件格式不支持: {}", bookFilename);
                response.put("success", false);
                response.put("message", "不支持的电子书格式，支持：TXT / PDF / EPUB / MOBI / MD");
                return ResponseEntity.badRequest().body(response);
            }

            log.info("收到新任务请求，进入任务配额 / 数量校验");

            // 磁盘配额：单用户未完成任务数超限则拒绝，防止一个账号无限堆积任务写满磁盘
            if (isUserTaskQuotaReached(support.currentUser(authentication))) {
                log.warn("用户 {} 未完成任务数已达配额上限 {}，拒绝新任务", userId, maxUnfinishedTasksPerUser);
                response.put("success", false);
                response.put("message", "您的进行中任务已达上限（" + maxUnfinishedTasksPerUser
                        + " 个），请等待完成或删除历史任务后再试");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }

            // 前置容量校验：队列占满时快速返回 429，而不是让任务入库后被线程池拒绝
            if (isTtsQueueFull()) {
                log.warn("TTS 任务队列已满（容量 {}），拒绝新任务", ttsQueueCapacity);
                response.put("success", false);
                response.put("message", "当前合成任务排队较多，请稍后再试");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }

            log.info("开始创建TTS任务...");
            // Redis 任务登记与异步派发都在 createTtsTask 内部、事务提交后统一完成，
            // 这里不要再重复 recordNewTask，否则会把已经跑起来的任务状态覆盖回 queued
            // 用户自行上传的书与书城无关：storeBookId 传 null，不生成正文快照
            Long taskId = service.createTtsTask(bookFile, referenceAudio, referenceId, extension, userId, voiceId, segmentVoicesJson, null);

            log.info("✅ TTS任务创建成功，任务ID: {} - 将立即返回，后台异步处理", taskId);

            response.put("success", true);
            response.put("taskId", taskId);
            // 返回段落数，供前端预估耗时
            Task created = taskRepository.findById(taskId).orElse(null);
            response.put("totalSegments", created != null ? created.getTotalSegments() : 0);
            response.put("message", "任务已创建，正在后台处理中");
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // 业务校验异常（如「当前节点要求参考音频，无法只用预设音色合成」）。
            // 必须单独接住并放在 catch (Exception) 之前：
            // 兜底分支会把它包装成 500「服务器错误，请稍后重试」，
            // 那句可操作的提示（告诉用户去传参考音频）就彻底丢了，
            // 用户只看到一句毫无意义的「服务器错误」。
            //
            // 这里刻意不自行拼文案，而是交回 GlobalExceptionHandler.handleIllegalArgument
            // 统一处理：它有一张「技术性消息」前缀白名单，能把 JDK 抛的
            // IllegalArgumentException（URLDecoder / For input string 等实现细节）
            // 挡在服务端，不外泄给客户端。
            log.warn("创建 TTS 任务被业务校验拒绝: user={}, reason={}",
                    authentication != null ? authentication.getName() : "unknown", e.getMessage());
            throw e;
        } catch (InsufficientCreditsException e) {
            // 必须在 catch (Exception) 之前单独接住：否则会被兜底分支包装成 500，
            // 前端无法识别「该去充值」，用户只会看到莫名的服务器错误。
            log.warn("积分不足，拒绝创建任务: user={}, required={}, balance={}",
                    authentication != null ? authentication.getName() : "unknown", e.getRequired(), e.getBalance());
            return support.insufficientCreditsResponse(e);
        } catch (IOException e) {
            log.error("文件上传失败", e);
            response.put("success", false);
            response.put("message", "文件上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (Exception e) {
            log.error("服务器错误", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 从书城书籍直接创建 TTS 合成任务（打通"逛书城 → 合成"闭环）。
     * 用户无需先下载电子书到本地再上传，服务端直接读书城文件建任务。
     */
    @PostMapping(value = "/from-store", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createTtsFromStore(
            @RequestParam("bookId") Long bookId,
            @RequestParam(value = "reference_audio", required = false) MultipartFile referenceAudio,
            @RequestParam(value = "reference_id", required = false) String referenceId,
            @RequestParam(value = "reference_source", required = false) String referenceSource,
            @RequestParam(value = "voice_id", required = false) String voiceId,
            @RequestParam(value = "segment_voices", required = false) String segmentVoicesJson,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            StoreBook book = storeBookRepository.findById(bookId).orElse(null);
            if (book == null) {
                response.put("success", false);
                response.put("message", "书籍不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // 定位书城文件（分类子目录优先，兼容"默认分类"放根目录）
            // category 为 NULL 的历史数据按根目录查找（与 BookStoreService.resolveBookFile 口径一致，
            // new File(storeDir, null) 会抛 NPE）
            String category = book.getCategory();
            java.io.File bookFile = category != null
                    ? new java.io.File(new java.io.File(storeDir, category), book.getFileName())
                    : new java.io.File(storeDir, book.getFileName());
            if (!bookFile.exists()) {
                bookFile = new java.io.File(storeDir, book.getFileName());
            }
            if (!bookFile.exists()) {
                response.put("success", false);
                response.put("message", "书籍文件已下架或缺失");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            String extension = book.getFileExtension();
            if (extension == null || !support.isSupportedEbookFormat(extension)) {
                response.put("success", false);
                response.put("message", "不支持的电子书格式");
                return ResponseEntity.badRequest().body(response);
            }

            // 校验至少提供了参考音频（直接上传或引用库文件名）或预设音色
            boolean hasDirectAudio = referenceAudio != null && !referenceAudio.isEmpty();
            boolean hasReferenceId = referenceId != null && !referenceId.isEmpty();
            boolean hasVoice = voiceId != null && !voiceId.isBlank();
            if (!hasDirectAudio && !hasReferenceId && !hasVoice) {
                response.put("success", false);
                response.put("message", "必须提供参考音频文件、从我的库选择或预设音色 voice_id");
                return ResponseEntity.badRequest().body(response);
            }

            // 引用库文件时校验合法性（同 /upload-book）
            if (hasReferenceId) {
                String resolved = support.resolveReferenceFileName(referenceId, referenceSource, user.getUsername());
                if (resolved == null) {
                    response.put("success", false);
                    response.put("message", "参考音频不存在或无权限引用");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                }
                referenceId = resolved;
            }

            // 磁盘配额：与 /upload-book 同口径，未完成任务超限则拒绝
            if (isUserTaskQuotaReached(user)) {
                log.warn("用户 {} 未完成任务数已达配额上限 {}，拒绝书城建任务: bookId={}",
                        user.getUsername(), maxUnfinishedTasksPerUser, bookId);
                response.put("success", false);
                response.put("message", "您的进行中任务已达上限（" + maxUnfinishedTasksPerUser
                        + " 个），请等待完成或删除历史任务后再试");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }

            // 前置容量校验：队列占满时快速返回 429
            if (isTtsQueueFull()) {
                log.warn("TTS 任务队列已满（容量 {}），拒绝新任务: bookId={}", ttsQueueCapacity, bookId);
                response.put("success", false);
                response.put("message", "当前合成任务排队较多，请稍后再试");
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            }

            final java.io.File sourceFile = bookFile;
            // 书城文件不走 multipart 容器，必须手动套用与上传相同的大小上限：
            // 不限制时巨书会被 readAllBytes 整本进堆（包装/落盘各一份拷贝）→ OOM。
            final long sourceSize = sourceFile.length();
            long sizeLimit = support.maxFileSizeBytes();
            if (sourceSize > sizeLimit) {
                log.warn("书城文件超过大小上限，拒绝建任务: bookId={}, size={} bytes, limit={} bytes",
                        bookId, sourceSize, sizeLimit);
                response.put("success", false);
                response.put("message", "书籍文件过大（上限 " + TtsControllerSupport.formatMB(sizeLimit) + " MB），无法创建合成任务");
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
            }

            // 把书城文件以「文件流」方式包装成 MultipartFile，复用现有 createTtsTask 全链路
            // （复制到 uploads、提取、切段、派发）。全程流式读写，不把整本书载入堆内存。
            MultipartFile wrapped = new MultipartFile() {
                @Override
                public String getName() {
                    return "book_file";
                }
                @Override
                public String getOriginalFilename() {
                    return book.getFileName();
                }
                @Override
                public String getContentType() {
                    return "application/octet-stream";
                }
                @Override
                public boolean isEmpty() {
                    return sourceSize == 0;
                }
                @Override
                public long getSize() {
                    return sourceSize;
                }
                @Override
                public byte[] getBytes() throws IOException {
                    // 仅视频转码等边缘路径会走到；大小已前置校验，堆占用有界
                    return java.nio.file.Files.readAllBytes(sourceFile.toPath());
                }
                @Override
                public InputStream getInputStream() throws IOException {
                    return java.nio.file.Files.newInputStream(sourceFile.toPath());
                }
                @Override
                public void transferTo(File dest) throws IOException, IllegalStateException {
                    java.nio.file.Files.copy(sourceFile.toPath(), dest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            };

            // 书城书籍：把 bookId 带进任务，异步提取时会顺手落「正文快照」（W1）
            Long taskId = service.createTtsTask(wrapped, referenceAudio, referenceId, extension, user.getUsername(), voiceId, segmentVoicesJson, bookId);
            response.put("success", true);
            response.put("taskId", taskId);
            Task created = taskRepository.findById(taskId).orElse(null);
            response.put("totalSegments", created != null ? created.getTotalSegments() : 0);
            response.put("message", "任务已创建，正在后台处理中");
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            // 同 upload-book：业务校验异常必须在兜底 catch 之前拆出来，
            // 否则「该节点不支持仅预设音色」这类可操作提示会被压成「创建失败，请稍后重试」。
            log.warn("从书城创建任务被业务校验拒绝: bookId={}, reason={}", bookId, e.getMessage());
            throw e;
        } catch (InsufficientCreditsException e) {
            log.warn("积分不足，拒绝从书城创建任务: bookId={}, required={}, balance={}",
                    bookId, e.getRequired(), e.getBalance());
            return support.insufficientCreditsResponse(e);
        } catch (Exception e) {
            log.error("从书城创建任务失败: bookId={}, error={}", bookId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "创建失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取当前用户的任务列表。
     * 可选分页参数 page/size（从 1 开始）；不传则返回最近
     * {@link #MAX_TASKS_BULK_LIMIT} 条（兼容 Android 端与 Web 端的
     * 活跃任务跟踪，超限携带 truncated 标志）。
     *
     * 分页走数据库级 LIMIT/OFFSET + count：先全量加载该用户全部任务再内存 subList 时，
     * 任务多的用户每次请求都要全表反序列化 + DTO 转换，且 size 无上限可被一次拉全表。
     */
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> getUserTasks(
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            String userId = authentication.getName();
            User user = support.userByUsername(userId);
            if (user == null) {
                throw new RuntimeException("用户不存在: " + userId);
            }

            List<Task> tasks;
            long total;
            if (page != null && size != null && page >= 1 && size >= 1) {
                int pageSize = Math.min(size, MAX_TASKS_PAGE_SIZE);
                Page<Task> taskPage = taskRepository.findByUserIdOrderByCreatedAtDesc(
                        user.getId(), PageRequest.of(page - 1, pageSize));
                tasks = taskPage.getContent();
                total = taskPage.getTotalElements();
                response.put("page", page);
                response.put("size", pageSize);
                response.put("truncated", false);
            } else {
                // 用带上限的 Page 查询（第 1 页、上限条数），不全量返回；
                // total 来自 count 仍为真实总数；超限置 truncated，前端可据此提示。
                Page<Task> taskPage = taskRepository.findByUserIdOrderByCreatedAtDesc(
                        user.getId(), PageRequest.of(0, MAX_TASKS_BULK_LIMIT));
                tasks = taskPage.getContent();
                total = taskPage.getTotalElements();
                response.put("truncated", total > tasks.size());
            }

            // 返回精简 DTO（taskId 字段名与前端对齐，并附带书名供在线听书页显示；
            // errorMessage 供 failed 任务直接展示失败原因）
            List<TtsTaskResponse> taskList = tasks.stream()
                    .map(t -> {
                        TtsTaskResponse dto = new TtsTaskResponse(
                                t.getId(), t.getStatus(), t.getTotalSegments(),
                                t.getCompletedSegments(), t.getCreatedAt(), t.getOriginalBookName());
                        dto.setErrorMessage(t.getErrorMessage());
                        dto.setCreditsCost(t.getCreditsCost());
                        return dto;
                    })
                    .toList();

            response.put("total", total);
            response.put("success", true);
            response.put("tasks", taskList);
            // 当前系统排队规模：前端对 queued/pending 任务展示「排队中 · 队列约 N 个」
            response.put("queuedCount", redisTaskService.getSystemStats().getOrDefault(TaskStatus.QUEUED.value(), 0L));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询任务列表失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable Long taskId,
                                                             Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                if (taskRepository.findById(taskId).isEmpty()) {
                    response.put("success", false);
                    response.put("message", "任务不存在");
                    // .notFound().build() 会丢弃已 put 的响应体，
                    // "任务不存在"提示永远到不了前端；改用 body(response) 携带消息
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            TtsTaskResponse taskResponse = new TtsTaskResponse(
                    task.getId(),
                    task.getStatus(),
                    task.getTotalSegments(),
                    task.getCompletedSegments(),
                    task.getCreatedAt()
            );
            taskResponse.setErrorMessage(task.getErrorMessage());
            taskResponse.setCreditsCost(task.getCreditsCost());

            response.put("success", true);
            response.put("task", taskResponse);
            // 当前系统排队规模：客户端对 queued/pending 任务展示「排队中 · 队列约 N 个」
            response.put("queuedCount", redisTaskService.getSystemStats().getOrDefault(TaskStatus.QUEUED.value(), 0L));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("查询任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "查询任务失败");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 返回可选预设音色清单，供前端「按段/按角色绑定音色」下拉选择。
     * 清单来自配置 moss.tts.voices（默认一组常用音色），每个元素含 id 与中文名。
     */
    @GetMapping("/voices")
    public ResponseEntity<Map<String, Object>> getVoices() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, String>> list = new ArrayList<>();
        if (voicesConfig != null) {
            for (String id : voicesConfig.split(",")) {
                id = id.trim();
                if (id.isEmpty()) continue;
                Map<String, String> v = new HashMap<>();
                v.put("id", id);
                v.put("name", VOICE_NAMES.getOrDefault(id, id));
                v.put("engine", "MOSS");
                list.add(v);
            }
        }
        // Pocket 预设音色仅在引擎开启时列出：关闭状态下列出只会让运营选到必失败的音色。
        // 显示名走 pocket.tts.voices 自带配置（id 或 id:显示名），解析口径与合成侧选引擎一致。
        if (pocketEnabled) {
            for (Map.Entry<String, String> e : PocketVoiceConfig.parse(pocketVoicesConfig).entrySet()) {
                Map<String, String> v = new HashMap<>();
                v.put("id", e.getKey());
                v.put("name", e.getValue());
                v.put("engine", "POCKET");
                list.add(v);
            }
        }
        response.put("success", true);
        response.put("voices", list);
        return ResponseEntity.ok(response);
    }

    /**
     * 获取任务的音频片段列表（返回片段元数据，供客户端逐个下载）
     */
    @GetMapping("/task/{taskId}/segments-list")
    public ResponseEntity<Map<String, Object>> getTaskSegmentsList(@PathVariable Long taskId,
                                                                   Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                if (taskRepository.findById(taskId).isEmpty()) {
                    response.put("success", false);
                    response.put("message", "任务不存在");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            // 用元数据投影查询，避免把整本书的 @Lob audio_data 大字段一次性加载进堆
            List<AudioSegmentRepository.SegmentMeta> segments =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);

            // 构建精简的片段信息列表（不含音频数据）
            List<Map<String, Object>> segmentInfoList = new java.util.ArrayList<>();
            for (AudioSegmentRepository.SegmentMeta segment : segments) {
                Map<String, Object> info = new HashMap<>();
                info.put("id", segment.getId());
                info.put("segmentNumber", segment.getSegmentNumber());
                info.put("textContent", segment.getTextContent());
                info.put("status", segment.getStatus());
                info.put("errorMessage", segment.getErrorMessage());
                // 返回本段绑定音色，供前端回显 / 二次编辑
                info.put("voice", segment.getVoice());
                segmentInfoList.add(info);
            }

            response.put("success", true);
            response.put("segments", segmentInfoList);
            response.put("total", segmentInfoList.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "查询片段列表失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 修改任务的音色分配（任务级默认音色 + 段落级映射），并触发受影响片段的重新合成。
     *
     * <p>请求体：{ "defaultVoiceId": "可选", "segmentVoices": { "1": "xiaoyan", "5": "xiaoyu" } }
     * segmentVoices 的 value 传 null / 空串表示清除该段预设、回退到克隆参考音频。
     * 仅当任务处于终态（completed / partial / failed）时才允许修改；处理中任务拒绝。
     */
    @PostMapping("/task/{taskId}/voices")
    public ResponseEntity<Map<String, Object>> updateTaskVoices(@PathVariable Long taskId,
                                                              @RequestBody Map<String, Object> body,
                                                              Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                if (taskRepository.findById(taskId).isEmpty()) {
                    response.put("success", false);
                    response.put("message", "任务不存在");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            if (TaskStatus.isUnfinished(task.getStatus())) {
                response.put("success", false);
                response.put("message", "任务仍在处理中，请稍后再修改音色");
                return ResponseEntity.badRequest().body(response);
            }
            // paused 同理（isUnfinished 不含它）：applySegmentVoices 会把段重置为 pending、
            // 把任务改回 queued 再派发，但**不会**清掉内存里的暂停标记 ——
            // 工作线程被派发后第一件事就是检查该标记并立刻收手，任务卡在 queued 无人执行，
            // 却占着一个队列位。要求先「继续合成」再改音色，路径唯一、状态干净。
            if (TaskStatus.isPaused(task.getStatus())) {
                response.put("success", false);
                response.put("message", "任务已暂停，请先在管理端点「继续合成」后再修改音色");
                return ResponseEntity.badRequest().body(response);
            }

            // 用 containsKey 区分「未传该字段（不修改默认音色）」与「显式传 null（清空默认音色）」。
            // 两端调用方（Web Player.vue / Android SegmentVoiceDialog）都只上报变更段、
            // 并传 null 表示「不动默认音色」，若不区分就会每次改一段都把整本书的默认音色抹掉。
            boolean defaultVoiceProvided = body.containsKey("defaultVoiceId");
            String defaultVoiceId = null;
            if (defaultVoiceProvided) {
                Object dv = body.get("defaultVoiceId");
                String raw = (dv == null) ? null : String.valueOf(dv);
                defaultVoiceId = (raw == null || raw.isBlank()) ? null : raw.trim();
            }

            Map<Integer, String> voices = new HashMap<>();
            Object sv = body.get("segmentVoices");
            if (sv instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    try {
                        int num = Integer.parseInt(String.valueOf(e.getKey()));
                        Object val = e.getValue();
                        voices.put(num, val == null ? null : String.valueOf(val));
                    } catch (NumberFormatException ignore) {
                        // 跳过非法段落号
                    }
                }
            }

            service.applySegmentVoices(taskId, defaultVoiceId, voices, defaultVoiceProvided);

            response.put("success", true);
            response.put("message", "音色已更新，正在重新合成受影响的片段");
            return ResponseEntity.ok(response);

        } catch (InsufficientCreditsException e) {
            // 任务此前失败已退款，改音色重合成需要补扣积分；余额不足时给明确提示而非 500
            return support.insufficientCreditsResponse(e);
        } catch (IllegalStateException e) {
            // chargeForRetry 在任务行锁下复核到任务已重新派发（并发改音色/重试）时抛出
            response.put("success", false);
            response.put("message", e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : "任务仍在处理中，请稍后再修改音色");
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("修改任务音色失败: taskId={}, error={}", taskId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "修改音色失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 重试任务的失败片段（复用断点续跑逻辑：只重新合成 failed 段，已完成段跳过）。
     */
    @PostMapping("/task/{taskId}/retry")
    public ResponseEntity<Map<String, Object>> retryTask(@PathVariable Long taskId,
                                                         Authentication authentication) {
        try {
            RetryOutcome outcome = performRetry(taskId, authentication);
            Map<String, Object> response = new HashMap<>();
            response.put("success", outcome.success());
            response.put("message", outcome.message());
            if (outcome.success()) {
                response.put("creditsCost", outcome.recharged());
                return ResponseEntity.ok(response);
            }
            return ResponseEntity.status(HttpStatus.valueOf(outcome.httpStatus())).body(response);
        } catch (InsufficientCreditsException e) {
            log.warn("积分不足，拒绝重试任务: taskId={}, required={}, balance={}",
                    taskId, e.getRequired(), e.getBalance());
            return support.insufficientCreditsResponse(e);
        } catch (Exception e) {
            log.error("重试任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "重试失败，请稍后重试"));
        }
    }

    /** 单个任务重试结果（内部传递）：success + HTTP 语义 + 用户提示 + 本次补扣积分 */
    private record RetryOutcome(boolean success, int httpStatus, String message, int recharged) {}

    /**
     * 单次批量重试的任务数上限。
     * 对 taskIds 长度不设限时，一个请求可提交数万个 ID，逐个走归属校验/行锁/补扣，
     * 构成资源放大面。100 远超一页可见的失败任务数，正常前端交互不受影响。
     */
    private static final int MAX_RETRY_BATCH_SIZE = 100;

    /**
     * 批量重试全部失败任务（一键重试）。
     * 每个任务独立执行与单任务重试完全相同的流程（含积分补扣与队列满回退），
     * 汇总返回成功数、总补扣积分与跳过明细，供前端二次确认后调用。
     */
    @PostMapping("/tasks/retry-failed")
    public ResponseEntity<Map<String, Object>> retryFailedTasks(@RequestBody Map<String, Object> body,
                                                                Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            Object idsObj = body.get("taskIds");
            if (!(idsObj instanceof List<?> rawIds) || rawIds.isEmpty()) {
                response.put("success", false);
                response.put("message", "缺少 taskIds 参数");
                return ResponseEntity.badRequest().body(response);
            }
            if (rawIds.size() > MAX_RETRY_BATCH_SIZE) {
                response.put("success", false);
                response.put("message", "单次批量重试最多支持 " + MAX_RETRY_BATCH_SIZE + " 个任务，请分批提交");
                return ResponseEntity.badRequest().body(response);
            }

            List<Long> taskIds = new ArrayList<>();
            for (Object raw : rawIds) {
                try {
                    taskIds.add(Long.parseLong(String.valueOf(raw)));
                } catch (NumberFormatException e) {
                    response.put("success", false);
                    response.put("message", "taskIds 包含非法值: " + raw);
                    return ResponseEntity.badRequest().body(response);
                }
            }

            int retried = 0;
            int totalRecharged = 0;
            List<Map<String, Object>> skipped = new ArrayList<>();
            for (Long taskId : taskIds) {
                try {
                    RetryOutcome outcome = performRetry(taskId, authentication);
                    if (outcome.success()) {
                        retried++;
                        totalRecharged += outcome.recharged();
                    } else {
                        skipped.add(Map.of("taskId", taskId, "message", outcome.message()));
                    }
                } catch (InsufficientCreditsException e) {
                    log.warn("批量重试：任务积分不足被跳过: taskId={}, reason={}", taskId, e.getMessage());
                    skipped.add(Map.of("taskId", taskId, "message", e.getMessage()));
                } catch (Exception e) {
                    log.warn("批量重试：单个任务重试失败: taskId={}, error={}", taskId, e.getMessage());
                    skipped.add(Map.of("taskId", taskId, "message", "重试失败，请稍后重试"));
                }
            }

            response.put("success", true);
            response.put("retried", retried);
            response.put("totalRecharged", totalRecharged);
            response.put("skipped", skipped);
            response.put("message", "已重新调度 " + retried + " 个任务"
                    + (totalRecharged > 0 ? "，本次共消耗 " + totalRecharged + " 积分" : "")
                    + (skipped.isEmpty() ? "" : "，" + skipped.size() + " 个任务未能重试"));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("批量重试失败: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "批量重试失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 重试单个失败任务的核心流程（单任务与批量接口共用）。
     * InsufficientCreditsException 向上抛出，由端点映射为 402 结构化响应。
     */
    private RetryOutcome performRetry(Long taskId, Authentication authentication) {
        Task task = support.getOwnedTask(taskId, authentication);
        if (task == null) {
            if (taskRepository.findById(taskId).isEmpty()) {
                return new RetryOutcome(false, HttpStatus.NOT_FOUND.value(), "任务不存在", 0);
            }
            return new RetryOutcome(false, HttpStatus.FORBIDDEN.value(), "无权操作该任务", 0);
        }

        String status = task.getStatus();
        if (TaskStatus.isUnfinished(status)) {
            return new RetryOutcome(false, HttpStatus.BAD_REQUEST.value(), "任务仍在处理中，无需重试", 0);
        }
        // paused 不在 isUnfinished 里，但同样不能走重试：paused 是管理员的显式挂起，
        // 唯一出口是书城管理端的「继续合成」（它会先 clearTaskPaused 再派发）。
        // 放行到这里会写出「DB=queued、内存暂停标记还在」——工作线程看见标记立刻收手，
        // 任务永远停在 queued 无人执行，界面还显示「正在处理」，点一次白点一次。
        if (TaskStatus.isPaused(status)) {
            return new RetryOutcome(false, HttpStatus.BAD_REQUEST.value(),
                    "任务已暂停，请先在管理端点「继续合成」", 0);
        }

        // 只取 id 的投影查询：实体查询会把 @Lob audio_data 一起拉进堆，
        // 且脱管实体 save() 会绕开 updateState 的既定约束（见下方重置逻辑）
        //
        // 重试范围 = failed + pending。pending 段来自「合成节点不可用导致任务被中断」：
        // 那批段刻意没被标成 failed（它们不是合成失败，只是还没轮到），
        // 只认 failed 会让这类任务重试时报「没有失败的片段」而无法续跑。
        List<Long> retrySegmentIds = audioSegmentRepository.findIdsByTaskIdAndStatusIn(
                taskId, List.of(SegmentStatus.FAILED.value(), SegmentStatus.PENDING.value()));
        if (retrySegmentIds.isEmpty()) {
            return new RetryOutcome(false, HttpStatus.BAD_REQUEST.value(), "没有需要重试的片段", 0);
        }

        // 补扣积分：任务整体失败时积分已全额退还，重试必须重新收费，
        // 否则「失败 → 退款 → 重试」就是一条无限免费合成的路径。
        // 未退过款的任务（如 partial 重试）不会重复收费，chargeForRetry 内部已判定。
        // chargeForRetry 对任务行加锁，串行化并发重试的判定-扣费，防止双扣。
        int recharged;
        try {
            recharged = creditService.chargeForRetry(taskId);
        } catch (IllegalStateException e) {
            // chargeForRetry 拿锁后复核发现任务已被并发重试重新派发等业务状态，
            // 原因直接返回给用户，避免“请稍后重试”误导反复点击
            return new RetryOutcome(false, HttpStatus.BAD_REQUEST.value(),
                    e.getMessage() != null ? e.getMessage() : "请稍后重试", 0);
        }

        // chargeForRetry 在独立事务里改过任务行（creditsRefunded 归零、creditsCost 更新）。
        // 手里的 task 是扣费前加载的脱管快照，直接 save 会经 merge 整行写回、
        // 把刚归零的 creditsRefunded 覆盖回去（破坏后续退款幂等），必须先重新加载。
        task = taskRepository.findById(taskId).orElse(task);

        // 重置失败段为待处理、清空历史错误原因，再重新派发（completed 段会被自动跳过）
        // 用 updateState JPQL 而非「实体查询 + save()」：后者是脱管实体 merge 整行写回，
        // 任务被并发删除时会把已删片段行重新 INSERT 复活（AudioSegmentRepository 既定口径）。
        // audioFilePath 传 null 无害：重新合成会按 tts_{taskId}_{段号}.mp3 的确定性文件名回写同一路径
        for (Long segmentId : retrySegmentIds) {
            audioSegmentRepository.updateState(segmentId, SegmentStatus.PENDING.value(), null, null);
        }

        // DB 状态同步为 queued：只更新 Redis 时 DB 仍停在 failed，
        // 任务真正跑起来之前双端状态不一致，且队列容量统计也不含这条重试。
        //
        // 用条件 UPDATE 而不是 save()：save() 对脱管实体走 merge，任务行若在这期间被
        // 删除，merge 会按主键重新 INSERT，把任务「复活」成一条悬空 user_id 的幽灵行
        // （updateFinal / updateRuntime 也都是为这个原因改用 JPQL 的）。
        // 顺带把并发也挡了：两个人同时点重试，第二个 UPDATE 命中 0 行。
        //
        // ⚠️ from 里**不含 paused**：paused 是管理员的显式意图，只能由「继续合成」离开。
        // 若把它算作可改写的来源，就会写出「DB=queued 但内存暂停标记还在」的状态 ——
        // 工作线程一进来看见标记就收手，任务永远停在 queued 没人跑。
        int movedToQueued = taskRepository.setStatusIfInStatus(taskId, TaskStatus.QUEUED.value(),
                List.of(TaskStatus.COMPLETED.value(), TaskStatus.PARTIAL.value(), TaskStatus.FAILED.value()));
        if (movedToQueued == 0) {
            // 两种可能：任务被删了，或状态被并发改成 paused / 另一个重试抢先改写。
            // 无论哪种，本次都不该继续派发；已扣的积分要退（任务已删时 refundForTask 自行返回 0）。
            log.info("重试放弃：任务状态已变化或任务已删除: taskId={}", taskId);
            if (recharged > 0) {
                try {
                    creditService.refundForTask(taskId, "任务状态已变化，退还重试积分");
                } catch (Exception re) {
                    log.error("⚠️ 重试状态冲突后的退款失败，需人工核对: taskId={}", taskId, re);
                }
            }
            return new RetryOutcome(false, HttpStatus.BAD_REQUEST.value(),
                    "任务状态刚刚发生变化，请刷新后重试", 0);
        }
        redisTaskService.updateTaskStatus(taskId, TaskStatus.QUEUED, task.getCompletedSegments(), task.getTotalSegments());

        try {
            service.processTtsTaskAsync(taskId);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            // AbortPolicy 兜底（对齐 createTtsTask 的 dispatchAfterCommit 拒绝处理）：
            // 积分已扣但任务被队列拒绝、根本没跑起来，必须回退状态并退款，否则用户白扣积分。
            log.error("重试任务被队列拒绝，回退状态并退款: taskId={}", taskId, e);
            // 同样不用 save()：任务若已被并发删除，merge 会把它 INSERT 回来。
            // updateFinal 是 JPQL UPDATE，行没了只返回 0，绝不插入。
            taskRepository.updateFinal(taskId, TaskStatus.FAILED.value(),
                    task.getCompletedSegments(), task.getTotalSegments(),
                    "任务队列已满，重试未能进入合成队列");
            redisTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, task.getCompletedSegments(), task.getTotalSegments());
            if (recharged > 0) {
                try {
                    creditService.refundForTask(taskId, "任务队列已满，重试未执行，退还积分");
                } catch (Exception re) {
                    log.error("⚠️ 重试被拒绝后的退款失败，需人工核对: taskId={}", taskId, re);
                }
            }
            return new RetryOutcome(false, HttpStatus.SERVICE_UNAVAILABLE.value(), "任务队列已满，请稍后再试", recharged);
        }

        return new RetryOutcome(true, HttpStatus.OK.value(),
                "已重新调度 " + retrySegmentIds.size() + " 个待合成片段，正在处理中"
                        + (recharged > 0 ? "（本次重试消耗 " + recharged + " 积分）" : ""),
                recharged);
    }

    /**
     * 删除任务及其音频片段。
     * - 排队中（queued/pending）的任务：删除即"取消"（尚未开始合成）。
     * - 合成中（processing）的任务：无法中断合成线程，需等其完成后再删。
     * - 终态（completed/partial/failed）任务：直接删除。
     */
    @DeleteMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> deleteTask(@PathVariable Long taskId,
                                                          Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                if (taskRepository.findById(taskId).isEmpty()) {
                    response.put("success", false);
                    response.put("message", "任务不存在");
                    // .notFound().build() 会丢弃已 put 的响应体，
                    // "任务不存在"提示永远到不了前端；改用 body(response) 携带消息
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            String status = task.getStatus();
            if (TaskStatus.of(status) == TaskStatus.PROCESSING) {
                response.put("success", false);
                response.put("message", "任务正在合成中，暂不能删除，请稍后重试");
                return ResponseEntity.badRequest().body(response);
            }

            // 先登记「任务已删除」：若该任务恰好正被工作线程处理（状态检查与派发
            // 之间的竞态窗口），工作线程会在下一个检查点中止，不再合成、不再写库。
            VoiceCloningTtsService.markTaskDeleted(taskId);

            // 取消「从未开始合成」的任务要退还已扣积分。
            // createTaskRecord 在创建任务时就已 chargeForTask 扣费，而 queued/pending 的任务
            // 一段都没合成过，直接删行不退费等于「未交付却收费」，与失败必全额退款的口径不一致。
            //
            // 只在 QUEUED/PENDING 上退：refundForTask 的幂等判据是「本周期已退 < cost」，
            // 该判据对 COMPLETED（已交付）与 PARTIAL（部分交付）任务同样成立——若无条件调用，
            // 会把已交付任务的积分也退掉，属反向资损。
            //
            // paused 也不在名单里，这是**有意的**：paused 只由管理端「暂停合成」写入，
            // 而那条链路只作用于书城任务（createTtsTask 的 adminExempt=true，全链路 0 积分），
            // 删除它没有钱可退。若将来把暂停开放给用户任务，这里必须补一条按已交付段折算的规则，
            // 不能简单地加进白名单——paused 的已完成段通常 > 0，全额退等于「拿了一半货还全退」。
            TaskStatus deleteStatus = TaskStatus.of(status);
            if (deleteStatus == TaskStatus.QUEUED || deleteStatus == TaskStatus.PENDING) {
                int refunded = creditService.refundForTask(taskId, "任务已取消，退还积分");
                if (refunded > 0) {
                    log.info("取消未开始的任务，已退还积分: taskId={}, amount={}", taskId, refunded);
                }
            }

            // 清理 Redis 任务登记（避免取消后仍被统计为进行中）
            if (authentication.getName() != null) {
                redisTaskService.cleanupCompletedTask(taskId, authentication.getName());
            }

            // 删除任务专属音频目录（uploads/{taskId}/），不动可能被复用的参考音频文件
            try {
                java.io.File audioDir = support.resolveUploadFile(String.valueOf(taskId));
                if (audioDir.exists() && audioDir.isDirectory()) {
                    java.io.File[] files = audioDir.listFiles();
                    if (files != null) {
                        for (java.io.File f : files) {
                            if (!f.delete()) {
                                log.warn("删除音频文件失败: {}", f.getAbsolutePath());
                            }
                        }
                    }
                    audioDir.delete();
                }
            } catch (Exception e) {
                log.warn("删除任务音频目录失败: taskId={}, error={}", taskId, e.getMessage());
            }

            // 清理该任务上传的电子书源文件（文件名带毫秒时间戳，任务间不共享；
            // 不清理会随任务数无限累积成磁盘孤儿。参考音频可能被多任务复用，保留）
            try {
                if (task.getBookFilePath() != null && !task.getBookFilePath().isEmpty()) {
                    java.io.File bookFile = support.resolveUploadFile(task.getBookFilePath());
                    if (bookFile.exists() && bookFile.isFile() && !bookFile.delete()) {
                        log.warn("删除任务电子书文件失败: {}", bookFile.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                log.warn("删除任务电子书文件失败: taskId={}, error={}", taskId, e.getMessage());
            }

            // 先删音频片段记录，再删任务记录（不依赖数据库外键级联）。
            // JPQL 批量删除：不会把 @Lob audio_data 大字段加载进堆内存，
            // 也不会因逐条 delete 与并发写产生多余的往返。
            // 两步删除合并进单一事务（见 TtsControllerSupport.deleteTaskData），
            // 避免第一步提交后第二步失败留下「片段已删、任务残留」的脏数据。
            support.deleteTaskData(taskId);

            response.put("success", true);
            TaskStatus taskStatus = TaskStatus.of(status);
            response.put("message", taskStatus == TaskStatus.QUEUED || taskStatus == TaskStatus.PENDING
                    ? "任务已取消" : "任务已删除");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("删除任务失败: taskId={}, error={}", taskId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "删除失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 获取 TTS 任务的目录（将服务端目录条目映射到对应的 segmentNumber）。
     * 适用于已通过 TTS 合成过音频的书籍。
     */
    @GetMapping("/task/{taskId}/toc")
    public ResponseEntity<Map<String, Object>> getTaskToc(@PathVariable Long taskId,
                                                          Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                if (taskRepository.findById(taskId).isEmpty()) {
                    response.put("success", false);
                    response.put("message", "任务不存在");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            String bookFilePath = task.getBookFilePath();
            if (bookFilePath == null || bookFilePath.isEmpty()) {
                response.put("success", false);
                response.put("message", "任务没有关联的电子书文件");
                return ResponseEntity.badRequest().body(response);
            }

            // 从文件名提取扩展名
            String extension = support.getFileExtension(new java.io.File(bookFilePath).getName());

            // 提取目录（charOffset 为该章在全文中的字符偏移）
            // 统一用 resolveUploadFile 解析相对路径，与 getAudioFilePath 保持一致
            java.io.File bookFile = support.resolveUploadFile(bookFilePath);
            if (!bookFile.exists()) {
                response.put("success", false);
                response.put("message", "电子书文件不存在: " + bookFilePath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            List<BookTextExtractor.TocEntry> tocEntries;
            if (extension != null && (extension.equalsIgnoreCase("pdf") || extension.equalsIgnoreCase("epub") || extension.equalsIgnoreCase("mobi") || extension.equalsIgnoreCase("md"))) {
                tocEntries = bookTextExtractor.extractToc(bookFile, extension);
            } else {
                tocEntries = List.of(); // TXT 不含结构信息；MD 的标题会导出为目录
            }

            // 用元数据投影查询，避免把整本书的 @Lob audio_data 大字段一次性加载进堆
            List<AudioSegmentRepository.SegmentMeta> segments =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);
            // 无片段、或该格式本就没有目录（TXT）时直接返回空目录。
            // 后者顺带省掉一次整本原文解析 —— extractText 对 PDF 是重操作。
            if (segments.isEmpty() || tocEntries.isEmpty()) {
                response.put("success", true);
                response.put("toc", List.of());
                return ResponseEntity.ok(response);
            }

            // 目录条目的 charOffset 是「原文真实下标」（extractEpub / extractPdf / extractMobiToc
            // 都按原始文本长度累加），所以段落起点也必须在**同一坐标系**里求。
            //
            // 不能按 `text_content.length()` 累加算「压缩坐标」：切段时会 trim 掉句子与段落的首尾空白，
            // 段文本并非原文的连续子串，累加值系统性偏小且偏差随段数持续累积 ——
            // 拿目录的真实下标去比压缩坐标，映射出的段号必然**偏大**，表现就是「点目录跳过头」。
            // 这是 detail#B5 的同类缺陷，阅读页端点（extractBookContent）已修，此处同步统一。
            //
            // 复用 BookTextExtractor 的统一实现求真实下标。注意用「实际合成文本」而不是
            // 重新 TextSegmenter 切段：段号必须锚定 audio_segments 里真实存在的那些段，
            // 这样对切段规则不同的旧任务同样成立。
            String fullText = bookTextExtractor.extractText(bookFile, extension);
            java.util.ArrayList<String> segmentTexts = new java.util.ArrayList<>(segments.size());
            for (AudioSegmentRepository.SegmentMeta segment : segments) {
                String text = segment.getTextContent();
                segmentTexts.add(text != null ? text : "");
            }
            int[] segmentStartOffsets = BookTextExtractor.locateStartOffsets(fullText, segmentTexts);
            fullText = null;
            segmentTexts = null;

            // 将目录条目的 charOffset 映射到最近的 segmentNumber
            java.util.ArrayList<Map<String, Object>> toc = new java.util.ArrayList<>();
            for (BookTextExtractor.TocEntry entry : tocEntries) {
                int paragraphIndex = BookTextExtractor.paragraphIndexOfOffset(segmentStartOffsets, entry.charOffset);
                Map<String, Object> item = new HashMap<>();
                item.put("title", entry.title);
                item.put("segmentNumber", segments.get(paragraphIndex).getSegmentNumber());
                item.put("pageNumber", entry.pageNumber);
                toc.add(item);
            }

            response.put("success", true);
            response.put("toc", toc);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("获取目录失败: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("message", "获取目录失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}