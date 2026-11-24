package com.audiobook.server.service;

import com.audiobook.server.model.UserBookProgress;
import com.audiobook.server.repository.UserBookProgressRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨端阅读进度（book_key 口径）的存取与归一化。
 *
 * <p>为什么这几条值得单测：bookKey 归一化一旦与客户端不一致，同一本书就会在服务端
 * 裂成多行进度 —— 表现为「A 端读到第 50 段，B 端跳回开头」，且不报错。
 * 而「无音频的书也能存进度」是这次改动的核心目的，老口径下它是直接 400 的。
 */
class BookProgressServiceTest {

    private final UserBookProgressRepository repository = mock(UserBookProgressRepository.class);
    private final BookProgressService service = new BookProgressService(repository);

    @Test
    @DisplayName("保存后读回：段号与偏移逐字段一致")
    void saveThenLoad() {
        when(repository.findByUserIdAndBookKey(7L, "s:45")).thenReturn(Optional.empty());
        when(repository.save(any(UserBookProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.save(7L, "s:45", 42, 1500)).isTrue();

        UserBookProgress saved = captured();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getBookKey()).isEqualTo("s:45");
        assertThat(saved.getSegmentNumber()).isEqualTo(42);
        assertThat(saved.getOffsetMs()).isEqualTo(1500);
    }

    @Test
    @DisplayName("无音频的书（n: 身份）也能存进度 —— 老口径下这里是 400")
    void supportsBookWithoutAudio() {
        when(repository.findByUserIdAndBookKey(7L, "n:无穷的开始")).thenReturn(Optional.empty());
        when(repository.save(any(UserBookProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.save(7L, "n:无穷的开始", 12, 0)).isTrue();
        assertThat(captured().getBookKey()).isEqualTo("n:无穷的开始");
    }

    @Test
    @DisplayName("bookKey 归一化：大小写/扩展名/空白差异归一到同一行")
    void normalizesBookKey() {
        // 「The Beginning.PDF」与「the beginning」应落到同一行
        when(repository.findByUserIdAndBookKey(7L, "n:the beginning"))
                .thenReturn(Optional.empty());
        when(repository.save(any(UserBookProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.save(7L, "n:The Beginning.PDF", 3, 0)).isTrue();

        assertThat(captured().getBookKey()).isEqualTo("n:the beginning");
        verify(repository).findByUserIdAndBookKey(7L, "n:the beginning");
    }

    @Test
    @DisplayName("bookKey 归一后为空 ⇒ 拒绝保存（不落库、不抛异常）")
    void rejectsEmptyBookKey() {
        assertThat(service.save(7L, "   ", 5, 0)).isFalse();
        assertThat(service.save(7L, null, 5, 0)).isFalse();
        verify(repository, never()).save(any(UserBookProgress.class));
    }

    @Test
    @DisplayName("非法位置（负数）⇒ 拒绝保存")
    void rejectsNegativePosition() {
        assertThat(service.save(7L, "s:45", -1, 0)).isFalse();
        assertThat(service.save(7L, "s:45", 1, -100)).isFalse();
        verify(repository, never()).save(any(UserBookProgress.class));
    }

    @Test
    @DisplayName("读取：bookKey 非法 ⇒ 返回空（当没有进度）")
    void loadRejectsEmptyBookKey() {
        assertThat(service.load(7L, "  ")).isEmpty();
        assertThat(service.load(null, "s:45")).isEmpty();
        verify(repository, never()).findByUserIdAndBookKey(any(), any());
    }

    @Test
    @DisplayName("读取：命中则返回该行")
    void loadReturnsRow() {
        UserBookProgress row = new UserBookProgress(7L, "s:45", 9, 300);
        when(repository.findByUserIdAndBookKey(7L, "s:45")).thenReturn(Optional.of(row));

        Optional<UserBookProgress> got = service.load(7L, "s:45");

        assertThat(got).isPresent();
        assertThat(got.get().getSegmentNumber()).isEqualTo(9);
        assertThat(got.get().getOffsetMs()).isEqualTo(300);
    }

    @Test
    @DisplayName("重复保存同一本书 ⇒ 覆盖同一行（不新增）")
    void overwriteSameRow() {
        UserBookProgress existing = new UserBookProgress(7L, "s:45", 1, 0);
        when(repository.findByUserIdAndBookKey(7L, "s:45")).thenReturn(Optional.of(existing));
        when(repository.save(any(UserBookProgress.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.save(7L, "s:45", 88, 0)).isTrue();

        assertThat(existing.getSegmentNumber()).isEqualTo(88);
        verify(repository).save(existing);
    }

    /** 取出本次保存的行（mock 的 save 会回填参数） */
    private UserBookProgress captured() {
        org.mockito.ArgumentCaptor<UserBookProgress> captor =
                org.mockito.ArgumentCaptor.forClass(UserBookProgress.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
