package com.audiobook.server.service;

import com.audiobook.server.dto.FavoriteItemRequest;
import com.audiobook.server.model.TextFavorite;
import com.audiobook.server.repository.TextFavoriteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 听书收藏（正文收藏）服务 —— 跨端同步的单一真源。
 *
 * 唯一身份 = (userId, bookName, segmentNumber)。taskId 只是深链辅助字段，不参与去重：
 * - Web 端收藏时能带 taskId、Android 端带不了，若把 taskId 纳入身份，两端对同一段会各存一条；
 * - 同一 (book, segment) 已存在但缺 taskId 时，本次带上 taskId 则回填，使「去听」可用。
 *
 * 并发兜底：唯一约束 uk_text_fav_user_book_seg 会在并发重复插入时抛
 * DataIntegrityViolationException，此处捕获后再查一次返回既有记录，保证幂等。
 */
@Service
public class FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteService.class);

    private final TextFavoriteRepository favoriteRepository;

    public FavoriteService(TextFavoriteRepository favoriteRepository) {
        this.favoriteRepository = favoriteRepository;
    }

    /** 我的收藏（按收藏时间倒序） */
    @Transactional(readOnly = true)
    public List<TextFavorite> list(Long userId) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    /** 新增（幂等）：已存在同段收藏时返回既有记录，并尝试回填 taskId */
    @Transactional
    public TextFavorite add(Long userId, FavoriteItemRequest req) {
        String bookName = req.getBookName().trim();
        int segment = req.getSegmentNumber();
        Optional<TextFavorite> existing =
                favoriteRepository.findByUserIdAndBookNameAndSegmentNumber(userId, bookName, segment);
        if (existing.isPresent()) {
            TextFavorite fav = existing.get();
            backfillTaskId(fav, req.getTaskId());
            return fav;
        }
        TextFavorite fav = new TextFavorite(userId, bookName, segment, req.getContent().trim(), req.getTaskId());
        try {
            return favoriteRepository.save(fav);
        } catch (DataIntegrityViolationException e) {
            // 并发插入撞唯一约束：回查既有记录返回，保证幂等而非 500
            log.debug("收藏并发插入撞唯一约束，回查既有记录 userId={} book={} seg={}", userId, bookName, segment);
            return favoriteRepository
                    .findByUserIdAndBookNameAndSegmentNumber(userId, bookName, segment)
                    .orElseThrow(() -> e);
        }
    }

    /** 批量合并（迁移 / 离线补传）：逐条 upsert，返回合并后的全量列表 */
    @Transactional
    public List<TextFavorite> sync(Long userId, List<FavoriteItemRequest> items) {
        if (items != null) {
            for (FavoriteItemRequest item : items) {
                add(userId, item);
            }
        }
        return list(userId);
    }

    /** 按 id 删除（校验归属）。返回是否确有删除 */
    @Transactional
    public boolean delete(Long userId, Long id) {
        return favoriteRepository.deleteByUserIdAndId(userId, id) > 0;
    }

    /** 仅在已有记录缺 taskId、且本次请求带 taskId 时回填（不覆盖已有 taskId） */
    private void backfillTaskId(TextFavorite fav, Long incomingTaskId) {
        if (fav.getTaskId() == null && incomingTaskId != null) {
            fav.setTaskId(incomingTaskId);
            favoriteRepository.save(fav);
        }
    }

    /** 实体 → 响应项（Web / Android 两端共用同一字段命名） */
    public Map<String, Object> toItem(TextFavorite fav) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", fav.getId());
        item.put("bookName", fav.getBookName());
        item.put("taskId", fav.getTaskId());
        item.put("segmentNumber", fav.getSegmentNumber());
        item.put("content", fav.getContent());
        item.put("createdAt", fav.getCreatedAt() == null ? null : fav.getCreatedAt().toString());
        return item;
    }

    /** 批量转换 */
    public List<Map<String, Object>> toItems(List<TextFavorite> list) {
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (TextFavorite fav : list) {
            out.add(toItem(fav));
        }
        return out;
    }
}
