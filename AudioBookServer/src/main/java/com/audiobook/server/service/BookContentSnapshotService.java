package com.audiobook.server.service;

import com.audiobook.server.model.BookContentSnapshot;
import com.audiobook.server.repository.BookContentSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 书城书籍正文快照的存取（W1 治本）。
 *
 * <p>只做三件事：把合成时那次提取的 {@link BookTextExtractor.BookContent} 序列化落库、
 * 需要时读回来、判断磁盘文件是否已经换了版本。**不参与**任何阅读逻辑，
 * 免得"快照服务"变成第二个真相来源。
 */
@Service
public class BookContentSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BookContentSnapshotService.class);

    private final BookContentSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    public BookContentSnapshotService(BookContentSnapshotRepository repository,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 读回一份快照（含反序列化后的内容）；没有或损坏都返回 empty，调用方回退到实时提取 */
    public record Snapshot(BookTextExtractor.BookContent content,
                           Long taskId,
                           long fileSize,
                           LocalDateTime createdAt) {
    }

    /**
     * 保存/覆盖一本书的正文快照。
     *
     * <p>按 {@code store_book_id} 覆盖：重新合成后旧快照就过期了，留历史没有意义，
     * 反而会在读的时候选错。
     *
     * @param storeBookId 书城书籍 id
     * @param taskId      本次合成任务 id（与 store_books.task_id 对应）
     * @param fileSize    合成时书文件的字节数（用于后续判断是否换过版本）
     */
    @Transactional
    public void save(Long storeBookId, Long taskId, long fileSize,
                     BookTextExtractor.BookContent content) {
        if (storeBookId == null || taskId == null || content == null) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            // 快照失败不能影响合成主流程：阅读页会退化为实时提取（即修复前的行为）
            log.warn("正文快照序列化失败，跳过: bookId={}, taskId={}, err={}",
                    storeBookId, taskId, e.getMessage());
            return;
        }

        BookContentSnapshot entity = repository.findByStoreBookId(storeBookId)
                .orElseGet(BookContentSnapshot::new);
        entity.setStoreBookId(storeBookId);
        entity.setTaskId(taskId);
        entity.setFileSize(fileSize);
        entity.setParagraphCount(content.paragraphs() == null ? 0 : content.paragraphs().size());
        entity.setPayload(payload);
        entity.setCreatedAt(LocalDateTime.now());
        repository.save(entity);
        log.info("已保存正文快照: bookId={}, taskId={}, 段落数={}, 体积={} 字符",
                storeBookId, taskId, entity.getParagraphCount(), payload.length());
    }

    /** 读取快照；反序列化失败视为"没有快照"，并记日志（不回退到异常，避免阅读页整页失败） */
    public Optional<Snapshot> load(Long storeBookId) {
        if (storeBookId == null) {
            return Optional.empty();
        }
        Optional<BookContentSnapshot> row = repository.findByStoreBookId(storeBookId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        BookContentSnapshot entity = row.get();
        try {
            BookTextExtractor.BookContent content =
                    objectMapper.readValue(entity.getPayload(), BookTextExtractor.BookContent.class);
            return Optional.of(new Snapshot(content, entity.getTaskId(),
                    entity.getFileSize() == null ? -1L : entity.getFileSize(), entity.getCreatedAt()));
        } catch (Exception e) {
            log.error("正文快照反序列化失败，将回退到实时提取: bookId={}, id={}, err={}",
                    storeBookId, entity.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 磁盘文件是否已经与快照不是同一份。
     *
     * <p>判据只用字节数：换版本几乎一定会改大小；同尺寸替换属于极少数，
     * 而且那种情况下快照里的正文仍然与音频同源，不算错。
     *
     * <p><b>包内可见是为了单测</b>：这段判断没有 IO，纯算术，最值得钉住。
     */
    static boolean isFileChanged(long recordedSize, long currentSize) {
        if (recordedSize < 0 || currentSize < 0) {
            return false;   // 未知就不报警，避免误伤
        }
        return recordedSize != currentSize;
    }

    /** 删除一本书的快照（书被移除/重新上传时清理） */
    @Transactional
    public void delete(Long storeBookId) {
        if (storeBookId != null) {
            repository.deleteByStoreBookId(storeBookId);
        }
    }
}
