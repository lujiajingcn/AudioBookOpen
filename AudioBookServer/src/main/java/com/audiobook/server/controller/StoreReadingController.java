package com.audiobook.server.controller;

import com.audiobook.server.model.ReadingProgress;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.ReadingProgressRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.service.BookProgressService;
import com.audiobook.server.service.BookStoreService;
import com.audiobook.server.service.BookTextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 书城书籍的「阅读侧」接口：片段元数据 / 目录 / 单段音频 / 阅读进度。
 *
 * <p><b>为什么单开一组端点而不复用 {@code /api/tts/**}</b>：
 * TTS 任务是<b>用户私有资源</b> —— 段列表、单段下载、进度读写全部以
 * {@code tasks.user_id} 做归属校验（见 {@link TtsControllerSupport#getOwnedTask}）。
 * 而书城书籍的音频是<b>公共资源</b>：既有的
 * {@code GET /api/store/book/{id}/content}（正文）与
 * {@code GET /api/store/book/{id}/audio/download}（整本音频 zip）都只要求登录、
 * 不校验任务归属。
 *
 * <p>书城里的书通常由管理员在管理端合成，{@code store_books.task_id} 指向的是
 * <b>管理员的任务</b>。阅读页若继续调私有接口，普通用户必然 403 ——
 * 表现就是「左键点击书籍 → 加载书籍内容失败」，而管理员账号（任务归他）却正常。
 * 因此本组端点按 {@code store_books.id} 推导任务（{@code store_books.task_id}），
 * 只校验「书存在且在售」，不再校验任务归属。
 *
 * <p>越权防线在<b>书的边界</b>上：单段音频必须属于本书的 task_id，
 * 否则拿着 A 书的 id 就能播 B 书的片段。
 */
@RestController
@RequestMapping("/api/store/book/{bookId}")
public class StoreReadingController {

    private static final Logger log = LoggerFactory.getLogger(StoreReadingController.class);

    /** 下架书籍对普通用户不可见，与详情/封面/正文/下载保持同一口径 */
    private static final String STATUS_OFFSHELF = "offshelf";

    private final BookStoreService bookStoreService;
    private final AudioSegmentRepository audioSegmentRepository;
    private final TaskRepository taskRepository;
    private final ReadingProgressRepository progressRepository;
    private final BookTextExtractor bookTextExtractor;
    private final BookProgressService bookProgressService;
    private final TtsControllerSupport support;

    public StoreReadingController(BookStoreService bookStoreService,
                                  AudioSegmentRepository audioSegmentRepository,
                                  TaskRepository taskRepository,
                                  ReadingProgressRepository progressRepository,
                                  BookTextExtractor bookTextExtractor,
                                  BookProgressService bookProgressService,
                                  TtsControllerSupport support) {
        this.bookStoreService = bookStoreService;
        this.audioSegmentRepository = audioSegmentRepository;
        this.taskRepository = taskRepository;
        this.progressRepository = progressRepository;
        this.bookTextExtractor = bookTextExtractor;
        this.bookProgressService = bookProgressService;
        this.support = support;
    }

    // ---------- 片段列表 ----------

    /**
     * 本书的音频片段元数据（与 {@code /api/tts/task/{id}/segments-list} 同构，
     * 前端可复用同一套 SegmentInfo 渲染与播放逻辑）。
     *
     * <p>无音频的书（{@code task_id IS NULL}）返回空列表而非报错：
     * 阅读页本就用 {@code hasAudio} 分流到纯文本分支，此处只作兜底。
     */
    @GetMapping("/segments")
    public ResponseEntity<Map<String, Object>> segments(@PathVariable Long bookId) {
        Map<String, Object> response = new HashMap<>();
        try {
            StoreBook book = onshelfBookOrNull(bookId);
            if (book == null) {
                return notFound(response, "书籍不存在");
            }
            if (book.getTaskId() == null) {
                response.put("success", true);
                response.put("segments", List.of());
                response.put("total", 0);
                return ResponseEntity.ok(response);
            }

            // 元数据投影查询：不触碰 @Lob 的 audio_data 大字段（整本音频驻留堆会 OOM）
            List<AudioSegmentRepository.SegmentMeta> segments =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(book.getTaskId());

            List<Map<String, Object>> list = new ArrayList<>(segments.size());
            for (AudioSegmentRepository.SegmentMeta segment : segments) {
                Map<String, Object> info = new HashMap<>();
                info.put("id", segment.getId());
                info.put("segmentNumber", segment.getSegmentNumber());
                info.put("textContent", segment.getTextContent());
                info.put("status", segment.getStatus());
                info.put("errorMessage", segment.getErrorMessage());
                info.put("voice", segment.getVoice());
                list.add(info);
            }
            response.put("success", true);
            response.put("segments", list);
            response.put("total", list.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取书城书籍片段列表失败: bookId={}", bookId, e);
            return error(response, "查询片段列表失败，请稍后重试");
        }
    }

    // ---------- 目录 ----------

    /**
     * 本书目录（将电子书目录条目映射到合成片段号）。
     *
     * <p>映射口径与 {@code /api/tts/task/{id}/toc} 完全一致：目录条目的 charOffset 是
     * 「原文真实下标」，所以段落起点必须在同一坐标系里用
     * {@link BookTextExtractor#locateStartOffsets} 求，不能按段文本长度累加
     * （切段会 trim 首尾空白，累加坐标系统性偏小 → 映射出的段号偏大 → 点目录跳过头）。
     */
    @GetMapping("/toc")
    public ResponseEntity<Map<String, Object>> toc(@PathVariable Long bookId) {
        Map<String, Object> response = new HashMap<>();
        try {
            StoreBook book = onshelfBookOrNull(bookId);
            if (book == null) {
                return notFound(response, "书籍不存在");
            }
            if (book.getTaskId() == null) {
                return emptyToc(response);
            }
            Task task = taskRepository.findById(book.getTaskId()).orElse(null);
            if (task == null) {
                return emptyToc(response);
            }
            String bookFilePath = task.getBookFilePath();
            if (bookFilePath == null || bookFilePath.isEmpty()) {
                return emptyToc(response);
            }

            String extension = support.getFileExtension(new File(bookFilePath).getName());
            File bookFile = support.resolveUploadFile(bookFilePath);
            if (!bookFile.exists()) {
                return emptyToc(response);
            }

            List<BookTextExtractor.TocEntry> tocEntries;
            if (extension != null
                    && (extension.equalsIgnoreCase("pdf")
                        || extension.equalsIgnoreCase("epub")
                        || extension.equalsIgnoreCase("mobi")
                        || extension.equalsIgnoreCase("md"))) {
                tocEntries = bookTextExtractor.extractToc(bookFile, extension);
            } else {
                tocEntries = List.of(); // TXT 不含结构信息；MD 的标题会导出为目录
            }

            List<AudioSegmentRepository.SegmentMeta> segments =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(book.getTaskId());
            // 无片段、或该格式本就没有目录（TXT）时直接空目录：后者顺带省掉一次整本原文解析
            // （extractText 对 PDF 是重操作）
            if (segments.isEmpty() || tocEntries.isEmpty()) {
                return emptyToc(response);
            }

            List<String> segmentTexts = new ArrayList<>(segments.size());
            for (AudioSegmentRepository.SegmentMeta segment : segments) {
                String text = segment.getTextContent();
                segmentTexts.add(text != null ? text : "");
            }
            String fullText = bookTextExtractor.extractText(bookFile, extension);
            int[] segmentStartOffsets = BookTextExtractor.locateStartOffsets(fullText, segmentTexts);

            List<Map<String, Object>> toc = new ArrayList<>(tocEntries.size());
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
            log.error("获取书城书籍目录失败: bookId={}", bookId, e);
            return error(response, "获取目录失败，请稍后重试");
        }
    }

    // ---------- 单段音频 ----------

    /**
     * 播放某一段音频（流式）。
     *
     * <p>返回类型必须是 {@code ResponseEntity<StreamingResponseBody>}：Spring 的
     * HttpEntityMethodProcessor 据此走 streaming 分支；写成 {@code ResponseEntity<?>}
     * 会因 lambda 运行时类型丢失而被当成普通 body，对 audio/mpeg 找不到
     * HttpMessageConverter，抛 HttpMessageNotWritableException。
     *
     * <p>越权边界：片段必须属于<b>本本书</b>的 task_id。少了这一步，拿到任意
     * {@code segmentId} 就能通过任意在售书的 id 播放他人任务的音频。
     */
    @GetMapping("/segments/{segmentId}/audio")
    public ResponseEntity<StreamingResponseBody> segmentAudio(@PathVariable Long bookId,
                                                              @PathVariable Long segmentId) {
        StoreBook book = onshelfBookOrNull(bookId);
        if (book == null || book.getTaskId() == null) {
            return ResponseEntity.notFound().build();
        }
        AudioSegmentRepository.SegmentDownloadMeta segment =
                audioSegmentRepository.findDownloadMetaById(segmentId).orElse(null);
        if (segment == null || !book.getTaskId().equals(segment.getTaskId())) {
            return ResponseEntity.notFound().build();
        }

        // 先确定可用音源：文件缺失且 BLOB 为空时若返回 0 字节，浏览器既不报错也不出声，
        // 表现为「点了毫无反应」。显式抛异常交由 GlobalExceptionHandler 转 JSON，前端可提示。
        java.nio.file.Path audioPath = segment.getAudioFilePath() != null
                ? support.resolveUploadFile(segment.getAudioFilePath()).toPath() : null;
        boolean fileUsable = false;
        if (audioPath != null && java.nio.file.Files.exists(audioPath)) {
            try {
                fileUsable = java.nio.file.Files.size(audioPath) > 0;
            } catch (java.io.IOException e) {
                fileUsable = false;
            }
        }
        if (!fileUsable) {
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
                    byte[] audioData = audioSegmentRepository.findAudioDataById(segmentId);
                    if (audioData != null && audioData.length > 0) {
                        outputStream.write(audioData);
                    }
                }
                outputStream.flush();
            } catch (java.io.IOException e) {
                // 流出中客户端中断（broken pipe）：常见情况，记录后结束，不要冒泡到异步错误派发
                log.warn("书城片段流出中断/失败: bookId={}, segmentId={}, error={}",
                        bookId, segmentId, e.getMessage());
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.setContentDispositionFormData("attachment",
                "segment_" + segment.getSegmentNumber() + ".mp3");
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    // ---------- 阅读进度 ----------

    /**
     * 读取当前用户在本书上的阅读进度。
     *
     * <p>落库键仍是 {@code (user_id, store_books.task_id)} —— 与任务播放页
     * （{@code /api/progress}，按 taskId）共用同一行，这样「在书城读」与
     * 「在任务页听」同一本书时进度互通。差异只在校验侧：这里校验的是书，
     * 而非任务归属。
     */
    @GetMapping("/progress")
    public ResponseEntity<Map<String, Object>> getProgress(@PathVariable Long bookId,
                                                           Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                return unauthorized(response);
            }
            StoreBook book = onshelfBookOrNull(bookId);
            if (book == null) {
                return notFound(response, "书籍不存在");
            }
            response.put("success", true);
            // 书级身份 = s:{bookId}：与合成状态无关，因此**无音频的书也能记进度**。
            // 老实现挂在 store_books.task_id 上，task_id 为空（从未合成）时
            // 直接返回 hasProgress=false —— 纯阅读的书因此完全记不住位置。
            String bookKey = "s:" + bookId;
            var byBookKey = bookProgressService.load(user.getId(), bookKey).orElse(null);
            if (byBookKey != null) {
                response.put("hasProgress", true);
                response.put("segment", byBookKey.getSegmentNumber());
                response.put("offsetMs", byBookKey.getOffsetMs());
                response.put("updatedAt", byBookKey.getUpdatedAt() == null
                        ? null : byBookKey.getUpdatedAt().toString());
                return ResponseEntity.ok(response);
            }
            if (book.getTaskId() == null) {
                response.put("hasProgress", false);
                return ResponseEntity.ok(response);
            }
            ReadingProgress progress =
                    progressRepository.findByUserIdAndTaskId(user.getId(), book.getTaskId()).orElse(null);
            if (progress == null) {
                response.put("hasProgress", false);
            } else {
                response.put("hasProgress", true);
                response.put("segment", progress.getSegmentNumber());
                response.put("offsetMs", progress.getOffsetMs());
                response.put("updatedAt",
                        progress.getUpdatedAt() == null ? null : progress.getUpdatedAt().toString());
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询书城阅读进度失败: bookId={}", bookId, e);
            return error(response, "查询失败，请稍后重试");
        }
    }

    /**
     * 保存当前用户在本书上的阅读进度。
     *
     * <p>入参只需 {@code {segment, offsetMs}}：taskId 由书推导，不接受客户端传入 ——
     * 否则普通用户能借这条公共通道写任意任务的进度。
     */
    @PutMapping("/progress")
    public ResponseEntity<Map<String, Object>> saveProgress(@PathVariable Long bookId,
                                                            @RequestBody Map<String, Object> body,
                                                            Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                return unauthorized(response);
            }
            StoreBook book = onshelfBookOrNull(bookId);
            if (book == null) {
                return notFound(response, "书籍不存在");
            }
            Integer segment = parseInt(body.get("segment"));
            Integer offsetMs = parseInt(body.get("offsetMs"));
            if (segment == null || offsetMs == null) {
                response.put("success", false);
                response.put("message", "参数缺失：segment / offsetMs 均为必填");
                return ResponseEntity.badRequest().body(response);
            }
            if (segment < 0 || offsetMs < 0) {
                response.put("success", false);
                response.put("message", "参数非法：segment / offsetMs 不能为负");
                return ResponseEntity.badRequest().body(response);
            }
            // 书级身份 = s:{bookId}：与合成状态无关 ⇒ **无音频的书也能记进度**。
            // 老实现在这里直接 400「该书尚未合成音频，无阅读进度可保存」，
            // 于是纯阅读的书在所有终端都记不住位置。
            String bookKey = "s:" + bookId;
            bookProgressService.save(user.getId(), bookKey, segment, offsetMs);

            // 任务口径那一份也照写（老客户端读它）：有音频时两边一致，互不打架。
            Long taskId = book.getTaskId();
            if (taskId != null) {
                ReadingProgress progress = progressRepository.findByUserIdAndTaskId(user.getId(), taskId)
                        .orElseGet(() -> {
                            ReadingProgress p = new ReadingProgress();
                            p.setUserId(user.getId());
                            p.setTaskId(taskId);
                            return p;
                        });
                progress.setSegmentNumber(segment);
                progress.setOffsetMs(offsetMs);
                progress.setUpdatedAt(LocalDateTime.now());
                progressRepository.save(progress);
            }
            response.put("success", true);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("保存书城阅读进度失败: bookId={}", bookId, e);
            return error(response, "保存失败，请稍后重试");
        }
    }

    // ---------- 内部辅助 ----------

    /** 取「存在且在售」的书；否则 null（下架书对普通用户与不存在等价） */
    private StoreBook onshelfBookOrNull(Long bookId) {
        Optional<StoreBook> book = bookStoreService.getBook(bookId);
        if (book.isEmpty() || STATUS_OFFSHELF.equals(book.get().getStatus())) {
            return null;
        }
        return book.get();
    }

    private ResponseEntity<Map<String, Object>> emptyToc(Map<String, Object> response) {
        response.put("success", true);
        response.put("toc", List.of());
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> notFound(Map<String, Object> response, String message) {
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    private ResponseEntity<Map<String, Object>> unauthorized(Map<String, Object> response) {
        response.put("success", false);
        response.put("message", "用户未认证");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    private ResponseEntity<Map<String, Object>> error(Map<String, Object> response, String message) {
        response.put("success", false);
        response.put("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private Integer parseInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
