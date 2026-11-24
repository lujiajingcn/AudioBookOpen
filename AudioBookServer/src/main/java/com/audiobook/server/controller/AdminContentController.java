package com.audiobook.server.controller;

import com.audiobook.server.model.User;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.service.AdminContentService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理：发现页内容审核（帖子 + 分享参考音频）。
 *
 * <p>权限由 SecurityConfig 的 {@code /api/admin/** hasRole("ADMIN")} 统一保证。
 *
 * <p><b>响应口径</b>：成功 {@code 200 + {success:true, ...}}；业务校验失败
 * {@code 400 + 具体 message}；资源不存在也用 {@code 400}（本项目后台工具的统一口径，
 * 见记忆「诊断口诀：400+具体 message = 业务校验；500+泛化文案 = 请求没进 Controller」）。
 */
@RestController
@RequestMapping("/api/admin/content")
public class AdminContentController {

    private final AdminContentService service;
    private final UserRepository userRepository;

    public AdminContentController(AdminContentService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    // ---------- 帖子 ----------

    /**
     * 帖子审核列表。
     *
     * @param status {@code all}（默认） / {@code active} / {@code removed}
     * @return { success, posts:[{id, username, title, content, commentCount,
     *         favoriteCount, status, removed, removedAt, createdAt}],
     *         total, page, size, counts:{active, removed, total} }
     */
    @GetMapping("/posts")
    public ResponseEntity<Map<String, Object>> listPosts(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size) {

        Page<Map<String, Object>> result = service.listPosts(status, page, size);
        Map<String, Object> body = ok();
        body.put("posts", result.getContent());
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("hasNext", result.hasNext());
        body.put("counts", service.postStatusCounts());
        return ResponseEntity.ok(body);
    }

    /** 逻辑下架：前台立即不可见，数据保留，可恢复 */
    @PostMapping("/posts/{id}/remove")
    public ResponseEntity<Map<String, Object>> removePost(@PathVariable Long id,
                                                          Principal principal) {
        Map<String, Object> item = service.removePost(id, operatorName(principal));
        Map<String, Object> body = ok();
        body.put("post", item);
        return ResponseEntity.ok(body);
    }

    /** 恢复上架 */
    @PostMapping("/posts/{id}/restore")
    public ResponseEntity<Map<String, Object>> restorePost(@PathVariable Long id,
                                                           Principal principal) {
        Map<String, Object> item = service.restorePost(id, operatorName(principal));
        Map<String, Object> body = ok();
        body.put("post", item);
        return ResponseEntity.ok(body);
    }

    /**
     * 彻底删除帖子（不可恢复，连带评论与收藏）。
     *
     * <p>DELETE 语义上是幂等的：id 不存在时同样返回成功，
     * 免得前端在「列表已被别人清空」时弹一个无意义的错误。
     */
    @DeleteMapping("/posts/{id}")
    public ResponseEntity<Map<String, Object>> deletePost(@PathVariable Long id,
                                                          Principal principal) {
        service.deletePostPermanently(id, operatorName(principal));
        Map<String, Object> body = ok();
        body.put("deletedId", id);
        return ResponseEntity.ok(body);
    }

    // ---------- 分享参考音频 ----------

    /**
     * 参考音频审核列表。
     *
     * @param onlyHidden true = 只看被管理员下架的
     * @return { success, references:[{id, name, description, ownerName, useCount,
     *         shared, adminHidden, sharedAt, createdAt}], total }
     */
    @GetMapping("/references")
    public ResponseEntity<Map<String, Object>> listReferences(
            @RequestParam(value = "onlyHidden", required = false, defaultValue = "false") boolean onlyHidden,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {

        List<Map<String, Object>> items = service.listReferences(onlyHidden, limit);
        Map<String, Object> body = ok();
        body.put("references", items);
        body.put("total", items.size());
        return ResponseEntity.ok(body);
    }

    /**
     * 下架 / 恢复分享参考音频（POST 幂等切换）。
     *
     * <p>只改 {@code admin_hidden}，不动用户自己的 {@code shared}——
     * 详见 {@link AdminContentService} 类注释。
     */
    @PostMapping("/references/{id}/hidden")
    public ResponseEntity<Map<String, Object>> setReferenceHidden(
            @PathVariable Long id,
            @RequestParam(value = "hidden", required = false, defaultValue = "true") boolean hidden,
            Principal principal) {

        Map<String, Object> item = service.setReferenceHidden(id, hidden, operatorName(principal));
        Map<String, Object> body = ok();
        body.put("reference", item);
        return ResponseEntity.ok(body);
    }

    // ---------- 工具 ----------

    /** 取管理员用户名写日志；取不到就退化成 "unknown"，不让日志记录本身成为失败点 */
    private String operatorName(Principal principal) {
        if (principal == null) {
            return "unknown";
        }
        try {
            User u = userRepository.findByUsername(principal.getName()).orElse(null);
            return u != null ? u.getUsername() : principal.getName();
        } catch (Exception e) {
            return principal.getName();
        }
    }

    private Map<String, Object> ok() {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        return m;
    }
}