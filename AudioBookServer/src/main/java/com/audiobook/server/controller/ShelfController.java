package com.audiobook.server.controller;

import com.audiobook.server.model.User;
import com.audiobook.server.service.BookStoreService;
import com.audiobook.server.service.ShelfService;
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
 * 用户书架接口（跨端同步）。
 *
 * <p>与 {@code /api/favorites}（收藏「某本书第 N 段正文」）不同：书架的粒度是**整本书**，
 * 表达「我想读 / 在读这本书」，与具体段落无关。两者互不替代，可同时存在。
 *
 * <p>与 /api/store、/api/favorites、/api/tts 一样受 JWT 保护
 * （SecurityConfig 的 {@code anyRequest().authenticated()} 已覆盖 /api/shelf/**，
 * 无需在 SecurityConfig 里额外加规则）。
 *
 * <p>身份口径：书架项以 {@code storeBookId}（书城主键）标识，不用书名 ——
 * 书名可重复、可被管理员修改，用 id 才能保证「同一本书」判定稳定。
 */
@RestController
@RequestMapping("/api/shelf")
public class ShelfController {

    private static final Logger log = LoggerFactory.getLogger(ShelfController.class);

    private final TtsControllerSupport support;
    private final ShelfService shelfService;
    private final BookStoreService bookStoreService;

    public ShelfController(TtsControllerSupport support,
                           ShelfService shelfService,
                           BookStoreService bookStoreService) {
        this.support = support;
        this.shelfService = shelfService;
        this.bookStoreService = bookStoreService;
    }

    /** 我的书架列表（按加入时间倒序，已带书名/作者/封面等元信息） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        List<Map<String, Object>> items = shelfService.list(user.getId());
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("items", items);
        response.put("total", items.size());
        return ResponseEntity.ok(response);
    }

    /**
     * 书架中所有书籍 id。
     * 供书城列表一次性拿到「哪些书已在书架」，避免前端逐本调 contains 造成 N 次请求。
     */
    @GetMapping("/ids")
    public ResponseEntity<Map<String, Object>> ids(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("bookIds", shelfService.bookIds(user.getId()));
        return ResponseEntity.ok(response);
    }

    /** 某本书是否已在书架 */
    @GetMapping("/contains/{storeBookId}")
    public ResponseEntity<Map<String, Object>> contains(@PathVariable Long storeBookId,
                                                        Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("contains", shelfService.contains(user.getId(), storeBookId));
        return ResponseEntity.ok(response);
    }

    /**
     * 加入书架（幂等）。
     *
     * <p>会校验书是否存在且在售 —— 否则可以往书架里塞任意不存在的 id，
     * 书架列表就会堆满 {@code missing} 空项，用户既看不懂也清不掉。
     * 已在书架中时返回 {@code added=false}（而非报错），使重复点击/多端并发都安全。
     */
    @PostMapping("/{storeBookId}")
    public ResponseEntity<Map<String, Object>> add(@PathVariable Long storeBookId,
                                                   Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }

        var bookOpt = bookStoreService.getBook(storeBookId);
        if (bookOpt.isEmpty() || "offshelf".equals(bookOpt.get().getStatus())) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "书籍不存在或已下架");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }

        Map<String, Object> result = shelfService.add(user.getId(), storeBookId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("shelfItem", result.get("shelfItem"));
        response.put("added", result.get("added"));
        response.put("message", Boolean.TRUE.equals(result.get("added")) ? "已加入书架" : "已在书架中");
        return ResponseEntity.ok(response);
    }

    /**
     * 移出书架（幂等）。
     * 用 storeBookId 而非 shelfBookId 作为路径参数：客户端（含书城页）手上只有书籍 id，
     * 若要求先查 shelfBookId 才能移除，书城页就得先发一次列表请求。
     */
    @DeleteMapping("/{storeBookId}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable Long storeBookId,
                                                      Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        boolean removed = shelfService.remove(user.getId(), storeBookId);
        if (!removed) {
            log.debug("移出书架未命中 userId={} bookId={}（可能已移除），按幂等成功返回",
                    user.getId(), storeBookId);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("removed", removed);
        response.put("message", "已移出书架");
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户未认证"));
    }
}
