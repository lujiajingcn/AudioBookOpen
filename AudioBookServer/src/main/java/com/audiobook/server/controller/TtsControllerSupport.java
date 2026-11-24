package com.audiobook.server.controller;

import com.audiobook.server.exception.InsufficientCreditsException;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.ReferenceAudioRepository;
import com.audiobook.server.repository.StoreBookRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.repository.TempReferenceAudioRepository;
import com.audiobook.server.repository.UserReferenceAudioRepository;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.util.PathSafetyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * TTS 控制器共享支持组件。
 *
 * 原 Controller.java（1923 行）拆分为多个按业务命名的控制器后，
 * 各控制器共用的文件解析、归属校验、大小限制、统一错误响应等逻辑集中在此，
 * 避免在多个控制器之间复制粘贴。
 */
@Component
public class TtsControllerSupport {

    private static final Logger log = LoggerFactory.getLogger(TtsControllerSupport.class);

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final UserReferenceAudioRepository userReferenceAudioRepository;
    private final TempReferenceAudioRepository tempReferenceAudioRepository;
    private final ReferenceAudioRepository referenceAudioRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final StoreBookRepository storeBookRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /**
     * 合成语音时电子书与参考音频的最大文件大小。
     * 统一取自 multipart 配置，与 Spring 容器实际拦截阈值（max-file-size）以及前端 /api/config 下发值保持一致，
     * 避免「控制器 / 容器 / 前端」三层阈值各说各话导致用户在前端通过、却被控制器拒收。
     */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSizeConfig;

    /**
     * 参考音频（音色克隆素材）送入引擎前的最大时长（秒），{@code <=0} 表示不裁切。
     *
     * <p>与 {@code VoiceCloningTtsService} 读的是同一个配置项。放在这里是为了让
     * 同步端点 {@code /clone} 也能用同一口径裁切 —— 它在 Controller 层就把上传的
     * 参考音频落成临时文件，不经过 Service 的入库路径。
     * 两处必须同源，否则「入库的裁了、直传的没裁」，表现为批量任务正常、
     * 交互式合成偶尔打死节点。
     *
     * <p>注意默认值必须与 {@code VoiceCloningTtsService} 的同名 {@code @Value} 兜底值
     * 以及 {@code application.yml} 里的默认值三处一致（现为 8）。三处不一致时，
     * 只有「没配 env」的那一条路径会静默走另一个上限。
     */
    @Value("${moss.tts.reference-max-seconds:8}")
    private float referenceMaxSeconds;

    /** 参考音频最大时长（秒），供 Controller 层裁切参考音频时复用 */
    public float referenceMaxSeconds() {
        return referenceMaxSeconds;
    }

    public TtsControllerSupport(TaskRepository taskRepository,
                                UserRepository userRepository,
                                UserReferenceAudioRepository userReferenceAudioRepository,
                                TempReferenceAudioRepository tempReferenceAudioRepository,
                                ReferenceAudioRepository referenceAudioRepository,
                                AudioSegmentRepository audioSegmentRepository,
                                StoreBookRepository storeBookRepository,
                                ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.userReferenceAudioRepository = userReferenceAudioRepository;
        this.tempReferenceAudioRepository = tempReferenceAudioRepository;
        this.referenceAudioRepository = referenceAudioRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.storeBookRepository = storeBookRepository;
        this.objectMapper = objectMapper;
    }

    /** 解析 maxFileSizeConfig 为字节数（解析失败回退 50MB，与 ConfigController / application.yml 默认值保持一致），供各处大小校验复用 */
    public long maxFileSizeBytes() {
        try {
            return org.springframework.util.unit.DataSize.parse(maxFileSizeConfig.trim()).toBytes();
        } catch (Exception e) {
            log.warn("解析 spring.servlet.multipart.max-file-size 失败，回退 50MB: {}", e.getMessage());
            return 50L * 1024 * 1024;
        }
    }

    public static String formatMB(long bytes) {
        return String.valueOf(bytes / 1024 / 1024);
    }

