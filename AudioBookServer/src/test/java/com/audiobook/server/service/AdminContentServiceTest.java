package com.audiobook.server.service;

import com.audiobook.server.model.Post;
import com.audiobook.server.model.UserReferenceAudio;
import com.audiobook.server.repository.CommentRepository;
import com.audiobook.server.repository.PostFavoriteRepository;
import com.audiobook.server.repository.PostRepository;
import com.audiobook.server.repository.UserReferenceAudioRepository;
import com.audiobook.server.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容审核（帖子下架/恢复/删除 + 参考音频管理员下架）的语义回归测试。
 *
 * <p>测的是三条<b>容易写错、错了就出事</b>的不变量：
 * <ol>
 *   <li><b>下架不动数据</b>：remove只翻 status + 记 removedAt，绝不删帖（可恢复是设计目标）。</li>
 *   <li><b>彻底删除必须先清子表</b>：comments / post_favorites 无DB 级联，
 *       顺序反了会留下指向不存在帖子的孤儿行，且 countByPostId 静默失真。</li>
 *   <li><b>管理员下架 ≠ 用户取消分享</b>：setReferenceHidden 只翻 admin_hidden，
 *       shared 必须原样保留 —— 否则用户「取消分享再分享」就能把违规内容复活，
 *       管理员的处置被用户一次点击撤销。</li>
 * </ol>
 */
class AdminContentServiceTest {

    private final PostRepository postRepo = mock(PostRepository.class);
    private final CommentRepository commentRepo = mock(CommentRepository.class);
    private final PostFavoriteRepository favoriteRepo = mock(PostFavoriteRepository.class);
    private final UserReferenceAudioRepository refRepo = mock(UserReferenceAudioRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);

    private final AdminContentService service =
            new AdminContentService(postRepo, commentRepo, favoriteRepo, refRepo, userRepo);

    private static Post post(long id) {
        Post p = new Post();
        p.setId(id);
        p.setUserId(7L);
        p.setUsername("someone");
        p.setContent("违规内容");
        p.setCommentCount(2);
        p.setFavoriteCount(3);
        return p;
    }

    /**
     * 让 mock 像数据库那样执行条件 UPDATE（v5 / S8）。
     *
     * <p>真实 SQL 是 {@code UPDATE posts SET status=?, removed_at=? WHERE id=? AND status<>?}：
     * 命中一行返回 1，状态已相同返回 0。测试里没有数据库，就把这份语义照搬过来，
     * 否则「幂等」「恢复清空 removedAt」这两条根本测不到。
     */
    private void stubStatusUpdate(Post p) {
        when(postRepo.updateStatusIfDifferent(any(), anyString(), any())).thenAnswer(inv -> {
            String status = inv.getArgument(1);
            java.time.LocalDateTime removedAt = inv.getArgument(2);
            if (status.equals(p.getStatus())) {
                return 0;                     // 已是目标状态 ⇒ 幂等，不写
            }
            p.setStatus(status);
            p.setRemovedAt(removedAt);
            return 1;
        });
    }

    private static UserReferenceAudio ref(long id, boolean shared, boolean adminHidden) {
        UserReferenceAudio r = new UserReferenceAudio();
        r.setId(id);
        r.setUserId(7L);
        r.setName("音色-" + id);
        r.setFileName("ref-" + id + ".wav");
        r.setShared(shared);
        r.setAdminHidden(adminHidden);
        return r;
    }

    // ---------- 帖子 ----------

    @Test
    @DisplayName("下架：只翻 status 并记 removedAt，不删帖（数据保留可恢复）")
    void removePostKeepsRow() {
        Post p = post(11L);
        when(postRepo.findById(11L)).thenReturn(Optional.of(p));

        stubStatusUpdate(p);

        var item = service.removePost(11L, "admin");

        assertThat(item.get("status")).isEqualTo(Post.STATUS_REMOVED);
        assertThat(item.get("removed")).isEqualTo(Boolean.TRUE);
        assertThat(p.getRemovedAt()).isNotNull();
        // 关键：只做条件 UPDATE，不 delete（数据保留可恢复）
        verify(postRepo).updateStatusIfDifferent(eq(11L), eq(Post.STATUS_REMOVED), any());
        verify(postRepo, never()).delete(any());
    }

    @Test
    @DisplayName("重复下架幂等：不覆盖首次的 removedAt")
    void removePostIsIdempotent() {
        Post p = post(12L);
        p.setStatus(Post.STATUS_REMOVED);
        java.time.LocalDateTime first = java.time.LocalDateTime.of(2026, 1, 1, 10, 0);
        p.setRemovedAt(first);
        when(postRepo.findById(12L)).thenReturn(Optional.of(p));
        stubStatusUpdate(p);   // 状态已相同 ⇒ 桩返回 0，等价于 SQL 的 status<>? 不命中

        service.removePost(12L, "admin");

        assertThat(p.getRemovedAt()).isEqualTo(first);
        verify(postRepo, never()).save(any());   // 条件 UPDATE 不命中时不得有 save
    }

    @Test
    @DisplayName("恢复：status 回 active 且清空 removedAt")
    void restorePostClearsRemovedAt() {
        Post p = post(13L);
        p.setStatus(Post.STATUS_REMOVED);
        p.setRemovedAt(java.time.LocalDateTime.of(2026, 1, 1, 10, 0));
        when(postRepo.findById(13L)).thenReturn(Optional.of(p));
        stubStatusUpdate(p);

        var item = service.restorePost(13L, "admin");

        assertThat(item.get("status")).isEqualTo(Post.STATUS_ACTIVE);
        assertThat(item.get("removed")).isEqualTo(Boolean.FALSE);
        assertThat(p.getRemovedAt()).isNull();
        verify(postRepo).updateStatusIfDifferent(eq(13L), eq(Post.STATUS_ACTIVE), any());
    }

