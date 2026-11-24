package com.audiobook.server.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 用户书架（用户维度，跨端同步）。
 *
 * <p>与 {@link TextFavorite}（收藏「某本书第 N 段正文」）不同：书架的粒度是**整本书**，
 * 收藏的是「我想读/在读这本书」这件事，与具体段落无关。
 *
 * <p>唯一身份 = (user_id, store_book_id)，同一用户对同一本书只应有一条书架记录。
 * 注意存的是 {@code store_book_id}（书城主键）而**不是书名**：书名可能重复、可被管理员改，
 * 用 id 才能保证「同一本书」判定稳定；且书籍下架后仍能靠 id 反查到状态并提示用户。
 *
 * <p>除 store_book_id 外，仅冻结一份「入架时书名/作者快照」({@code title}/{@code author})。
 * 书在架时列表优先用 {@code store_books} 的实时元信息（管理员改名后立刻反映）；
 * 书被物理删除后 {@code store_books} 已无此行，列表再用该快照回显书名，
 * 避免「已失效」卡片退化成无名项、用户认不出是哪本书。
 */
@Entity
@Table(name = "shelf_books",
        uniqueConstraints = @UniqueConstraint(name = "uk_shelf_user_book",
                columnNames = {"user_id", "store_book_id"}),
        indexes = @Index(name = "idx_shelf_user_created", columnList = "user_id, created_at"))
public class ShelfBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 书城书籍 id（{@code store_books.id}） */
    @Column(name = "store_book_id", nullable = false)
    private Long storeBookId;

    /**
     * 入架时从 {@code store_books} 冻结的书名快照。
     * 书被物理删除后 {@code store_books} 已无此行，列表用它回显书名，
     * 避免「已失效」卡片变成无名项。书在架时 {@code ShelfService.toItem} 优先用实时书名，
     * 仅当书已删除（{@code book} 为 null）才回退到此快照。
     */
    @Column(name = "title", length = 255)
    private String title;

    /**
     * 入架时冻结的作者快照，用途同 {@link #title}。
     */
    @Column(name = "author", length = 255)
    private String author;

    /**
     * 加入书架时间。
     * 书架默认按「最近加入在前」排序，故需要该列；updatable=false 表示入架时间一经写入不再变。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ShelfBook() {
    }

    public ShelfBook(Long userId, Long storeBookId) {
        this.userId = userId;
        this.storeBookId = storeBookId;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getStoreBookId() {
        return storeBookId;
    }

    public void setStoreBookId(Long storeBookId) {
        this.storeBookId = storeBookId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
