package com.audiobook.server.controller;

import com.audiobook.server.dto.FavoriteItemRequest;
import com.audiobook.server.dto.SyncFavoritesRequest;
import com.audiobook.server.model.TextFavorite;
import com.audiobook.server.model.User;
import com.audiobook.server.service.FavoriteService;
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
 * 听书收藏（正文收藏）跨端同步接口。
 *
 * 与讨论区的 /api/posts/{id}/favorite（收藏帖子）是两回事：这里同步的是
 * 「某本书第 N 段正文」，供 Web 播放器与 Android 阅读页共用同一份收藏数据。
 *
 * 设计要点：
 * - 服务端是唯一真源，两端本地只做缓存；换设备 / 清缓存不再丢收藏；
 * - 唯一身份 = (bookName, segmentNumber)（taskId 只作深链辅助，不参与去重），
 *   保证 Web（带 taskId）与 Android（无 taskId）对同一段收藏能正确合并；
 * - /sync 用于首次迁移与离线补传：客户端把本地已有收藏一次性上报，服务端合并后回全量列表。
 *
 * 与 /api/tts、/api/store、/api/posts 一样受 JWT 保护（SecurityConfig 默认要求认证）。
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private static final Logger log = LoggerFactory.getLogger(FavoriteController.class);

    private final TtsControllerSupport support;
    private final FavoriteService favoriteService;

    public FavoriteController(TtsControllerSupport support, FavoriteService favoriteService) {
        this.support = support;
        this.favoriteService = favoriteService;
    }

    /** 我的收藏列表（按收藏时间倒序） */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        List<TextFavorite> list = favoriteService.list(user.getId());
        return ResponseEntity.ok(listResponse(list, null));
    }

    /** 新增一条收藏（幂等：同书同段已存在则返回既有记录） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@Valid @RequestBody FavoriteItemRequest request,
                                                   Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        TextFavorite fav = favoriteService.add(user.getId(), request);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("favorite", favoriteService.toItem(fav));
        response.put("message", "已收藏");
        return ResponseEntity.ok(response);
    }

    /**
     * 批量合并（首次迁移 / 离线补传）：上报本地已有收藏，服务端按 (bookName, segmentNumber)
     * 与已有记录合并去重，返回合并后的全量列表 + 本次新增条数。
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@Valid @RequestBody SyncFavoritesRequest request,
                                                    Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        int before = favoriteService.list(user.getId()).size();
        List<TextFavorite> merged = favoriteService.sync(user.getId(), request.getFavorites());
        return ResponseEntity.ok(listResponse(merged, Math.max(0, merged.size() - before)));
    }

    /** 取消收藏（按 id，校验归属）。删除不存在的记录也返回成功，保证幂等。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        boolean removed = favoriteService.delete(user.getId(), id);
        if (!removed) {
            log.debug("删除收藏未命中 userId={} id={}（可能已删除），按幂等成功返回", user.getId(), id);
        }
        return ResponseEntity.ok(Map.of("success", true, "removed", removed, "message", "已取消收藏"));
    }

    private Map<String, Object> listResponse(List<TextFavorite> list, Integer imported) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("favorites", favoriteService.toItems(list));
        response.put("total", list.size());
        if (imported != null) {
            response.put("imported", imported);
        }
        return response;
    }
}
