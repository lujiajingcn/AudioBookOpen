package com.audiobook.server.controller;

import com.audiobook.server.dto.BookMetaItemRequest;
import com.audiobook.server.dto.SyncBookMetaRequest;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserBookMeta;
import com.audiobook.server.service.BookMetaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨端「每书元数据」接口（LWW，身份 = userId + bookKey）。
 *
 * <p>只同步 {@code sortOrder}（手动排序）与 {@code lastReadAt}（毫秒 epoch 最近阅读），
 * <b>刻意不含 category</b>：两端该列语义不同，同步会互相污染。
 *
 * <p>POST 一次往返完成拉 + 推：客户端上报本机全部书的现值 + 各自版本，
 * 服务端逐 key LWW 合并后返回权威全量。客户端按 bookKey 匹配本地书行后只 UPDATE 两列，
 * 不 INSERT/DELETE 书籍行。
 */
@RestController
@RequestMapping("/api/book-meta")
public class BookMetaController {

    private final TtsControllerSupport support;
    private final BookMetaService bookMetaService;

    public BookMetaController(TtsControllerSupport support, BookMetaService bookMetaService) {
        this.support = support;
        this.bookMetaService = bookMetaService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(response(bookMetaService.list(user.getId())));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@Valid @RequestBody SyncBookMetaRequest request,
                                                    Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(response(bookMetaService.sync(user.getId(), request.getItems())));
    }

    private static Map<String, Object> response(List<UserBookMeta> rows) {
        List<BookMetaItemRequest> items = new ArrayList<>();
        for (UserBookMeta row : rows) {
            items.add(new BookMetaItemRequest(row.getBookKey(), row.getSortOrder(),
                    row.getLastReadAt(), row.getUpdatedAt()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("items", items);
        body.put("total", items.size());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户未认证"));
    }
}
