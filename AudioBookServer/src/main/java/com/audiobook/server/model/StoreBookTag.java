package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 书城书籍标签（一本书 → 多个标签）。
 *
 * <p>为什么用独立关联表而不是 {@code store_books} 上的一个逗号分隔字段：
 * <ul>
 *   <li>书城列表要「按标签筛选」，逗号串只能退化成 {@code LIKE '%tag%'} 全表扫，
 *       且会误命中「历史」/「历史小说」这类前缀相同的标签；</li>
 *   <li>标签栏要显示每个标签下的书籍数量，需要按标签 GROUP BY 聚合，
 *       逗号串在 SQL 里做不到；</li>
 *   <li>一本书天然对应多行，唯一键 {@code (book_id, tag)} 保证重复提交不会产生重复行。</li>
 * </ul>
 *
 * <p>刻意不建 {@code StoreBook} 的 JPA 关系（{@code @ManyToMany}）：书城列表每次分页
 * 只取 20~24 本，标签用一次 {@code IN} 查询批量补齐（见 {@link #bookId}），
 * 建立双向关系后 Hibernate 会在每次加载书时顺带抓取，反而引入 N+1 或笛卡尔积。
 * 这与本工程既有做法一致（{@code StoreBook.taskId}、{@code ShelfBook.storeBookId}
 * 都是裸 id 字段）。
 *
 * <p>不建外键：与 {@code shelf_books.store_book_id}、{@code posts.ref_audio_id} 同一风格 ——
 * 删书时由 Service 在同一短事务里清理标签行，脏数据不会因约束缺失而静默累积
 *（{@code AdminService.deleteBookInTx} 显式删除）。
 */
@Entity
@Table(name = "store_book_tags",
        uniqueConstraints = @UniqueConstraint(name = "uk_store_book_tag",
                columnNames = {"book_id", "tag"}),
        indexes = @jakarta.persistence.Index(name = "idx_store_book_tag_tag", columnList = "tag"))
public class StoreBookTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书籍 id（store_books.id） */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /** 标签名，已由 Service 规范化（trim、去空、限制长度）后落库 */
    @Column(nullable = false, length = 50)
    private String tag;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public StoreBookTag() {
        this.createdAt = LocalDateTime.now();
    }

    public StoreBookTag(Long bookId, String tag) {
        this();
        this.bookId = bookId;
        this.tag = tag;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
