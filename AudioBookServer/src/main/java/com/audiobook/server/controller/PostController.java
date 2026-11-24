package com.audiobook.server.controller;

import com.audiobook.server.dto.CreateCommentRequest;
import com.audiobook.server.dto.CreatePostRequest;
import com.audiobook.server.dto.CreateReportRequest;
import com.audiobook.server.model.Report;
import com.audiobook.server.model.User;
import com.audiobook.server.service.PostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

import java.io.File;
import java.util.*;

/**
 * 发现页帖子：列表（按发帖时间倒序，id 兜底去重）、详情（含评论）、发布
 * （标题 / 图片 / 嵌入参考音频 / @提及）、评论（含嵌套回复）、收藏、举报、图片上传。
 *
 * 与 /api/tts、/api/store 一样受 JWT 保护（SecurityConfig 默认要求认证）。
 * 异常不再在这里 try-catch 吞掉——400/409/500 由 {@link GlobalExceptionHandler} 统一映射。
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private static final Logger log = LoggerFactory.getLogger(PostController.class);

    /** 单次拉取上限：发现页只展示最近若干条，避免帖子多了之后一次拉全表 */
    private static final int MAX_LIMIT = 200;

    /** 单张图片上限（与全局 multipart 50MB 相比更严，图片通常很小） */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final Set<String> ALLOWED_IMAGE_EXT =
            Set.of("jpg", "jpeg", "png", "webp", "gif");

    private final PostService postService;
    private final TtsControllerSupport support;

    public PostController(PostService postService, TtsControllerSupport support) {
        this.postService = postService;
        this.support = support;
    }

    /**
     * 帖子列表：按发帖时间倒序（最新发布的排在最前），同秒用 id 兜底保证稳定排序。
     * 可选 refAudioId 过滤（聚合「用某音色分享的帖子」）。分页用 page + limit。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listPosts(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "refAudioId", required = false) Long refAudioId,
            Authentication authentication) {

        User user = support.currentUser(authentication);
        int size = (limit == null || limit <= 0) ? MAX_LIMIT : Math.min(limit, MAX_LIMIT);
        Page<Map<String, Object>> postPage = (refAudioId != null)
                ? postService.listPostsByRefAudio(refAudioId, page, size, user)
                : postService.listPosts(page, size, user);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("posts", postPage.getContent());
        response.put("total", postPage.getTotalElements());
        response.put("currentPage", page);
        response.put("hasMore", postPage.getTotalPages() > page + 1);
        return ResponseEntity.ok(response);
    }

    /** 帖子详情：帖子本体 + 评论（含嵌套回复） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPost(
            @PathVariable Long id,
            Authentication authentication) {
        User user = support.currentUser(authentication);
        try {
            Map<String, Object> detail = postService.getPostDetail(id, user);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.putAll(detail);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 发布帖子（支持标题 / 图片 / 嵌入参考音频 / @提及） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPost(
            @Valid @RequestBody CreatePostRequest request,
            Authentication authentication) {

        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }

        try {
            Map<String, Object> post = postService.postToItem(postService.createPost(request, user), user);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("post", post);
            response.put("message", "发布成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 发表评论 / 回复 */
    @PostMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> createComment(
            @PathVariable Long id,
            @Valid @RequestBody CreateCommentRequest request,
            Authentication authentication) {

        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        try {
            Map<String, Object> comment = postService.commentToItem(postService.createComment(id, request, user));
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("comment", comment);
            response.put("message", "评论成功");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 切换收藏状态（已收藏则取消，未收藏则添加） */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<Map<String, Object>> toggleFavorite(
            @PathVariable Long id,
            Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        try {
            Map<String, Object> result = postService.toggleFavorite(id, user);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.putAll(result);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 切换点赞（已赞则取消）。响应：{ success, liked, likeCount }。
     *
     * 与收藏分开：收藏进「我的收藏」列表，点赞只表态（见 [PostLike] 的类注释）。
     */
    @PostMapping("/{id}/like")
    public ResponseEntity<Map<String, Object>> toggleLike(
            @PathVariable Long id,
            Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        try {
            Map<String, Object> result = postService.toggleLike(id, user);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.putAll(result);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /**
     * 删除自己发布的帖子（逻辑删除，前台立即不可见）。
     *
     * 只认作者本人：别人的帖子返回 403，不存在的返回 404 ——
     * 两者分开是为了让客户端能把「这不归你删」和「帖子没了」显示成不同的提示。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deletePost(
            @PathVariable Long id,
            Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        try {
            postService.deleteOwnPost(id, user);
            return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatus status = msg.startsWith("只能删除自己") ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND;
            return ResponseEntity.status(status).body(Map.of("success", false, "message", msg));
        }
    }

    /** 我的收藏列表 */
    @GetMapping("/favorites/mine")
    public ResponseEntity<Map<String, Object>> myFavorites(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        List<Map<String, Object>> items = postService.listFavorites(user);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("posts", items);
        response.put("total", items.size());
        return ResponseEntity.ok(response);
    }

    /** 举报帖子或评论 */
    @PostMapping("/reports")
    public ResponseEntity<Map<String, Object>> createReport(
            @Valid @RequestBody CreateReportRequest request,
            Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        try {
            Report report = postService.createReport(request, user);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("reportId", report.getId());
            response.put("message", "举报已提交，感谢反馈");
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    /** 上传帖子图片，返回可访问的 URL（存于上传目录，文件名带 post_ 前缀便于隔离） */
    @PostMapping(value = "/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "用户未认证"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "文件为空"));
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "图片超过 10MB 上限"));
        }
        String original = file.getOriginalFilename();
        String ext = (original == null || original.lastIndexOf('.') < 0)
                ? "" : original.substring(original.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_IMAGE_EXT.contains(ext)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "message", "仅支持 jpg/png/webp/gif 图片"));
        }
        try {
            String name = "post_" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
            File target = support.resolveUploadFile(name);
            file.transferTo(target);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", "/api/posts/image/" + name);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("上传帖子图片失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "上传失败，请稍后重试"));
        }
    }

    /** 访问帖子图片（上传目录内的文件，经路径穿越防御解析） */
    @GetMapping("/image/{filename}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename) {
        // 拒绝任何可能逃逸/穿越的文件名
        if (filename == null || filename.contains("/") || filename.contains("\\")
                || filename.contains("..") || filename.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            File file = support.resolveUploadFile(filename);
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }
            String ext = support.getFileExtension(filename);
            MediaType mime = imageMime(ext);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mime);
            return ResponseEntity.ok()
                    .headers(headers)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(24, java.util.concurrent.TimeUnit.HOURS))
                    .body(new FileSystemResource(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private static MediaType imageMime(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase()) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.valueOf("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };
    }
}
