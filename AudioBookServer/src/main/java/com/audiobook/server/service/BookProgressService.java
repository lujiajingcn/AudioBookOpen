package com.audiobook.server.service;

import com.audiobook.server.model.UserBookProgress;
import com.audiobook.server.repository.UserBookProgressRepository;
import com.audiobook.server.util.BookKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 跨端阅读进度的读写（身份 = book_key）。
 *
 * <p><b>为什么与老表 {@code reading_progress} 并存</b>：老表以 {@code (user_id, task_id)}
 * 为键，服务于「私有 TTS 任务」的断点续播（{@code /api/progress}），语义是
 * 「这个合成任务播到哪了」。本表以 {@code (user_id, book_key)} 为键，语义是
 * 「这本书读到哪了」。前者是<b>任务</b>维度、要求任务存在且归属本人；后者是<b>书</b>维度、
 * 与合成状态无关。二者面向的场景不同，各自保留：
 * <ul>
 *   <li>老端点写入时额外镜像到本表 ⇒ 老客户端（已在用的 APK/Web）也能吃到新口径；</li>
 *   <li>本表写入不影响老表 ⇒ 老客户端行为完全不变（渐进迁移，不破坏存量）。</li>
 * </ul>
 *
 * <p><b>book_key 归一化</b>：一律走 {@link BookKeys#normalize}（与有声覆盖图同一条路径），
 * 否则客户端各自算出的 key 只要差一个大小写/扩展名/空白，就会裂成两行进度。
 * 归一后为空 ⇒ 非法身份，直接拒绝（不落库、不报错，只当没这条进度）。
 */
@Service
public class BookProgressService {

    private static final Logger log = LoggerFactory.getLogger(BookProgressService.class);

    private final UserBookProgressRepository repository;

    public BookProgressService(UserBookProgressRepository repository) {
        this.repository = repository;
    }

    /**
     * 读取某用户对某本书的进度。
     *
     * @param bookKey 客户端上报的跨端身份键（{@code s:/t:/n:} 前缀）
     * @return 进度；没有记录或 key 非法时返回 empty
     */
    @Transactional(readOnly = true)
    public Optional<UserBookProgress> load(Long userId, String bookKey) {
        if (userId == null) {
            return Optional.empty();
        }
        String key = BookKeys.normalize(bookKey);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return repository.findByUserIdAndBookKey(userId, key);
    }

    /**
     * 保存（覆盖）某用户对某本书的进度。
     *
     * @param bookKey    跨端身份键
     * @param segment    1-based 位置（音频段号 / 正文段落序号）
     * @param offsetMs   段内偏移（毫秒）；纯阅读为 0
     * @return 是否真的保存了（key 非法 ⇒ false）
     */
    @Transactional
    public boolean save(Long userId, String bookKey, int segment, int offsetMs) {
        if (userId == null) {
            return false;
        }
        String key = BookKeys.normalize(bookKey);
        if (key.isEmpty()) {
            log.debug("进度上报 bookKey 归一化后为空，跳过 userId={} raw={}", userId, bookKey);
            return false;
        }
        if (segment < 0 || offsetMs < 0) {
            return false;
        }
        UserBookProgress row = repository.findByUserIdAndBookKey(userId, key)
                .orElseGet(() -> new UserBookProgress(userId, key, segment, offsetMs));
        row.setSegmentNumber(segment);
        row.setOffsetMs(offsetMs);
        try {
            repository.save(row);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 并发首次写入撞唯一约束：回查重试一次（与覆盖图的并集合并同一套路）
            UserBookProgress existing = repository.findByUserIdAndBookKey(userId, key).orElse(null);
            if (existing == null) {
                return false;
            }
            existing.setSegmentNumber(segment);
            existing.setOffsetMs(offsetMs);
            repository.save(existing);
            return true;
        }
    }
}
