package com.audiobook.server.repository;

import com.audiobook.server.model.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    /**
     * 发现页列表：按发帖时间倒序（最新在前），同秒用 id 兜底保证稳定排序，
     * 避免翻页时同一秒内的帖子出现重复/漏项。条数由 Pageable 控制。
     *
     * <p><b>只返回未被下架的帖子</b>（status = active）：发现页是用户可见面，
     * 管理员下架的内容必须从这条默认路径里消失，否则「下架」形同虚设。
     * 需要看已下架内容请走 {@link #findAllForAdmin}。
     */
    Page<Post> findAllByStatusOrderByCreatedAtDescIdDesc(String status, Pageable pageable);

    /** 同 {@link #findAllByStatusOrderByCreatedAtDescIdDesc}，恒为 active 的快捷入口 */
    default Page<Post> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable) {
        return findAllByStatusOrderByCreatedAtDescIdDesc(
                com.audiobook.server.model.Post.STATUS_ACTIVE, pageable);
    }

    /**
     * 后台内容审核列表：{@code status} 传 {@code null} 表示不限状态（管理员要看「全部」）。
     *
     * <p>不用派生查询：派生方法的 {@code Pageable} 必须排在参数末尾，
     * 无法表达「status 可空」这种可选条件，故改写 JPQL。
     */
    @Query("SELECT p FROM Post p WHERE (:status IS NULL OR p.status = :status) "
            + "ORDER BY p.createdAt DESC, p.id DESC")
    Page<Post> findAllForAdmin(@Param("status") String status, Pageable pageable);

    /** 按嵌入的参考音频 id 过滤，按发帖时间倒序（用于「用某音色分享的帖子」聚合） */
    Page<Post> findByRefAudioIdOrderByCreatedAtDescIdDesc(Long refAudioId, Pageable pageable);

    /** 统计处于某状态的帖子数（后台列表页签上的计数角标） */
    long countByStatus(String status);

    // -----------------------------------------------------------------------
    // 原子计数：解决 Post.commentCount / favoriteCount 的 read-modify-write 竞态
    //
    // 之前 createComment 的实现是 findById → setCommentCount(+1) → save(post)，
    // 两次并发评论时 A 与 B 都读到 commentCount=0、各自写回 1，最终值 1 而非 2。
    // @Transactional 在没有行锁 / 乐观锁的前提下对这种跨事务读写毫无保护。
    //
    // 现在改用 SQL 原子 UPDATE（数据库内部加锁），由数据库保证 lost-update-free。
    // 必须在事务内调用（@Modifying + 调用方 @Transactional），否则 flush 不会提交。
    // -----------------------------------------------------------------------

    /** 评论数 +1（原子）。评论新增时由 PostService 调用，避免 lost update。 */
    @Modifying
    @Query("UPDATE Post p SET p.commentCount = COALESCE(p.commentCount, 0) + 1 WHERE p.id = :id")
    int incrementCommentCount(@Param("id") Long id);

    /**
     * 直接用真实收藏数重置 favoriteCount（原子）。
     * toggleFavorite 流程在 add/delete 收藏行后调 favoriteRepository.countByPostId() 拿到
     * 真实的当前行数，再走这个原子 UPDATE 写回，避免两次并发 toggle 都把内存中的 +1/-1 写回 DB 造成丢失更新。
     */
    @Modifying
    @Query("UPDATE Post p SET p.favoriteCount = :count WHERE p.id = :id")
    int setFavoriteCount(@Param("id") Long id, @Param("count") int count);

    /**
     * 条件更新「下架 / 恢复」（v5 / S8）。
     *
     * <p><b>为什么不用 findById + save</b>：那是典型的 read-modify-write ——
     * 两个管理员（或同一个人双击）几乎同时点下架/恢复时，后写者会整行覆盖先写者，
     * 可能出现「status=removed 但 removedAt 是空的」这种自相矛盾的行，
     * 或把对方的操作人/时间戳抹掉。这里改成单条 UPDATE：
     * <ul>
     *   <li>{@code status <> :status} 保证**幂等**：已经是目标状态时影响 0 行，不重复写；</li>
     *   <li>status 与 removedAt 在**同一条语句**里改，不存在中间态；</li>
     *   <li>数据库行锁天然串行化两个并发操作，不需要引入 {@code @Version}。</li>
     * </ul>
     *
     * <p>必须在事务内调用（{@code @Modifying} 需要调用方 {@code @Transactional}）。
     *
     * @return 受影响行数；0 表示该帖子已处于目标状态（或不存在）
     */
    @Modifying
    @Query("UPDATE Post p SET p.status = :status, p.removedAt = :removedAt "
            + "WHERE p.id = :id AND p.status <> :status")
    int updateStatusIfDifferent(@Param("id") Long id,
                                @Param("status") String status,
                                @Param("removedAt") java.time.LocalDateTime removedAt);
}
