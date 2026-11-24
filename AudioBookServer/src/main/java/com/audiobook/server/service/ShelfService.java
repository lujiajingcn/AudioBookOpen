package com.audiobook.server.service;

import com.audiobook.server.model.ShelfBook;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.repository.ShelfBookRepository;
import com.audiobook.server.repository.StoreBookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户书架服务（跨端同步的单一真源）。
 *
 * <p>唯一身份 = (userId, storeBookId)，入库即幂等：
 * 重复加入返回既有记录而非报错，避免客户端重复点击/多端同时操作时弹错。
 *
 * <p>书架列表把 {@code shelf_books} 与 {@code store_books} 在服务层拼接成一份
 * 「书架项」视图。之所以不冗余书名到 shelf_books：管理员改书名 / 换封面 /
 * 完成音频合成后，书架应立刻反映最新状态；冗余字段必然导致两处真相不一致。
 *
 * <p>书籍已被物理删除（不在 store_books 里）时，列表仍返回该条并标
 * {@code missing=true}，前端提示「书籍已下架」并提供移除入口 —— 直接过滤掉的话
 * 用户会发现书凭空消失、又找不到地方清理。
 */
@Service
public class ShelfService {

    private static final Logger log = LoggerFactory.getLogger(ShelfService.class);

    private final ShelfBookRepository shelfBookRepository;
    private final StoreBookRepository storeBookRepository;

    public ShelfService(ShelfBookRepository shelfBookRepository,
                        StoreBookRepository storeBookRepository) {
        this.shelfBookRepository = shelfBookRepository;
        this.storeBookRepository = storeBookRepository;
    }

    /** 我的书架（按加入时间倒序），已带书籍元信息 */
    @Transactional
    public List<Map<String, Object>> list(Long userId) {
        List<ShelfBook> items = shelfBookRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }

