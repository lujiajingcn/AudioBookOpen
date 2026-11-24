package com.audiobook.server.repository;

import com.audiobook.server.model.StoreBookTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface StoreBookTagRepository extends JpaRepository<StoreBookTag, Long> {

    /** 单本书的标签（新建在前，展示顺序稳定） */
    List<StoreBookTag> findByBookIdOrderByIdAsc(Long bookId);

    /**
     * 批量取多本书的标签：书城列表一页 20~24 本，逐本查会打出 20+ 个请求（N+1）。
     * 调用方按 bookId 分组后填回各书，见 {@code StoreBookTagService.attachTags}。
     */
    List<StoreBookTag> findByBookIdInOrderByIdAsc(Collection<Long> bookIds);

    /**
     * 在售书籍的标签聚合：标签 → 书籍数。
     * 用子查询而不是 JOIN 关联实体（两者没有 JPA 关系，只能写 ON 子句的 entity join，
     * 在不同 Hibernate 版本上容易被当成立即抓取的隐式连接）。
     *
     * <p>status 为 NULL 的历史数据按上架计，与 {@code BookStoreService.onShelfOnly} 口径一致；
     * 下架书不计入标签数，否则用户点进去看到的数量与列表条数对不上。
     */
    @Query("SELECT t.tag, COUNT(t) FROM StoreBookTag t "
            + "WHERE t.bookId IN (SELECT b.id FROM StoreBook b "
            + "WHERE b.status IS NULL OR b.status <> 'offshelf') "
            + "GROUP BY t.tag ORDER BY COUNT(t) DESC, t.tag ASC")
    List<Object[]> countOnShelfTags();

    /**
     * 库里出现过的全部标签名（管理端编辑时的联想词）。
     * 不做下架过滤、不计数 —— 标签是否「存在」与其下书籍当前是否在售无关，
     * 全部书籍都下架了的标签仍应可被复用，否则管理员会重复发明同一个标签。
     */
    @Query("SELECT DISTINCT t.tag FROM StoreBookTag t ORDER BY t.tag ASC")
    List<String> findDistinctTagNames();

    /** 删书时清理该书全部标签（与删书同一个短事务，见 AdminService.deleteBookInTx） */
    @Modifying
    @Transactional
    @Query("DELETE FROM StoreBookTag t WHERE t.bookId = :bookId")
    int deleteByBookId(@Param("bookId") Long bookId);

    /** 批量删书时清理标签 */
    @Modifying
    @Transactional
    @Query("DELETE FROM StoreBookTag t WHERE t.bookId IN :bookIds")
    int deleteByBookIdIn(@Param("bookIds") Collection<Long> bookIds);

    /** 全量替换标签前的存在性判定（前端重复提交同一批标签时走「无变化」短路） */
    boolean existsByBookId(Long bookId);
}
