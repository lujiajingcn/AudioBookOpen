package com.audiobook.server.repository;

import com.audiobook.server.model.UserAudioCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAudioCoverageRepository extends JpaRepository<UserAudioCoverage, Long> {

    /** 我的有声覆盖图列表，按更新时间倒序（最新在前） */
    List<UserAudioCoverage> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);

    /** 按 (user, bookKey, source) 查唯一覆盖行；唯一约束保证至多一条 */
    Optional<UserAudioCoverage> findByUserIdAndBookKeyAndSource(Long userId, String bookKey, String source);

    /**
     * 迁移专用：拉出所有 book_key 以指定前缀开头的行，供一次性归一化重写。
     * 生产库量级较小（覆盖图每人每源一本书一行），全表 LIKE 'n:%' 扫可接受；
     * 走 uk_cov_user_book_src 的 book_key 部分无法命中索引，故本方法只在
     * {@code AudioCoverageBookKeyMigration} 启动时调用一次，不在业务路径上。
     */
    List<UserAudioCoverage> findByBookKeyStartingWith(String prefix);
}
