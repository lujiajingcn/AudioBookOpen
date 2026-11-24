package com.audiobook.server.repository;

import com.audiobook.server.model.UserWordBook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserWordBookRepository extends JpaRepository<UserWordBook, Long> {

    /** 我的生词本列表，按收藏时间倒序（最新在前） */
    List<UserWordBook> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /**
     * 按「用户 + 归一化键 sw」查唯一词条。
     * 唯一约束 uk_word_user_sw 保证至多一条；用 Optional 承接。
     */
    Optional<UserWordBook> findByUserIdAndSw(Long userId, String sw);

    /** 按 id 删除且校验归属（0 = 不存在或非本人），支持客户端 id-based 删除 */
    long deleteByUserIdAndId(Long userId, Long id);

    /** 按 sw 删除且校验归属，供「同一词跨设备重收藏后再删」的路径 */
    long deleteByUserIdAndSw(Long userId, String sw);
}
