package com.audiobook.server.service;

import com.audiobook.server.model.StoreBook;
import com.audiobook.server.model.StoreBookTag;
import com.audiobook.server.repository.StoreBookTagRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 书籍标签的规范化、全量替换与批量填充。
 *
 * <p>为什么这几条值得单测：
 * <ul>
 *   <li>规范化口径决定「同一本书不会存出两个看起来一样的标签」——
 *       不去重时 MySQL 唯一键会抛异常，整次保存失败（前端只看到「操作失败」）；</li>
 *   <li>批量填充一旦漏填某几本，前端会渲染成「这本书没标签」，
 *       看上去像是数据没保存，排查方向会被带偏；</li>
 *   <li>全量替换必须先删后插，只插不删会让取消掉的标签永远留在库里。</li>
 * </ul>
 */
class StoreBookTagServiceTest {

    private final StoreBookTagRepository repository = mock(StoreBookTagRepository.class);
    private final StoreBookTagService service = new StoreBookTagService(repository);

    @Test
    @DisplayName("规范化：trim、去空、去重（忽略大小写）、保留首次出现的写法")
    void normalizeTrimsAndDedupes() {
        List<String> result = StoreBookTagService.normalize(
                java.util.Arrays.asList("  历史 ", "", "   ", "History", "history", null, "战争"));

        assertThat(result).containsExactly("历史", "History", "战争");
    }

    @Test
    @DisplayName("规范化：超过 10 个只保留前 10 个")
    void normalizeCapsCount() {
        List<String> raw = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            raw.add("标签" + i);
        }
        List<String> result = StoreBookTagService.normalize(raw);
        assertThat(result).hasSize(StoreBookTagService.MAX_TAGS_PER_BOOK);
        assertThat(result.get(0)).isEqualTo("标签1");
        assertThat(result.get(9)).isEqualTo("标签10");
    }

    @Test
    @DisplayName("规范化：超长标签按码点截断，不劈开 emoji 的代理对")
    void normalizeTruncatesByCodePoint() {
        // 19 个汉字 + 1 个 emoji（2 个 char），共 20 个码点 → 正好不截断
        String boundary = "一二三四五六七八九十一二三四五六七八九" + "🙂";
        assertThat(StoreBookTagService.normalize(List.of(boundary)))
                .containsExactly(boundary);

        // 再多一个字就要截断；截断点落在 emoji 之后，不能留下半个代理对
        String over = boundary + "尾";
        List<String> result = StoreBookTagService.normalize(List.of(over));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(boundary);
        assertThat(result.get(0).codePointCount(0, result.get(0).length()))
                .isEqualTo(StoreBookTagService.MAX_TAG_LENGTH);
    }

    @Test
    @DisplayName("逗号串解析：中英文逗号、分号、换行都能当分隔符")
    void parseDelimitedSplitsOnCommonSeparators() {
        assertThat(StoreBookTagService.parseDelimited("历史,战争，人物传记;小说\n随笔"))
                .containsExactly("历史", "战争", "人物传记", "小说", "随笔");
        assertThat(StoreBookTagService.parseDelimited("")).isEmpty();
        assertThat(StoreBookTagService.parseDelimited(null)).isEmpty();
    }

    @Test
    @DisplayName("全量替换：先删后插，写入的是规范化后的标签")
    void replaceTagsDeletesThenInserts() {
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<String> applied = service.replaceTags(7L, List.of(" 历史", "历史", "战争 "));

        assertThat(applied).containsExactly("历史", "战争");
        verify(repository).deleteByBookId(7L);
        List<StoreBookTag> saved = capturedTags();
        assertThat(saved).extracting(StoreBookTag::getTag).containsExactly("历史", "战争");
        assertThat(saved).allMatch(t -> t.getBookId().equals(7L));
    }

    @Test
    @DisplayName("全量替换：提交空集合只删不插（清空标签）")
    void replaceTagsWithEmptyClears() {
        assertThat(service.replaceTags(7L, List.of())).isEmpty();
        verify(repository).deleteByBookId(7L);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("批量填充：一次 IN 查询把标签按 bookId 分回各书，没标签的书填空列表")
    void attachTagsFillsPerBook() {
        StoreBook a = book(1L);
        StoreBook b = book(2L);
        when(repository.findByBookIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                new StoreBookTag(1L, "历史"),
                new StoreBookTag(1L, "战争"),
                new StoreBookTag(2L, "随笔")));

        service.attachTags(List.of(a, b));

        assertThat(a.getTags()).containsExactly("历史", "战争");
        assertThat(b.getTags()).containsExactly("随笔");
    }

    @Test
    @DisplayName("批量填充：空集合不查库（详情页传 null 也不应 NPE）")
    void attachTagsSkipsEmptyInput() {
        service.attachTags(new ArrayList<>());
        service.attachTags((StoreBook) null);
        verify(repository, never()).findByBookIdInOrderByIdAsc(anyCollection());
    }

    @Test
    @DisplayName("批量填充：tags 为 null 时归一为空列表，前端不必判空")
    void setTagsNullBecomesEmptyList() {
        StoreBook book = new StoreBook();
        book.setTags(null);
        assertThat(book.getTags()).isNotNull().isEmpty();
    }

    private static StoreBook book(Long id) {
        StoreBook book = new StoreBook();
        book.setId(id);
        book.setTitle("书" + id);
        return book;
    }

    @SuppressWarnings("unchecked")
    private List<StoreBookTag> capturedTags() {
        org.mockito.ArgumentCaptor<List<StoreBookTag>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }
}
