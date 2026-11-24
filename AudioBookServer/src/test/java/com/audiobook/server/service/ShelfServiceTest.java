package com.audiobook.server.service;

import com.audiobook.server.model.ShelfBook;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.repository.ShelfBookRepository;
import com.audiobook.server.repository.StoreBookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 书架的三个易错点回归测试。
 *
 * <p>覆盖的都是「看起来对、实际会把用户数据搞坏」的行为：
 * <ol>
 *   <li><b>幂等</b>：重复加入不能报错、不能产生第二条记录（多端同时点 /
 *       用户连点两下都会走到这条路）。</li>
 *   <li><b>书已删除时的列表项</b>：必须仍然返回、标 missing，不能过滤掉 ——
 *       否则用户会看到书凭空消失且无从清理。</li>
 *   <li><b>hasAudio 口径与书城一致</b>：taskId 非空且未下架才算有音频，
 *       否则书架卡片会显示「有声书」但点进去没声音。</li>
 * </ol>
 *
 * <p>用 Mockito 直接驱动 service，不启动 Spring 上下文 —— 与项目里
 * {@code TtsLoadBalancerTest} 等既有测试保持同一形态（快、无外部依赖）。
 */
class ShelfServiceTest {

    private final ShelfBookRepository shelfRepo = mock(ShelfBookRepository.class);
    private final StoreBookRepository storeRepo = mock(StoreBookRepository.class);
    private final ShelfService service = new ShelfService(shelfRepo, storeRepo);

    private static StoreBook book(long id, String title, Long taskId, String status) {
        StoreBook b = new StoreBook();
        b.setId(id);
        b.setTitle(title);
        b.setAuthor("某作者");
        b.setCategory("职场");
        b.setCoverColor("#123456");
        b.setFileExtension("epub");
        b.setFileSize(1234L);
        b.setTaskId(taskId);
        b.setStatus(status);
        return b;
    }

    private static ShelfBook shelfRow(long id, long userId, long bookId) {
        ShelfBook s = new ShelfBook(userId, bookId);
        s.setId(id);
        s.setCreatedAt(LocalDateTime.of(2026, 9, 17, 10, 0));
        return s;
    }

    // ---------- 1. 幂等 ----------

    @Test
    @DisplayName("重复加入书架：不新增记录，返回 added=false")
    void addIsIdempotent() {
        ShelfBook existing = shelfRow(7L, 1L, 9L);
        when(shelfRepo.findByUserIdAndStoreBookId(1L, 9L)).thenReturn(Optional.of(existing));
        when(storeRepo.findById(9L)).thenReturn(Optional.of(book(9L, "一人公司", 337L, "onshelf")));

        Map<String, Object> result = service.add(1L, 9L);

        assertThat(result.get("added")).isEqualTo(false);
        // 关键：绝不能走 insert —— 否则会撞唯一约束或产生重复行
        verify(shelfRepo, never()).save(any(ShelfBook.class));
    }

    @Test
    @DisplayName("首次加入书架：落库一条，added=true")
    void addCreatesWhenAbsent() {
        when(shelfRepo.findByUserIdAndStoreBookId(1L, 9L)).thenReturn(Optional.empty());
        when(shelfRepo.save(any(ShelfBook.class))).thenAnswer(inv -> {
            ShelfBook s = inv.getArgument(0);
            s.setId(11L);
            s.setCreatedAt(LocalDateTime.of(2026, 9, 17, 12, 0));
            return s;
        });
        when(storeRepo.findById(9L)).thenReturn(Optional.of(book(9L, "一人公司", 337L, "onshelf")));

        Map<String, Object> result = service.add(1L, 9L);

        assertThat(result.get("added")).isEqualTo(true);
        ArgumentCaptor<ShelfBook> captor = ArgumentCaptor.forClass(ShelfBook.class);
        verify(shelfRepo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getStoreBookId()).isEqualTo(9L);
    }

    // ---------- 2. 书已删除 ----------

    @Test
    @DisplayName("书已从书城删除：列表仍返回该条并标 missing=true（不过滤）")
    void listKeepsMissingBook() {
        when(shelfRepo.findByUserIdOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(shelfRow(1L, 1L, 999L)));
        // 999 已不在 store_books 里
        when(storeRepo.findByIdIn(anyCollection())).thenReturn(List.of());

        List<Map<String, Object>> items = service.list(1L);

        assertThat(items).hasSize(1);
        Map<String, Object> item = items.get(0);
        assertThat(item.get("missing")).isEqualTo(true);
        assertThat(item.get("storeBookId")).isEqualTo(999L);
        assertThat(item.get("title")).isNull();
        assertThat(item.get("hasAudio")).isEqualTo(false);
    }

    // ---------- 3. hasAudio / offshelf 口径 ----------

    @Test
    @DisplayName("有 taskId 且在售 → hasAudio=true")
    void hasAudioTrueWhenTaskIdPresent() {
        when(shelfRepo.findByUserIdOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(shelfRow(1L, 1L, 9L)));
        when(storeRepo.findByIdIn(anyCollection()))
                .thenReturn(List.of(book(9L, "一人公司", 337L, "onshelf")));

        Map<String, Object> item = service.list(1L).get(0);

        assertThat(item.get("hasAudio")).isEqualTo(true);
        assertThat(item.get("offshelf")).isEqualTo(false);
        assertThat(item.get("title")).isEqualTo("一人公司");
    }

    @Test
    @DisplayName("已下架的书：即使有 taskId，hasAudio 也必须为 false")
    void offshelfBookIsNotPlayable() {
        when(shelfRepo.findByUserIdOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(shelfRow(1L, 1L, 9L)));
        when(storeRepo.findByIdIn(anyCollection()))
                .thenReturn(List.of(book(9L, "已下架的书", 337L, "offshelf")));

        Map<String, Object> item = service.list(1L).get(0);

        assertThat(item.get("offshelf")).isEqualTo(true);
        // 若这里为 true，书架会显示「有声书」但点进去读不了，是最容易漏的一条
        assertThat(item.get("hasAudio")).isEqualTo(false);
        assertThat(item.get("missing")).isEqualTo(false);
    }

    // ---------- 4. 批量取书（N+1 防护） ----------

    @Test
    @DisplayName("列表用一次批量查询取书，不逐本 findById")
    void listUsesSingleBatchQuery() {
        when(shelfRepo.findByUserIdOrderByCreatedAtDescIdDesc(1L))
                .thenReturn(List.of(shelfRow(1L, 1L, 9L), shelfRow(2L, 1L, 8L)));
        when(storeRepo.findByIdIn(anyCollection()))
                .thenReturn(List.of(book(9L, "A", null, "onshelf"), book(8L, "B", null, "onshelf")));

        service.list(1L);

        ArgumentCaptor<java.util.Collection<Long>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(storeRepo).findByIdIn(captor.capture());
        assertThat(Set.copyOf(captor.getValue())).containsExactlyInAnyOrder(9L, 8L);
        verify(storeRepo, never()).findById(anyLong());
    }

    @Test
    @DisplayName("空书架：不发起任何书籍查询")
    void emptyShelfSkipsBookQuery() {
        when(shelfRepo.findByUserIdOrderByCreatedAtDescIdDesc(1L)).thenReturn(List.of());

        assertThat(service.list(1L)).isEmpty();
        verify(storeRepo, never()).findByIdIn(anyCollection());
    }
}
