package com.audiobook.server.controller;

import com.audiobook.server.model.ReferenceAudio;
import com.audiobook.server.model.TempReferenceAudio;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserReferenceAudio;
import com.audiobook.server.repository.ReferenceAudioRepository;
import com.audiobook.server.repository.TempReferenceAudioRepository;
import com.audiobook.server.repository.UserReferenceAudioRepository;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.service.TtsService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 参考音频：临时上传（/upload-reference）、我的参考音频库（/references/**）、
 * 服务器共享库（/server-references/**）。
 * 由原 Controller.java（God Object）拆分而来，路径保持不变。
 */
@RestController
@RequestMapping("/api/tts")
public class ReferenceAudioController {

    private static final Logger log = LoggerFactory.getLogger(ReferenceAudioController.class);

    private final TtsService service;
    private final UserReferenceAudioRepository userReferenceAudioRepository;
    private final TempReferenceAudioRepository tempReferenceAudioRepository;
    private final ReferenceAudioRepository referenceAudioRepository;
    private final UserRepository userRepository;
    private final TtsControllerSupport support;

    /** 排行榜单次返回上限，防止分享条目变多后全表返回拖垮响应 */
    private static final int MAX_RANK_LIMIT = 200;
    private static final int DEFAULT_RANK_LIMIT = 50;

    /** 与 PostController 保持一致的日期格式（系统默认时区），避免 references 返回 ISO 时间戳而 posts 返回 yyyy-MM-dd HH:mm:ss 的形态不一致 */
    private static final java.time.format.DateTimeFormatter TS_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String formatTs(java.time.LocalDateTime t) {
        return t == null ? null : TS_FORMATTER.format(t);
    }

    public ReferenceAudioController(TtsService service,
                                    UserReferenceAudioRepository userReferenceAudioRepository,
                                    TempReferenceAudioRepository tempReferenceAudioRepository,
                                    ReferenceAudioRepository referenceAudioRepository,
                                    UserRepository userRepository,
                                    TtsControllerSupport support) {
        this.service = service;
        this.userReferenceAudioRepository = userReferenceAudioRepository;
        this.tempReferenceAudioRepository = tempReferenceAudioRepository;
        this.referenceAudioRepository = referenceAudioRepository;
        this.userRepository = userRepository;
        this.support = support;
    }

