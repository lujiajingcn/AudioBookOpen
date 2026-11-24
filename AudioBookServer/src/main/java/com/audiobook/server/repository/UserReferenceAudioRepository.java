package com.audiobook.server.repository;

import com.audiobook.server.model.UserReferenceAudio;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserReferenceAudioRepository extends JpaRepository<UserReferenceAudio, Long> {
    List<UserReferenceAudio> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按文件名 + 归属用户定位（校验「我的库」引用的合法性，防越权/路径穿越） */
    java.util.Optional<UserReferenceAudio> findByFileNameAndUserId(String fileName, Long userId);

    /**
     * 按 id + 归属用户定位（v5 / S7）。
     *
     * <p>发帖可以嵌入「参考音频」的 id。只按 id 查会让用户 A 把用户 B 的音频嵌进自己的帖子
     * （IDOR：越权引用他人私有资源）。把 userId 一并放进查询条件，越权自然落空。
     */
    java.util.Optional<UserReferenceAudio> findByIdAndUserId(Long id, Long userId);

    /**
     * 分享排行榜：已分享 <b>且未被管理员下架</b>的按使用量倒序；使用量相同时分享早的在前。
     *
     * <p>{@code admin_hidden} 与 {@code shared} 正交：shared 是「用户自己要不要分享」，
     * adminHidden 是「管理员要不要隐藏」。用户取消分享后再重新分享时，被管理员下架的
     * 那份<b>不会</b>重新出现在排行榜里——违规内容不该因为用户点一下就复活。
     *
     * <p>⚠️ 走 JPQL 而不是派生查询：{@code admin_hidden} 是 {@code bit(1)} 列，
     * 派生查询生成的 SQL 是 {@code admin_hidden = ?} 绑 0，MySQL 侧 bit 与数字比较
     * 能命中但走不了索引；写成 {@code adminHidden = false} 由 Hibernate 转成
     * {@code admin_hidden = b'0'}，语义明确且与实体类型一致。
     *
     * @param pageable 分页，调用方传 limit
     */
    @Query("SELECT u FROM UserReferenceAudio u WHERE u.shared = true AND u.adminHidden = false "
            + "ORDER BY u.useCount DESC, u.sharedAt ASC, u.createdAt ASC")
    List<UserReferenceAudio> findShareRankList(Pageable pageable);

    /**
     * 后台内容审核列表：与前台排行榜同一排序口径，但不排除已下架的，
     * 由调用方按 {@code onlyHidden} 决定是否只看隐藏项。
     *
     * @param onlyHidden true=只看被管理员下架的；false=全部（含未下架）
     */
    @Query("SELECT u FROM UserReferenceAudio u WHERE (:onlyHidden = false OR u.adminHidden = true) "
            + "ORDER BY u.useCount DESC, u.sharedAt ASC, u.createdAt ASC")
    List<UserReferenceAudio> findForAdmin(@Param("onlyHidden") boolean onlyHidden, Pageable pageable);

    /** 前台分享排行榜（保留旧签名供既有调用方使用，内部走带下架过滤的 JPQL） */
    default List<UserReferenceAudio> findBySharedOrderByUseCountDescSharedAtAscCreatedAtAsc(
            Boolean shared, Pageable pageable) {
        return findShareRankList(pageable);
    }

    /**
     * 使用量原子自增。
     *
     * 用 UPDATE ... SET use_count = use_count + 1 而不是「读出来 +1 再 save」，
     * 是为了避免并发下载时相互覆盖（读改写丢失更新）。
     *
     * @return 受影响行数，0 表示 id 不存在
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserReferenceAudio u SET u.useCount = COALESCE(u.useCount, 0) + 1 WHERE u.id = :id")
    int incrementUseCount(@Param("id") Long id);
}
