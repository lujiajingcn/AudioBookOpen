package com.audiobook.server.service;

import com.audiobook.server.model.Post;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserReferenceAudio;
import com.audiobook.server.repository.CommentRepository;
import com.audiobook.server.repository.PostFavoriteRepository;
import com.audiobook.server.repository.PostRepository;
import com.audiobook.server.repository.UserReferenceAudioRepository;
import com.audiobook.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台「发现内容审核」：帖子下架/恢复/彻底删除 + 分享参考音频下架/恢复。
 *
 * <p>与 {@link PostService} 的分工：PostService 面向用户侧（只处理可见内容），
 * 本类面向管理员（唯一能触碰 {@code removed} / {@code admin_hidden} 的地方）。
 * 把下架逻辑塞进 PostService 会让「谁能下架」这条边界糊掉，故独立成类。
 *
 * <p><b>两种隐藏手段的分工</b>：
 * <ul>
 *   <li>帖子 → {@code status = removed}（逻辑下架）。内容审核是「先隐藏、留证据、必要时恢复」，
 *       误判能一键还原；真要清干净走 {@link #deletePostPermanently(Long)}。</li>
 *   <li>参考音频 → {@code admin_hidden = true}。<b>刻意不动 {@code shared}</b>：
 *       shared 是用户自己的开关，管理员下架若顺手把它改成 false，用户取消再分享就会让
 *       违规内容复活，管理员的处置被用户一次点击撤销。</li>
 * </ul>
 */
@Service
public class AdminContentService {

    private static final Logger log = LoggerFactory.getLogger(AdminContentService.class);

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 后台列表每页上限，防止一次拉爆 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 分享排行列表条数上限 */
    private static final int MAX_REFERENCE_LIMIT = 200;

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final UserReferenceAudioRepository userReferenceAudioRepository;
    private final UserRepository userRepository;

    public AdminContentService(PostRepository postRepository,
                               CommentRepository commentRepository,
                               PostFavoriteRepository favoriteRepository,
                               UserReferenceAudioRepository userReferenceAudioRepository,
                               UserRepository userRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.favoriteRepository = favoriteRepository;
        this.userReferenceAudioRepository = userReferenceAudioRepository;
        this.userRepository = userRepository;
    }

    // ---------- 帖子 ----------

    /**
     * 帖子审核列表。
     *
     * @param status {@code all} / {@code active} / {@code removed}，非法值按 {@code all} 处理
     */
    public Page<Map<String, Object>> listPosts(String status, int page, int size) {
        String normalized = normalizePostStatus(status);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<Post> postPage = ("all".equals(normalized))
                ? postRepository.findAllForAdmin(null, pageable)
                : postRepository.findAllForAdmin(normalized, pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Post p : postPage.getContent()) {
            items.add(postToAdminItem(p));
        }
        return new org.springframework.data.domain.PageImpl<>(
                items, postPage.getPageable(), postPage.getTotalElements());
    }

    /** 各状态的帖子计数，给前端列表页签打角标 */
    public Map<String, Object> postStatusCounts() {
        Map<String, Object> m = new HashMap<>();
        long active = postRepository.countByStatus(Post.STATUS_ACTIVE);
        long removed = postRepository.countByStatus(Post.STATUS_REMOVED);
        m.put("active", active);
        m.put("removed", removed);
        m.put("total", active + removed);
        return m;
    }

    /**
     * 逻辑下架：置 status = removed 并记下架时刻。已下架则原样返回（幂等）。
     *
     * <p><b>改为条件 UPDATE</b>（v5 / S8）：原实现是 findById + 改字段 + save，
     * 属于 read-modify-write —— 并发或双击时后写者整行覆盖先写者，可能出现
     * 「status=removed 但 removedAt 为空」这种自相矛盾的行。现在两个字段在一条
     * UPDATE 里原子改写，{@code status <> removed} 同时保证幂等。
     */
    @Transactional
    public Map<String, Object> removePost(Long postId, String operator) {
        int updated = postRepository.updateStatusIfDifferent(
                postId, Post.STATUS_REMOVED, LocalDateTime.now());
        if (updated > 0) {
            log.info("内容审核：帖子 {} 已下架，操作人={}", postId, operator);
        }
        // 重新读一次：既返回最新状态，也保留「帖子不存在 ⇒ IllegalArgumentException(400)」的既有契约
        return postToAdminItem(requirePost(postId));
    }

    /** 恢复：status 回 active，清空下架时刻。同一套条件 UPDATE（v5 / S8） */
    @Transactional
    public Map<String, Object> restorePost(Long postId, String operator) {
        int updated = postRepository.updateStatusIfDifferent(
                postId, Post.STATUS_ACTIVE, null);
        if (updated > 0) {
            log.info("内容审核：帖子 {} 已恢复，操作人={}", postId, operator);
        }
        return postToAdminItem(requirePost(postId));
    }

    /**
     * 彻底删除帖子：连带清掉评论与收藏。
     *
     * <p><b>先删子表再删主表</b>：comments / post_favorites 只是普通外键（无 DB 级
     * ON DELETE CASCADE），直接删 posts 会留下指向不存在帖子的孤儿行，
     * 而 {@code countByPostId} 这类统计并不会因为主表没了而报错，只会静默失真。
     *
     * <p>举报记录（reports）<b>刻意保留</b>：targetId 只是普通列，删除帖子不影响
     * 举报数据的可读性，而审核痕迹本身有留存价值。
     */
    @Transactional
    public void deletePostPermanently(Long postId, String operator) {
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null) {
            return;
        }
        commentRepository.deleteByPostId(postId);
        favoriteRepository.deleteByPostId(postId);
        postRepository.delete(post);
        log.warn("内容审核：帖子 {} 被彻底删除（连带评论与收藏），操作人={}", postId, operator);
    }

    // ---------- 分享参考音频 ----------

    /**
     * 参考音频审核列表。
     *
     * @param onlyHidden true = 只看被管理员下架的；false = 全部（含未下架的）
     */
    public List<Map<String, Object>> listReferences(boolean onlyHidden, int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_REFERENCE_LIMIT);
        List<UserReferenceAudio> list =
                userReferenceAudioRepository.findForAdmin(onlyHidden, PageRequest.of(0, n));

        // 批量查分享者昵称，避免 N+1
        Set<Long> ownerIds = new HashSet<>();
        for (UserReferenceAudio r : list) {
            ownerIds.add(r.getUserId());
        }
        Map<Long, String> ownerNames = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            for (User u : userRepository.findAllById(ownerIds)) {
                ownerNames.put(u.getId(), u.getUsername());
            }
        }

        List<Map<String, Object>> items = new ArrayList<>(list.size());
        for (UserReferenceAudio r : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("fileName", r.getFileName());
            m.put("fileSize", r.getFileSize());
            m.put("useCount", r.getUseCount() == null ? 0L : r.getUseCount());
            m.put("ownerName", ownerNames.getOrDefault(r.getUserId(), "未知用户"));
            m.put("shared", r.isShared());
            m.put("adminHidden", r.isAdminHidden());
            m.put("sharedAt", formatTs(r.getSharedAt()));
            m.put("createdAt", formatTs(r.getCreatedAt()));
            items.add(m);
        }
        return items;
    }

    /**
     * 下架 / 恢复分享参考音频：只翻 {@code adminHidden}，<b>绝不改 {@code shared}</b>。
     *
     * <p>这样用户端的「取消分享 / 重新分享」不会把管理员的处置撤销掉；
     * 恢复也必须由管理员显式执行。详见 {@link UserReferenceAudio#getAdminHidden()}。
     */
    @Transactional
    public Map<String, Object> setReferenceHidden(Long id, boolean hidden, String operator) {
        UserReferenceAudio entity = userReferenceAudioRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("参考音频不存在"));
        if (entity.isAdminHidden() != hidden) {
            entity.setAdminHidden(hidden);
            userReferenceAudioRepository.save(entity);
            log.info("内容审核：参考音频 {} adminHidden={}，操作人={}", id, hidden, operator);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("id", entity.getId());
        m.put("shared", entity.isShared());
        m.put("adminHidden", entity.isAdminHidden());
        m.put("visibleInShareList", entity.isVisibleInShareList());
        return m;
    }

    // ---------- 工具 ----------

    private Post requirePost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("帖子不存在"));
    }

    /**
     * all / active / removed；<b>null 与非法值一律当 all</b>。
     *
     * <p>后台是运维工具，参数写错时应当「多给一点」而不是「什么都不给」：
     * 落到 active 会让管理员以为全站只有这些帖子，从而漏审已下架的内容。
     */
    private String normalizePostStatus(String status) {
        if (status == null) {
            return "all";
        }
        String s = status.trim().toLowerCase();
        if ("all".equals(s)) return "all";
        if (Post.STATUS_ACTIVE.equals(s)) return Post.STATUS_ACTIVE;
        if (Post.STATUS_REMOVED.equals(s)) return Post.STATUS_REMOVED;
        return "all";
    }

    private Map<String, Object> postToAdminItem(Post p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("username", p.getUsername());
        m.put("userId", p.getUserId());
        m.put("title", p.getTitle());
        m.put("content", p.getContent());
        m.put("commentCount", p.getCommentCount() == null ? 0 : p.getCommentCount());
        m.put("favoriteCount", p.getFavoriteCount() == null ? 0 : p.getFavoriteCount());
        m.put("status", p.getStatus());
        m.put("removed", p.isRemoved());
        m.put("removedAt", formatTs(p.getRemovedAt()));
        m.put("createdAt", formatTs(p.getCreatedAt()));
        return m;
    }

    private String formatTs(LocalDateTime t) {
        return t == null ? null : t.format(TS_FORMATTER);
    }
}