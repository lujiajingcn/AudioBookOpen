package com.audiobook.server.repository;

import com.audiobook.server.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    long countByIsAdminTrue();

    /**
     * 加行锁读取用户，用于积分余额的读-改-写。
     *
     * 为什么必须加锁：并发提交两个合成任务时，两条连接可能同时读到同一个余额、
     * 各自减去成本再写回，后写覆盖先写 —— 用户只被扣了一次钱却跑了两个任务（资损）。
     * PESSIMISTIC_WRITE 让第二条连接在 SELECT ... FOR UPDATE 处等待，串行化同一用户的余额变更。
     *
     * 注意：调用方必须处于事务中，且应尽量缩短持锁时间（先做完文件落盘等慢操作再锁）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    /** 后台概览：全平台未消耗的积分存量（发出去但还没花掉的「负债」） */
    @Query("SELECT COALESCE(SUM(u.credits), 0) FROM User u")
    long sumAllCredits();
}
