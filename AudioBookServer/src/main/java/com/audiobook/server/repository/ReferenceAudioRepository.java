package com.audiobook.server.repository;

import com.audiobook.server.model.ReferenceAudio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.Tuple;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferenceAudioRepository extends JpaRepository<ReferenceAudio, Long> {

    /** 按创建时间倒序，最近的在前 */
    List<ReferenceAudio> findAllByOrderByCreatedAtDesc();

    Optional<ReferenceAudio> findByFileName(String fileName);

    /** 某主题下的音频数量（删主题前的非空校验） */
    long countByTopicId(Long topicId);

    /**
     * 各主题的音频数分组统计：一次 GROUP BY 拿全，避免主题列表页逐主题 count 的 N+1。
     *
     * <p>刻意用 {@link Tuple} 而不是接口投影：接口投影在聚合查询下的行为依赖
     * 具体 Spring Data 版本，而本仓测试全部是脱离容器的纯单测，
     * 没有能真正跑起这段 JPQL 的地方。Tuple 是别名取值 + 运行时类型检查，
     * 即使写错也会在启动时给出明确异常，而不是等到线上取到 null 计数。
     */
    @Query("select a.topicId as topicId, count(a.id) as audioCount "
            + "from ReferenceAudio a where a.topicId is not null group by a.topicId")
    List<Tuple> countGroupByTopicId();
}