    @Test
    @DisplayName("彻底删除：先清评论与收藏再删主表，且顺序不能反")
    void deletePostCascadesChildrenFirst() {
        Post p = post(14L);
        when(postRepo.findById(14L)).thenReturn(Optional.of(p));

        service.deletePostPermanently(14L, "admin");

        // InOrder 断言顺序：孤儿行的根因就是顺序反了
        org.mockito.InOrder inOrder =
                org.mockito.Mockito.inOrder(commentRepo, favoriteRepo, postRepo);
        inOrder.verify(commentRepo).deleteByPostId(14L);
        inOrder.verify(favoriteRepo).deleteByPostId(14L);
        inOrder.verify(postRepo).delete(p);
    }

    @Test
    @DisplayName("彻底删除：id 不存在时静默成功，不抛异常（DELETE 幂等）")
    void deleteMissingPostIsNoop() {
        when(postRepo.findById(99L)).thenReturn(Optional.empty());

        service.deletePostPermanently(99L, "admin");

        verify(postRepo, never()).delete(any());
    }

    @Test
    @DisplayName("下架 / 恢复不存在的帖子 → IllegalArgumentException（→ 400 带具体 message）")
    void operateMissingPostThrows() {
        when(postRepo.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removePost(404L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("帖子不存在");
        assertThatThrownBy(() -> service.restorePost(404L, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 参考音频 ----------

    @Test
    @DisplayName("管理员下架只翻 admin_hidden，绝不碰用户的 shared")
    void hideReferenceKeepsSharedFlag() {
        UserReferenceAudio r = ref(21L, true, false);
        when(refRepo.findById(21L)).thenReturn(Optional.of(r));

        var m = service.setReferenceHidden(21L, true, "admin");

        assertThat(r.isAdminHidden()).isTrue();
        // shared 仍是true：用户仍处于「分享中」，只是被管理员藏起来
        assertThat(r.isShared()).isTrue();
        assertThat(m.get("visibleInShareList")).isEqualTo(Boolean.FALSE);
        verify(refRepo).save(r);
    }

    @Test
    @DisplayName("恢复参考音频：admin_hidden 回落，前台重新可见")
    void restoreReference() {
        UserReferenceAudio r = ref(22L, true, true);
        when(refRepo.findById(22L)).thenReturn(Optional.of(r));

        var m = service.setReferenceHidden(22L, false, "admin");

        assertThat(r.isAdminHidden()).isFalse();
        assertThat(r.isVisibleInShareList()).isTrue();
        assertThat(m.get("visibleInShareList")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("未分享的音频被下架：仍保持 shared=false，恢复后也不进前台")
    void hiddenButNotSharedStaysInvisible() {
        UserReferenceAudio r = ref(23L, false, false);
        when(refRepo.findById(23L)).thenReturn(Optional.of(r));

        service.setReferenceHidden(23L, true, "admin");
        assertThat(r.isVisibleInShareList()).isFalse();

        service.setReferenceHidden(23L, false, "admin");
        // 用户本来就没分享，恢复管理员标记也不该让它冒出来
        assertThat(r.isVisibleInShareList()).isFalse();
    }

    @Test
    @DisplayName("重复下架同一参考音频幂等：不重复 save")
    void hideReferenceIsIdempotent() {
        UserReferenceAudio r = ref(24L, true, true);
        when(refRepo.findById(24L)).thenReturn(Optional.of(r));

        service.setReferenceHidden(24L, true, "admin");

        verify(refRepo, never()).save(any());
    }

    @Test
    @DisplayName("审核列表：onlyHidden=false 时列出全部（含未下架）")
    void listReferencesWithoutFilter() {
        when(refRepo.findForAdmin(eq(false), any())).thenReturn(java.util.List.of(
                ref(31L, true, false), ref(32L, true, true)));

        var items = service.listReferences(false, 50);

        assertThat(items).hasSize(2);
        verify(refRepo, times(1)).findForAdmin(eq(false), any());
    }

    // ---------- 状态过滤 ----------

    @Test
    @DisplayName("status 归一化：null 与非法值都当 all（宁可多给，不可漏审）")
    void normalizePostStatus() {
        when(postRepo.findAllForAdmin(eq(null), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        when(postRepo.findAllForAdmin(eq(Post.STATUS_ACTIVE), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));
        when(postRepo.findAllForAdmin(eq(Post.STATUS_REMOVED), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        service.listPosts("active", 0, 20);     // → active
        service.listPosts("removed", 0, 20);    // → removed
        service.listPosts("all", 0, 20);        // → all
        service.listPosts("weird", 0, 20);      // → all
        service.listPosts(null, 0, 20);         // → all

        verify(postRepo, times(1)).findAllForAdmin(eq(Post.STATUS_ACTIVE), any());
        verify(postRepo, times(1)).findAllForAdmin(eq(Post.STATUS_REMOVED), any());
        verify(postRepo, times(3)).findAllForAdmin(eq(null), any());
    }

    @Test
    @DisplayName("分页参数被夹紧：size 超过上限不报错，page 为负归零")
    void clampPagingParams() {
        when(postRepo.findAllForAdmin(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        service.listPosts("all", -5, 100000);
        // 越界参数被夹紧、不抛异常即达目的；具体取值由 Pageable 语义保证
        verify(postRepo, times(1)).findAllForAdmin(any(), any());
    }
}