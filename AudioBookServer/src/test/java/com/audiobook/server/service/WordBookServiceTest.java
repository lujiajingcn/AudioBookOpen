package com.audiobook.server.service;

import com.audiobook.server.dto.WordBookItemRequest;
import com.audiobook.server.model.UserWordBook;
import com.audiobook.server.repository.UserWordBookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生词本「收藏出处」的跨端语义回归。
 *
 * <p>锁住三条不变式（改任何一条，三端单词本的「跳转到原书」都会静默失效）：
 * <ol>
 *   <li>新收藏必须把书名/书键/书城 id/段号/行号/句子原文一起落库；</li>
 *   <li>命中同 sw 时出处按「记最近一次」刷新，但音标/释义快照绝不覆盖；</li>
 *   <li>旧客户端（或存量同步）不带出处时一次库写入都不做 —— 否则一次同步
 *       就会把已有出处清成 null，这是最容易漏、也最难在现场复现的坑。</li>
 * </ol>
 *
 * <p>纯 JUnit + Mockito，不启动 Spring 上下文（与服务端其它测试一致），
 * 因此不覆盖 prod 的 ddl-auto=validate —— 线上必须先跑
 * {@code src/main/resources/db/user_word_books.sql} 再加列脚本。
 */
class WordBookServiceTest {

    private final UserWordBookRepository repo = mock(UserWordBookRepository.class);
    private final WordBookService service = new WordBookService(repo);

    private static WordBookItemRequest request(boolean withSource) {
        WordBookItemRequest req = new WordBookItemRequest();
        req.setSw("apple");
        req.setWord("Apple");
        req.setPhonetic("/ˈæpl/");
        req.setTranslations("苹果\n苹果树");
        if (withSource) {
            req.setBookName("English 101");
            req.setBookKey("s:42");
            req.setStoreBookId(42L);
            req.setSegmentNumber(7);
            req.setLineNumber(131);
            req.setSentenceText("An apple a day keeps the doctor away.");
        }
        return req;
    }

    @Test
    @DisplayName("新收藏：出处六字段随词条一起写入实体")
    void newEntryPersistsSource() {
        when(repo.findByUserIdAndSw(1L, "apple")).thenReturn(Optional.empty());
        when(repo.save(any(UserWordBook.class))).thenAnswer(inv -> inv.getArgument(0));

        UserWordBook saved = service.add(1L, request(true));

        assertThat(saved.getBookName()).isEqualTo("English 101");
        assertThat(saved.getBookKey()).isEqualTo("s:42");
        assertThat(saved.getStoreBookId()).isEqualTo(42L);
        assertThat(saved.getSegmentNumber()).isEqualTo(7);
        assertThat(saved.getLineNumber()).isEqualTo(131);
        assertThat(saved.getSentenceText()).isEqualTo("An apple a day keeps the doctor away.");
        // 快照没被出处逻辑带坏
        assertThat(saved.getPhonetic()).isEqualTo("/ˈæpl/");
        assertThat(saved.getTranslations()).isEqualTo("苹果\n苹果树");
    }

    @Test
    @DisplayName("同 sw 再次上报带出处：出处刷新为最近一次，音标/释义快照不动")
    void existingEntryRefreshesSourceButKeepsSnapshot() {
        UserWordBook existing = new UserWordBook(1L, "apple", "Apple", "OLD-PHONETIC", "OLD-TRANS");
        existing.applySource("旧书", "n:old", null, 1, 3, "old sentence");
        when(repo.findByUserIdAndSw(1L, "apple")).thenReturn(Optional.of(existing));
        when(repo.save(any(UserWordBook.class))).thenAnswer(inv -> inv.getArgument(0));

        UserWordBook result = service.add(1L, request(true));

        ArgumentCaptor<UserWordBook> captor = ArgumentCaptor.forClass(UserWordBook.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(result.getBookName()).isEqualTo("English 101");
        assertThat(result.getBookKey()).isEqualTo("s:42");
        assertThat(result.getStoreBookId()).isEqualTo(42L);
        assertThat(result.getSegmentNumber()).isEqualTo(7);
        assertThat(result.getSentenceText()).isEqualTo("An apple a day keeps the doctor away.");
        assertThat(result.getPhonetic()).isEqualTo("OLD-PHONETIC");
        assertThat(result.getTranslations()).isEqualTo("OLD-TRANS");
    }

    @Test
    @DisplayName("旧客户端不带出处：绝不清空已有出处，且一次库写入都不做")
    void legacyUploadDoesNotWipeSource() {
        UserWordBook existing = new UserWordBook(1L, "apple", "apple", null, null);
        existing.applySource("English 101", "s:42", 42L, 7, 131, "An apple a day.");
        when(repo.findByUserIdAndSw(1L, "apple")).thenReturn(Optional.of(existing));

        UserWordBook result = service.add(1L, request(false));

        verify(repo, never()).save(any(UserWordBook.class));
        assertThat(result.getBookName()).isEqualTo("English 101");
        assertThat(result.getSentenceText()).isEqualTo("An apple a day.");
    }

    @Test
    @DisplayName("hasSource：只有 bookName/bookKey 才算带出处，防止旧报文误判")
    void hasSourceDetection() {
        assertThat(request(true).hasSource()).isTrue();
        assertThat(request(false).hasSource()).isFalse();
        WordBookItemRequest onlyNumbers = request(false);
        onlyNumbers.setStoreBookId(42L);
        onlyNumbers.setSegmentNumber(7);
        assertThat(onlyNumbers.hasSource()).isFalse();
        WordBookItemRequest blankName = request(false);
        blankName.setBookName("   ");
        assertThat(blankName.hasSource()).isFalse();
    }

    @Test
    @DisplayName("toItem：出处六键必须出现在响应里（null 也是合法值）")
    void toItemExposesSource() {
        UserWordBook entry = new UserWordBook(1L, "apple", "Apple", null, null);
        entry.applySource("English 101", "s:42", 42L, 7, 131, "An apple a day.");

        Map<String, Object> item = service.toItem(entry);

        assertThat(item).containsKeys("bookName", "bookKey", "storeBookId",
                "segmentNumber", "lineNumber", "sentenceText");
        assertThat(item.get("bookName")).isEqualTo("English 101");
        assertThat(item.get("storeBookId")).isEqualTo(42L);
        assertThat(item.get("lineNumber")).isEqualTo(131);

        UserWordBook legacy = new UserWordBook(1L, "banana", "banana", null, null);
        Map<String, Object> legacyItem = service.toItem(legacy);
        assertThat(legacyItem).containsKeys("bookName", "sentenceText");
        assertThat(legacyItem.get("bookName")).isNull();
    }
}
