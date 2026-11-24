package com.audiobook.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReferenceAudioTopic} 入库前的默认值填充测试。
 *
 * <p>{@code sort_order} 在 DDL 里是 {@code NOT NULL}，而管理端新建主题时通常不填排序值。
 * 若实体不把它兜成 0，Hibernate 就会带着 null 去写这一列：
 * 本地 dev 的 ddl-auto=update 可能侥幸放过，生产 validate 下则可能直接启动/写入失败；
 * 即使写进去了，列表按 sort_order 排序时空值的位置也会变得不可预期。
 */
class ReferenceAudioTopicPrePersistTest {

    @Test
    @DisplayName("排序值缺省补 0，不会带着 null 去写 NOT NULL 列")
    void sortOrderDefaultsToZero() {
        ReferenceAudioTopic topic = new ReferenceAudioTopic();
        assertThat(topic.getSortOrder()).isNull();

        topic.prePersist();

        assertThat(topic.getSortOrder()).isZero();
    }

    @Test
    @DisplayName("入库前自动补齐创建/更新时间")
    void timestampsFilled() {
        ReferenceAudioTopic topic = new ReferenceAudioTopic();
        topic.prePersist();

        assertThat(topic.getCreatedAt()).isNotNull();
        assertThat(topic.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("显式给过的时间与排序值不被默认值覆盖")
    void explicitValuesKept() {
        java.time.LocalDateTime fixed = LocalDateTime.of(2026, 10, 18, 12, 0, 0);
        ReferenceAudioTopic topic = new ReferenceAudioTopic();
        topic.setSortOrder(7);
        topic.setCreatedAt(fixed);

        topic.prePersist();

        assertThat(topic.getSortOrder()).isEqualTo(7);
        assertThat(topic.getCreatedAt()).isEqualTo(fixed);
    }

    @Test
    @DisplayName("更新时刷新 updatedAt，但不动 createdAt")
    void preUpdateTouchesOnlyUpdatedAt() {
        ReferenceAudioTopic topic = new ReferenceAudioTopic();
        topic.prePersist();
        LocalDateTime created = topic.getCreatedAt();
        topic.setName("改名后的主题");

        topic.preUpdate();

        assertThat(topic.getCreatedAt()).isEqualTo(created);
        assertThat(topic.getUpdatedAt()).isNotNull();
        assertThat(topic.getName()).isEqualTo("改名后的主题");
    }
}
