package com.audiobook.server.repository;

import com.audiobook.server.model.UserBookMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 每书跨端元数据（排序 / 最近阅读，LWW）。 */
public interface UserBookMetaRepository extends JpaRepository<UserBookMeta, Long> {

    Optional<UserBookMeta> findByUserIdAndBookKey(Long userId, String bookKey);

    List<UserBookMeta> findByUserId(Long userId);
}
