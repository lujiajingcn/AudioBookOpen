package com.audiobook.server.repository;

import com.audiobook.server.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    /** 某帖子的全部评论，按时间正序（楼序从早到晚） */
    List<Comment> findByPostIdOrderByCreatedAtAsc(Long postId);

    /** 某条评论的嵌套回复，按时间正序 */
    List<Comment> findByParentIdOrderByCreatedAtAsc(Long parentId);

    /** 统计某帖子评论数（含嵌套回复） */
    long countByPostId(Long postId);

    /** 删除帖子时级联删除其下全部评论（含嵌套回复） */
    void deleteByPostId(Long postId);
}
