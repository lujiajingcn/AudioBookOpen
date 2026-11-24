package com.audiobook.server.service;

import com.audiobook.server.dto.BookMetaItemRequest;
import com.audiobook.server.model.UserBookMeta;
import com.audiobook.server.repository.UserBookMetaRepository;
import com.audiobook.server.util.BookKeys;
import com.audiobook.server.util.LwwVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每书跨端元数据（手动排序 / 最近阅读）的 LWW 合并。
 *
 * <p><b>只管元数据、不管书籍是否存在</b>：客户端按 bookKey 匹配本地书行后
 * 只 UPDATE 两列，绝不 INSERT/DELETE 书籍行。书籍真源仍是 {@code shelf_books} /
 * 本地导入文件，本表不参与书架增删判定。
 *
 * <p><b>不含 category</b>：Android 该列是用户手工分类，PC 是文件扩展名/书城固定值
 * 且会被书架同步覆盖，同步必然互相污染。
 */
@Service
public class BookMetaService {

    private static final Logger log = LoggerFactory.getLogger(BookMetaService.class);

    private static final int MAX_BOOK_KEY_LENGTH = 255;
    private static final int MAX_ABS_SORT_ORDER = 1_000_000;

    private final UserBookMetaRepository repository;

    public BookMetaService(UserBookMetaRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<UserBookMeta> list(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return repository.findByUserId(userId);
    }

    /** 一次往返完成「拉 + 推」：逐 key LWW 合并后返回权威全量。 */
    @Transactional
    public List<UserBookMeta> sync(Long userId, List<BookMetaItemRequest> items) {
        if (userId == null) {
            return List.of();
        }
        if (items != null) {
            // 同批次同一 bookKey 只保留最后一条（归一化后再去重，防止 "n:Book" 与 "n:book" 自我覆盖）
            Set<String> seen = new LinkedHashSet<>();
            List<BookMetaItemRequest> ordered = new ArrayList<>(items);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                BookMetaItemRequest it = ordered.get(i);
                String key = it == null ? "" : BookKeys.normalize(it.getBookKey());
                if (key.isEmpty() || key.length() > MAX_BOOK_KEY_LENGTH || !seen.add(key)) {
                    ordered.remove(i);
                }
            }
            for (BookMetaItemRequest item : ordered) {
                mergeOne(userId, BookKeys.normalize(item.getBookKey()), item);
            }
        }
        return list(userId);
    }

    private void mergeOne(Long userId, String bookKey, BookMetaItemRequest item) {
        try {
            mergeOneOnce(userId, bookKey, item);
        } catch (DataIntegrityViolationException e) {
            log.debug("书籍元数据并发插入撞唯一约束，回查重合并 userId={} bookKey={}", userId, bookKey);
            mergeOneOnce(userId, bookKey, item);
        }
    }

    private void mergeOneOnce(Long userId, String bookKey, BookMetaItemRequest item) {
        int sortOrder = clampSortOrder(item.getSortOrder());
        long lastReadAt = item.getLastReadAt() == null ? 0L : Math.max(0L, item.getLastReadAt());
        UserBookMeta row = repository.findByUserIdAndBookKey(userId, bookKey).orElse(null);
        if (row == null) {
            repository.save(new UserBookMeta(userId, bookKey, sortOrder, lastReadAt,
                    LwwVersions.firstVersion(item.getUpdatedAt())));
            return;
        }
        if (!LwwVersions.shouldOverwrite(item.getUpdatedAt(), row.getUpdatedAt())) {
            return;
        }
        row.setSortOrder(sortOrder);
        row.setLastReadAt(lastReadAt);
        row.setUpdatedAt(LwwVersions.nextVersion(item.getUpdatedAt(), row.getUpdatedAt()));
        repository.save(row);
    }

    private static int clampSortOrder(Integer raw) {
        if (raw == null) {
            return 0;
        }
        return Math.max(-MAX_ABS_SORT_ORDER, Math.min(MAX_ABS_SORT_ORDER, raw));
    }
}
