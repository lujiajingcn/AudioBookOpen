package com.audiobook.server.repository;

import com.audiobook.server.model.ReferenceAudioTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReferenceAudioTopicRepository extends JpaRepository<ReferenceAudioTopic, Long> {

    /** 主题列表：按 sort_order 升序，同序时按 id 升序（保证顺序稳定、可预期） */
    List<ReferenceAudioTopic> findAllByOrderBySortOrderAscIdAsc();

    /** 主题名全局唯一，用于创建/改名时的查重 */
    Optional<ReferenceAudioTopic> findByName(String name);

    /** 改名场景的查重：排除自身 */
    Optional<ReferenceAudioTopic> findByNameAndIdNot(String name, Long id);
}
