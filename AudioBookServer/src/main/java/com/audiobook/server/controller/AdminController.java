package com.audiobook.server.controller;

import com.audiobook.server.model.ReferenceAudio;
import com.audiobook.server.model.ReferenceAudioTopic;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.model.User;
import com.audiobook.server.service.AdminService;
import com.audiobook.server.service.BookStoreService;
import com.audiobook.server.service.StoreBookTagService;
import com.audiobook.server.util.PageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理接口。
 * 所有端点需要管理员权限（由 SecurityConfig 或拦截器保证）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final AdminService adminService;
    /** 重建正文快照用（逻辑在 BookStoreService 里，避免 AdminService 与它互相依赖） */
    private final BookStoreService bookStoreService;

    public AdminController(AdminService adminService, BookStoreService bookStoreService) {
        this.adminService = adminService;
        this.bookStoreService = bookStoreService;
    }

    // ===================== 用户管理 =====================

    /** 分页查询用户列表 */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            size = Math.min(size, PageUtil.MAX_PAGE_SIZE);
            PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<User> userPage = adminService.listUsers(pageRequest);

            // 脱敏：不返回密码
            List<Map<String, Object>> content = userPage.getContent().stream().map(user -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", user.getId());
                map.put("username", user.getUsername());
                map.put("email", user.getEmail());
                map.put("isAdmin", user.getIsAdmin());
                // 积分余额：后台用户列表需要直接看到，并支持就地手动调整
                // （老数据可能为 null，统一兜底成 0，前端无需再判空）
                map.put("credits", user.getCredits() == null ? 0 : user.getCredits());
                map.put("bannedUntil", user.getBannedUntil());
                map.put("createdAt", user.getCreatedAt());
                return map;
            }).toList();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", content);
            result.put("totalPages", userPage.getTotalPages());
            result.put("totalElements", userPage.getTotalElements());
            result.put("currentPage", page);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询用户列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 删除用户 */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        try {
            adminService.deleteUser(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "用户已删除"));
        } catch (Exception e) {
            log.warn("删除用户失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 设置/取消管理员 */
    @PutMapping("/users/{id}/admin")
    public ResponseEntity<Map<String, Object>> toggleAdmin(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Object isAdminObj = body.get("isAdmin");
            if (isAdminObj == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "缺少 isAdmin 参数"));
            }
            boolean isAdmin = Boolean.parseBoolean(String.valueOf(isAdminObj));
            adminService.toggleAdmin(id, isAdmin);
            return ResponseEntity.ok(Map.of("success", true, "message", isAdmin ? "已设为管理员" : "已取消管理员"));
        } catch (Exception e) {
            log.warn("修改管理员权限失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 封禁用户 */
    @PutMapping("/users/{id}/ban")
    public ResponseEntity<Map<String, Object>> banUser(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Object durationObj = body.get("durationMinutes");
            if (durationObj == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "缺少封禁时长参数"));
            }
            int durationMinutes;
            try {
                durationMinutes = Integer.parseInt(String.valueOf(durationObj));
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "封禁时长必须为整数"));
            }
            if (durationMinutes <= 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "封禁时长必须大于 0"));
            }
            LocalDateTime bannedUntil = LocalDateTime.now().plusMinutes(durationMinutes);
            adminService.banUser(id, bannedUntil);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "用户已封禁");
            result.put("bannedUntil", bannedUntil.toString());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("封禁用户失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 解封用户 */
    @PutMapping("/users/{id}/unban")
    public ResponseEntity<Map<String, Object>> unbanUser(@PathVariable Long id) {
        try {
            adminService.unbanUser(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "用户已解封"));
        } catch (Exception e) {
            log.warn("解封用户失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 重置用户密码 */
    @PutMapping("/users/{id}/password")
    public ResponseEntity<Map<String, Object>> resetPassword(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Object passwordObj = body.get("newPassword");
            if (passwordObj == null || String.valueOf(passwordObj).trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "缺少新密码"));
            }
            String newPassword = String.valueOf(passwordObj);
            adminService.resetPassword(id, newPassword);
            return ResponseEntity.ok(Map.of("success", true, "message", "密码已重置"));
        } catch (Exception e) {
            log.warn("重置密码失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    // ===================== 书城电子书管理 =====================

    /** 上传电子书到书城 */
    @PostMapping(value = "/books", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadBook(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "originalTitle", required = false) String originalTitle,
            @RequestParam(value = "author", required = false) String author,
            @RequestParam(value = "category", required = false, defaultValue = "其他") String category,
            @RequestParam(value = "description", required = false) String description,
            // 标签：multipart 里传一个用逗号分隔的串（"历史,战争,人物传记"）。
            // 不用多值参数（tags=a&tags=b）是因为管理端表单是「多选框 + 自由输入」，
            // 拼成一个串更好拼，也更好在浏览器 Network 里看清管理员到底提交了什么。
            @RequestParam(value = "tags", required = false) String tags) {
        try {
            StoreBook book = adminService.uploadBook(file, title, originalTitle, author, category,
                    description, StoreBookTagService.parseDelimited(tags));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("book", book);
            result.put("message", "电子书上传成功");
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IOException e) {
            log.error("上传电子书失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /**
     * 服务器上已有的书城分类名（磁盘目录 ∪ 数据库已用分类）。
     * 管理端上传时用它做分类联想，并据此区分「复用已有分类」与「将在服务器新建分类文件夹」。
     */
    @GetMapping("/books/categories")
    public ResponseEntity<Map<String, Object>> listBookCategories() {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("categories", adminService.listCategoryNames());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询书城分类失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /**
     * 库里已用过的全部标签名（管理端编辑/上传时的标签联想）。
     *
     * <p>与用户端 {@code GET /api/store/tags} 的区别：那个返回「标签 + 在售书籍数」给标签栏用，
     * 且只统计在售书籍；本端点只给名字、不做在售过滤 —— 一本在售书都没有的标签
     * 仍然要让管理员能选中复用，否则每本书都会重新发明一个同义标签。
     */
    @GetMapping("/books/tags")
    public ResponseEntity<Map<String, Object>> listBookTags() {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("tags", adminService.listBookTagNames());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询书城标签失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 分页查询书城书籍 */
    @GetMapping("/books")
    public ResponseEntity<Map<String, Object>> listStoreBooks(            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        try {
            size = Math.min(size, PageUtil.MAX_PAGE_SIZE);
            if (keyword != null && !keyword.isBlank()) {
                // 搜索分支与无关键词分支同为数据库分页，排序规则保持原搜索语义（下载量降序），
                // 排序在 JPQL 内声明，故此处传无排序的 Pageable
                Page<StoreBook> bookPage = adminService.searchBooks(
                        keyword, PageRequest.of(Math.max(0, page), size));
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("content", adminService.toStoreBookViews(bookPage.getContent()));
                result.put("totalElements", bookPage.getTotalElements());
                result.put("totalPages", bookPage.getTotalPages());
                result.put("currentPage", page);
                return ResponseEntity.ok(result);
            }

            PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
            Page<StoreBook> bookPage = adminService.listStoreBooks(pageRequest);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            // 每本书附带 taskStatus / taskCompletedSegments / taskTotalSegments：
            // 管理端据此区分「未合成 / 合成中 / 已暂停 / 已完成」并给出暂停、继续、删除语音按钮。
            result.put("content", adminService.toStoreBookViews(bookPage.getContent()));
            result.put("totalPages", bookPage.getTotalPages());
            result.put("totalElements", bookPage.getTotalElements());
            result.put("currentPage", page);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询书籍列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 从书城删除书籍 */
    @DeleteMapping("/books/{id}")
    public ResponseEntity<Map<String, Object>> deleteBook(@PathVariable Long id) {
        try {
            adminService.deleteBook(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "书籍已删除"));
        } catch (Exception e) {
            log.warn("删除书籍失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /**
     * 重建这本书的正文快照（`book_content_snapshots`）。
     *
     * <p>快照本来只在「服务端合成」收尾时写入。但书城里的书也可能由**本地工具**合成音频后
     * 直接绑定 `task_id` 上架（AudioBookLocalTtsTool 的发布流程），这类书没有快照，
     * 于是每次打开阅读都要实时解析整本（实测国富论 9.2s，有快照的书 95ms）。
     *
     * <p>幂等：重复调用只是重新提取并覆盖同一行的快照。
     * 要求书已绑定 `task_id`（快照必须与音频同源），否则返回 400。
     */
    @PostMapping("/books/{id}/content-snapshot")
    public ResponseEntity<Map<String, Object>> rebuildContentSnapshot(@PathVariable Long id) {
        try {
            BookStoreService.SnapshotRebuild result = bookStoreService.rebuildContentSnapshot(id);
            if (result == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "success", false,
                        "message", "书籍不存在，或文件缺失/无法解析"));
            }
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("title", result.title());
            body.put("paragraphs", result.paragraphCount());
            body.put("rows", result.rowCount());
            body.put("fileSize", result.fileSize());
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "无法重建正文快照")));
        } catch (Exception e) {
            log.warn("重建正文快照失败: bookId={}, {}", id, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /**
     * 暂停一本书的语音合成。
     *
     * <p>立即返回、不等合成停下来：工作线程会在**下一个段落边界**检查点收手（一个段落的
     * TTS 调用通常几秒），已完成的段与 mp3 全部保留，状态落库为 paused。
     */
    @PostMapping("/books/{id}/synthesize/pause")
    public ResponseEntity<Map<String, Object>> pauseBookSynthesis(@PathVariable Long id,
                                                                    Authentication authentication) {
        try {
            String requester = requester(authentication);
            if (requester == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            adminService.pauseBookSynthesis(id, requester);
            return ResponseEntity.ok(Map.of("success", true, "message", "已暂停合成，正在合成的那一段完成后即停止"));
        } catch (Exception e) {
            log.warn("暂停书城合成失败: bookId={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "暂停失败")));
        }
    }

    /**
     * 继续合成：从已完成的段落数断点续跑（不重做已合成的段）。
     */
    @PostMapping("/books/{id}/synthesize/resume")
    public ResponseEntity<Map<String, Object>> resumeBookSynthesis(@PathVariable Long id,
                                                                    Authentication authentication) {
        try {
            String requester = requester(authentication);
            if (requester == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            adminService.resumeBookSynthesis(id, requester);
            return ResponseEntity.ok(Map.of("success", true, "message", "已继续合成"));
        } catch (Exception e) {
            log.warn("继续书城合成失败: bookId={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "继续失败")));
        }
    }

    /**
     * 继续合成（补齐缺失段）：partial / failed 的任务只重跑没成功的段。
     *
     * <p>与 {@link #resumeBookSynthesis} 的分工：那条走「已暂停 → 继续」，
     * 这条走「已经跑到终态但没跑全 → 只补缺的」。前端同一个按钮「继续合成」，
     * 按当前状态选其中之一。
     *
     * <p>立即返回、不等合成跑完：重置失败段与派发都在这里做完，实际合成在异步线程里。
     */
    @PostMapping("/books/{id}/synthesize/retry")
    public ResponseEntity<Map<String, Object>> retryBookSynthesis(@PathVariable Long id,
                                                                  Authentication authentication) {
        try {
            String requester = requester(authentication);
            if (requester == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            adminService.retryBookSynthesis(id, requester);
            return ResponseEntity.ok(Map.of("success", true, "message", "已继续合成，只补未完成的段落"));
        } catch (Exception e) {
            log.warn("补齐书城合成缺失段失败: bookId={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "继续失败")));
        }
    }

    /**
     * 删除这本书已合成的语音（音频文件 + 段落记录 + 任务行），并把书解绑回「未合成」。
     *
     * <p>与 {@link #deleteBook} 的区别：书本身（电子文件、封面、分类）留着不动。
     * 仍在合成中的书会被拒绝，需先暂停。
     */
    @DeleteMapping("/books/{id}/synthesize")
    public ResponseEntity<Map<String, Object>> deleteBookAudio(@PathVariable Long id,
                                                                Authentication authentication) {
        try {
            String requester = requester(authentication);
            if (requester == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            adminService.deleteBookAudio(id, requester);
            return ResponseEntity.ok(Map.of("success", true, "message", "语音已删除"));
        } catch (Exception e) {
            log.warn("删除书城语音失败: bookId={}, error={}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "删除失败")));
        }
    }

    /** 取当前管理员名；未登录返回 null（由各端点决定是 401 还是当作匿名） */
    private String requester(Authentication authentication) {
        String name = authentication != null ? authentication.getName() : null;
        return name == null || name.isBlank() ? null : name;
    }

    /** 批量删除书籍（请求体 {"ids":[1,2,3]}；尽力而为，返回实际删除条数） */
    @DeleteMapping("/books/batch")
    public ResponseEntity<Map<String, Object>> deleteBooks(@RequestBody Map<String, Object> body) {
        try {
            Object idsObj = body.get("ids");
            if (!(idsObj instanceof List<?> rawIds) || rawIds.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "缺少 ids 参数"));
            }
            List<Long> ids = new java.util.ArrayList<>();
            for (Object raw : rawIds) {
                try {
                    ids.add(Long.parseLong(String.valueOf(raw)));
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "message", "ids 包含非法值: " + raw));
                }
            }

            int deleted = adminService.deleteBooks(ids);
            int failed = ids.size() - deleted;
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("deleted", deleted);
            result.put("message", failed == 0
                    ? "成功删除 " + deleted + " 本书籍"
                    : "成功删除 " + deleted + " 本，" + failed + " 本删除失败");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("批量删除书籍失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "批量删除失败")));
        }
    }

    /** 编辑书城书籍信息（书名/作者/分类/简介） */
    @PutMapping("/books/{id}")
    public ResponseEntity<Map<String, Object>> updateBook(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            StoreBook book = adminService.updateBook(
                    id,
                    body.get("title") != null ? String.valueOf(body.get("title")) : null,
                    body.get("author") != null ? String.valueOf(body.get("author")) : null,
                    body.get("category") != null ? String.valueOf(body.get("category")) : null,
                    body.get("description") != null ? String.valueOf(body.get("description")) : null);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("book", book);
            result.put("message", "书籍信息已更新");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "编辑失败")));
        } catch (IOException e) {
            log.error("编辑书籍失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", msg(e, "编辑失败")));
        } catch (Exception e) {
            log.warn("编辑书籍失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "编辑失败")));
        }
    }

    /**
     * 全量设置书籍标签（请求体 {@code {"tags":["历史","战争"]}}）。
     *
     * <p><b>提交即为最终集合</b>：body 里没有的标签会被删掉，{@code {"tags":[]}} 或缺省
     * {@code tags} 字段都会清空该书标签。管理端表单是「多选框 + 自由输入」，
     * 一次提交完整集合最不容易出错。
     *
     * <p>规范化（trim / 忽略大小写去重 / 单标签最长 20 字 / 最多 10 个）在服务端做，
     * 响应里回传真正落库的 {@code book.tags}，前端据此回显而不是自己猜。
     */
    @PutMapping("/books/{id}/tags")
    public ResponseEntity<Map<String, Object>> updateBookTags(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Object raw = body.get("tags");
            if (raw != null && !(raw instanceof List<?>)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "tags 必须是字符串数组"));
            }
            List<String> tags = new java.util.ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        tags.add(String.valueOf(item));
                    }
                }
            }
            StoreBook book = adminService.setBookTags(id, tags);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("book", book);
            result.put("message", book.getTags().isEmpty() ? "已清空标签" : "标签已更新");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        } catch (Exception e) {
            log.warn("更新书籍标签失败: bookId={}, {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 切换书籍的编辑推荐标记（书城首页编辑推荐入口） */
    @PutMapping("/store/books/{id}/editor-pick")    public ResponseEntity<Map<String, Object>> setEditorPick(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            Object v = body.get("editorPick");
            if (v == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "message", "缺少 editorPick 参数"));
            }
            boolean pick = Boolean.parseBoolean(String.valueOf(v));
            StoreBook book = adminService.setEditorPick(id, pick);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("book", book);
            result.put("message", pick ? "已设为编辑推荐" : "已取消编辑推荐");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 上传书籍自定义封面 */
    @PostMapping(value = "/books/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadCover(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        try {
            StoreBook book = adminService.uploadCover(id, file);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("book", book);
            result.put("message", "封面上传成功");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        } catch (IOException e) {
            log.error("上传封面失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    // ===================== 书城合成语音 =====================

    /**
     * 单本书发起 TTS 合成（管理员在「书城管理」点击合成语音时调用）。
     * 复用 TtsService.createTtsTask 全异步链路，adminExempt 时 0 积分。
     * 入参 referenceId / voiceId 二选一：
     * - referenceId：已上传的服务器共享参考音频文件名（来自 /admin/reference-audios 列表）
     * - voiceId：预设音色 ID（来自 /api/tts/voices）
     */
    @PostMapping(value = "/books/{id}/synthesize")
    public ResponseEntity<Map<String, Object>> synthesizeBook(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            Authentication authentication) {
        try {
            String requester = authentication != null ? authentication.getName() : null;
            if (requester == null || requester.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            String referenceId = body != null && body.get("referenceId") != null
                    ? String.valueOf(body.get("referenceId")) : null;
            String voiceId = body != null && body.get("voiceId") != null
                    ? String.valueOf(body.get("voiceId")) : null;
            Long taskId = adminService.synthesizeBookForStore(id, referenceId, voiceId, requester);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("bookId", id);
            result.put("taskId", taskId);
            result.put("message", "合成任务已创建，可在 TTS 任务列表跟踪进度");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "合成失败")));
        } catch (IOException e) {
            log.error("书城合成发起失败: bookId={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "合成任务发起失败，请稍后重试"));
        } catch (Exception e) {
            log.error("书城合成失败: bookId={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "合成任务发起失败，请稍后重试"));
        }
    }

    /**
     * 批量合成。body 结构：
     * - referenceId / voiceId：与 /synthesize 二选一
     * - bookIds：可选 long 数组；不传则扫描所有 task_id IS NULL 的书
     *
     * 返回 { success, total, submitted, skipped, results }，results 为单本回执列表。
     */
    @PostMapping(value = "/books/batch-synthesize")
    public ResponseEntity<Map<String, Object>> batchSynthesize(
            @RequestBody(required = false) Map<String, Object> body,
            Authentication authentication) {
        try {
            String requester = authentication != null ? authentication.getName() : null;
            if (requester == null || requester.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            String referenceId = body != null && body.get("referenceId") != null
                    ? String.valueOf(body.get("referenceId")) : null;
            String voiceId = body != null && body.get("voiceId") != null
                    ? String.valueOf(body.get("voiceId")) : null;
            java.util.List<Long> bookIds = null;
            if (body != null && body.get("bookIds") instanceof java.util.List<?> raw) {
                bookIds = new java.util.ArrayList<>();
                for (Object o : raw) {
                    try {
                        bookIds.add(Long.parseLong(String.valueOf(o)));
                    } catch (NumberFormatException nfe) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("success", false, "message", "bookIds 包含非法值: " + o));
                    }
                }
            }
            AdminService.BatchSynthResult outcome =
                    adminService.synthesizeBooksBatch(referenceId, voiceId, requester, bookIds);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("total", outcome.submitted().size() + outcome.skipped().size());
            result.put("submitted", outcome.submitted().size());
            result.put("skipped", outcome.skipped().size());
            result.put("results", outcome);
            result.put("message",
                    "已发起 " + outcome.submitted().size() + " 个合成任务"
                            + (outcome.skipped().isEmpty()
                                    ? ""
                                    : "，" + outcome.skipped().size() + " 本未能发起（详见 results.skipped）"));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "批量合成失败")));
        } catch (IOException e) {
            log.error("书城批量合成失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "批量合成失败，请稍后重试"));
        } catch (Exception e) {
            log.error("书城批量合成失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "批量合成失败，请稍后重试"));
        }
    }

    /**
     * 批量补齐「上一轮没跑完」的书（partial / failed），只重跑没成功的段。
     *
     * <p>与 {@code /books/batch-synthesize} 是两个动作、不是一个参数：
     * 那边是「从头做一版」（可换参考音频/音色），这边是「只补缺的」（保留已有段与音色）。
     * 合并成一个按钮会让管理员分不清这次是重烧整本还是补几段。
     *
     * <p>不传 bookIds 时扫描全部 partial/failed 的书；传则只处理指定的那几本。
     */
    @PostMapping("/books/batch-gap-retry")
    public ResponseEntity<Map<String, Object>> batchGapRetry(
            @RequestBody(required = false) Map<String, Object> body,
            Authentication authentication) {
        try {
            String requester = authentication != null ? authentication.getName() : null;
            if (requester == null || requester.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "未登录"));
            }
            java.util.List<Long> bookIds = null;
            if (body != null && body.get("bookIds") instanceof java.util.List<?> raw) {
                bookIds = new java.util.ArrayList<>();
                for (Object o : raw) {
                    try {
                        bookIds.add(Long.parseLong(String.valueOf(o)));
                    } catch (NumberFormatException nfe) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("success", false, "message", "bookIds 包含非法值: " + o));
                    }
                }
            }
            AdminService.BatchSynthResult outcome = adminService.batchGapRetry(requester, bookIds);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("total", outcome.submitted().size() + outcome.skipped().size());
            result.put("submitted", outcome.submitted().size());
            result.put("skipped", outcome.skipped().size());
            result.put("results", outcome);
            result.put("message",
                    "已重新派发 " + outcome.submitted().size() + " 本书的补齐任务"
                            + (outcome.skipped().isEmpty()
                                    ? ""
                                    : "，" + outcome.skipped().size() + " 本未能补齐（详见 results.skipped）"));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "批量补齐失败")));
        } catch (Exception e) {
            log.error("书城批量补齐失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "批量补齐失败，请稍后重试"));
        }
    }

    // ===================== 参考音频管理 =====================

    /** 上传参考音频 */
    @PostMapping(value = "/reference-audios", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadReferenceAudio(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "topicId", required = false) Long topicId) {
        try {
            ReferenceAudio audio = adminService.uploadReferenceAudio(file, name, description, topicId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("audio", audio);
            result.put("message", "参考音频上传成功");
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IOException e) {
            log.error("上传参考音频失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 查询所有参考音频 */
    @GetMapping("/reference-audios")
    public ResponseEntity<Map<String, Object>> listReferenceAudios() {
        try {
            List<ReferenceAudio> audios = adminService.listReferenceAudios();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", audios);
            result.put("totalElements", audios.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询参考音频列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 删除参考音频 */
    @DeleteMapping("/reference-audios/{id}")
    public ResponseEntity<Map<String, Object>> deleteReferenceAudio(@PathVariable Long id) {
        try {
            adminService.deleteReferenceAudio(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "参考音频已删除"));
        } catch (Exception e) {
            log.warn("删除参考音频失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "操作失败")));
        }
    }

    /** 编辑参考音频信息（名称/描述/所属主题） */
    @PutMapping("/reference-audios/{id}")
    public ResponseEntity<Map<String, Object>> updateReferenceAudio(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            // topicId 必须区分三态：不传该 key = 不改主题；传 null = 显式移出主题；传数字 = 改归属。
            // 只看值是否为 null 会让「移出主题」永远做不到。
            boolean updateTopic = body.containsKey("topicId");
            Long topicId = null;
            if (updateTopic && body.get("topicId") != null) {
                topicId = parseLong(body.get("topicId"), "主题参数不合法");
            }
            ReferenceAudio audio = adminService.updateReferenceAudio(
                    id,
                    body.get("name") != null ? String.valueOf(body.get("name")) : null,
                    body.get("description") != null ? String.valueOf(body.get("description")) : null,
                    topicId,
                    updateTopic);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("audio", audio);
            result.put("message", "参考音频已更新");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("编辑参考音频失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "编辑失败")));
        }
    }

    // ===================== 参考音频主题 =====================

    /** 主题列表（附每个主题下的音频数） */
    @GetMapping("/reference-audio-topics")
    public ResponseEntity<Map<String, Object>> listReferenceAudioTopics() {
        try {
            List<Map<String, Object>> topics = adminService.listReferenceAudioTopics();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("content", topics);
            result.put("totalElements", topics.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询参考音频主题列表失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "请稍后重试"));
        }
    }

    /** 创建主题 */
    @PostMapping("/reference-audio-topics")
    public ResponseEntity<Map<String, Object>> createReferenceAudioTopic(
            @RequestBody Map<String, Object> body) {
        try {
            ReferenceAudioTopic topic = adminService.createReferenceAudioTopic(
                    body.get("name") != null ? String.valueOf(body.get("name")) : null,
                    body.get("description") != null ? String.valueOf(body.get("description")) : null,
                    parseInteger(body.get("sortOrder"), "排序值必须是数字"));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("topic", topic);
            result.put("message", "主题已创建");
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (Exception e) {
            log.warn("创建参考音频主题失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "创建失败")));
        }
    }

    /** 编辑主题（名称/描述/排序，传 null 的字段保持原值） */
    @PutMapping("/reference-audio-topics/{id}")
    public ResponseEntity<Map<String, Object>> updateReferenceAudioTopic(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            ReferenceAudioTopic topic = adminService.updateReferenceAudioTopic(
                    id,
                    body.get("name") != null ? String.valueOf(body.get("name")) : null,
                    body.get("description") != null ? String.valueOf(body.get("description")) : null,
                    parseInteger(body.get("sortOrder"), "排序值必须是数字"));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("topic", topic);
            result.put("message", "主题已更新");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("编辑参考音频主题失败: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", msg(e, "编辑失败")));
        }
    }

    /** 删除主题（主题下仍有音频时拒绝，避免误删连带丢掉素材） */
    @DeleteMapping("/reference-audio-topics/{id}")
    public ResponseEntity<Map<String, Object>> deleteReferenceAudioTopic(@PathVariable Long id) {
        try {
            adminService.deleteReferenceAudioTopic(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "主题已删除"));
        } catch (Exception e) {
            log.warn("删除参考音频主题失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg(e, "删除失败")));
        }
    }

    /** JSON 数字反序列化成 Long（null = 未提供该字段，见 parseInteger 的说明） */
    private static Long parseLong(Object value, String errorMessage) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    /**
     * JSON 数字反序列化成 Integer。
     * null 必须直接返回 null 表示「请求未带该字段，保持原值」——
     * String.valueOf(null) 得到的是字符串 "null"，Integer.parseInt("null")
     * 必然抛 NumberFormatException，会让「前端不传排序值的新建主题」100% 失败。
     */
    private static Integer parseInteger(Object value, String errorMessage) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    /** Map.of 不接受 null 值，异常消息为空时回退到默认文案 */
    private static String msg(Exception e, String fallback) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : fallback;
    }
}
