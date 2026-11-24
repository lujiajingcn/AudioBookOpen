package com.audiobook.server.repository;

import com.audiobook.server.model.StoreBook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreBookRepository extends JpaRepository<StoreBook, Long> {

    /** 按分类查询（按下载量降序） */
    List<StoreBook> findByCategoryOrderByDownloadCountDesc(String category);

    /** 全部分类及其书籍数量（排除已下架书籍，status 为 NULL 的历史数据按上架计） */
    @Query("SELECT b.category, COUNT(b) FROM StoreBook b WHERE b.category IS NOT NULL " +
           "AND (b.status IS NULL OR b.status <> 'offshelf') GROUP BY b.category ORDER BY COUNT(b) DESC")
    List<Object[]> countByCategory();

    /**
     * 数据库里出现过的全部分类名（供管理端上传时的分类联想）。
     * 与 countByCategory 的区别：不做下架过滤、不分组计数——
     * 分类是否「存在」与其下书籍当前是否上架无关，全部书籍下架的分类仍应可被选中复用，
     * 否则管理员再次上传同类书籍时会误判为新分类、重复建目录。
     * 结果与磁盘目录取并集，见 AdminService.listCategoryNames。
     */
    @Query("SELECT DISTINCT b.category FROM StoreBook b WHERE b.category IS NOT NULL AND b.category <> ''")
    List<String> findDistinctCategories();

    /** 关键词搜索（书名或作者模糊匹配，按下载量降序） */
    @Query("SELECT b FROM StoreBook b WHERE b.title LIKE %:keyword% ESCAPE '\\' OR b.author LIKE %:keyword% ESCAPE '\\' ORDER BY b.downloadCount DESC")
    List<StoreBook> searchByKeyword(@Param("keyword") String keyword);

    /** 关键词搜索分页版（排序与全量版一致，分页下沉到数据库，杜绝全量返回） */
    @Query("SELECT b FROM StoreBook b WHERE b.title LIKE %:keyword% ESCAPE '\\' OR b.author LIKE %:keyword% ESCAPE '\\' ORDER BY b.downloadCount DESC")
    Page<StoreBook> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /** 分类 + 关键词组合搜索 */
    @Query("SELECT b FROM StoreBook b WHERE b.category = :category AND (b.title LIKE %:keyword% ESCAPE '\\' OR b.author LIKE %:keyword% ESCAPE '\\') ORDER BY b.downloadCount DESC")
    List<StoreBook> searchByCategoryAndKeyword(@Param("category") String category, @Param("keyword") String keyword);

    // ---------- 书城用户端分页查询 ----------
    // 以下四个方法把「下架过滤 + 排序 + 分页」全部下沉到 SQL：
    // - 下架条件 (status IS NULL OR status <> 'offshelf') 与 onShelfOnly/countByCategory 语义一致；
    // - 排序声明在 JPQL 内（调用方传无排序的 Pageable），downloadCount 之后追加 id
    //   作 tie-breaker，避免并列下载量时 MySQL 分页顺序漂移导致跨页重复/遗漏。

    /** 全部上架书籍分页（按下载量降序） */
    @Query("SELECT b FROM StoreBook b WHERE (b.status IS NULL OR b.status <> 'offshelf') " +
           "ORDER BY b.downloadCount DESC, b.id ASC")
    Page<StoreBook> findOnShelfAllPaged(Pageable pageable);

    /** 按分类分页查询上架书籍（按下载量降序） */
    @Query("SELECT b FROM StoreBook b WHERE b.category = :category " +
           "AND (b.status IS NULL OR b.status <> 'offshelf') " +
           "ORDER BY b.downloadCount DESC, b.id ASC")
    Page<StoreBook> findOnShelfByCategoryPaged(@Param("category") String category, Pageable pageable);

    /** 关键词搜索分页（书名或作者模糊匹配，仅上架，按下载量降序） */
    @Query("SELECT b FROM StoreBook b WHERE (b.title LIKE %:keyword% ESCAPE '\\' OR b.author LIKE %:keyword% ESCAPE '\\') " +
           "AND (b.status IS NULL OR b.status <> 'offshelf') " +
           "ORDER BY b.downloadCount DESC, b.id ASC")
    Page<StoreBook> searchOnShelfByKeywordPaged(@Param("keyword") String keyword, Pageable pageable);

    /** 分类 + 关键词组合搜索分页（仅上架，按下载量降序） */
    @Query("SELECT b FROM StoreBook b WHERE b.category = :category " +
           "AND (b.title LIKE %:keyword% ESCAPE '\\' OR b.author LIKE %:keyword% ESCAPE '\\') " +
           "AND (b.status IS NULL OR b.status <> 'offshelf') " +
           "ORDER BY b.downloadCount DESC, b.id ASC")
    Page<StoreBook> searchOnShelfByCategoryAndKeywordPaged(@Param("category") String category,
                                                           @Param("keyword") String keyword,
                                                           Pageable pageable);

    /**
     * 标签筛选分页（可与分类、关键词组合）。
     *
     * <p>为什么用「可选参数」而不是再按 category/keyword 有无拆成 4 个方法：
     * 书城用户端本来就有「分类 × 关键词」四种组合，加上标签会变成八种；
     * 每多一个维度就翻倍，方法名会不可维护。两个可选条件出现在 WHERE 里，
     * 调用方不传就是 null，语义等价于「不过滤」：
     * <ul>
     *   <li>{@code :category IS NULL OR b.category = :category} —— 不选分类即不过滤；</li>
     *   <li>{@code :keyword IS NULL OR ... LIKE %} —— 不搜即不过滤。注意 SQL 里
     *       {@code NULL LIKE '%x%'} 求值为 NULL（非 FALSE），但外层是
     *       {@code :keyword IS NULL OR ...}，TRUE OR NULL = TRUE，结果仍然是「不过滤」，
     *       不会把行筛掉；</li>
     *   <li>标签用 EXISTS 子查询而不是 JOIN 关联表：JOIN 会因一本书多标签产生重复行，
     *       分页时同一本书跨页重复出现；EXISTS 语义是「至少有一个该标签」，天然去重。</li>
     * </ul>
     *
     * <p>countQuery 必须显式给：Spring Data 对带子查询的 @Query 无法可靠地自动派生 count，
     * 派生失败会在启动或首次调用时抛错（而不是悄悄算错）。
     */
    @Query(value = "SELECT b FROM StoreBook b WHERE "
            + "(:category IS NULL OR b.category = :category) "
            + "AND (:keyword IS NULL OR b.title LIKE %:keyword% ESCAPE '\\' "
            + "     OR b.author LIKE %:keyword% ESCAPE '\\') "
            + "AND EXISTS (SELECT t.id FROM StoreBookTag t WHERE t.bookId = b.id AND t.tag = :tag) "
            + "AND (b.status IS NULL OR b.status <> 'offshelf') "
            + "ORDER BY b.downloadCount DESC, b.id ASC",
            countQuery = "SELECT COUNT(b) FROM StoreBook b WHERE "
                    + "(:category IS NULL OR b.category = :category) "
                    + "AND (:keyword IS NULL OR b.title LIKE %:keyword% ESCAPE '\\' "
                    + "     OR b.author LIKE %:keyword% ESCAPE '\\') "
                    + "AND EXISTS (SELECT t.id FROM StoreBookTag t WHERE t.bookId = b.id AND t.tag = :tag) "
                    + "AND (b.status IS NULL OR b.status <> 'offshelf')")
    Page<StoreBook> searchOnShelfByTagPaged(@Param("category") String category,
                                            @Param("keyword") String keyword,
                                            @Param("tag") String tag,
                                            Pageable pageable);


    /** 根据分类和文件名查找（用于扫描去重） */
    Optional<StoreBook> findByCategoryAndFileName(String category, String fileName);

    /**
     * 启动扫描对账用的轻量投影：附带 fileSize，供新书入库时的文件大小变更比对。
     * 无 fileSize 的 {@code findScanProjections()} 已由本方法取代并删除（零调用方的死代码）。
     */
    @Query("SELECT b.id, b.category, b.fileName, b.status, b.fileSize FROM StoreBook b")
    List<Object[]> findScanProjectionsWithSize();

    /** 热门书籍（按下载量降序，取前 N 条） */
    List<StoreBook> findTop10ByOrderByDownloadCountDesc();

    /** 最新上架（按创建时间降序） */
    List<StoreBook> findTop10ByOrderByCreatedAtDesc();

    /** 编辑推荐书单（按创建时间降序） */
    List<StoreBook> findByEditorPickTrueOrderByCreatedAtDesc();

    /**
     * 按 id 集合批量查书（书架列表一次取全，避免逐本 findById 造成 N+1）。
     * 不在这里过滤 offshelf —— 书架要能看到「已下架」并给出提示，
     * 若在此过滤掉，用户的书会从书架里无声消失、以为是丢数据。
     */
    List<StoreBook> findByIdIn(java.util.Collection<Long> ids);

    /** 原子递增下载次数（避免并发下的丢失更新） */
    @Modifying
    @Query("UPDATE StoreBook b SET b.downloadCount = COALESCE(b.downloadCount, 0) + 1 WHERE b.id = :id")
    int incrementDownloadCount(@Param("id") Long id);

    /**
     * 扫描所有未合成书（task_id IS NULL），仅返回 id 列；批量合成时用，
     * 避免一次性加载全表实体。下载过 / 已下架 / 已删除的书仍然出现在结果里，
     * 交由 AdminService 在循环里按需再次校验状态。
     */
    @Query("SELECT b.id FROM StoreBook b WHERE b.taskId IS NULL")
    List<Long> findIdsByTaskIdIsNull();

    /**
     * 扫描「上一轮没跑完」的书：绑定的任务处于 partial / failed，仅返回书 id。
     *
     * <p>与 {@link #findIdsByTaskIdIsNull()} 互补 —— 那个只覆盖「从未合成」，
     * 而一次节点抖动就会把书永久留在 partial（例：6513/6517，缺 4 段），
     * 只靠逐本点「继续合成」在大批量下不可行。
     *
     * <p>用子查询而不是交叉连接：{@code StoreBook.taskId} 是裸 Long 字段（没有 JPA 关系），
     * 只能借 {@code IN (SELECT t.id …)} 关联，比 {@code FROM StoreBook b, Task t WHERE …}
     * 更明确，也不会在某些 Hibernate 版本上被当成立即抓取的隐式连接。
     * 状态字面量必须与 {@link com.audiobook.server.model.TaskStatus} 的 value 一致
     * （partial / failed），改枚举值时要同步这里 —— JPQL 无法调用枚举方法。
     *
     * <p>已下架的书不进扫描：它们对用户不可见，补齐只是白烧算力。
     */
    @Query("SELECT b.id FROM StoreBook b "
            + "WHERE (b.status IS NULL OR b.status <> 'offshelf') "
            + "AND b.taskId IN (SELECT t.id FROM Task t WHERE t.status IN ('partial', 'failed')) "
            + "ORDER BY b.id")
    List<Long> findIdsByGapRetryable();

    /**
     * 并发安全的合成任务绑定：仅当该书尚未绑定任何任务（taskId IS NULL）时才写入。
     *
     * <p>存在的意义：调用方在做 {@code book.getTaskId() != null} 检查与本次写入之间，
     * 夹着一整个 {@code TtsService.createTtsTask}（落盘副本 + 建任务，大书可达分钟级）。
     * 两个管理员同时对同一本书点「合成语音」时，两次读都会看到 taskId 为空，
     * 于是各建一个任务；后写者覆盖 task_id，先建的成为永远不会被下载的孤儿任务
     * （白耗算力，且其已落盘的书籍副本无人清理）。
     *
     * <p>把判定下沉到 UPDATE 的 WHERE 里，依赖数据库行锁保证只有一个请求拿到 1 行受影响，
     * 另一个拿到 0 行并由调用方回收自己刚建的任务。
     *
     * @return 受影响行数：1 = 绑定成功；0 = 已被其他请求抢先绑定
     */
    @Modifying
    @Query("UPDATE StoreBook b SET b.taskId = :taskId, b.synthesizedAt = :synthesizedAt, "
            + "b.synthesizedBy = :synthesizedBy WHERE b.id = :id AND b.taskId IS NULL")
    int bindSynthTaskIfUnbound(@Param("id") Long id,
                               @Param("taskId") Long taskId,
                               @Param("synthesizedAt") java.time.LocalDateTime synthesizedAt,
                               @Param("synthesizedBy") String synthesizedBy);

    /**
     * 解绑一次**没成功**的合成任务，让这本书可以重新发起。
     *
     * <p>为什么需要：{@code task_id} 是在建任务时就回绑的（早于异步合成结果），
     * 所以一次失败会把书永久卡在「已合成过，不支持重新合成」。
     *
     * <p>{@code AND b.taskId = :taskId} 是并发闸门：只有「当前绑定的仍是那个失败任务」时才解绑，
     * 否则说明期间有人重新绑定过，本次调用应当失败而不是把别人的绑定清掉。
     *
     * @return 受影响行数：1 = 解绑成功；0 = 绑定已被改变
     */
    @Modifying
    @Query("UPDATE StoreBook b SET b.taskId = NULL, b.synthesizedAt = NULL, b.synthesizedBy = NULL "
            + "WHERE b.id = :id AND b.taskId = :taskId")
    int clearSynthBinding(@Param("id") Long id, @Param("taskId") Long taskId);

    /**
     * 按 task_id 解绑：任务行被删除时清掉书城书上的悬挂引用。
     *
     * <p>为什么需要：{@code DELETE /api/tts/task/{id}} 是通用端点，直接删任务行，
     * 不知道也不关心这个任务被 {@code store_books.task_id} 引用着。删完就留下一条
     * 「task_id 指向不存在的任务」的记录 —— 管理端只能显示「状态未知」，
     * 点合成被 {@code allowsResynthesis(null)} 拒绝，点补齐被 {@code allowsGapRetry(null)} 拒绝，
     * 唯一出路是「删除语音」（它内部会自愈）。悬挂绑定在库里是永久错误数据，
     * 必须在删任务的那一笔事务里一并清掉。
     *
     * <p>{@code WHERE b.taskId = :taskId} 而不是 {@code b.id}：调用方只有任务 id，
     * 而且任务与书是一对一（绑定走 {@link #bindSynthTaskIfUnbound} 的 taskId IS NULL 闸门），
     * 命中行数正常为 0（不是书城任务）或 1。
     *
     * @return 受影响行数：0 = 该任务没被任何书绑定（普通用户任务），1 = 已解绑
     */
    @Modifying
    @Query("UPDATE StoreBook b SET b.taskId = NULL, b.synthesizedAt = NULL, b.synthesizedBy = NULL "
            + "WHERE b.taskId = :taskId")
    int clearSynthBindingByTaskId(@Param("taskId") Long taskId);
}
