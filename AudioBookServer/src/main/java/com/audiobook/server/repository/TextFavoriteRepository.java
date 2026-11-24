package com.audiobook.server.repository;

import com.audiobook.server.model.TextFavorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TextFavoriteRepository extends JpaRepository<TextFavorite, Long> {

    /** 我的听书收藏列表，按收藏时间倒序（最新收藏在前） */
    List<TextFavorite> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /**
     * 按「用户 + 书名 + 段落号」查唯一收藏。
     * 唯一约束 uk_text_fav_user_book_seg 保证至多一条；用 Optional 承接。
     */
    Optional<TextFavorite> findByUserIdAndBookNameAndSegmentNumber(Long userId, String bookName, int segmentNumber);

    /** 按 id 删除且校验归属，返回删除行数（0 表示不存在或不属于该用户） */
    long deleteByUserIdAndId(Long userId, Long id);
}
