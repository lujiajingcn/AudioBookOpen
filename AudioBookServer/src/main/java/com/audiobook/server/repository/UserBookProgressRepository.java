package com.audiobook.server.repository;

import com.audiobook.server.model.UserBookProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 跨端阅读进度（按 book_key 身份）。
 *
 * @see UserBookProgress
 */
public interface UserBookProgressRepository extends JpaRepository<UserBookProgress, Long> {

    /** 查某用户对某本书的进度；没有记录返回 empty（而不是 null，调用方少一次判空）。 */
    Optional<UserBookProgress> findByUserIdAndBookKey(Long userId, String bookKey);

    /** 列出某用户的全部进度（书架页展示「读到哪了」时用）。 */
    List<UserBookProgress> findByUserId(Long userId);
}
