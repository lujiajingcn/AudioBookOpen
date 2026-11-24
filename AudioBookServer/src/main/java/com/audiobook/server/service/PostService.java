package com.audiobook.server.service;

import com.audiobook.server.dto.CreateCommentRequest;
import com.audiobook.server.dto.CreatePostRequest;
import com.audiobook.server.dto.CreateReportRequest;
import com.audiobook.server.model.Comment;
import com.audiobook.server.model.Post;
import com.audiobook.server.model.PostFavorite;
import com.audiobook.server.model.PostLike;
import com.audiobook.server.model.Report;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.CommentRepository;
import com.audiobook.server.repository.PostFavoriteRepository;
import com.audiobook.server.repository.PostLikeRepository;
import com.audiobook.server.repository.PostRepository;
import com.audiobook.server.repository.ReportRepository;
import com.audiobook.server.repository.UserReferenceAudioRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 发现页帖子业务逻辑。
 *
 * 列表分页、发布（正文长度 + @提及解析 + 图片/参考音频嵌入）、评论（含嵌套回复）、
 * 收藏、举报都收敛在这里；Controller 只负责鉴权与响应装配。
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** 正文最大长度（按 Unicode 码点计，emoji/生僻字各算 1），与 CreatePostRequest 的 @Size 口径一致 */
    private static final int MAX_CONTENT_CODE_POINTS = 500;

    /** 单条评论最大长度 */
    private static final int MAX_COMMENT_CODE_POINTS = 500;

    /** 解析正文中的 @用户名（中文 / 英文 / 数字 / 下划线，1~20 字） */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([A-Za-z0-9_\\u4e00-\\u9fa5]{1,20})");

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final PostLikeRepository likeRepository;
    private final ReportRepository reportRepository;
    /** 发帖嵌入参考音频时的归属校验（v5 / S7） */
    private final UserReferenceAudioRepository userReferenceAudioRepository;
    private final ObjectMapper objectMapper;

    public PostService(PostRepository postRepository,
                       CommentRepository commentRepository,
                       PostFavoriteRepository favoriteRepository,
                       PostLikeRepository likeRepository,
                       ReportRepository reportRepository,
                       UserReferenceAudioRepository userReferenceAudioRepository,
                       ObjectMapper objectMapper) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.favoriteRepository = favoriteRepository;
        this.likeRepository = likeRepository;
        this.reportRepository = reportRepository;
        this.userReferenceAudioRepository = userReferenceAudioRepository;
        this.objectMapper = objectMapper;
    }

    // ---------- 列表 ----------

    /** 仅供 Controller 装配响应：把 Post 渲染为对外 Map（含当前用户收藏态） */
    public Map<String, Object> postToItem(Post p, User currentUser) {
        return toItem(p, currentUser);
    }

    /** 仅供 Controller 装配响应：把 Comment 渲染为对外 Map */
    public Map<String, Object> commentToItem(Comment c) {
        return commentToMap(c);
    }

    /**
     * 帖子列表：按发帖时间倒序（最新在前），同秒用 id 兜底。
     * 每条附带当前用户的收藏/点赞状态与冗余计数。
     */
    public Page<Map<String, Object>> listPosts(int page, int size, User currentUser) {
        Page<Post> postPage = postRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size));
        LikeStats likes = loadLikeStats(postPage.getContent(), currentUser);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Post p : postPage.getContent()) {
            items.add(toItem(p, currentUser, likes));
        }
        return new PageImpl<>(items, postPage.getPageable(), postPage.getTotalElements());
    }

    /** 按嵌入参考音频过滤的帖子列表（用于「用某音色分享的帖子」聚合） */
    public Page<Map<String, Object>> listPostsByRefAudio(Long refAudioId, int page, int size, User currentUser) {
        Page<Post> postPage = postRepository.findByRefAudioIdOrderByCreatedAtDescIdDesc(
                refAudioId, PageRequest.of(page, size));
        List<Post> visible = new ArrayList<>();
        for (Post p : postPage.getContent()) {
            // 同样过滤已下架：这是前台聚合入口（「用某音色分享的帖子」），不能让违规帖露出来
            if (p.isRemoved()) {
                continue;
            }
            visible.add(p);
        }
        LikeStats likes = loadLikeStats(visible, currentUser);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Post p : visible) {
            items.add(toItem(p, currentUser, likes));
        }
        return new PageImpl<>(items, postPage.getPageable(), postPage.getTotalElements());
    }

    /**
     * 一页帖子的点赞统计：一次查询同时算出「每条几个赞」与「我赞过哪些」。
     *
     * <p>不让 {@link #toItem} 逐条去查的理由见 [PostLike] 的类注释（N+1）。
     * 帖子数为 0 时**不发查询**：空列表 IN () 在部分数据库上直接报语法错。
     */
    private LikeStats loadLikeStats(List<Post> posts, User currentUser) {
        Map<Long, Integer> counts = new HashMap<>();
        Set<Long> likedByMe = new HashSet<>();
        if (posts.isEmpty()) {
            return new LikeStats(counts, likedByMe);
        }
        List<Long> ids = posts.stream().map(Post::getId).toList();
        for (PostLike like : likeRepository.findByPostIdIn(ids)) {
            counts.merge(like.getPostId(), 1, Integer::sum);
            if (currentUser != null && currentUser.getId().equals(like.getUserId())) {
                likedByMe.add(like.getPostId());
            }
        }
        return new LikeStats(counts, likedByMe);
    }

    /** 一页帖子的点赞视图（见 [loadLikeStats]） */
    private record LikeStats(Map<Long, Integer> counts, Set<Long> likedByMe) {
    }

    private Map<String, Object> toItem(Post p, User currentUser) {
        return toItem(p, currentUser, loadLikeStats(List.of(p), currentUser));
    }

    private Map<String, Object> toItem(Post p, User currentUser, LikeStats likes) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.getId());
        m.put("username", p.getUsername());
        m.put("title", p.getTitle());
        m.put("content", p.getContent());
        m.put("imageUrls", parseJsonList(p.getImageUrls()));
        m.put("refAudioId", p.getRefAudioId());
        m.put("mentions", parseJsonList(p.getMentions()));
        m.put("commentCount", p.getCommentCount() == null ? 0 : p.getCommentCount());
        m.put("favoriteCount", p.getFavoriteCount() == null ? 0 : p.getFavoriteCount());
        m.put("likeCount", likes.counts().getOrDefault(p.getId(), 0));
        m.put("liked", likes.likedByMe().contains(p.getId()));
        m.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().format(TS_FORMATTER));
        m.put("isFavorited", currentUser != null
                && favoriteRepository.existsByUserIdAndPostId(currentUser.getId(), p.getId()));
        return m;
    }

    // ---------- 发布 ----------

    /**
     * 发布帖子（支持标题 / 图片 / 嵌入参考音频 / @提及）。
     *
     * @throws IllegalArgumentException 正文为空（@Valid 已拦）或超过 {@link #MAX_CONTENT_CODE_POINTS} 码点
     */
    @Transactional
    public Post createPost(CreatePostRequest req, User user) {
        String content = req.getContent().trim();
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_CODE_POINTS) {
            throw new IllegalArgumentException("帖子内容不能超过 " + MAX_CONTENT_CODE_POINTS + " 字");
        }

        Post post = new Post();
        post.setUserId(user.getId());
        post.setUsername(user.getUsername());
        post.setTitle(req.getTitle() == null ? null : req.getTitle().trim());
        post.setContent(content);

        // 图片 URL 列表：校验非空且为字符串列表，序列化为 JSON 存储
        List<String> imageUrls = req.getImageUrls() == null ? null : req.getImageUrls().stream()
                .filter(u -> u != null && !u.isBlank())
                .toList();
        post.setImageUrls(imageUrls == null || imageUrls.isEmpty() ? null : toJson(imageUrls));

        // 嵌入参考音频（v5 / S7）：**必须校验归属**。
        // 原实现直接写入客户端给的 id，等于把「引用了谁的音频」交给客户端决定 ——
        // 用户 A 可以嵌用户 B 的音频 id（IDOR）；id 不存在时帖子还会留一个坏引用。
        // 策略：非法（<=0 / 不存在 / 不属于当前用户）一律**丢弃嵌入**而不是让发帖失败 ——
        // 帖子正文才是用户的心血，因为一个失效的音频 id 让整条帖子发不出去是更差的结果。
        Long requestedRefAudioId = req.getRefAudioId();
        if (requestedRefAudioId != null && requestedRefAudioId > 0
                && userReferenceAudioRepository.findByIdAndUserId(requestedRefAudioId, user.getId()).isPresent()) {
            post.setRefAudioId(requestedRefAudioId);
        } else {
            if (requestedRefAudioId != null) {
                log.warn("发帖引用的参考音频无效或不属于当前用户，已丢弃嵌入: refAudioId={}, userId={}",
                        requestedRefAudioId, user.getId());
            }
            post.setRefAudioId(null);
        }

        // @提及：请求显式给就用显式给的，否则从正文里解析 @用户名
        List<String> mentions = req.getMentions();
        if (mentions == null || mentions.isEmpty()) {
            mentions = parseMentions(content);
        }
        post.setMentions(mentions.isEmpty() ? null : toJson(mentions));

        post.setCommentCount(0);
        post.setFavoriteCount(0);
        return postRepository.save(post);
    }

    // ---------- 详情 + 评论 ----------

    /** 帖子详情：帖子本体 + 评论（含嵌套回复），按时间正序 */
    public Map<String, Object> getPostDetail(Long postId, User currentUser) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("帖子不存在"));
        // 已下架的帖子对普通用户等同「不存在」：详情/评论/收藏/举报四条入口都要拦住，
        // 否则用户从历史收藏或直链进来还能看到违规内容（下架形同虚设）。
        // 后台审核列表走 AdminContentService，不受这里影响。
        if (post.isRemoved()) {
            throw new IllegalArgumentException("帖子不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("post", toItem(post, currentUser));

        List<Comment> comments = commentRepository.findByPostIdOrderByCreatedAtAsc(postId);
        List<Map<String, Object>> commentItems = new ArrayList<>();
        for (Comment c : comments) {
            commentItems.add(commentToMap(c));
        }
        result.put("comments", commentItems);
        result.put("commentCount", comments.size());
        return result;
    }

    /**
     * 发表评论 / 回复。
     *
     * @throws IllegalArgumentException 帖子不存在、正文为空/超长
     */
    @Transactional
    public Comment createComment(Long postId, CreateCommentRequest req, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("帖子不存在"));
        if (post.isRemoved()) {
            throw new IllegalArgumentException("帖子不存在");
        }
        String content = req.getContent().trim();
        if (content.codePointCount(0, content.length()) > MAX_COMMENT_CODE_POINTS) {
            throw new IllegalArgumentException("评论内容不能超过 " + MAX_COMMENT_CODE_POINTS + " 字");
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(user.getId());
        comment.setUsername(user.getUsername());
        comment.setContent(content);

        Long parentId = req.getParentId();
        if (parentId != null) {
            Comment parent = commentRepository.findById(parentId).orElse(null);
            if (parent != null && postId.equals(parent.getPostId())) {
                comment.setParentId(parentId);
                comment.setParentUsername(parent.getUsername());
            }
        }

        Comment saved = commentRepository.save(comment);
        // 冗余计数 +1：改走原子 SQL UPDATE（PostRepository#incrementCommentCount），
        // 解决原 findById → setCommentCount(+1) → save 在并发评论时两事务都读到旧值、各写回 +1 的 lost update。
        // @Transactional + @Modifying 让 SQL UPDATE 在同一事务内提交，DB 行锁保证原子性。
        postRepository.incrementCommentCount(postId);
        return saved;
    }

    // ---------- 收藏 ----------

    /**
     * 切换收藏状态：已收藏则取消，未收藏则添加。返回最新状态与收藏数。
     */
    @Transactional
    public Map<String, Object> toggleFavorite(Long postId, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("帖子不存在"));
        if (post.isRemoved()) {
            throw new IllegalArgumentException("帖子不存在");
        }
        boolean nowFav;
        if (favoriteRepository.existsByUserIdAndPostId(user.getId(), postId)) {
            favoriteRepository.deleteByUserIdAndPostId(user.getId(), postId);
            nowFav = false;
        } else {
            favoriteRepository.save(new PostFavorite(user.getId(), postId));
            nowFav = true;
        }
        int count = (int) favoriteRepository.countByPostId(postId);
        // 改走原子 SQL UPDATE（PostRepository#setFavoriteCount），用真实行数重置。
        // 之前 setFavoriteCount(count) + save(post) 同样存在 lost update：两个并发 toggleFavorite
        // 都在内存中 +1/-1，最后 save 时后写覆盖先写，丢失一次变更。
        // count 来自 countByPostId()（直接 SELECT COUNT(*)）已是当前真实值，原子 UPDATE 写回即可。
        postRepository.setFavoriteCount(postId, count);

        Map<String, Object> m = new HashMap<>();
        m.put("favorited", nowFav);
        m.put("favoriteCount", count);
        return m;
    }

    // ---------- 点赞 ----------

    /**
     * 切换点赞：已赞则取消，未赞则点赞。返回最新状态与点赞数。
     *
     * <p>计数不落库（不做 {@code posts.like_count}），每次从 {@code post_likes} 现算 ——
     * 理由见 [PostLike] 类注释。
     *
     * @throws IllegalArgumentException 帖子不存在或已下架
     */
    @Transactional
    public Map<String, Object> toggleLike(Long postId, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("帖子不存在"));
        if (post.isRemoved()) {
            throw new IllegalArgumentException("帖子不存在");
        }
        boolean nowLiked;
        if (likeRepository.existsByUserIdAndPostId(user.getId(), postId)) {
            likeRepository.deleteByUserIdAndPostId(user.getId(), postId);
            nowLiked = false;
        } else {
            likeRepository.save(new PostLike(user.getId(), postId));
            nowLiked = true;
        }
        Map<String, Object> m = new HashMap<>();
        m.put("liked", nowLiked);
        m.put("likeCount", (int) likeRepository.countByPostId(postId));
        return m;
    }

    // ---------- 删除（仅作者） ----------

    /**
     * 删除自己的帖子（逻辑删除，与后台下架共用 {@code status = removed}）。
     *
     * <p><b>为什么复用 removed 而不新增 deleted 状态</b>：前台判断「能不能看见」
     * 只该有一个判据（[Post#isRemoved]）。加一个新状态就得同步改详情、评论、
     * 收藏、按参考音频列、后台统计这五处过滤，任何一处漏掉都会让已删的帖子
     * 从某个入口漏出来。代价是后台会把它显示成「已下架」—— 对审核而言
     * 「这条已经不在前台」是同一个事实。
     *
     * @return 被删帖子的 id
     * @throws IllegalArgumentException 帖子不存在 / 已下架 / 不是本人发的
     */
    @Transactional
    public Long deleteOwnPost(Long postId, User user) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("帖子不存在"));
        if (post.isRemoved()) {
            throw new IllegalArgumentException("帖子不存在");
        }
        // 归属校验按**用户名**比对：posts 表存的是发帖时的用户名快照（没有 user_id 列），
        // 这也是全工程判断「是不是我的帖子」的唯一依据（客户端同口径）。
        if (!user.getUsername().equals(post.getUsername())) {
            throw new IllegalArgumentException("只能删除自己发布的帖子");
        }
        // 原子条件更新：与后台下架走同一条语句，避免 read-modify-write
        postRepository.updateStatusIfDifferent(postId, Post.STATUS_REMOVED, java.time.LocalDateTime.now());
        log.info("用户删除自己的帖子: postId={}, username={}", postId, user.getUsername());
        return postId;
    }

    /** 我的收藏列表（按收藏时间倒序） */
    public List<Map<String, Object>> listFavorites(User user) {
        List<PostFavorite> favs = favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        if (favs.isEmpty()) {
            return new ArrayList<>();
        }

        // 一次性批量取帖，避免 N+1：原先在循环里逐条 findById，收藏 N 条就打 N 次库
        // （与 ShelfService.list 的批量口径一致）。
        Map<Long, Post> byId = new HashMap<>();
        for (Post p : postRepository.findAllById(favs.stream().map(PostFavorite::getPostId).toList())) {
            byId.put(p.getId(), p);
        }

        // 被管理员下架的帖子不出现在「我的收藏」里：否则用户点进去会看到 404。
        // 收藏行本身保留，管理员恢复后用户的收藏自动回来。
        List<Post> visible = new ArrayList<>(favs.size());
        for (PostFavorite fav : favs) {
            Post post = byId.get(fav.getPostId());
            if (post != null && !post.isRemoved()) {
                visible.add(post);
            }
        }
        // 点赞数同样批量取（见 loadLikeStats）：逐条 toItem(p, user) 会为每条各打一次库
        LikeStats likes = loadLikeStats(visible, user);
        List<Map<String, Object>> items = new ArrayList<>(visible.size());
        for (Post post : visible) {
            items.add(toItem(post, user, likes));
        }
        return items;
    }

    // ---------- 举报 ----------

    @Transactional
    public Report createReport(CreateReportRequest req, User user) {
        String type = req.getTargetType().trim().toUpperCase();
        if (!Report.TYPE_POST.equals(type) && !Report.TYPE_COMMENT.equals(type)) {
            throw new IllegalArgumentException("举报类型无效");
        }
        Report report = new Report();
        report.setReporterId(user.getId());
        report.setReporterName(user.getUsername());
        Long targetId = req.getTargetId();
        // 只挡「明显非法」（空/负数）：reports.targetId 是按设计保留的宽松列 ——
        // 帖子被永久删除后举报记录仍在（AdminContentService 的注释写明），
        // 所以这里**不能**做存在性拒绝，否则会与该设计冲突。
        if (targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("举报目标无效");
        }
        report.setTargetType(type);
        report.setTargetId(targetId);
        report.setReason(req.getReason().trim());
        report.setStatus(Report.STATUS_PENDING);
        return reportRepository.save(report);
    }

    // ---------- 工具 ----------

    private Map<String, Object> commentToMap(Comment c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("postId", c.getPostId());
        m.put("username", c.getUsername());
        m.put("content", c.getContent());
        m.put("parentId", c.getParentId());
        m.put("parentUsername", c.getParentUsername());
        m.put("createdAt", c.getCreatedAt() == null ? null : c.getCreatedAt().format(TS_FORMATTER));
        return m;
    }

    /** 解析正文里的 @用户名，去重保序 */
    private List<String> parseMentions(String content) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = MENTION_PATTERN.matcher(content);
        while (matcher.find()) {
            seen.add(matcher.group(1));
        }
        return new ArrayList<>(seen);
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            log.warn("解析 JSON 列表失败: {}", json, e);
            return Collections.emptyList();
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("序列化列表失败: {}", list, e);
            return null;
        }
    }
}
