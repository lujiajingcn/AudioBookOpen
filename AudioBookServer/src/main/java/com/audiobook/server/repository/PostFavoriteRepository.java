package com.audiobook.server.repository;

import com.audiobook.server.model.PostFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostFavoriteRepository extends JpaRepository<PostFavorite, Long> {

    /** 判断当前用户是否已收藏某帖子 */
    boolean existsByUserIdAndPostId(Long userId, Long postId);

    /** 我的收藏列表，按收藏时间倒序 */
    List<PostFavorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 取消收藏（唯一约束保证至多一条） */
    void deleteByUserIdAndPostId(Long userId, Long postId);

    /** 统计某帖子被收藏次数 */
    long countByPostId(Long postId);

    /** 彻底删除帖子时级联删除其下全部收藏（否则留下指向不存在帖子的孤儿行） */
    void deleteByPostId(Long postId);
}
