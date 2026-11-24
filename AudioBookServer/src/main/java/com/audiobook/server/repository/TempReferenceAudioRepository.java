package com.audiobook.server.repository;

import com.audiobook.server.model.TempReferenceAudio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TempReferenceAudioRepository extends JpaRepository<TempReferenceAudio, Long> {
    List<TempReferenceAudio> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按文件名 + 归属用户定位（校验 /upload-reference 临时文件引用的合法性，防越权/路径穿越） */
    java.util.Optional<TempReferenceAudio> findByFileNameAndUserId(String fileName, Long userId);
}
