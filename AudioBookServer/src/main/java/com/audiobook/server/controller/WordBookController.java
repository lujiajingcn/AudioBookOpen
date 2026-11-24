package com.audiobook.server.controller;

import com.audiobook.server.dto.SyncWordBookRequest;
import com.audiobook.server.dto.WordBookItemRequest;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserWordBook;
import com.audiobook.server.service.WordBookService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生词本 REST 接口（跨端同步）。
 *
 * <p>路径 {@code /api/word-book/**} —— 与 {@code /api/favorites/**} 一样由
 * SecurityConfig 的 {@code anyRequest().authenticated()} 兜底，不必另写 matcher。
 * 所有方法都从 {@link Authentication} 解析当前 {@link User}；未登录返 401。
 *
 * <p>四个端点：
 * <ol>
 *   <li>{@code GET    /api/word-book}：拉全量列表（PC/Android 每次进单词本页触发）</li>
 *   <li>{@code POST   /api/word-book}：单条幂等 upsert（用户点星标即时入库）</li>
 *   <li>{@code POST   /api/word-book/sync}：批量合并 + 返全量（老数据迁移 / 离线补传）</li>
 *   <li>{@code DELETE  /api/word-book/{id}}：按 id 删除（幂等，未命中也 200）</li>
 *   <li>{@code DELETE  /api/word-book/by-sw/{sw}}：按归一化键删除（客户端没有 id 时的降级）</li>
 * </ol>
 *
 * <p>响应体结构与 {@link FavoriteController} 保持同族（{@code success/words/total/imported}），
 * 客户端解析模式可以复制收藏那条链路。
 */
@RestController
@RequestMapping("/api/word-book")
public class WordBookController {

    private static final Logger log = LoggerFactory.getLogger(WordBookController.class);

    private final TtsControllerSupport support;
    private final WordBookService wordBookService;

    public WordBookController(TtsControllerSupport support, WordBookService wordBookService) {
        this.support = support;
        this.wordBookService = wordBookService;
    }

    /** GET /api/word-book — 我的收藏（全量列表） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        List<UserWordBook> list = wordBookService.list(user.getId());
        return ResponseEntity.ok(listResponse(list, null));
    }

    /** POST /api/word-book — 单条 upsert（幂等：已存在同 sw 返回既有） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody WordBookItemRequest request,
                                                    Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        UserWordBook saved = wordBookService.add(user.getId(), request);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("word", wordBookService.toItem(saved));
        response.put("message", "已加入生词本");
        return ResponseEntity.ok(response);
    }

    /** POST /api/word-book/sync — 批量合并 + 返回合并后全量 */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@Valid @RequestBody SyncWordBookRequest request,
                                                     Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        int before = wordBookService.list(user.getId()).size();
        List<UserWordBook> merged = wordBookService.sync(user.getId(), request.getWords());
        int imported = Math.max(0, merged.size() - before);
        return ResponseEntity.ok(listResponse(merged, imported));
    }

    /** DELETE /api/word-book/{id} — 按 id 删除（幂等） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id,
                                                       Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        boolean removed = wordBookService.delete(user.getId(), id);
        if (!removed) {
            log.debug("删除生词本词条未命中 userId={} id={}（可能已删除），按幂等成功返回",
                    user.getId(), id);
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "removed", removed,
                "message", "已从生词本移除"
        ));
    }

    /**
     * DELETE /api/word-book/by-sw/{sw} — 按归一化键删除（幂等）。
     * 用于客户端「本地老数据没有 server_id」场景：PC/Android 升级前收藏的词条
     * 只有 sw，走 by-sw 端点删除比"先 sync 拿 id 再删 id"更省一次网络往返。
     */
    @DeleteMapping("/by-sw/{sw}")
    public ResponseEntity<Map<String, Object>> deleteBySw(@PathVariable String sw,
                                                           Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        boolean removed = wordBookService.deleteBySw(user.getId(), sw);
        if (!removed) {
            log.debug("按 sw 删除生词本词条未命中 userId={} sw={}（可能已删除），按幂等成功返回",
                    user.getId(), sw);
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "removed", removed,
                "message", "已从生词本移除"
        ));
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户未认证"));
    }

    private Map<String, Object> listResponse(List<UserWordBook> list, Integer imported) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("words", wordBookService.toItems(list));
        response.put("total", list.size());
        if (imported != null) {
            response.put("imported", imported);
        }
        return response;
    }
}
