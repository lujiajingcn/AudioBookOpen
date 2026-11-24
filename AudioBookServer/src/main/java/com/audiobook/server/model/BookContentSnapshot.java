package com.audiobook.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 书城书籍的「正文快照」—— 合成那一刻看到的正文，原样存下来（W1 的治本）。
 *
 * <p><b>为什么需要它</b>：阅读页有两套数据源 ——
 * <ul>
 *   <li>音频：{@code audio_segments}（合成时落的库，text_content 就是当时切出来的句子）；</li>
 *   <li>正文行：{@code GET /api/store/book/{id}/content} **按当前磁盘文件实时提取**。</li>
 * </ul>
 * 两者只靠「段号 = paragraphIndex + 1」这个隐式约定对齐。一旦磁盘文件被替换（管理员更新版本、
 * 重新上传同名文件），实时提取出来的段落与当初合成用的段落就对不上：
 * 点播放第 N 段，高亮却落在另一句话上 —— 而且**不会报错**，只会静默指错位置。
 *
 * <p>把合成时那次提取的完整结果（段落 + 目录 + 原文行 + 行↔段映射）存成快照，
 * 阅读页在「这本书有音频」时直接读快照，正文与音频就永远同源。
 *
 * <p>与 {@code store_books} 一对一（唯一键 {@code store_book_id}）；重新合成时覆盖同一行，
 * 所以不需要历史版本，也不会无限增长。
 */
@Entity
@Table(name = "book_content_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_bcs_book", columnNames = "store_book_id"))
public class BookContentSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书城书籍 id（store_books.id）；一本书只留最新一份快照 */
    @Column(name = "store_book_id", nullable = false)
    private Long storeBookId;

    /** 快照对应的合成任务 id；与 store_books.task_id 必须一致才认为可复用 */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /**
     * 快照对应的书文件字节数。
     *
     * <p>用来判断「磁盘上的文件是否已经换了版本」：大小不同 ⇒ 几乎一定换过；
     * 大小相同也可能是同尺寸替换，但那种情况下正文内容通常也不变，快照仍然对得上音频。
     */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 段落数（= 音频段数），便于不反序列化就能做一致性检查 */
    @Column(name = "paragraph_count", nullable = false)
    private Integer paragraphCount;

    /** 序列化后的 {@code BookTextExtractor.BookContent}（JSON） */
    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStoreBookId() {
        return storeBookId;
    }

    public void setStoreBookId(Long storeBookId) {
        this.storeBookId = storeBookId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getParagraphCount() {
        return paragraphCount;
    }

    public void setParagraphCount(Integer paragraphCount) {
        this.paragraphCount = paragraphCount;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
