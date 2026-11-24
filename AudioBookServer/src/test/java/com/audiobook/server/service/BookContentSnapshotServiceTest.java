package com.audiobook.server.service;

import com.audiobook.server.model.BookContentSnapshot;
import com.audiobook.server.repository.BookContentSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 正文快照的存取与「文件是否换过版本」判定（v5 / W1）。
 *
 * <p>为什么这几条值得单测：快照一旦序列化/反序列化不对称，阅读页就会拿到**残缺的正文**
 * （比如 rows 有、rowParagraphSegments 没了），而那种缺失在页面上表现为"高亮不太准"，
 * 没人会往"存的时候丢了字段"上想。所以这里用「再序列化一次比对 JSON」的方式钉住往返一致性。
 */
class BookContentSnapshotServiceTest {

    private final BookContentSnapshotRepository repository = mock(BookContentSnapshotRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BookContentSnapshotService service =
            new BookContentSnapshotService(repository, objectMapper);

    /** 一份形状完整的正文：段落 + 目录 + 原文行 + 行内段区间（含二维 int[]，最容易在往返里丢） */
    private static BookTextExtractor.BookContent sampleContent() {
        return new BookTextExtractor.BookContent(
                List.of(new BookTextExtractor.Paragraph("第一句。", 0, 0),
                        new BookTextExtractor.Paragraph("第二句。", 4, 1)),
                List.of(new BookTextExtractor.TocEntry("第一章", 0, 1)),
                "epub",
                List.of("　　第一句。第二句。"),
                List.of(0),
                List.of(1),
                List.of(List.of(new int[]{0, 0, 5}, new int[]{1, 6, 10})));
    }

    @Test
    @DisplayName("保存后读回：内容逐字段一致（含 List<List<int[]>>）")
    void saveThenLoadRoundTrip() throws Exception {
        BookContentSnapshot stored = new BookContentSnapshot();
        when(repository.findByStoreBookId(7L)).thenAnswer(inv -> Optional.of(stored));
        when(repository.save(any(BookContentSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        BookTextExtractor.BookContent original = sampleContent();
        service.save(7L, 42L, 1234L, original);

        assertThat(stored.getStoreBookId()).isEqualTo(7L);
        assertThat(stored.getTaskId()).isEqualTo(42L);
        assertThat(stored.getFileSize()).isEqualTo(1234L);
        assertThat(stored.getParagraphCount()).isEqualTo(2);

        Optional<BookContentSnapshotService.Snapshot> loaded = service.load(7L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().taskId()).isEqualTo(42L);
        assertThat(loaded.get().fileSize()).isEqualTo(1234L);

        // 用「再序列化后比对」代替 equals：BookContent 里含 int[]，数组的 equals 是引用比较
        assertThat(objectMapper.writeValueAsString(loaded.get().content()))
                .as("快照往返必须保住每一个字段，尤其是 rowParagraphSegments")
                .isEqualTo(objectMapper.writeValueAsString(original));
    }

    @Test
    @DisplayName("payload 损坏时返回 empty，不抛异常（阅读页要能回退到实时提取）")
    void brokenPayloadFallsBackToEmpty() {
        BookContentSnapshot stored = new BookContentSnapshot();
        stored.setStoreBookId(8L);
        stored.setTaskId(1L);
        stored.setFileSize(10L);
        stored.setPayload("{ this is not json");
        when(repository.findByStoreBookId(8L)).thenReturn(Optional.of(stored));

        assertThat(service.load(8L)).isEmpty();
    }

    @Test
    @DisplayName("重新合成：覆盖同一行，而不是新增第二份快照")
    void resynthesisOverwritesSameRow() {
        BookContentSnapshot existing = new BookContentSnapshot();
        existing.setStoreBookId(9L);
        existing.setTaskId(100L);
        existing.setPayload("{}");
        when(repository.findByStoreBookId(9L)).thenReturn(Optional.of(existing));
        when(repository.save(any(BookContentSnapshot.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(9L, 200L, 555L, sampleContent());

        assertThat(existing.getTaskId()).isEqualTo(200L);
        assertThat(existing.getFileSize()).isEqualTo(555L);
        verify(repository).save(existing);
    }

    @Test
    @DisplayName("入参不全（无 bookId / 无 taskId / 无内容）时什么都不写")
    void saveIgnoresIncompleteInput() {
        service.save(null, 1L, 1L, sampleContent());
        service.save(1L, null, 1L, sampleContent());
        service.save(1L, 1L, 1L, null);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("换版本判定：只按字节数比较；未知大小（-1）不报警")
    void fileChangeDetection() {
        assertThat(BookContentSnapshotService.isFileChanged(100L, 100L)).isFalse();
        assertThat(BookContentSnapshotService.isFileChanged(100L, 101L)).isTrue();
        assertThat(BookContentSnapshotService.isFileChanged(-1L, 100L)).isFalse();
        assertThat(BookContentSnapshotService.isFileChanged(100L, -1L)).isFalse();
    }
}
