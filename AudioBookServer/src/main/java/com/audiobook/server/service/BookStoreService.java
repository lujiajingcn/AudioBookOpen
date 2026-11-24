package com.audiobook.server.service;

import com.audiobook.server.controller.TtsControllerSupport;
import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.StoreBookRepository;
import com.audiobook.server.util.CoverRenderer;
import com.audiobook.server.util.PageUtil;
import com.audiobook.server.util.PathSafetyUtil;
import com.audiobook.server.util.ZipAudioPacker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 书城服务：书籍目录扫描入库、查询、动态封面生成、下载文件定位。
 *
 * 书籍来源：服务端工作目录下的 store_books/<分类>/<书籍文件> 结构，
 * 启动时自动扫描入库（已存在的跳过，文件被删除的下架）。
 */
@Service
public class BookStoreService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BookStoreService.class);

    /** 支持的电子书格式 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "pdf", "epub", "mobi", "md");

    /** 封面配色板（低饱和度、适合阅读的色系） */
    private static final String[] COVER_COLORS = {
            "#8D6E63", "#78909C", "#A1887F", "#90A4AE",
            "#7E57C2", "#5C6BC0", "#42A5F5", "#26A69A",
            "#66BB6A", "#9CCC65", "#D4E157", "#FFB300",
            "#FB8C00", "#F4511E", "#E53935", "#D81B60"
    };

    private final StoreBookRepository storeBookRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final TtsControllerSupport support;
    /** 正文提取器：供「按 id 直接阅读」复用与 /api/tts/extract-content 同一套段落/目录口径 */
    private final BookTextExtractor bookTextExtractor;
    /** 正文快照：书城书籍一旦有音频，阅读页就改读快照，保证正文与音频同源（W1） */
    private final BookContentSnapshotService bookContentSnapshotService;
    /** 书籍标签：列表/榜单返回前批量填充 tags（一次 IN 查询，避免 N+1） */
    private final StoreBookTagService storeBookTagService;

    /** 注入自身代理：scanStoreDirectory 内调用事务子方法 applyDiskSnapshot/reconcileOffShelf
     *  属于自调用，会绕过 AOP 代理使 @Transactional 失效，
     *  必须经代理调用才能让各阶段的短事务边界成立 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private BookStoreService self;

    @Value("${app.store.dir:store_books}")
    private String storeDir;

    /**
     * 统一书城目录解析基准。app.store.dir 配置为相对路径时，一律以
     * 进程工作目录（user.dir）为基准解析为绝对路径，与 TtsControllerSupport.resolveUploadFile
     * 对 uploadDir 的处理语义一致。
     *
     * 按 new File(storeDir) 解析时，基准取决于各入口的 JVM 默认 CWD：从仓库根目录启动与
     * 从 AudioBookServer/ 子目录启动会分别使用根目录和子目录下两份不同的 store_books，
     * 书籍内容漂移不一致，且扫描/上传/删除各走各的目录。规范化后全进程单一目录。
     */
    @PostConstruct
    void normalizeStoreDir() {
        File dir = new File(storeDir);
        if (!dir.isAbsolute()) {
            storeDir = new File(System.getProperty("user.dir"), storeDir).getPath();
        }
        log.info("书城目录解析为: {}", new File(storeDir).getAbsolutePath());
    }

    public BookStoreService(StoreBookRepository storeBookRepository,
                            AudioSegmentRepository audioSegmentRepository,
                            TtsControllerSupport support,
                            BookTextExtractor bookTextExtractor,
                            BookContentSnapshotService bookContentSnapshotService,
                            StoreBookTagService storeBookTagService) {
        this.storeBookRepository = storeBookRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.support = support;
        this.bookTextExtractor = bookTextExtractor;
        this.bookContentSnapshotService = bookContentSnapshotService;
        this.storeBookTagService = storeBookTagService;
    }

    // ---------- 启动时扫描入库 ----------

    /**
     * 应用启动完成后扫描书城目录。
     * 用 ApplicationRunner 而非 @PostConstruct：
     * 1. @PostConstruct 阶段 AOP 代理未就绪，@Transactional 不生效
     * 2. 数据库未就绪时扫描失败不会阻断应用启动
     *
     * 扫描拆为三段，各段幂等、部分失败下次启动自愈：
     * 1. 纯磁盘扫描（无事务、无 DB 访问）：收集文件快照
     * 2. 短事务：新书入库 + 文件大小更新（一次投影查询建索引，替代逐文件查库）
     * 3. 短事务：上架状态对账（基于磁盘快照判定，事务内不再有任何磁盘 IO）
     *
     * 整体包在一个大 @Transactional 里会全程占用数据库连接，且对账阶段在事务内
     * 逐书做磁盘 exists() 检查，任一异常还会把整次扫描全部回滚。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            scanStoreDirectory();
        } catch (Exception e) {
            log.error("书城目录扫描失败（不影响应用启动）: {}", e.getMessage(), e);
        }
    }

    /** 磁盘扫描结果快照：在磁盘上发现的一本受支持格式的书 */
    private record DiskBook(String category, String fileName, long fileSize) {}

    /**
     * 数据库扫描投影行：只承载启动对账需要的列
     *（id/分类/文件名/状态/大小），不加载完整实体——
     * description 等 TEXT 字段扫描时从不读取，无须进内存。
     */
    private record ScanRow(Long id, String category, String fileName, String status, Long fileSize) {
        static ScanRow fromProjection(Object[] row) {
            return new ScanRow((Long) row[0], (String) row[1], (String) row[2],
                    (String) row[3], (Long) row[4]);
        }
    }

    /**
     * 应用启动完成后扫描书城目录。
     * 用 ApplicationRunner 而非 @PostConstruct：
     * 1. @PostConstruct 阶段 AOP 代理未就绪，@Transactional 不生效
     * 2. 数据库未就绪时扫描失败不会阻断应用启动
     *
     * 扫描拆为三段，各段幂等、部分失败下次启动自愈：
     * 1. 纯磁盘扫描（无事务、无 DB 访问）：收集文件快照
     * 2. 短事务：新书入库 + 文件大小更新（一次投影查询建索引，替代逐文件查库）
     * 3. 短事务：上架状态对账（基于磁盘快照判定，事务内不再有任何磁盘 IO）
     *
     * 整体包在一个大 @Transactional 里会全程占用数据库连接，且对账阶段在事务内
     * 逐书做磁盘 exists() 检查，任一异常还会把整次扫描全部回滚。
     */
    public void scanStoreDirectory() {
        File dir = new File(storeDir);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                log.info("书城目录不存在，已创建: {}", dir.getAbsolutePath());
            } else {
                log.warn("书城目录创建失败: {}", dir.getAbsolutePath());
                return;
            }
        }

        log.info("开始扫描书城目录: {}", dir.getAbsolutePath());

        List<DiskBook> diskBooks = new ArrayList<>();
        Set<String> rootFileNames = new HashSet<>();

        File[] categoryDirs = dir.listFiles(File::isDirectory);
        if (categoryDirs != null) {
            for (File categoryDir : categoryDirs) {
                collectSupportedFiles(categoryDir, categoryDir.getName(), diskBooks, null);
            }
        }
        // 根目录下的散书归入"默认分类"；文件名单独记录，供对账时的根目录兜底匹配
        collectSupportedFiles(dir, "默认分类", diskBooks, rootFileNames);

        // 一次投影查询（仅取对账所需列）供两个阶段共用，语义不变且各段仍保持幂等、
        // 部分失败下次启动自愈；两阶段各做一次 findAll() 会两次把全表完整实体载入内存。
        List<ScanRow> scanRows = storeBookRepository.findScanProjectionsWithSize().stream()
                .map(ScanRow::fromProjection)
                .toList();

        int added = self.applyDiskSnapshot(diskBooks, scanRows);
        int offShelved = self.reconcileOffShelf(diskBooks, rootFileNames, scanRows);

        log.info("书城目录扫描完成，新增 {} 本书，下架 {} 本，当前共 {} 本书",
                added, offShelved, storeBookRepository.count());
    }

    /** 纯磁盘扫描：收集受支持格式的电子书文件，不做任何数据库访问 */
    private void collectSupportedFiles(File dir, String category,
                                       List<DiskBook> out, Set<String> rootFileNames) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) continue;
            String name = file.getName();
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex <= 0) continue;

            String extension = name.substring(dotIndex + 1).toLowerCase();
            if (!SUPPORTED_EXTENSIONS.contains(extension)) continue;

            out.add(new DiskBook(category, name, file.length()));
            if (rootFileNames != null) {
                rootFileNames.add(name);
            }
        }
    }

    /**
     * 短事务：按磁盘快照同步数据库 —— 新书入库、文件大小变化则更新。
     * 索引基于投影快照构建（不加载全表实体）；
     * 文件大小更新逐条 findById 精确更新，不让全表实体驻留持久化上下文。
     */
    @Transactional
    public int applyDiskSnapshot(List<DiskBook> diskBooks, List<ScanRow> scanRows) {
        Map<String, ScanRow> byKey = new HashMap<>();
        for (ScanRow row : scanRows) {
            byKey.putIfAbsent(bookKey(row.category(), row.fileName()), row);
        }

        int added = 0;
        for (DiskBook disk : diskBooks) {
            ScanRow existing = byKey.get(bookKey(disk.category(), disk.fileName()));
            if (existing != null) {
                // 更新文件大小（可能文件被替换过）；大小可能为 NULL（历史数据），
                // 变化时按 id 精确取出实体更新，避免依赖全表实体
                Long dbSize = existing.fileSize();
                if (dbSize == null || dbSize != disk.fileSize()) {
                    storeBookRepository.findById(existing.id()).ifPresent(book -> {
                        book.setFileSize(disk.fileSize());
                        storeBookRepository.save(book);
                    });
                }
                continue;
            }

            int dotIndex = disk.fileName().lastIndexOf('.');
            String title = disk.fileName().substring(0, dotIndex);

            StoreBook book = new StoreBook();
            book.setTitle(title);
            book.setAuthor("佚名");
            book.setCategory(disk.category());
            book.setDescription("《" + title + "》");
            book.setFileName(disk.fileName());
            book.setFileExtension(disk.fileName().substring(dotIndex + 1).toLowerCase());
            book.setFileSize(disk.fileSize());
            book.setCoverColor(pickCoverColor(title));

            storeBookRepository.save(book);
            added++;
            log.info("书城新书上架: [{}] {} ({}KB)", disk.category(), title, disk.fileSize() / 1024);
        }
        return added;
    }

    /**
     * 短事务：上架状态对账 —— 磁盘文件已缺失的书置为 offshelf（下架），
     * 文件重新出现的下架书重新置为 onshelf（上架）。返回本次新下架的数量。
     * 磁盘状态完全由入参快照决定，事务内不再逐书做磁盘 IO。
     * 遍历对象为投影快照，状态变更按 id 精确更新单行。
     */
    @Transactional
    public int reconcileOffShelf(List<DiskBook> diskBooks, Set<String> rootFileNames, List<ScanRow> scanRows) {
        Set<String> categoryKeys = diskBooks.stream()
                .map(d -> bookKey(d.category(), d.fileName()))
                .collect(Collectors.toSet());

        int offShelved = 0;
        for (ScanRow row : scanRows) {
            boolean exists = existsOnDisk(row, categoryKeys, rootFileNames);
            if (!exists && !"offshelf".equals(row.status())) {
                storeBookRepository.findById(row.id()).ifPresent(book -> {
                    book.setStatus("offshelf");
                    storeBookRepository.save(book);
                });
                offShelved++;
                log.info("书城书籍下架（磁盘文件缺失）: [{}] {}", row.category(), row.fileName());
            } else if (exists && "offshelf".equals(row.status())) {
                storeBookRepository.findById(row.id()).ifPresent(book -> {
                    book.setStatus("onshelf");
                    storeBookRepository.save(book);
                });
                log.info("书城书籍重新上架（磁盘文件恢复）: [{}] {}", row.category(), row.fileName());
            }
        }
        return offShelved;
    }

    /**
     * 基于扫描快照判断书籍文件是否存在（与原逐书磁盘检查语义一致）：
     * 先查「分类目录/文件名」，再兜底查根目录散文件；
     * category 为 NULL 的历史数据直接按根目录匹配。
     */
    private static boolean existsOnDisk(ScanRow book, Set<String> categoryKeys, Set<String> rootFileNames) {
        String category = book.category();
        if (category != null && categoryKeys.contains(bookKey(category, book.fileName()))) {
            return true;
        }
        return rootFileNames.contains(book.fileName());
    }

    /** 去重索引键：同分类同文件名视为同一本书 */
    private static String bookKey(String category, String fileName) {
        return (category == null ? "" : category) + "/" + fileName;
    }

    // ---------- 查询 ----------

    /**
     * 书籍列表。
     *
     * @param page 从 1 开始；为 null 时返回全量（兼容 Android 端）
     * @param size 每页条数
     * @return 分页结果：books(当页数据) + total(过滤后的总条数)
     */
    public record BookPage(List<StoreBook> books, long total) {}

    public BookPage listBooksPaged(String category, String keyword, Integer page, Integer size) {
        return listBooksPaged(category, keyword, null, page, size);
    }

    /**
     * 书籍列表（含标签筛选）。
     *
     * @param category 可选，分类名
     * @param keyword  可选，书名/作者模糊搜索
     * @param tag      可选，标签名（精确匹配，大小写不敏感在写入侧已归一）
     * @param page     从 1 开始；为 null 时返回全量（兼容 Android 端旧版本）
     * @param size     每页条数
     */
    public BookPage listBooksPaged(String category, String keyword, String tag,
                                   Integer page, Integer size) {
        boolean hasCategory = category != null && !category.isBlank();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        boolean hasTag = tag != null && !tag.isBlank();

        // 分页 size 上限钳制：size 来自前端，不设上限会被一次拉全表打爆内存，
        // 统一走 PageUtil（与各 Controller / Service 的既有规范一致）。
        // 仅当 size 确实有值时才钳制，保持「不传 page/size 返回全量」的客户端契约不变。
        Integer clampedSize = PageUtil.clampSizeOrNull(size);

        // 兼容旧客户端（未传 page/size）：契约就是返回全量，此路径全量加载不可避免，
        // 与「分页」无关；分页请求一律走下方数据库分页分支。
        // 标签过滤在内存里做：这条路径本来就把全表载进内存了，
        // 顺便用已经填好的 tags 过滤，比再发一次带子查询的查询更省。
        if (page == null || size == null || page < 1 || size < 1) {
            List<StoreBook> all = listBooksRaw(category, keyword);
            storeBookTagService.attachTags(all);
            List<StoreBook> filtered = filterByTag(all, hasTag ? tag.trim() : null);
            return new BookPage(filtered, filtered.size());
        }

        // 筛选、下架过滤、排序、分页全部下沉到 SQL，只取当页数据；
        // 先全量加载（无筛选时 findAll() 全表实体）再内存 subList 分页会无谓地吃掉内存。
        // 排序已在 JPQL 内声明（与管理端搜索同一模式），此处传无排序的 Pageable。
        // 关键词先转义通配符，避免用户输入 %/_ 触发 SQL LIKE 通配语义。
        Pageable pageable = PageRequest.of(page - 1, clampedSize);
        Page<StoreBook> result;
        String safeKeyword = hasKeyword ? escapeLikeKeyword(keyword.trim()) : null;
        if (hasTag) {
            // 标签筛选：分类与关键词作为可选条件一并下推（不传即不过滤）。
            // 不按 2×2 组合各写一个仓储方法，否则每加一个筛选维度就翻倍。
            result = storeBookRepository.searchOnShelfByTagPaged(
                    hasCategory ? category : null, safeKeyword, tag.trim(), pageable);
        } else if (hasCategory && hasKeyword) {
            result = storeBookRepository.searchOnShelfByCategoryAndKeywordPaged(category, safeKeyword, pageable);
        } else if (hasCategory) {
            result = storeBookRepository.findOnShelfByCategoryPaged(category, pageable);
        } else if (hasKeyword) {
            result = storeBookRepository.searchOnShelfByKeywordPaged(safeKeyword, pageable);
        } else {
            result = storeBookRepository.findOnShelfAllPaged(pageable);
        }
        // 当页标签一次 IN 查询补齐（不填的话前端拿不到 tags 字段）
        storeBookTagService.attachTags(result.getContent());
        // total 恒为过滤后的总条数（由 COUNT 查询给出），前端据此计算总页数
        return new BookPage(result.getContent(), result.getTotalElements());
    }

    /** 内存标签过滤（仅全量兼容路径使用，tag 为 null 时原样返回） */
    private static List<StoreBook> filterByTag(List<StoreBook> books, String tag) {
        if (tag == null || tag.isBlank()) {
            return books;
        }
        return books.stream()
                .filter(b -> b.getTags().stream().anyMatch(t -> t.equalsIgnoreCase(tag)))
                .toList();
    }

    private List<StoreBook> listBooksRaw(String category, String keyword) {
        boolean hasCategory = category != null && !category.isBlank();
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String safeKeyword = hasKeyword ? escapeLikeKeyword(keyword.trim()) : null;

        if (hasCategory && hasKeyword) {
            return onShelfOnly(storeBookRepository.searchByCategoryAndKeyword(category, safeKeyword));
        }
        if (hasCategory) {
            return onShelfOnly(storeBookRepository.findByCategoryOrderByDownloadCountDesc(category));
        }
        if (hasKeyword) {
            return onShelfOnly(storeBookRepository.searchByKeyword(safeKeyword));
        }
        return onShelfOnly(storeBookRepository.findAll());
    }

    /**
     * 过滤已下架书籍。
     * 扫描时把磁盘文件缺失的书置为 offshelf 后，还必须在所有对外查询里排除它们，
     * 否则「下架」只是一个数据库标记，用户列表照旧能看到并点进去下载失败。
     * status 为 NULL 的历史数据按上架处理。
     */
    private static List<StoreBook> onShelfOnly(List<StoreBook> books) {
        return books.stream()
                .filter(b -> !"offshelf".equals(b.getStatus()))
                .toList();
    }

    public List<Object[]> listCategories() {
        return storeBookRepository.countByCategory();
    }

    public Optional<StoreBook> getBook(Long id) {
        return storeBookRepository.findById(id);
    }

    /**
     * 书籍详情（附标签）。详情页要展示标签，而 {@link #getBook} 被封面/下载/合成等多条
     * 链路复用，不该让它们都多付一次标签查询 —— 所以标签只在这条详情链路上补。
     */
    public Optional<StoreBook> getBookWithTags(Long id) {
        Optional<StoreBook> book = storeBookRepository.findById(id);
        book.ifPresent(b -> storeBookTagService.attachTags(b));
        return book;
    }

    public List<StoreBook> getHotBooks() {
        return withTags(onShelfOnly(storeBookRepository.findTop10ByOrderByDownloadCountDesc()));
    }

    public List<StoreBook> getNewBooks() {
        return withTags(onShelfOnly(storeBookRepository.findTop10ByOrderByCreatedAtDesc()));
    }

    /**
     * 编辑推荐书单：editor_pick=true 且在售的书籍，按创建时间倒序。
     * 书城首页「编辑推荐 / 热门书单」入口的数据源；空列表时前端隐藏该区块。
     */
    public List<StoreBook> getEditorPicks() {
        return withTags(onShelfOnly(storeBookRepository.findByEditorPickTrueOrderByCreatedAtDesc()));
    }

    /** 在售书籍的标签聚合（标签栏：标签名 + 书籍数），按书籍数降序 */
    public List<StoreBookTagService.TagCount> listTagCounts() {
        return storeBookTagService.listOnShelfTagCounts();
    }

    /** 给一批书补上 tags 字段（榜单/列表都要展示标签，一次 IN 查询批量补齐） */
    private List<StoreBook> withTags(List<StoreBook> books) {
        storeBookTagService.attachTags(books);
        return books;
    }

    // ---------- 直接阅读：按书籍 id 提取全文 ----------

    /** 按 id 提取书籍正文的结果：段落 + 目录 + 扩展名 */
    public record StoreBookContent(BookTextExtractor.BookContent content, String title) {}

    /** 正文来源：合成时的快照（与音频同源） */
    public static final String SOURCE_SNAPSHOT = "snapshot";
    /** 正文来源：按当前磁盘文件实时提取（无音频的书，或尚无快照） */
    public static final String SOURCE_LIVE = "live";

    /**
     * 阅读页正文的返回体。
     *
     * @param content   正文
     * @param source    {@link #SOURCE_SNAPSHOT} / {@link #SOURCE_LIVE}
     * @param staleFile 磁盘文件已与快照不同（仍返回快照：那才是音频对应的文字）
     */
    public record ReadingContent(StoreBookContent content, String source, boolean staleFile) {}

    /**
     * 阅读页要用的正文：**有音频时优先读合成时的快照**（W1）。
     *
     * <p>为什么不能一律实时提取：音频是合成就定死的，而正文是每次请求按当前磁盘文件重新提取的。
     * 文件一旦被替换（管理员更新版本 / 重传同名文件），两边就各说各话，
     * 播放第 N 段却高亮到别的句子 —— 而且不报错。
     *
     * <p>返回值的三个字段：
     * <ul>
     *   <li>{@code content}：正文（快照或实时提取）；</li>
     *   <li>{@code source}：{@code snapshot} / {@code live}，便于排查与前端提示；</li>
     *   <li>{@code staleFile}：磁盘文件已与快照不是同一份（大小不同）。此时仍返回快照 ——
     *       因为快照才是「这些音频对应的文字」，前端据此提示用户「文件已更新」。</li>
     * </ul>
     *
     * @return 文件缺失/无法解析时返回 null（与 {@link #extractReadableContent} 语义一致）
     */
    public ReadingContent readableContentForReading(StoreBook book) {
        if (book.getTaskId() != null) {
            Optional<BookContentSnapshotService.Snapshot> snapshot =
                    bookContentSnapshotService.load(book.getId());
            if (snapshot.isPresent() && book.getTaskId().equals(snapshot.get().taskId())) {
                File current = locateBookFile(book);
                boolean stale = BookContentSnapshotService.isFileChanged(
                        snapshot.get().fileSize(), current == null ? -1L : current.length());
                if (stale) {
                    log.info("书城书籍文件已变更，按快照返回（音频来自旧版本）: bookId={}, 快照大小={}, 当前={}B",
                            book.getId(), snapshot.get().fileSize(),
                            current == null ? -1L : current.length());
                }
                return new ReadingContent(
                        new StoreBookContent(snapshot.get().content(), book.getTitle()),
                        SOURCE_SNAPSHOT, stale);
            }
        }
        // 无快照可依（无音频的书，或有音频但快照缺失）→ 实时提取。
        // 走带缓存的入口：这类书每次打开都要重解析整本（PDF 实测 10s），
        // 而「打开→返回→再打开」拿到的是同一份内容，不该重复付这个代价。
        StoreBookContent live = extractReadableContentCached(book);
        return live == null ? null : new ReadingContent(live, SOURCE_LIVE, false);
    }

    /**
     * 按书籍 id 提取可直接阅读的正文（段落 + 目录）。
     *
     * <p>与 {@code GET /api/store/book/{id}/download} 同一套定位与校验口径：
     * 书不存在 / 已下架 / 磁盘文件缺失都在此拦截，返回 {@code null} 由控制器给出对应状态码。
     *
     * <p>本方法<b>不</b>做 @Transactional：
     * ① {@code resolveBookFile} 内含磁盘 exists() 检查，与本工程「事务内不做磁盘 IO」的口径不符；
     * ② 整本电子书文本提取（TXT/PDF/EPUB/MOBI 解析 + 切段）是秒级 CPU/IO 密集操作，
     *    放进事务会把数据库连接按住整个解析时长。这两点都决定了提取必须在事务外做，
     *    而本方法也确实是纯读路径、没有任何写库动作。
     *
     * <p>注意不要复用 {@code resolveBookFile}：它顺带自增下载次数，
     * 「打开来读」不是「下载」，复用会让下载榜被阅读行为刷高。
     *
     * @return 提取结果；书不存在 / 已下架 / 文件缺失返回 null
     */
    public StoreBookContent extractReadableContent(StoreBook book) {
        File file = locateBookFile(book);
        if (file == null) {
            return null;
        }
        String extension = book.getFileExtension();
        if (extension == null) {
            // 兜底：file_extension 为 NULL 的历史数据，从文件名推
            int dot = book.getFileName() == null ? -1 : book.getFileName().lastIndexOf('.');
            if (dot < 0 || dot == book.getFileName().length() - 1) {
                log.warn("书城书籍缺少可识别的扩展名，无法提取正文: bookId={}, fileName={}",
                        book.getId(), book.getFileName());
                return null;
            }
            extension = book.getFileName().substring(dot + 1);
        }
        try {
            return new StoreBookContent(
                    bookTextExtractor.extractBookContent(file, extension), book.getTitle());
        } catch (IOException e) {
            // 典型来源：加密 PDF、扫描件 PDF、损坏的 EPUB/MOBI。
            // 抛出受检异常会让控制器只能笼统回 500，这里收窄成 null 让调用方区分
            // 「文件缺失」与「文件在但读不出内容」两种语义，前端提示才准确。
            log.warn("书城书籍正文提取失败: bookId={}, file={}, 原因: {}",
                    book.getId(), file.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 实时提取结果的<b>进程内缓存</b>（按书 id 缓存）。
     *
     * <p><b>为什么需要</b>：无音频的书（{@code task_id IS NULL}）走的是
     * {@link #extractReadableContent} 的实时提取路径 —— 每次打开都要把整本书重新解析一遍。
     * PDF 尤其昂贵：《无穷的开始》401 页实测 <b>10s</b>（有快照的书只要 85ms）。
     * 用户「打开书 → 返回 → 再打开」就会连续等两次 10s，而两次拿到的是<b>同一份内容</b>。
     *
     * <p><b>为什么不用快照表</b>：快照的语义是「正文与音频同源」（音频是合成时定死的），
     * 而这些书<b>根本没有音频</b>，没有「同源」约束需要满足；把它们的正文塞进
     * {@code book_content_snapshots} 会污染那张表的语义（它按 {@code store_books.task_id} 对齐）。
     * 进程内缓存不落库，天然只服务于「同一本书被反复打开」这一真实场景。
     *
     * <p><b>失效判据</b>：磁盘文件被替换（管理员更新版本 / 重传同名文件）时必须重提取，
     * 否则读到旧内容且不报错。判据用 <b>字节数 + 最后修改时间</b>：
     * 字节数沿用快照那条路的既有口径（{@link BookContentSnapshotService#isFileChanged}），
     * 再加上修改时间以覆盖「同尺寸替换」这一少数情况。
     *
     * <p><b>容量</b>：整本书的正文（段落 + 原文行 + 行内段区间）可达数 MB
     * （实测 401 页 PDF 的响应体就有 2.9MB，对象图更大），
     * 无上限会撑爆堆（生产 {@code -Xmx1g}）。这里限制条目数，超出后淘汰最久未用的。
     */
    private static final int LIVE_CONTENT_CACHE_MAX_ENTRIES = 4;

    /**
     * 实时提取缓存条目：内容 + 提取时磁盘文件的指纹（判定是否已过期）+ 写入时刻（判定淘汰次序）。
     *
     * @param fileSize     提取时磁盘文件的字节数
     * @param fileModified 提取时磁盘文件的最后修改时间（毫秒）
     * @param cachedAt     本条目的写入时刻（毫秒），用于 LRU 淘汰
     */
    private record LiveContentEntry(long fileSize, long fileModified, long cachedAt,
                                    StoreBookContent content) {}

    private final Map<Long, LiveContentEntry> liveContentCache = new ConcurrentHashMap<>();

    /** 每本书一把锁：保证同一本书同一时刻只有一次提取在跑（避免并发重复解析整本 PDF）。 */
    private final Map<Long, Object> liveContentLocks = new ConcurrentHashMap<>();

    /**
     * 实时提取正文（带缓存）。
     *
     * <p>缓存命中时直接返回；未命中则提取后写入。
     * 同一本书的并发请求会重复提取（PDF 场景是 10s 级的重复劳动，且会互相抢 CPU），
     * 因此这里对同一本书做<b>串行化</b>：后来的请求等待前一个的结果，而不是各自重算。
     */
    public StoreBookContent extractReadableContentCached(StoreBook book) {
        Long bookId = book.getId();
        if (bookId == null) {
            return extractReadableContent(book);
        }
        File file = locateBookFile(book);
        if (file == null) {
            return null;
        }
        long size = file.length();
        long modified = file.lastModified();

        LiveContentEntry hit = liveContentCache.get(bookId);
        if (hit != null && hit.fileSize() == size && hit.fileModified() == modified) {
            return hit.content();
        }

        // 同书并发：按书 id 加锁，保证同一本书同一时刻只有一次提取在跑。
        // 锁对象从 map 取（不 new Object()），避免为每本书分配一把一次性锁。
        Object lock = liveContentLocks.computeIfAbsent(bookId, k -> new Object());
        synchronized (lock) {
            // 等锁期间可能已被别的线程填好缓存，再判一次
            hit = liveContentCache.get(bookId);
            if (hit != null && hit.fileSize() == size && hit.fileModified() == modified) {
                return hit.content();
            }
            StoreBookContent content = extractReadableContent(book);
            if (content != null) {
                liveContentCache.put(bookId,
                        new LiveContentEntry(size, modified, System.currentTimeMillis(), content));
                evictLiveContentCacheIfNeeded();
            }
            return content;
        }
    }

    /** 缓存条目数超限时淘汰最久未写入的条目（LRU 近似：按写入时刻升序）。 */
    private void evictLiveContentCacheIfNeeded() {
        while (liveContentCache.size() > LIVE_CONTENT_CACHE_MAX_ENTRIES) {
            Long oldestKey = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<Long, LiveContentEntry> e : liveContentCache.entrySet()) {
                if (e.getValue().cachedAt() < oldestAt) {
                    oldestAt = e.getValue().cachedAt();
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey == null) {
                return;
            }
            liveContentCache.remove(oldestKey);
        }
    }

    /**
     * 重建某本书的正文快照（`book_content_snapshots`）。
     *
     * <p><b>为什么需要这个入口</b>：快照只由 {@code VoiceCloningTtsService} 在「服务端合成」收尾时
     * 写入。但书城里的书还有另一条来源 —— 本地工具合成好音频后直接写入 {@code store_books.task_id}
     * （见 AudioBookLocalTtsTool 的发布流程），它不经过服务端合成，于是**永远没有快照**。
     * 结果就是打开这本书时每次都要实时解析整本 PDF（实测国富论 9.2s，有快照的书 95ms）。
     *
     * <p>快照与音频必须同源：所以只允许已绑定 {@code task_id} 的书重建，且写入的 task_id
     * 就是 {@code store_books.task_id}（{@code readableContentForReading} 会校验两者一致，
     * 不一致就退回实时解析）。
     *
     * @return 重建结果（段落数/行数/记录的文件字节数）；书不存在或文件缺失/读不出内容返回 null
     * @throws IllegalStateException 书还没有绑定合成任务（快照无从对齐）
     */
    public SnapshotRebuild rebuildContentSnapshot(Long storeBookId) {
        StoreBook book = storeBookRepository.findById(storeBookId).orElse(null);
        if (book == null) {
            return null;
        }
        if (book.getTaskId() == null) {
            throw new IllegalStateException(
                    "这本书还没有绑定合成任务：正文快照要与音频同源，请先把 store_books.task_id 指向已合成任务");
        }
        StoreBookContent content = extractReadableContent(book);
        if (content == null) {
            return null;
        }
        File file = locateBookFile(book);
        long fileSize = file != null
                ? file.length()
                : (book.getFileSize() == null ? -1L : book.getFileSize());
        bookContentSnapshotService.save(book.getId(), book.getTaskId(), fileSize, content.content());
        BookTextExtractor.BookContent raw = content.content();
        int paragraphCount = raw.paragraphs() == null ? 0 : raw.paragraphs().size();
        int rowCount = raw.rows() == null ? 0 : raw.rows().size();
        log.info("手动重建正文快照: bookId={}, taskId={}, 段落={}, 行={}, 文件={}B",
                book.getId(), book.getTaskId(), paragraphCount, rowCount, fileSize);
        return new SnapshotRebuild(paragraphCount, rowCount, fileSize, content.title());
    }

    /** {@link #rebuildContentSnapshot(Long)} 的结果。 */
    public record SnapshotRebuild(int paragraphCount, int rowCount, long fileSize, String title) {}

    /**
     * 定位书籍文件，<b>不</b>自增下载次数。
     *
     * <p>与 {@link #resolveBookFile(StoreBook)} 的区别仅在于副作用：那条路径供「下载电子书」用，
     * 必须计数；本条供「打开阅读」用，不该计数。两者共用同一套目录解析与
     * {@link PathSafetyUtil#assertWithinParent} 越界防御，避免两条路径的定位口径漂移。
     */
    private File locateBookFile(StoreBook book) {
        String category = book.getCategory();
        File file = category != null
                ? new File(new File(storeDir, category), book.getFileName())
                : new File(storeDir, book.getFileName());
        if (!file.exists()) {
            // 兼容"默认分类"直接放在根目录的情况
            file = new File(storeDir, book.getFileName());
        }
        if (!file.exists()) {
            log.warn("书城书籍文件缺失: {}/{}", book.getCategory(), book.getFileName());
            return null;
        }
        try {
            PathSafetyUtil.assertWithinParent(new File(storeDir), file);
        } catch (IOException e) {
            log.warn("书城书籍文件路径越界，拒绝读取: {}", file.getPath());
            return null;
        }
        return file;
    }

    // ---------- 下载 ----------

    /**
     * 定位书籍文件并累加下载次数（使用原子更新避免并发丢失）。
     *
     * 本方法不标注 @Transactional：内部两次 file.exists() 属磁盘 IO，
     * 与本工程「事务内不做磁盘 IO」的口径不符（量级虽小，但同样可能让连接被慢磁盘
     * 拖住）。因此磁盘定位在事务外完成，计数自增交给 {@link #incrementDownloadCount(Long)}
     * 的短事务（经 self 代理调用，避免自调用绕过 AOP 使 @Transactional 失效）。
     *
     * @return 书籍文件；不存在返回 null
     */
    public File resolveBookFile(StoreBook book) {
        // category 为 NULL 的历史数据按根目录查找（new File(storeDir, null) 会抛 NPE）
        String category = book.getCategory();
        File file = category != null
                ? new File(new File(storeDir, category), book.getFileName())
                : new File(storeDir, book.getFileName());
        if (!file.exists()) {
            // 兼容"默认分类"直接放在根目录的情况
            file = new File(storeDir, book.getFileName());
        }
        if (!file.exists()) {
            log.warn("书城书籍文件缺失: {}/{}", book.getCategory(), book.getFileName());
            return null;
        }
        // 纵深防御：确认解析出的文件确实位于书城目录内。当前 category/fileName
        // 来自 DB 且写入时经 sanitize + assertWithinParent 净化，但未来若新增直接写这些列的入口
        // 未复用净化逻辑，可形成目录穿越。与 AdminService.assertWithinParent 口径一致。
        try {
            PathSafetyUtil.assertWithinParent(new File(storeDir), file);
        } catch (IOException e) {
            log.warn("书城书籍文件路径越界，拒绝下载: {}", file.getPath());
            return null;
        }
        // 使用原子 UPDATE 避免并发下的丢失更新；短事务内只有这一条 UPDATE，不含任何磁盘 IO。
        // 计数自增异常（如 DB 瞬时抖动）不应阻断本可成功的下载：吞咽后继续返回文件。
        try {
            self.incrementDownloadCount(book.getId());
        } catch (Exception e) {
            log.warn("书城下载计数自增失败（不影响下载）: bookId={}, 原因: {}", book.getId(), e.getMessage());
        }
        return file;
    }

    /**
     * 短事务：原子自增下载次数。
     * storeBookRepository.incrementDownloadCount 是 @Modifying 更新，没有自带事务，
     * 缺少活动事务会直接抛 TransactionRequiredException——所以这里必须显式开事务，
     * 而不能随 resolveBookFile 一起把 @Transactional 删掉后裸调。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementDownloadCount(Long bookId) {
        storeBookRepository.incrementDownloadCount(bookId);
    }

    // ---------- 动态封面 ----------

    /**
     * 动态绘制书籍封面（300x400 PNG）：纯色背景 + 居中书名 + 底部署名。
     * 具体绘制（含内置中文字体的加载与缺字告警）在 {@link CoverRenderer}，
     * 抽出去是为了能脱离 Spring 上下文单测「中文是不是真的画得出来」。
     */
    public byte[] generateCover(StoreBook book) throws IOException {
        return CoverRenderer.render(book.getTitle(), book.getAuthor(), book.getCoverColor());
    }

    /** 按书名 hash 从配色板中稳定取色 */
    private String pickCoverColor(String title) {
        // Math.abs(Integer.MIN_VALUE) 仍为负数，会导致数组越界 500；
        // 改用位掩码清除符号位，取模索引恒为非负
        int hash = title == null ? 0 : (title.hashCode() & 0x7fffffff);
        return COVER_COLORS[hash % COVER_COLORS.length];
    }

    // ---------- 自定义封面 ----------

    /** 封面图片存放目录（store_books/covers/） */
    private File coverDir() {
        File dir = new File(storeDir, "covers");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 定位自定义封面文件（不存在返回 null） */
    public File resolveCoverFile(StoreBook book) {
        if (book.getCoverImage() == null || book.getCoverImage().isEmpty()) {
            return null;
        }
        File file = new File(coverDir(), book.getCoverImage());
        // 纵深防御：封面文件名来自 DB，新增写入入口时可能携带穿越字符，
        // 校验确在 covers 目录内，与 resolveBookFile / AdminService 口径一致。
        try {
            PathSafetyUtil.assertWithinParent(coverDir(), file);
        } catch (IOException e) {
            log.warn("封面文件路径越界，拒绝访问: {}", book.getCoverImage());
            return null;
        }
        return file.exists() ? file : null;
    }

    /**
     * 获取封面字节：优先返回自定义封面图片，否则用 AWT 动态生成。
     */
    public byte[] getCover(StoreBook book) throws IOException {
        File custom = resolveCoverFile(book);
        if (custom != null) {
            return java.nio.file.Files.readAllBytes(custom.toPath());
        }
        return generateCover(book);
    }

    /**
     * 转义 SQL LIKE 通配符：用户输入的 % / _ 会被 LIKE 当作通配符，
     * 例如搜索 "%" 返回全部、搜索 "_" 匹配任意单字符，造成语义异常。
     * 必须先转义反斜杠，再转义 % 与 _；配合 StoreBookRepository 各查询的 ESCAPE '\\' 子句。
     */
    private static String escapeLikeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---------- 书级合成音频下载（用户端 GET /api/store/book/{id}/audio/download） ----------

    /**
     * 流式把书关联的合成任务里所有"已完成"片段打成 zip 包写入 HTTP 响应。
     *
     * <p>与 {@code TtsDownloadController.downloadAllAudio} 的区别：本方法<b>不校验任务归属</b>——
     * 合成任务是管理员身份提交的（adminExempt 免积分），但产物以 {@code store_books.task_id} 挂在书下，
     * 供所有登录用户下载。归属校验在 downloadBook 处已隐含（书存在 + 在售），无需再做 user 校验。
     *
     * <p>取音频来源：只从磁盘文件读取（{@code audio_data} 列当前恒为 NULL，不再作为回退）。
     * 缺失的段记为未完成；若整本无可打包内容，交由调用方写错误 JSON，
     * 避免 zip 的 content-type 让前端"看起来下载成功"、用户却拿到空包。
     *
     * <p>失败处理：书不存在 / 已下架 / 未合成 / 任务空 / 任务尚未完成，均交由调用方写错误 JSON 后 return；
     * 本方法不抛业务异常（流式响应已写头后无法再回退）。
     */
    public void streamStoreAudioZip(Long bookId, HttpServletResponse response) throws IOException {
        StoreBook book = storeBookRepository.findById(bookId).orElse(null);
        if (book == null) {
            support.writeErrorJson(response, HttpServletResponse.SC_NOT_FOUND, "书籍不存在");
            return;
        }
        if ("offshelf".equals(book.getStatus())) {
            support.writeErrorJson(response, HttpServletResponse.SC_NOT_FOUND, "书籍已下架");
            return;
        }
        Long taskId = book.getTaskId();
        if (taskId == null) {
            support.writeErrorJson(response, HttpServletResponse.SC_NOT_FOUND, "本书尚未合成音频");
            return;
        }

        List<AudioSegmentRepository.SegmentMeta> segments =
                audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);
        if (segments.isEmpty()) {
            support.writeErrorJson(response, HttpServletResponse.SC_NOT_FOUND, "没有可下载的音频片段");
            return;
        }
        long completedCount = segments.stream()
                .filter(s -> SegmentStatus.COMPLETED.value().equals(s.getStatus()))
                .count();
        if (completedCount == 0) {
            support.writeErrorJson(response, HttpServletResponse.SC_BAD_REQUEST, "合成尚未完成，请稍后重试");
            return;
        }

        // 用书名作 zip 文件名（去除扩展名），UTF-8 URL 编码；与 TtsDownloadController.downloadAllAudio 同口径
        String encodedName;
        try {
            encodedName = URLEncoder.encode(book.getTitle(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            encodedName = "book_" + bookId;
        }
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"audio_book_" + bookId + ".zip\"; filename*=UTF-8''audio_" + encodedName + ".zip");

        ZipAudioPacker.PackResult packResult = ZipAudioPacker.pack(
                segments,
                response.getOutputStream(),
                segment -> {
                    if (segment.getAudioFilePath() == null) {
                        return null;
                    }
                    // 一级来源：磁盘文件（正常路径，合成时音频都落了盘）；
                    // 解析失败（含目录穿越拦截）时回退 BLOB，不打断整体打包
                    try {
                        File resolved = support.resolveUploadFile(segment.getAudioFilePath());
                        if (resolved.exists() && resolved.isFile() && resolved.length() > 0) {
                            return resolved;
                        }
                    } catch (Exception e) {
                        log.warn("解析音频段文件失败，尝试回退 BLOB: segmentId={}, error={}",
                                segment.getId(), e.getMessage());
                    }
                    return null;
                },
                audioSegmentRepository::findAudioDataById);

        for (Long skippedId : packResult.skippedSegmentIds()) {
            log.warn("书级音频下载跳过无来源段（磁盘与 BLOB 均缺失）: bookId={}, segmentId={}",
                    bookId, skippedId);
        }
        // 头已写出，此处无法再改状态码；successCount==0 属数据异常（文件与 BLOB 同时丢失），
        // 记 ERROR 让运维能在日志里发现，前端侧只能拿到一个空 zip
        if (packResult.successCount() == 0) {
            log.error("书级音频下载产物为空: bookId={}, taskId={}, 已完成段数={}",
                    bookId, taskId, completedCount);
        } else {
            log.info("书级音频下载: bookId={}, taskId={}, 成功打包 {}/{} 个片段",
                    bookId, taskId, packResult.successCount(), segments.size());
        }
    }
}