    /** 从认证信息解析当前用户，未认证/用户不存在返回 null */
    public User currentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(authentication.getName()).orElse(null);
    }

    /** 按用户名加载用户（不存在返回 null） */
    public User userByUsername(String username) {
        if (username == null) {
            return null;
        }
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * 校验任务是否属于当前登录用户。不属于返回 null，属于返回该任务。
     *
     * @return 归属校验通过的任务；任务不存在或不属于用户返回 null
     */
    public Task getOwnedTask(Long taskId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        if (!belongsToUser(task, authentication.getName())) {
            return null;
        }
        return task;
    }

    /** 判断任务是否属于指定用户名（authentication.getName() 返回用户名） */
    public boolean belongsToUser(Task task, String username) {
        if (username == null) {
            return false;
        }
        return userRepository.findByUsername(username)
                .map(u -> u.getId().equals(task.getUserId()))
                .orElse(false);
    }

    /**
     * 将 uploadDir 下的相对路径解析为绝对 File。
     * 兼容 app.upload.dir 配成相对路径的情况：相对时以进程工作目录(user.dir)为基准，
     * 避免不同入口(new File(uploadDir, x) 会以 JVM 默认 CWD 解析)之间不一致导致找不到文件。
     *
     * 目录穿越纵深防御：解析结果必须经规范路径校验仍位于 uploadDir 之内。
     * 现有调用方的相对路径均来自服务端写入的 DB 记录或经 DB 白名单校验，但无法保证
     * 未来新增的写路径不会注入 "../" 之类的逃逸序列；canonical 路径比较（会解析
     * 符号链接）在此兜底拦截。命中即抛 IllegalArgumentException：
     * - 请求链路由 GlobalExceptionHandler 统一转 400「非法文件路径」；
     * - 内部调用方按各自容错语义处理（见 deleteFileQuietly）。
     */
    public java.io.File resolveUploadFile(String relativePath) {
        java.io.File dir = PathSafetyUtil.resolveBaseDir(uploadDir);
        try {
            return PathSafetyUtil.resolveWithin(dir, relativePath);
        } catch (IllegalArgumentException e) {
            log.warn("拒绝上传目录之外的路径（疑似目录穿越）: relativePath={}", relativePath);
            throw e;
        }
    }

    /** 删除 uploadDir 下的文件（不存在则忽略），删除失败仅记日志，不打断调用方主流程 */
    public void deleteFileQuietly(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        java.io.File file;
        try {
            file = resolveUploadFile(fileName);
        } catch (IllegalArgumentException e) {
            // 配套：非法路径（逃逸出上传目录）直接拒绝触碰任何文件，
            // 但保持本方法「静默清理」的语义——只告警，不向调用方抛出
            log.warn("跳过非法路径的清理: {}", fileName);
            return;
        }
        if (!PathSafetyUtil.deleteQuietly(file)) {
            log.warn("清理文件失败: {}", file.getAbsolutePath());
        }
    }

    public String getFileExtension(String filename) {
        if (filename == null) return null;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) return null;
        return filename.substring(dotIndex + 1);
    }

    /** 支持的电子书扩展名 */
    private static final java.util.Set<String> SUPPORTED_EBOOK_FORMATS =
            java.util.Set.of("txt", "pdf", "epub", "mobi", "md");

    public boolean isSupportedEbookFormat(String extension) {
        return extension != null && SUPPORTED_EBOOK_FORMATS.contains(extension.toLowerCase());
    }

    /** 我的参考音频库允许上传的格式（音频 + 视频；视频在服务端转 WAV，音频原样保存） */
    private static final java.util.Set<String> ALLOWED_REFERENCE_EXTENSIONS = java.util.Set.of(
            "wav", "mp3", "flac", "aac", "ogg", "m4a",
            "mp4", "avi", "mkv", "mov", "flv", "wmv", "webm", "m4v", "3gp");

    /** 服务端兜底校验：前端已按扩展名过滤，这里防止绕过前端直接调用接口上传非法文件 */
    public boolean isAllowedReferenceExt(String ext) {
        if (ext == null || ext.isEmpty()) return false;
        String e = ext.startsWith(".") ? ext.substring(1) : ext;
        return ALLOWED_REFERENCE_EXTENSIONS.contains(e.toLowerCase());
    }

    /** 试听参考音频时按扩展名返回正确的 MIME，使浏览器能正常播放（flac/aac/ogg/m4a 等不再被误标为 wav） */
    public static String referenceAudioMimeType(String ext) {
        if (ext == null) return "audio/wav";
        return java.util.Map.of(
                "mp3", "audio/mpeg",
                "wav", "audio/wav",
                "flac", "audio/flac",
                "ogg", "audio/ogg",
                "m4a", "audio/mp4",
                "aac", "audio/aac"
        ).getOrDefault(ext.toLowerCase(), "audio/wav");
    }

    /**
     * 校验合成时引用的参考音频是否合法，并返回可用于 createTtsTask 的文件名。
     * - reference_source == "server"：引用服务器共享库，按 fileName 在 reference_audio 表定位（所有登录用户可用）。
     * - 其他（含默认/无 source，即「我的库」）：先按 fileName + 当前用户 在 user_reference_audio 表定位；
     *   查不到再回退 temp_reference_audio（/upload-reference 仅落盘的临时文件，上传时已登记归属）。
     * 返回 null 表示文件不存在或无权限引用（同时杜绝把任意字符串当路径的穿越风险）。
     */
    public String resolveReferenceFileName(String referenceId, String referenceSource, String username) {
        if (referenceId == null || referenceId.isBlank()) return null;
        if ("server".equalsIgnoreCase(referenceSource)) {
            // 仅校验 fileName 在服务器共享库中存在，存在即返回解析后的文件名（避免冗余 map）
            return referenceAudioRepository.findByFileName(referenceId).isPresent() ? referenceId : null;
        }
        User u = userRepository.findByUsername(username).orElse(null);
        if (u == null) return null;
        if (userReferenceAudioRepository.findByFileNameAndUserId(referenceId, u.getId()).isPresent()) {
            return referenceId;
        }
        // 第三种来源：/upload-reference 仅落盘的临时文件。归属记录只由服务端生成的文件名
        // 登记，命中即说明是本用户此前上传的；磁盘文件已丢失的记录同样拒绝，
        // 避免任务创建成功、后台合成时才因参考音频缺失而失败。
        if (tempReferenceAudioRepository.findByFileNameAndUserId(referenceId, u.getId()).isPresent()
                && resolveUploadFile(referenceId).exists()) {
            return referenceId;
        }
        return null;
    }

    /** 积分不足的统一响应体（402 + 结构化余额信息，与 GlobalExceptionHandler 保持一致） */
    public ResponseEntity<Map<String, Object>> insufficientCreditsResponse(InsufficientCreditsException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", e.getMessage());
        body.put("code", "INSUFFICIENT_CREDITS");
        body.put("required", e.getRequired());
        body.put("balance", e.getBalance());
        body.put("shortage", e.getShortage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(body);
    }

    /**
     * 写下载类接口的错误 JSON。必须显式设置 Content-Type 与 UTF-8 编码：
     * 前端把错误响应包成 Blob 后靠 content-type 识别 JSON 并解析 message，
     * 缺了头部只会得到泛化英文文案；getWriter 默认 ISO-8859-1，中文会变乱码。
     *
     * 响应体统一为全工程的 {"success": false, "message": ...}
     * 结构——三端（Web Blob 解析 / 管理端拦截器 / Android）读取的都是 message 字段，
     * {"error": ...} 结构会让下载失败时真实原因（如"无权访问该任务"）读不到，只能显示兜底话术。
     * 序列化用注入的共享 ObjectMapper，避免每次调用 new 一个实例。
     */
    public void writeErrorJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                java.util.Map.of("success", false, "message", message)));
    }

    /**
     * 在同一事务内删除任务的片段行与任务行。
     *
     * 分两次调用 audioSegmentRepository.deleteByTaskId() 与
     * taskRepository.deleteById() 时，两个方法各自携带独立事务：第一步提交后
     * 第二步若失败（连接抖动/数据库瞬时故障），会留下「片段已删、任务行残留」
     * 的脏数据：任务仍在列表里展示，播放/重试却全部失效，用户无法自愈。
     * 合并为一个事务后要么都成功、要么都回滚。
     *
     * 磁盘文件清理不进入本事务（项目既有口径：事务内不做磁盘 IO），
     * 残留文件由删除端点在事务外尽力清理，量级有限且不影响数据一致性。
     */
    @Transactional
    public void deleteTaskData(Long taskId) {
        audioSegmentRepository.deleteByTaskId(taskId);
        taskRepository.deleteById(taskId);
        // 书城任务被从此端点删除时，store_books.task_id 会变成悬挂引用：
        // 管理端显示「状态未知」，合成与补齐两条路都被状态判据拒绝，
        // 只剩「删除语音」能自愈。在同一事务里解绑，避免留下永久错误数据。
        // 返回 0 是常态（普通用户任务没有被任何书绑定），无需日志。
        int unbound = storeBookRepository.clearSynthBindingByTaskId(taskId);
        if (unbound > 0) {
            log.info("删除任务时解绑书城合成引用: taskId={}, books={}", taskId, unbound);
        }
    }

    public String extractBookNameFromPath(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "unknown_book";
        }
        java.io.File file = new java.io.File(filePath);
        String fileName = file.getName();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }
}