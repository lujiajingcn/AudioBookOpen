package com.audiobook.server.repository;

import com.audiobook.server.model.ReadingProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, Long> {

    /**
     * 按用户 + 任务取播放进度（task_id 即跨端同步键，对应 Android 端 server_task_id / 服务端 Task.id）。
     * 唯一索引 (user_id, task_id) 保证每用户每任务至多一行，upsert 通过先查后存实现。
     */
    Optional<ReadingProgress> findByUserIdAndTaskId(Long userId, Long taskId);
}