    /**
     * 单独上传参考音频（用于批量合成时避免重复上传同一参考音频）
     * 返回 referenceId，后续 upload-book 时可直接使用
     */
    @PostMapping(value = "/upload-reference", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadReferenceAudio(
            @RequestParam("reference_audio") MultipartFile referenceAudio,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();

        log.info("========== 收到参考音频上传请求 ==========");
        log.info("认证信息: {}", authentication != null ? authentication.getName() : "null");
        log.info("参考音频文件名: {}, 大小: {} bytes", referenceAudio.getOriginalFilename(), referenceAudio.getSize());

        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            if (referenceAudio.isEmpty()) {
                response.put("success", false);
                response.put("message", "参考音频文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            // 文件大小限制：与 multipart.max-file-size 统一（前端 /api/config 同源）
            if (referenceAudio.getSize() > support.maxFileSizeBytes()) {
                response.put("success", false);
                response.put("message", "参考音频文件大小不能超过 " + TtsControllerSupport.formatMB(support.maxFileSizeBytes()) + "MB");
                return ResponseEntity.badRequest().body(response);
            }

            // 扩展名服务端兜底校验（防止绕过前端直接调用接口上传非法文件）
            String originalExt = support.getFileExtension(referenceAudio.getOriginalFilename());
            if (!support.isAllowedReferenceExt(originalExt)) {
                response.put("success", false);
                response.put("message", "不支持的参考音频格式");
                return ResponseEntity.badRequest().body(response);
            }

            String referenceId = service.uploadReferenceAudio(referenceAudio);

            // 本端点只落盘、不入「我的库」，但 upload-book 的 resolveReferenceFileName 需要
            // 依据归属做越权校验，这里登记「文件名 → 归属用户」到 temp_reference_audio 表，
            // 使临时上传文件成为可被安全引用的第三种来源。
            User user = support.currentUser(authentication);
            if (user == null) {
                support.deleteFileQuietly(referenceId);
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            try {
                tempReferenceAudioRepository.save(new TempReferenceAudio(user.getId(), referenceId));
            } catch (Exception e) {
                // 登记失败时删除刚落盘的文件，避免孤儿文件累积（与 updateUserReference 的清理口径一致）
                support.deleteFileQuietly(referenceId);
                log.error("登记临时参考音频归属失败: userId={}, fileName={}", user.getId(), referenceId, e);
                response.put("success", false);
                response.put("message", "服务器错误，请稍后重试");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

            log.info("✅ 参考音频上传成功，referenceId: {}", referenceId);

            response.put("success", true);
            response.put("referenceId", referenceId);
            response.put("message", "参考音频上传成功");
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("参考音频上传失败", e);
            response.put("success", false);
            response.put("message", "参考音频上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (Exception e) {
            log.error("服务器错误", e);
            response.put("success", false);
            response.put("message", "服务器错误，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ===================== 用户参考音频库 =====================

    /**
     * 上传参考音频到「我的参考音频库」（保存文件 + 记录归属），供后续合成复用。
     */
    @PostMapping(value = "/references", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadUserReference(
            @RequestParam("reference_audio") MultipartFile referenceAudio,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            if (referenceAudio.isEmpty()) {
                response.put("success", false);
                response.put("message", "参考音频文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            // 服务端兜底校验扩展名：前端已校验，这里防止绕过前端直接调用接口上传非法文件
            String originalExt = support.getFileExtension(referenceAudio.getOriginalFilename());
            if (!support.isAllowedReferenceExt(originalExt)) {
                response.put("success", false);
                response.put("message", "不支持的文件格式: ." + originalExt);
                return ResponseEntity.badRequest().body(response);
            }
            if (referenceAudio.getSize() > support.maxFileSizeBytes()) {
                response.put("success", false);
                response.put("message", "参考音频文件大小不能超过 " + TtsControllerSupport.formatMB(support.maxFileSizeBytes()) + "MB");
                return ResponseEntity.badRequest().body(response);
            }

            // 视频格式会在此处被转成 WAV（VoiceCloningTtsService.saveUploadedFile 内部已转换）
            String fileName = service.uploadReferenceAudio(referenceAudio);

            UserReferenceAudio entity = new UserReferenceAudio();
            entity.setUserId(user.getId());
            String displayName = (name != null && !name.isBlank())
                    ? name.trim()
                    : (referenceAudio.getOriginalFilename() != null ? referenceAudio.getOriginalFilename() : "参考音频");
            entity.setName(displayName);
            entity.setFileName(fileName);
            // 视频转 WAV 后磁盘文件体积与上传文件不同，以实际落盘大小为准
            java.io.File savedFile = support.resolveUploadFile(fileName);
            entity.setFileSize(savedFile.exists() ? savedFile.length() : referenceAudio.getSize());
            entity.setDescription(description != null ? description.trim() : null);
            try {
                userReferenceAudioRepository.save(entity);
            } catch (Exception saveError) {
                // 文件已落盘但写库失败：删掉刚落盘的文件，避免孤儿文件累积
                // （与 /upload-reference 登记失败时的 deleteFileQuietly 口径一致）
                support.deleteFileQuietly(fileName);
                throw saveError;
            }

            response.put("success", true);
            response.put("id", entity.getId());
            response.put("name", entity.getName());
            response.put("fileName", entity.getFileName());
            response.put("description", entity.getDescription());
            response.put("fileSize", entity.getFileSize());
            response.put("message", "参考音频已保存到我的库");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("上传用户参考音频失败", e);
            response.put("success", false);
            response.put("message", "上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 我的参考音频库列表 */
    @GetMapping("/references")
    public ResponseEntity<Map<String, Object>> listUserReferences(Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            List<UserReferenceAudio> list = userReferenceAudioRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (UserReferenceAudio r : list) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", r.getId());
                m.put("name", r.getName());
                m.put("fileName", r.getFileName());
                m.put("description", r.getDescription());
                m.put("fileSize", r.getFileSize());
                m.put("shared", r.isShared());
                m.put("useCount", r.getUseCount());
                m.put("createdAt", formatTs(r.getCreatedAt()));
                items.add(m);
            }
            response.put("success", true);
            response.put("references", items);
            response.put("total", items.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("查询用户参考音频列表失败", e);
            response.put("success", false);
            response.put("message", "查询失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 删除我的参考音频（校验归属，同时删除磁盘文件） */
    @DeleteMapping("/references/{id}")
    public ResponseEntity<Map<String, Object>> deleteUserReference(@PathVariable Long id,
                                                                   Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            UserReferenceAudio entity = userReferenceAudioRepository.findById(id).orElse(null);
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                response.put("success", false);
                response.put("message", "参考音频不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // 顺序：先删数据库记录，成功后再尽力删磁盘文件。
            // 旧实现是先删文件、再删记录：记录删除失败（连接抖动等）时记录仍在但文件已丢，
            // 该条目永久指向不存在的文件（试听 404、建任务时拿不到文件）且无法自愈；
            // 反过来只留一个孤儿文件，可由清理脚本兜底、不影响功能。
            String fileName = entity.getFileName();
            userReferenceAudioRepository.delete(entity);

            java.io.File file = support.resolveUploadFile(fileName);
            if (file.exists() && !file.delete()) {
                log.warn("删除参考音频文件失败: {}", file.getAbsolutePath());
            }

            response.put("success", true);
            response.put("message", "已删除");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("删除用户参考音频失败", e);
            response.put("success", false);
            response.put("message", "删除失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 修改我的参考音频（仅本人，校验归属）。
     * 支持更新名称、描述；也可重新上传文件（视频会自动转 WAV），替换后删除旧文件。
     * 字段均为可选：只传 name 就只改名称，只传 description 就只改描述，都不传则原样返回。
     */
    @PostMapping(value = "/references/{id}/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> updateUserReference(
            @PathVariable Long id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "reference_audio", required = false) MultipartFile referenceAudio,
            Authentication authentication) {

        String originalFileName = null;
        String newFileName = null;
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            UserReferenceAudio entity = userReferenceAudioRepository.findById(id).orElse(null);
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                response.put("success", false);
                response.put("message", "参考音频不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            originalFileName = entity.getFileName();

            // 重新上传文件：转码（视频→WAV）+ 存盘 + 删旧文件
            if (referenceAudio != null && !referenceAudio.isEmpty()) {
                String replaceExt = support.getFileExtension(referenceAudio.getOriginalFilename());
                if (!support.isAllowedReferenceExt(replaceExt)) {
                    response.put("success", false);
                    response.put("message", "不支持的文件格式: ." + replaceExt);
                    return ResponseEntity.badRequest().body(response);
                }
                if (referenceAudio.getSize() > support.maxFileSizeBytes()) {
                    response.put("success", false);
                    response.put("message", "参考音频文件大小不能超过 " + TtsControllerSupport.formatMB(support.maxFileSizeBytes()) + "MB");
                    return ResponseEntity.badRequest().body(response);
                }
                newFileName = service.uploadReferenceAudio(referenceAudio);
                entity.setFileName(newFileName);
                java.io.File savedFile = support.resolveUploadFile(newFileName);
                entity.setFileSize(savedFile.exists() ? savedFile.length() : referenceAudio.getSize());
            }

            // 名称：仅当明确提供且非空时更新
            if (name != null && !name.isBlank()) {
                entity.setName(name.trim());
            }

            // 描述：参数存在即更新（允许传空字符串清空）
            if (description != null) {
                entity.setDescription(description.trim());
            }

            userReferenceAudioRepository.save(entity);

            // 删旧文件必须放在写库成功【之后】。
            // 旧实现是「先删旧文件、再 save」：一旦 save 失败（连接抖动等），DB 记录仍指向
            // 已被物理删除的旧文件名——该条目永久失效（试听 404、建任务时 resolveReferenceFileName
            // 拿到不存在的文件），且旧文件不可恢复。与 AdminService.uploadCover 同口径：
            // 先落新文件并写库，提交成功后再删旧件。
            if (newFileName != null && originalFileName != null && !newFileName.equals(originalFileName)) {
                java.io.File oldFile = support.resolveUploadFile(originalFileName);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除旧参考音频文件失败: {}", oldFile.getAbsolutePath());
                }
            }

            response.put("success", true);
            response.put("id", entity.getId());
            response.put("name", entity.getName());
            response.put("fileName", entity.getFileName());
            response.put("description", entity.getDescription());
            response.put("fileSize", entity.getFileSize());
            response.put("message", "已更新");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // 若替换文件时已上传新文件但记录保存失败，清理孤儿文件，避免磁盘残留
            // （newFileName 非空且与原文件名不同，说明旧文件已删除、新文件已落盘但 DB 未更新）
            if (newFileName != null && originalFileName != null && !newFileName.equals(originalFileName)) {
                java.io.File orphan = support.resolveUploadFile(newFileName);
                if (orphan.exists() && !orphan.delete()) {
                    log.warn("清理孤儿参考音频文件失败: {}", orphan.getAbsolutePath());
                }
            }
            log.error("修改用户参考音频失败: id={}", id, e);
            response.put("success", false);
            response.put("message", "修改失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 试听我的参考音频（流式输出，校验归属） */
    @GetMapping("/references/{id}/audio")
    public ResponseEntity<StreamingResponseBody> previewUserReference(@PathVariable Long id,
                                                                      Authentication authentication) {
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            UserReferenceAudio entity = userReferenceAudioRepository.findById(id).orElse(null);
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                return ResponseEntity.notFound().build();
            }

            java.io.File file = support.resolveUploadFile(entity.getFileName());
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            StreamingResponseBody body = outputStream ->
                    java.nio.file.Files.copy(file.toPath(), outputStream);
            String ext = support.getFileExtension(entity.getFileName());
            String mime = TtsControllerSupport.referenceAudioMimeType(ext);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mime));
            return new ResponseEntity<>(body, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("试听参考音频失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 服务器共享参考音频库列表（供普通用户合成时引用，所有登录用户可见）。
     */
    @GetMapping("/server-references")
    public ResponseEntity<Map<String, Object>> listServerReferences(Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            List<ReferenceAudio> list = referenceAudioRepository.findAllByOrderByCreatedAtDesc();
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (ReferenceAudio r : list) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", r.getId());
                m.put("name", r.getName());
                m.put("description", r.getDescription());
                m.put("fileName", r.getFileName());
                m.put("fileSize", r.getFileSize());
                m.put("createdAt", formatTs(r.getCreatedAt()));
                items.add(m);
            }
            response.put("success", true);
            response.put("references", items);
            response.put("total", items.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询服务器参考音频列表失败", e);
            response.put("success", false);
            response.put("message", "查询失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ===================== 用户分享参考音频（发现页排行榜） =====================

    /**
     * 开启/关闭分享（仅本人可操作）。
     *
     * 分享后该条目进入「发现 - 参考音频」排行榜，所有登录用户可见并可下载使用。
     * 取消分享只是从榜单撤下，<b>不影响已经下载到别人本地的副本</b>——
     * 那些副本属于对方，服务端无从（也不应）追溯删除。
     *
     * 请求体：{ "shared": true/false }
     */
    @PostMapping("/references/{id}/share")
    public ResponseEntity<Map<String, Object>> shareUserReference(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            UserReferenceAudio entity = userReferenceAudioRepository.findById(id).orElse(null);
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                response.put("success", false);
                response.put("message", "参考音频不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            boolean wantShare = body == null || !Boolean.FALSE.equals(body.get("shared"));
            entity.setShared(wantShare);
            // 只在「开启分享」时刷新时间：排序用「分享早的在前」，
            // 反复开关不应把老条目刷到榜单前面
            if (wantShare && entity.getSharedAt() == null) {
                entity.setSharedAt(java.time.LocalDateTime.now());
            }
            userReferenceAudioRepository.save(entity);

            response.put("success", true);
            response.put("id", entity.getId());
            response.put("shared", entity.isShared());
            response.put("useCount", entity.getUseCount());
            response.put("message", wantShare ? "已分享，他人可使用" : "已取消分享");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("切换参考音频分享状态失败: id={}", id, e);
            response.put("success", false);
            response.put("message", "操作失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 分享排行榜：已分享的参考音频按使用量倒序。
     *
     * 返回 { success, references: [{id, name, description, fileSize, useCount,
     * ownerName, sharedAt, createdAt, mine}], total }。
     * `mine` 表示是否本人分享，客户端据此决定显示「已使用」还是「使用」。
     */
    @GetMapping("/shared-references")
    public ResponseEntity<Map<String, Object>> listSharedReferences(
            @RequestParam(value = "limit", required = false) Integer limit,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();
        try {
            // 发现社区对游客开放：SecurityConfig 已 permitAll GET /api/tts/shared-references。
            // Spring MVC 的 ServletRequest 参数解析对 AnonymousAuthenticationToken 会注入 null，
            // 因此这里**不能**再靠 authentication==null 判未登录——currentUser(null) 天然返回 null，
            // 下面 mine 字段用 `current != null` 兜底即正确（游客视角永远 false）。
            User current = support.currentUser(authentication);

            int n = (limit == null || limit <= 0) ? DEFAULT_RANK_LIMIT : Math.min(limit, MAX_RANK_LIMIT);
            Pageable pageable = PageRequest.of(0, n);
            List<UserReferenceAudio> list =
                    userReferenceAudioRepository
                            .findBySharedOrderByUseCountDescSharedAtAscCreatedAtAsc(Boolean.TRUE, pageable);

            // 批量查分享者昵称，避免每行一次查询（N+1）
            java.util.Set<Long> ownerIds = new java.util.HashSet<>();
            for (UserReferenceAudio r : list) {
                ownerIds.add(r.getUserId());
            }
            Map<Long, String> ownerNames = new HashMap<>();
            if (!ownerIds.isEmpty()) {
                for (User u : userRepository.findAllById(ownerIds)) {
                    ownerNames.put(u.getId(), u.getUsername());
                }
            }

            List<Map<String, Object>> items = new java.util.ArrayList<>();
            int rank = 0;
            for (UserReferenceAudio r : list) {
                rank++;
                Map<String, Object> m = new HashMap<>();
                m.put("rank", rank);
                m.put("id", r.getId());
                m.put("name", r.getName());
                m.put("description", r.getDescription());
                m.put("fileName", r.getFileName());
                m.put("fileSize", r.getFileSize());
                m.put("useCount", r.getUseCount() == null ? 0L : r.getUseCount());
                m.put("ownerName", ownerNames.getOrDefault(r.getUserId(), "未知用户"));
                m.put("sharedAt", formatTs(r.getSharedAt()));
                m.put("createdAt", formatTs(r.getCreatedAt()));
                m.put("mine", current != null && r.getUserId().equals(current.getId()));
                items.add(m);
            }
            response.put("success", true);
            response.put("references", items);
            response.put("total", items.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("查询分享参考音频排行榜失败", e);
            response.put("success", false);
            response.put("message", "查询失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 上报一次「使用」：客户端把分享音频成功存进自己的参考音频库后调用，使用量 +1。
     *
     * 计数放在这里而不是下载端点，是为了把「试听」和「真正使用」区分开——
     * 两者走同一个下载流，若在那里计数，试听会把榜单刷花。
     * 本人使用自己的音频不计数（自己用不算「被使用」）。
     */
    @PostMapping("/shared-references/{id}/use")
    public ResponseEntity<Map<String, Object>> reportSharedReferenceUse(
            @PathVariable Long id,
            Authentication authentication) {

        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }

            UserReferenceAudio entity = userReferenceAudioRepository.findById(id).orElse(null);
            if (entity == null || !entity.isShared()) {
                response.put("success", false);
                response.put("message", "参考音频不存在或未分享");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // 自己用自己分享的音频不计数
            if (entity.getUserId().equals(user.getId())) {
                response.put("success", true);
                response.put("counted", false);
                response.put("useCount", entity.getUseCount() == null ? 0L : entity.getUseCount());
                return ResponseEntity.ok(response);
            }

            int updated = userReferenceAudioRepository.incrementUseCount(id);
            if (updated == 0) {
                response.put("success", false);
                response.put("message", "参考音频不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            long newCount = (entity.getUseCount() == null ? 0L : entity.getUseCount()) + 1;

            response.put("success", true);
            response.put("counted", true);
            response.put("useCount", newCount);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("上报参考音频使用量失败: id={}", id, e);
            response.put("success", false);
            response.put("message", "操作失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 单条分享参考音频详情（供帖子内嵌「分享好声音」卡片渲染用）。
     * 仅返回已分享的条目；未分享/不存在返回 404。
     */
    @GetMapping("/shared-references/{id}")
    public ResponseEntity<Map<String, Object>> getSharedReference(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            // 与 listSharedReferences 同源：游客打开帖子内嵌的分享音频卡片时，
            // MVC 注入的 Authentication 为 null（匿名 token 被过滤），不能再判 401。
            User current = support.currentUser(authentication);
            UserReferenceAudio r = userReferenceAudioRepository.findById(id).orElse(null);
            if (r == null || !r.isShared()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "参考音频不存在或未分享"));
            }
            Map<String, Object> m = new HashMap<>();
            m.put("success", true);
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("fileName", r.getFileName());
            m.put("fileSize", r.getFileSize());
            m.put("useCount", r.getUseCount() == null ? 0L : r.getUseCount());
            m.put("createdAt", formatTs(r.getCreatedAt()));
            m.put("mine", current != null && r.getUserId().equals(current.getId()));
            return ResponseEntity.ok(m);
        } catch (Exception e) {
            log.error("查询分享参考音频详情失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "查询失败，请稍后重试"));
        }
    }

    /**
     * 下载/试听分享的参考音频（流式输出）。
     *
     * <b>不计数</b>：使用量由客户端在「存进我的库」后调用
     * {@code POST /shared-references/{id}/use} 上报，见该方法的说明。
     */
    @GetMapping("/shared-references/{id}/audio")
    public ResponseEntity<StreamingResponseBody> downloadSharedReference(@PathVariable Long id,
                                                                         Authentication authentication) {
        try {
            // 试听流对游客开放（SecurityConfig 已 permitAll GET /api/tts/shared-references/*/audio）。
            // 分享出去参考音频的语义就是「谁都能试听」，不需要知道当前用户是谁；
            // 使用量上报（POST /use）仍要求登录，防刷榜。
            UserReferenceAudio entity = userReferenceAudioRepository.findById(id).orElse(null);
            if (entity == null || !entity.isShared()) {
                return ResponseEntity.notFound().build();
            }
            java.io.File file = support.resolveUploadFile(entity.getFileName());
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }
            StreamingResponseBody body = outputStream ->
                    java.nio.file.Files.copy(file.toPath(), outputStream);
            String ext = support.getFileExtension(entity.getFileName());
            String mime = TtsControllerSupport.referenceAudioMimeType(ext);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mime));
            return new ResponseEntity<>(body, headers, HttpStatus.OK);

        } catch (Exception e) {
            log.error("下载分享参考音频失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** 试听服务器共享参考音频（流式输出，校验存在即可，所有登录用户可用） */
    @GetMapping("/server-references/{id}/audio")
    public ResponseEntity<StreamingResponseBody> previewServerReference(@PathVariable Long id,
                                                                        Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            ReferenceAudio entity = referenceAudioRepository.findById(id).orElse(null);
            if (entity == null) {
                return ResponseEntity.notFound().build();
            }
            java.io.File file = support.resolveUploadFile(entity.getFileName());
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }
            StreamingResponseBody body = outputStream ->
                    java.nio.file.Files.copy(file.toPath(), outputStream);
            String ext = support.getFileExtension(entity.getFileName());
            String mime = TtsControllerSupport.referenceAudioMimeType(ext);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(mime));
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("试听服务器参考音频失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}