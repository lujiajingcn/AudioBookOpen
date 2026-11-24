package com.audiobook.server.repository;

import com.audiobook.server.model.BookContentSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BookContentSnapshotRepository extends JpaRepository<BookContentSnapshot, Long> {

    /** 一本书只保留一份快照（重新合成时覆盖） */
    Optional<BookContentSnapshot> findByStoreBookId(Long storeBookId);

    void deleteByStoreBookId(Long storeBookId);
}
