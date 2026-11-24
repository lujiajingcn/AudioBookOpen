package com.audiobook.server.service;

import com.audiobook.server.dto.WordBookItemRequest;
import com.audiobook.server.model.UserWordBook;
import com.audiobook.server.repository.UserWordBookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 生词本服务（跨端同步真源）。
 *
 * <p>与 {@link FavoriteService} 逐条对齐：身份 = {@code (userId, sw)}、幂等 upsert、
 * sync = 循环 add 后返全量、删除带归属校验。语义选择"存在即返回、不覆盖已有快照"：
 * 用户可能在一端改了音标/释义来源（比如装了不同的词典）——**本期不做词条编辑**，
 * 保留原收藏快照；将来要做"重查刷新"再引入显式的 PUT 端点，而不是靠 add 静默改写。
 *
 * <p>出处（bookName/bookKey/...）是**唯一例外**：它是可刷新的定位元数据、不是快照。
 * 命中既有词条且本次上报带出处时按「记最近一次」刷新；不带出处的旧客户端/
 * 存量同步原样返回，绝不把已有出处清成 null，否则一次同步就会抹掉跳转信息。
 *
 * <p>{@code updatedAt} 字段本期只建列不写。等引入"编辑词条 / 重传刷新"时再启用。
 */
@Service
public class WordBookService {

    private static final Logger log = LoggerFactory.getLogger(WordBookService.class);

    private final UserWordBookRepository wordBookRepository;

    public WordBookService(UserWordBookRepository wordBookRepository) {
        this.wordBookRepository = wordBookRepository;
    }

    /** 我的生词本（按收藏时间倒序） */
    @Transactional(readOnly = true)
    public List<UserWordBook> list(Long userId) {
        return wordBookRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    /**
     * 新增（幂等）：已存在同 sw 收藏时返回既有记录。
     *
     * <p>为什么不覆盖 translations/phonetic？—— 用户可能是先查了一个词的旧版词典
     * 得到翻译 A，装了新版词典后又查了一次得到翻译 B。若允许静默覆盖，跨端会看到
     * "上一次同步的还是 A、这次不知为何变 B 了"。产品语义里"收藏 = 那一刻的快照"，
     * 想刷新得先删再加。将来若引入显式"更新词条"端点，走 PUT 而不是 POST。
     *
     * <p>并发插入撞唯一约束（{@code uk_word_user_sw}）→ catch
     * {@link DataIntegrityViolationException} 后回查既有记录，保证幂等而非 500。
     *
     * <p>出处语义 = 「记最近一次」：{@code (user_id, sw)} 仍唯一，一个词只有一条记录；
     * 每次带出处的上报（新收藏 / 跨端同步）刷新出处，不改动音标与释义快照。
     * 旧客户端不带出处时 {@link WordBookItemRequest#hasSource()} 为 false，
     * 直接返回既有记录，保护已有出处不被清掉。
     */
    @Transactional
    public UserWordBook add(Long userId, WordBookItemRequest req) {
        String sw = req.getSw().trim();
        String word = req.getWord().trim();
        Optional<UserWordBook> existing = wordBookRepository.findByUserIdAndSw(userId, sw);
        if (existing.isPresent()) {
            UserWordBook entry = existing.get();
            if (req.hasSource()) {
                applySource(entry, req);
                return wordBookRepository.save(entry);
            }
            return entry;
        }
        UserWordBook entry = new UserWordBook(
                userId,
                sw,
                word,
                safeTrim(req.getPhonetic()),
                safeTrim(req.getTranslations())
        );
        applySource(entry, req);
        try {
            return wordBookRepository.save(entry);
        } catch (DataIntegrityViolationException e) {
            log.debug("生词本并发插入撞唯一约束，回查既有记录 userId={} sw={}", userId, sw);
            return wordBookRepository.findByUserIdAndSw(userId, sw).orElseThrow(() -> e);
        }
    }

    /** 把请求里的出处搬到实体；null 值即「未知」，客户端据此禁用跳转按钮 */
    private static void applySource(UserWordBook entry, WordBookItemRequest req) {
        entry.applySource(
                safeTrim(req.getBookName()),
                safeTrim(req.getBookKey()),
                req.getStoreBookId(),
                req.getSegmentNumber(),
                req.getLineNumber(),
                safeTrim(req.getSentenceText())
        );
    }

    /**
     * 批量合并（首次上云 / 离线补传）：逐条 upsert，返回合并后的全量列表。
     * 客户端凭此结果重建本地表（含 server_id 回填）。
     */
    @Transactional
    public List<UserWordBook> sync(Long userId, List<WordBookItemRequest> items) {
        if (items != null) {
            for (WordBookItemRequest item : items) {
                add(userId, item);
            }
        }
        return list(userId);
    }

    /** 按 id 删除（校验归属）。返回是否确有删除，未命中返回 false（客户端按幂等处理） */
    @Transactional
    public boolean delete(Long userId, Long id) {
        return wordBookRepository.deleteByUserIdAndId(userId, id) > 0;
    }

    /** 按 sw 删除（校验归属）。供客户端没有 server_id（老数据）时的降级删除 */
    @Transactional
    public boolean deleteBySw(Long userId, String sw) {
        if (sw == null || sw.isEmpty()) {
            return false;
        }
        return wordBookRepository.deleteByUserIdAndSw(userId, sw.trim()) > 0;
    }

    /** 实体 → 响应项；字段命名与 Android/PC 端解析约定对齐 */
    public Map<String, Object> toItem(UserWordBook entry) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", entry.getId());
        item.put("sw", entry.getSw());
        item.put("word", entry.getWord());
        item.put("phonetic", entry.getPhonetic());
        item.put("translations", entry.getTranslations());
        item.put("createdAt", entry.getCreatedAt() == null ? null : entry.getCreatedAt().toString());
        item.put("updatedAt", entry.getUpdatedAt() == null ? null : entry.getUpdatedAt().toString());
        // 收藏出处：三端单词本「跳转到原书」用。null 是合法的（老数据 / 旧客户端），
        // 客户端必须显式判空并禁用跳转按钮，不能假设一定有值。
        item.put("bookName", entry.getBookName());
        item.put("bookKey", entry.getBookKey());
        item.put("storeBookId", entry.getStoreBookId());
        item.put("segmentNumber", entry.getSegmentNumber());
        item.put("lineNumber", entry.getLineNumber());
        item.put("sentenceText", entry.getSentenceText());
        return item;
    }

    public List<Map<String, Object>> toItems(List<UserWordBook> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (UserWordBook e : list) {
            out.add(toItem(e));
        }
        return out;
    }

    /**
     * trim 但 null 安全。空串按 null 处理（音标/释义缺省不占存储）。
     * 不抛：老客户端可能压根没填 phonetic。
     */
    private static String safeTrim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 预留：将来若引入"更新词条"端点，用它打时间戳 */
    @SuppressWarnings("unused")
    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
