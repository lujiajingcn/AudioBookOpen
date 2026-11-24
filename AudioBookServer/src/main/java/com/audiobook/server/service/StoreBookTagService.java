package com.audiobook.server.service;

import com.audiobook.server.model.StoreBook;
import com.audiobook.server.model.StoreBookTag;
import com.audiobook.server.repository.StoreBookTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 书城书籍标签服务：读写 {@code store_book_tags} 关联表，并负责把标签填回书籍视图。
 *
 * <p>独立成 Service 而不是塞进 {@code BookStoreService} / {@code AdminService}：
 * 那两个类已经各自很大，且 {@code AdminService} 与 {@code BookStoreService} 之间
 * 刻意没有互相依赖（重建快照时才由 Controller 桥接）。标签是二者都要用的横切数据，
 * 放这里可以让读写两侧共用同一套规范化与批量填充逻辑，避免「管理端存的标签
 * 用户端筛不出来」这类两处各写一遍导致的口径漂移。
 *
 * <p>标签的语义约定：
 * <ul>
 *   <li>一本书最多 {@value #MAX_TAGS_PER_BOOK} 个标签（约束在写入侧，超出的截掉而不是报错 ——
 *       前端可被绕过，报错只会让整次保存失败）；</li>
 *   <li>单个标签最长 {@value #MAX_TAG_LENGTH} 字（库里列宽 50，留足余量）；</li>
 *   <li>去重**忽略大小写**：MySQL 的 utf8mb4_unicode_ci 唯一键本身大小写不敏感，
 *       不在这里先去重的话，"History" 与 "history" 会在插入时撞唯一键抛 SQL 异常；</li>
 *   <li>展示顺序 = 写入顺序（按 id 升序），不做字典序重排。</li>
 * </ul>
 */
@Service
public class StoreBookTagService {

    private static final Logger log = LoggerFactory.getLogger(StoreBookTagService.class);

    /** 单本书最多标签数 */
    public static final int MAX_TAGS_PER_BOOK = 10;

    /** 单个标签最大长度（字符数，非字节） */
    public static final int MAX_TAG_LENGTH = 20;

    private final StoreBookTagRepository storeBookTagRepository;

    public StoreBookTagService(StoreBookTagRepository storeBookTagRepository) {
        this.storeBookTagRepository = storeBookTagRepository;
    }

    /** 标签计数（标签名 + 该标签下的在售书籍数） */
    public record TagCount(String name, long count) {}

    /**
     * 规范化标签列表：trim → 去空 → 截断超长 → 忽略大小写去重 → 截断到上限。
     * 返回可变列表（调用方可能继续处理），顺序与入参一致。
     */
    public static List<String> normalize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashMap<String, String> deduped = new LinkedHashMap<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            // 全角空格、不间断空格一并当作空白清掉：从网页复制标签常带这些字符
            String trimmed = item.replace('\u00A0', ' ').trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_TAG_LENGTH) {
                // 不能简单 substring：可能把代理对（emoji）劈成半个字符
                trimmed = trimToLength(trimmed, MAX_TAG_LENGTH);
            }
            String key = trimmed.toLowerCase(Locale.ROOT);
            deduped.putIfAbsent(key, trimmed);
            if (deduped.size() >= MAX_TAGS_PER_BOOK) {
                break;
            }
        }
        return new ArrayList<>(deduped.values());
    }

    /** 按**码点**截断，避免把 surrogate pair 切坏（emoji / 部分生僻字） */
    private static String trimToLength(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end);
    }

    /** 逗号（中英文）、分号、换行分隔的字符串 → 标签列表。上传接口的多值参数用。 */
    public static List<String> parseDelimited(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        return normalize(List.of(raw.split("[,，;；\\n\\r\\t]")));
    }

    /**
     * 全量替换某本书的标签（先删后插）。
     *
     * <p>全量替换而不是增量 diff：管理端是「多选框 + 自由输入」一次提交完整集合，
     * 增量 diff 要在两处（前后端）都维护「哪些被删了」，反而容易漏；
     * 一本书最多 10 个标签，删+插的代价可以忽略。
     *
     * @return 规范化后的标签（即真正落库的内容，供调用方回显）
     */
    @Transactional
    public List<String> replaceTags(Long bookId, Collection<String> tags) {
        List<String> normalized = normalize(tags);
        storeBookTagRepository.deleteByBookId(bookId);
        if (!normalized.isEmpty()) {
            List<StoreBookTag> rows = new ArrayList<>(normalized.size());
            for (String tag : normalized) {
                rows.add(new StoreBookTag(bookId, tag));
            }
            storeBookTagRepository.saveAll(rows);
        }
        log.info("更新书籍标签: bookId={}, tags={}", bookId, normalized);
        return normalized;
    }

    /** 单本书的标签（按写入顺序） */
    public List<String> getTags(Long bookId) {
        if (bookId == null) {
            return new ArrayList<>();
        }
        return storeBookTagRepository.findByBookIdOrderByIdAsc(bookId).stream()
                .map(StoreBookTag::getTag)
                .toList();
    }

    /**
     * 批量把标签填进书籍视图（{@link StoreBook#getTags()}）。
     *
     * <p>一次 {@code IN} 查询取全页标签再分组，避免「逐本查一次」的 N+1。
     * 传入空集合直接返回，不产生任何 SQL。
     */
    public void attachTags(Collection<StoreBook> books) {
        if (books == null || books.isEmpty()) {
            return;
        }
        List<Long> bookIds = books.stream()
                .map(StoreBook::getId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (bookIds.isEmpty()) {
            return;
        }
        Map<Long, List<String>> byBook = new HashMap<>();
        for (StoreBookTag row : storeBookTagRepository.findByBookIdInOrderByIdAsc(bookIds)) {
            byBook.computeIfAbsent(row.getBookId(), k -> new ArrayList<>()).add(row.getTag());
        }
        for (StoreBook book : books) {
            book.setTags(byBook.getOrDefault(book.getId(), new ArrayList<>()));
        }
    }

    /** 给单本书填标签（详情接口用；attachTags 的单本重载） */
    public void attachTags(StoreBook book) {
        if (book != null) {
            attachTags(List.of(book));
        }
    }

    /** 在售书籍的标签聚合，按书籍数降序（书城标签栏的数据源） */
    public List<TagCount> listOnShelfTagCounts() {
        List<TagCount> counts = new ArrayList<>();
        for (Object[] row : storeBookTagRepository.countOnShelfTags()) {
            counts.add(new TagCount((String) row[0], ((Number) row[1]).longValue()));
        }
        return counts;
    }

    /** 全部已用标签名（管理端编辑时的联想词，不做在售过滤） */
    public List<String> listAllTagNames() {
        return storeBookTagRepository.findDistinctTagNames();
    }

    /** 删书时清理标签（调用方需在同一事务内，见 AdminService.deleteBookInTx） */
    public void deleteByBookId(Long bookId) {
        storeBookTagRepository.deleteByBookId(bookId);
    }

    /** 批量删书时清理标签 */
    public void deleteByBookIdIn(Collection<Long> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            return;
        }
        storeBookTagRepository.deleteByBookIdIn(new LinkedHashSet<>(bookIds));
    }
}
