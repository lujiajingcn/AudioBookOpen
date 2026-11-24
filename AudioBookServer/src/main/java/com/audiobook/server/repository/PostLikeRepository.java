package com.audiobook.server.repository;

import com.audiobook.server.model.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    /** 当前用户是否已点赞某帖 */
    boolean existsByUserIdAndPostId(Long userId, Long postId);

    /** 取消点赞（唯一约束保证至多一条） */
    void deleteByUserIdAndPostId(Long userId, Long postId);

    /** 单帖点赞数 */
    long countByPostId(Long postId);

    /**
     * 批量取一页帖子的全部点赞行 —— 列表页用它一次性算出「每条几个赞 + 我赞过哪些」。
     *
     * <p>不这么做就得对每条帖子各查一次 count 与 exists：发现页 100 条帖子
     * 就是 200 次往返（经典 N+1），而这里只有 1 次。
     */
    List<PostLike> findByPostIdIn(Collection<Long> postIds);

    /** 删帖时清理其下全部点赞（否则留下指向不存在帖子的孤儿行） */
    void deleteByPostId(Long postId);
}