        // 一次性批量取书，避免 N+1
        Set<Long> ids = items.stream().map(ShelfBook::getStoreBookId).collect(Collectors.toSet());
        Map<Long, StoreBook> byId = new HashMap<>();
        for (StoreBook b : storeBookRepository.findByIdIn(ids)) {
            byId.put(b.getId(), b);
        }

        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (ShelfBook item : items) {
            StoreBook book = byId.get(item.getStoreBookId());
            if (book != null) {
                // 书在架：顺手把实时书名/作者同步进书架快照，确保它日后被删除时仍能回显名称
                if (!Objects.equals(item.getTitle(), book.getTitle())
                        || !Objects.equals(item.getAuthor(), book.getAuthor())) {
                    applySnapshot(item, book);
                    shelfBookRepository.save(item);
                }
            }
            out.add(toItem(item, book));
        }
        return out;
    }

    /**
     * 加入书架（幂等）。
     * 已存在时返回既有记录并置 {@code added=false}，让前端能区分「本次真的加了」与
     * 「本来就在书架里」，从而给出不同文案而不是每次都提示「已加入」。
     *
     * <p>入架即把书名/作者冻结进书架快照（仅新建这条走一次 save）；已存在的行不在此处写，
     * 由 {@link #list(Long)} 在浏览书架时顺手补快照，避免幂等路径产生多余写。
     */
    @Transactional
    public Map<String, Object> add(Long userId, Long storeBookId) {
        Optional<ShelfBook> existing =
                shelfBookRepository.findByUserIdAndStoreBookId(userId, storeBookId);
        if (existing.isPresent()) {
            // 已在书架：不新增、不写快照（快照由 list 顺手补），直接返回现有项
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("shelfItem", toItem(existing.get(), storeBookRepository.findById(storeBookId).orElse(null)));
            result.put("added", false);
            return result;
        }

        StoreBook b = storeBookRepository.findById(storeBookId).orElse(null);
        ShelfBook created;
        try {
            ShelfBook s = new ShelfBook(userId, storeBookId);
            // 入架即冻结书名/作者快照：书被物理删除后仍能据此回显名称
            if (b != null) {
                s.setTitle(b.getTitle());
                s.setAuthor(b.getAuthor());
            }
            created = shelfBookRepository.save(s);
        } catch (DataIntegrityViolationException e) {
            // 并发插入撞唯一约束 uk_shelf_user_book：回查既有记录返回，保证幂等而非 500
            log.debug("书架并发插入撞唯一约束，回查既有记录 userId={} bookId={}", userId, storeBookId);
            created = shelfBookRepository.findByUserIdAndStoreBookId(userId, storeBookId).orElseThrow(() -> e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("shelfItem", toItem(created, b));
            result.put("added", false);
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shelfItem", toItem(created, b));
        result.put("added", true);
        return result;
    }

    /** 移出书架（幂等：不在书架中也返回成功，避免前端反复重试） */
    @Transactional
    public boolean remove(Long userId, Long storeBookId) {
        return shelfBookRepository.deleteByUserIdAndStoreBookId(userId, storeBookId) > 0;
    }

    /** 书架中所有书籍 id（供书城列表批量标注「已在书架」） */
    @Transactional(readOnly = true)
    public List<Long> bookIds(Long userId) {
        return shelfBookRepository.findByUserId(userId).stream()
                .map(ShelfBook::getStoreBookId)
                .collect(Collectors.toList());
    }

    /** 是否已在书架 */
    @Transactional(readOnly = true)
    public boolean contains(Long userId, Long storeBookId) {
        return shelfBookRepository.findByUserIdAndStoreBookId(userId, storeBookId).isPresent();
    }

    /**
     * 把书的实时书名/作者冻结进书架快照。
     * {@code book} 为 null（书已删）时不覆盖，保留既有快照，避免清空已存的书名。
     */
    private void applySnapshot(ShelfBook shelf, StoreBook book) {
        if (book == null) {
            return;
        }
        shelf.setTitle(book.getTitle());
        shelf.setAuthor(book.getAuthor());
    }

    /**
     * 实体 → 响应项。
     *
     * <p>书籍元信息取自 {@code store_books}；书已被删除时 {@code book} 为 null，
     * 此时仍返回 shelfBookId 与 storeBookId 并置 {@code missing=true}，
     * 书名/作者回退到入架时冻结的快照，前端据此渲染「已失效」卡片（带书名）并允许移除。
     */
    private Map<String, Object> toItem(ShelfBook item, StoreBook book) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shelfBookId", item.getId());
        out.put("storeBookId", item.getStoreBookId());
        out.put("addedAt", item.getCreatedAt() == null ? null : item.getCreatedAt().toString());

        if (book == null) {
            out.put("missing", true);
            // 书已被物理删除：回退到入架时冻结的书名/作者快照，让「已失效」卡片仍显示书名，
            // 用户能认出是哪本书、也知道要清理哪条记录；快照为空才是真正的无名项
            out.put("title", item.getTitle());
            out.put("author", item.getAuthor());
            out.put("category", null);
            out.put("coverColor", null);
            out.put("fileExtension", null);
            out.put("fileSize", null);
            out.put("taskId", null);
            out.put("offshelf", false);
            out.put("hasAudio", false);
            return out;
        }

        boolean offshelf = "offshelf".equals(book.getStatus());
        out.put("missing", false);
        out.put("title", book.getTitle());
        out.put("author", book.getAuthor());
        out.put("category", book.getCategory());
        out.put("coverColor", book.getCoverColor());
        out.put("fileExtension", book.getFileExtension());
        out.put("fileSize", book.getFileSize());
        out.put("taskId", book.getTaskId());
        out.put("offshelf", offshelf);
        // hasAudio 与书城口径一致：taskId 非空即视为已有合成音频（可边读边播）
        out.put("hasAudio", book.getTaskId() != null && !offshelf);
        return out;
    }
}
