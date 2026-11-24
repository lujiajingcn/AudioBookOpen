package com.audiobook.server.repository;

import com.audiobook.server.model.ShelfBook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ShelfBookRepository extends JpaRepository<ShelfBook, Long> {

    /**
     * 我的书架，按加入时间倒序（最近加入在前）。
     * 走 idx_shelf_user_created，避免全表扫描后排序。
     */
    List<ShelfBook> findByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /**
     * 按「用户 + 书城书籍 id」查唯一书架记录。
     * 唯一约束 uk_shelf_user_book 保证至多一条；用 Optional 承接。
     */
    Optional<ShelfBook> findByUserIdAndStoreBookId(Long userId, Long storeBookId);

    /** 按书城书籍 id 删除且校验归属，返回删除行数（0 表示未在书架中） */
    long deleteByUserIdAndStoreBookId(Long userId, Long storeBookId);

    /** 书架中所有书籍 id（供书城列表批量标注「是否已在书架」，避免逐本查一次） */
    List<ShelfBook> findByUserId(Long userId);
}
