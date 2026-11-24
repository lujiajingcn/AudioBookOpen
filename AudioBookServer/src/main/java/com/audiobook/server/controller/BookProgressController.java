package com.audiobook.server.controller;

import com.audiobook.server.model.User;
import com.audiobook.server.service.BookProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 跨端阅读进度接口（身份 = book_key）。
 *
 * <p><b>与老端点 {@code /api/progress} 的区别</b>：
 * <ul>
 *   <li>{@code /api/progress?taskId=} —— <b>私有任务</b>口径：按合成任务归属校验，
 *       只对「自己的任务」有效。书城书的音频常由管理员合成 ⇒ 普通用户调它必然 403，
 *       进度永远传不上去。</li>
 *   <li>本端点 —— <b>书</b>口径：按 {@code book_key} 身份，与合成状态、任务归属都无关。
 *       同一本书在 Android / PC / Web 三个终端算出同一个 key ⇒ 进度真正互通。</li>
 * </ul>
 *
 * <p><b>位置语义</b>：{@code segment} 是 1-based 位置。
 * 有音频的书它是音频段号；无音频的书它是正文段落序号
 * （服务端 {@code /content} 的 {@code paragraphs} 下标 +1）。
 * 客户端两者都能定位：有音频用段号，无音频用段落序号去正文行。
 */
@RestController
@RequestMapping("/api/book-progress")
public class BookProgressController {

    private static final Logger log = LoggerFactory.getLogger(BookProgressController.class);

    private final TtsControllerSupport support;
    private final BookProgressService progressService;

    public BookProgressController(TtsControllerSupport support, BookProgressService progressService) {
        this.support = support;
        this.progressService = progressService;
    }

    /**
     * 拉取某本书的阅读进度。
     * 返回 {success, hasProgress, segment, offsetMs, updatedAt}；尚无进度时 hasProgress=false。
     *
     * @param bookKey 跨端身份键（{@code s:{storeBookId}} / {@code t:{taskId}} / {@code n:{书名}}）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getProgress(@RequestParam("bookKey") String bookKey,
                                                           Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            Optional<com.audiobook.server.model.UserBookProgress> progress =
                    progressService.load(user.getId(), bookKey);
            response.put("success", true);
            if (progress.isEmpty()) {
                response.put("hasProgress", false);
            } else {
                response.put("hasProgress", true);
                response.put("segment", progress.get().getSegmentNumber());
                response.put("offsetMs", progress.get().getOffsetMs());
                response.put("updatedAt", progress.get().getUpdatedAt() == null
                        ? null : progress.get().getUpdatedAt().toString());
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询阅读进度失败 bookKey={}", bookKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "查询失败，请稍后重试"));
        }
    }

    /**
     * 保存/更新某本书的阅读进度。
     * 入参（JSON body）：bookKey(String)、segment(int)、offsetMs(int)。
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> saveProgress(@RequestBody Map<String, Object> body,
                                                           Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        try {
            User user = support.currentUser(authentication);
            if (user == null) {
                response.put("success", false);
                response.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            }
            String bookKey = body.get("bookKey") == null ? null : String.valueOf(body.get("bookKey"));
            Integer segment = parseInt(body.get("segment"));
            Integer offsetMs = parseInt(body.get("offsetMs"));
            if (bookKey == null || bookKey.isBlank() || segment == null || offsetMs == null) {
                response.put("success", false);
                response.put("message", "参数缺失：bookKey / segment / offsetMs 均为必填");
                return ResponseEntity.badRequest().body(response);
            }
            if (segment < 0 || offsetMs < 0) {
                response.put("success", false);
                response.put("message", "参数非法：segment / offsetMs 不能为负");
                return ResponseEntity.badRequest().body(response);
            }
            boolean saved = progressService.save(user.getId(), bookKey, segment, offsetMs);
            response.put("success", saved);
            if (!saved) {
                // bookKey 归一后为空 = 非法身份，不是用户的错，但要让调用方能区分
                response.put("message", "bookKey 非法，无法保存进度");
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("保存阅读进度失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "保存失败，请稍后重试"));
        }
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
