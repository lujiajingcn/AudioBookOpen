package com.audiobook.server.controller;

import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.model.Task;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.util.ZipAudioPacker;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 音频下载：单段下载与整任务 ZIP 打包下载。
 * 由原 Controller.java（God Object）拆分而来，路径保持不变。
 */
@RestController
@RequestMapping("/api/tts")
public class TtsDownloadController {

    private static final Logger log = LoggerFactory.getLogger(TtsDownloadController.class);

    private final TaskRepository taskRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final TtsControllerSupport support;

    public TtsDownloadController(TaskRepository taskRepository,
                                 AudioSegmentRepository audioSegmentRepository,
                                 TtsControllerSupport support) {
        this.taskRepository = taskRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.support = support;
    }

    /**
     * 下载单个音频片段（流式响应，避免一次性加载大文件到堆内存）。
     * <p>
     * 返回类型必须是 {@code ResponseEntity<StreamingResponseBody>}：Spring 的
     * HttpEntityMethodProcessor 据此识别流式响应并走 streaming 分支；若写成
     * {@code ResponseEntity<?>}，lambda 的运行时类型不再是声明泛型，会被当成普通 body
     * 去匹配 HttpMessageConverter，对 audio/mpeg 找不到 converter，从而抛出
     * HttpMessageNotWritableException（"No converter for ... with preset Content-Type"）。
     */
    @GetMapping("/segment/{segmentId}/download")
    public ResponseEntity<StreamingResponseBody> downloadSegment(
            @PathVariable Long segmentId, Authentication authentication) {
        // 只查元数据：@Lob audio_data 不随实体一起加载，绝大多数请求走磁盘文件时
        // 完全不会触碰 BLOB（findById 会把整段音频读进堆内存，再在下面被丢弃）
        AudioSegmentRepository.SegmentDownloadMeta segment =
                audioSegmentRepository.findDownloadMetaById(segmentId).orElse(null);
        if (segment == null) {
            return ResponseEntity.notFound().build();
        }
        // 校验归属：通过 task_id 找到任务，再确认用户
        Task task = taskRepository.findById(segment.getTaskId()).orElse(null);
        if (task == null || !support.belongsToUser(task, authentication.getName())) {
            // 越权抛出 AccessDeniedException，交由 GlobalExceptionHandler / SecurityConfig
            // 的 accessDeniedHandler 统一返回 403 JSON（{success:false, message:"没有权限执行此操作"}），
            // 与其他错误响应结构一致；返回空 body 的 403 则前端拿不到任何提示。
            throw new AccessDeniedException("无权访问该音频片段");
        }

        // 先确定可用的音频来源，避免「文件缺失且 BLOB 为空」时返回 0 字节的静默响应——
        // 浏览器对空音频往往既不报错也不出声，前端表现为「点击毫无反应」，用户无法判断是
        // 合成未完成还是文件丢失。缺失时显式抛异常，交由
        // GlobalExceptionHandler 转成 JSON 错误，前端即可明确提示「请重试该任务」。
        java.nio.file.Path audioPath = segment.getAudioFilePath() != null
                ? support.resolveUploadFile(segment.getAudioFilePath()).toPath() : null;
        // 文件需「存在且非空」才算可用；0 字节文件同样视为缺失，回落到 BLOB
        boolean fileUsable = false;
        if (audioPath != null && java.nio.file.Files.exists(audioPath)) {
            try {
                fileUsable = java.nio.file.Files.size(audioPath) > 0;
            } catch (java.io.IOException e) {
                // exists 之后文件被移除等导致取大小失败：视为不可用，回落到 BLOB
                fileUsable = false;
            }
        }

        if (!fileUsable) {
            // 磁盘缺失时尝试回读 audio_data（当前恒为 NULL），仍为空则显式报错
            byte[] fallback = audioSegmentRepository.findAudioDataById(segmentId);
            if (fallback == null || fallback.length == 0) {
                throw new IllegalArgumentException(
                        "该片段音频文件缺失（可能合成未成功完成），请重试该任务以重新生成");
            }
        }

        final boolean useFile = fileUsable;
        StreamingResponseBody body = outputStream -> {
            try {
                if (useFile) {
                    java.nio.file.Files.copy(audioPath, outputStream);
                } else {
                    // 回退分支：磁盘缺失时单独查一次 audio_data（当前恒为 NULL，通常走不到写入）
                    byte[] audioData = audioSegmentRepository.findAudioDataById(segmentId);
                    if (audioData != null && audioData.length > 0) {
                        outputStream.write(audioData);
                    }
                }
                outputStream.flush();
            } catch (java.io.IOException e) {
                // 流出过程中客户端中断（broken pipe）或读盘失败：属常见情况，记录后直接结束，
                // 不要冒泡到异步错误派发（否则配合容器错误页会再次走安全链，掩盖真实错误）。
                // 注意：此分支仅覆盖「文件确实存在且 >0 字节」的可用段，缺失音频已在上面
                // 同步抛 IllegalArgumentException 返回 400，不会走到这里。
                log.warn("音频片段流出中断/失败: segmentId={}, useFile={}, error={}",
                        segmentId, useFile, e.getMessage());
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.setContentDispositionFormData("attachment", "segment_" + segment.getSegmentNumber() + ".mp3");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /**
     * 批量下载任务的所有音频片段（ZIP压缩包）。
     * 使用元数据投影（不含 @Lob 大字段）查询，逐段从磁盘流式写入响应，
     * 整本书音频不再驻留堆内存；磁盘缺失的段会单独尝试回读（audio_data 恒为 NULL，实际按缺失报错）。
     */
    @GetMapping("/task/{taskId}/download-all")
    public void downloadAllAudio(@PathVariable Long taskId, HttpServletResponse response,
                                 Authentication authentication) {
        try {
            Task task = support.getOwnedTask(taskId, authentication);
            if (task == null) {
                support.writeErrorJson(response, HttpServletResponse.SC_FORBIDDEN, "无权访问该任务");
                return;
            }

            List<AudioSegmentRepository.SegmentMeta> segments =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);
            if (segments.isEmpty()) {
                support.writeErrorJson(response, HttpServletResponse.SC_NOT_FOUND, "没有可下载的音频片段");
                return;
            }

            // 检查是否有已完成的片段
            long completedCount = segments.stream()
                    .filter(s -> SegmentStatus.COMPLETED.value().equals(s.getStatus()))
                    .count();

            if (completedCount == 0) {
                support.writeErrorJson(response, HttpServletResponse.SC_BAD_REQUEST, "任务尚未完成，无法下载音频");
                return;
            }

            String bookName = support.extractBookNameFromPath(task.getBookFilePath());
            // 设置响应头（RFC 5987：中文名必须 URL 编码，否则头部损坏/文件名乱码）
            response.setContentType("application/zip");
            String encodedName = java.net.URLEncoder.encode(bookName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setHeader("Content-Disposition",
                    "attachment; filename=\"audio_" + taskId + ".zip\"; filename*=UTF-8''audio_" + encodedName + ".zip");

            // 逐段流式写出（每段一个 8KB 缓冲，内存占用与书大小无关）
            ZipAudioPacker.PackResult packResult = ZipAudioPacker.pack(
                    segments,
                    response.getOutputStream(),
                    // 优先从文件系统读取（正常路径：合成时音频都落了盘）；
                    // 路径解析失败会抛出，由外层统一写入错误 JSON
                    segment -> {
                        if (segment.getAudioFilePath() == null) {
                            return null;
                        }
                        java.io.File audioFile = support.resolveUploadFile(segment.getAudioFilePath());
                        return audioFile.exists() ? audioFile : null;
                    },
                    audioSegmentRepository::findAudioDataById);

            log.info("批量下载完成: taskId={}, 成功打包 {}/{} 个片段",
                    taskId, packResult.successCount(), segments.size());

        } catch (Exception e) {
            log.error("批量下载音频失败: taskId={}, 错误: {}", taskId, e.getMessage(), e);
            try {
                support.writeErrorJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "下载失败，请稍后重试");
            } catch (IOException ex) {
                log.error("写入错误响应失败", ex);
            }
        }
    }
}