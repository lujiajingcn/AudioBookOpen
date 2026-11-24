package com.audiobook.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.audiobook.server.model.ReferenceAudio;
import com.audiobook.server.model.ReferenceAudioTopic;
import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.TaskStatus;
import com.audiobook.server.model.StoreBook;
import com.audiobook.server.model.TempReferenceAudio;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserReferenceAudio;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.ReferenceAudioRepository;
import com.audiobook.server.repository.ReferenceAudioTopicRepository;
import com.audiobook.server.repository.StoreBookRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.repository.TempReferenceAudioRepository;
import com.audiobook.server.repository.UserReferenceAudioRepository;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.filter.JwtAuthenticationFilter;
import com.audiobook.server.service.RedisTaskService;
import com.audiobook.server.util.AudioVideoUtil;
import com.audiobook.server.util.PathSafetyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.Tuple;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** 书城支持的电子书格式 */
    private static final java.util.Set<String> SUPPORTED_BOOK_EXTENSIONS =
            java.util.Set.of("txt", "pdf", "epub", "mobi", "md");

    /** 参考音频支持的格式（音频 + 视频，视频会自动转 WAV） */
    private static final java.util.Set<String> SUPPORTED_AUDIO_EXTENSIONS = java.util.Set.of(
            "wav", "mp3", "flac", "aac", "ogg", "m4a",
            "mp4", "avi", "mkv", "mov", "flv", "wmv", "webm", "m4v", "3gp"
    );

    private final UserRepository userRepository;
    private final StoreBookRepository storeBookRepository;
    private final ReferenceAudioRepository referenceAudioRepository;
    private final ReferenceAudioTopicRepository referenceAudioTopicRepository;
    private final TaskRepository taskRepository;
    private final UserReferenceAudioRepository userReferenceAudioRepository;
    private final TempReferenceAudioRepository tempReferenceAudioRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisTaskService redisTaskService;
    private final TtsService ttsService;
    /** 上传电子书时自动从书里取封面图（取不到则走书名+作者封面） */
    private final BookCoverExtractor bookCoverExtractor;
    /** 书城列表把实体转 Map 后追加任务状态字段用（见 toStoreBookViews） */
    private final ObjectMapper objectMapper;
    /** 书籍标签读写：上传/编辑时落库，列表视图返回前批量填充 */
    private final StoreBookTagService storeBookTagService;

    /** 注入自身代理，用于通过代理调用事务方法（避免 this.xxx() 自调用使 @Transactional 失效） */
    @Autowired
    @Lazy
    private AdminService self;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.store.dir:store_books}")
    private String storeDir;

    /**
     * storeDir / uploadDir 配置为相对路径时，统一以进程工作目录（user.dir）
     * 为基准解析为绝对路径。按 new File(storeDir, ...) 直接以 JVM 默认 CWD 解析时，
     * 与 TtsControllerSupport.resolveUploadFile 的基准可能不一致，
     * 会让仓库中出现两份内容漂移的 store_books / uploads。
     */
    @PostConstruct
    void normalizeDirs() {
        File store = new File(storeDir);
        if (!store.isAbsolute()) {
            storeDir = new File(System.getProperty("user.dir"), storeDir).getPath();
        }
        File upload = new File(uploadDir);
        if (!upload.isAbsolute()) {
            uploadDir = new File(System.getProperty("user.dir"), uploadDir).getPath();
        }
        log.info("管理端存储目录解析为: storeDir={}, uploadDir={}",
                new File(storeDir).getAbsolutePath(), new File(uploadDir).getAbsolutePath());
    }

    public AdminService(
            UserRepository userRepository,
            StoreBookRepository storeBookRepository,
            ReferenceAudioRepository referenceAudioRepository,
            ReferenceAudioTopicRepository referenceAudioTopicRepository,
            TaskRepository taskRepository,
            UserReferenceAudioRepository userReferenceAudioRepository,
            TempReferenceAudioRepository tempReferenceAudioRepository,
            AudioSegmentRepository audioSegmentRepository,
            RedisTaskService redisTaskService,
            TtsService ttsService,
            BookCoverExtractor bookCoverExtractor,
            PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper,
            StoreBookTagService storeBookTagService) {
        this.userRepository = userRepository;
        this.storeBookRepository = storeBookRepository;
        this.referenceAudioRepository = referenceAudioRepository;
        this.referenceAudioTopicRepository = referenceAudioTopicRepository;
        this.taskRepository = taskRepository;
        this.userReferenceAudioRepository = userReferenceAudioRepository;
        this.tempReferenceAudioRepository = tempReferenceAudioRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.redisTaskService = redisTaskService;
        this.ttsService = ttsService;
        this.bookCoverExtractor = bookCoverExtractor;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.storeBookTagService = storeBookTagService;
    }

    /** deleteUser 事务内收集、用于事务提交后做非事务清理的数据 */
    private record DeletedUserData(
            List<Long> taskIds,
            List<String> bookPaths,
            List<String> audioFileNames,
            String username) {
    }

    // ---------- 用户管理 ----------

    /** 分页查询所有用户 */
    public Page<User> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    /**
     * 删除用户（不能删除管理员）。
     * 同时清理该用户的磁盘文件（合成任务音频目录、上传的电子书源文件、
     * 「我的参考音频库」文件），避免删用户后磁盘孤儿无限累积。
     * 积分流水/充值订单属财务审计数据，保留不删。
     * <p>
     * 设计：数据库操作放在 {@link #deleteUserInTx(Long)}（自有事务）中，本方法在事务提交后
     * 再做磁盘删除与 Redis 登记清理，避免把耗时磁盘 IO 与 Redis 操作长时间锁在事务里。
     */
    public void deleteUser(Long userId) {
        DeletedUserData data = self.deleteUserInTx(userId);

        // 以下均为非事务的“尽力而为”清理，已脱离 DB 事务
        for (Long taskId : data.taskIds()) {
            deleteTaskFilesQuietlyById(taskId);
        }
        for (String bookPath : data.bookPaths()) {
            try {
                File file = resolveUploadFile(bookPath);
                if (file.exists() && !file.delete()) {
                    log.warn("删除用户电子书文件失败: {}", file.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("删除用户电子书文件失败: path={}, error={}", bookPath, e.getMessage());
            }
        }
        for (String fileName : data.audioFileNames()) {
            try {
                File file = resolveUploadFile(fileName);
                if (file.exists() && !file.delete()) {
                    log.warn("删除用户参考音频文件失败: {}", file.getAbsolutePath());
                }
            } catch (Exception e) {
                log.warn("删除用户参考音频文件失败: fileName={}, error={}", fileName, e.getMessage());
            }
        }

        // 清理 Redis 登记（任务键 + 用户任务集合），与 DB 删除解耦。
        // 键以用户名（认证 principal）为标识，必须传用户名而非数字 ID，
        // 否则查 tts:user_tasks:{数字ID} 永远为空、清理形同虚设
        try {
            redisTaskService.cleanupUserTasks(data.username());
        } catch (Exception e) {
            log.warn("清理用户Redis登记失败(已忽略): username={}, error={}", data.username(), e.getMessage());
        }

        log.info("管理员删除了用户: {}（清理任务 {} 个、参考音频 {} 个）",
                data.username(), data.taskIds().size(), data.audioFileNames().size());
    }

    /**
     * 仅做数据库清理的事务方法（通过 self 代理调用，确保事务真正生效）。
     * 不含任何磁盘 IO / Redis 操作，事务尽可能短小。
     */
    @Transactional
    public DeletedUserData deleteUserInTx(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            throw new RuntimeException("不能删除管理员账号");
        }

        // 1) 清理合成任务：先登记「任务已删除」，工作线程会在下一个检查点中止，
        // 不会把已删的任务/片段行经 save-merge 重新 INSERT 复活。
        // 用投影查询只取 id + bookFilePath（不加载整行实体）；
        // 删用户属于合法的全量场景，走专用投影 + JPQL 批量删除入口。
        List<TaskRepository.TaskCleanupMeta> tasks = taskRepository.findCleanupMetaByUserId(userId);
        List<Long> taskIds = new java.util.ArrayList<>();
        List<String> bookPaths = new java.util.ArrayList<>();
        for (TaskRepository.TaskCleanupMeta task : tasks) {
            VoiceCloningTtsService.markTaskDeleted(task.getId());
        }
        for (TaskRepository.TaskCleanupMeta task : tasks) {
            taskIds.add(task.getId());
            if (task.getBookFilePath() != null && !task.getBookFilePath().isEmpty()) {
                bookPaths.add(task.getBookFilePath());
            }
            audioSegmentRepository.deleteByTaskId(task.getId());
        }
        taskRepository.deleteByUserId(userId);

        // 2) 清理「我的参考音频库」记录（磁盘文件移出事务，见 deleteUser）
        List<UserReferenceAudio> audios = userReferenceAudioRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<String> audioFileNames = new java.util.ArrayList<>();
        for (UserReferenceAudio audio : audios) {
            audioFileNames.add(audio.getFileName());
            userReferenceAudioRepository.delete(audio);
        }

        // 3) 清理「临时参考音频」归属记录（/upload-reference 仅落盘的文件），
        //    文件并入 audioFileNames，由事务提交后的磁盘清理统一删除
        List<TempReferenceAudio> tempAudios = tempReferenceAudioRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (TempReferenceAudio tempAudio : tempAudios) {
            audioFileNames.add(tempAudio.getFileName());
            tempReferenceAudioRepository.delete(tempAudio);
        }

        userRepository.delete(user);
        // Redis 用户任务集合以用户名为键（写入侧传的是 authentication.getName()），
        // 必须携带用户名返回，供事务外按用户名清理
        return new DeletedUserData(taskIds, bookPaths, audioFileNames, user.getUsername());
    }

    /** 设置用户管理员权限 */
    @Transactional
    public void toggleAdmin(Long userId, boolean isAdmin) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (Boolean.TRUE.equals(user.getIsAdmin()) && !isAdmin) {
            // 防止把自己非管理员化导致无法管理
            long adminCount = userRepository.countByIsAdminTrue();
            if (adminCount <= 1) {
                throw new RuntimeException("至少需要保留一个管理员账号");
            }
        }
        user.setIsAdmin(isAdmin);
        userRepository.save(user);
        log.info("修改用户 {} 的管理员状态为 {}", user.getUsername(), isAdmin);
    }

    /** 封禁用户（禁止登录指定时长） */
    @Transactional
    public void banUser(Long userId, LocalDateTime bannedUntil) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        if (Boolean.TRUE.equals(user.getIsAdmin())) {
            throw new RuntimeException("不能封禁管理员账号");
        }
        user.setBannedUntil(bannedUntil);
        userRepository.save(user);
        JwtAuthenticationFilter.evict(user.getUsername());
        log.info("封禁用户 {} 直到 {}", user.getUsername(), bannedUntil);
    }

    /** 解封用户 */
    @Transactional
    public void unbanUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setBannedUntil(null);
        userRepository.save(user);
        JwtAuthenticationFilter.evict(user.getUsername());
        log.info("解封用户: {}", user.getUsername());
    }

    /**
     * 重置用户密码（管理员操作）。
     * 重置密码的同时递增 token 版本号，
     * 该用户此前签发的所有 JWT 立即失效（校验链路在 JwtAuthenticationFilter 比对）。
     */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new RuntimeException("新密码不能为空");
        }
        if (newPassword.length() < 6) {
            throw new RuntimeException("密码长度至少为 6 位");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        JwtAuthenticationFilter.evict(user.getUsername());
        log.info("管理员重置了用户 {} 的密码（token 版本号已递增至 {}）",
                user.getUsername(), user.getTokenVersion());
    }

    // ---------- 书城电子书管理 ----------

    /** 封面图片目录名：与书籍分类目录同级存放，不属于分类，列出分类时必须排除 */
    private static final String COVER_DIR_NAME = "covers";

    /** 分类目录缺失时的兜底分类名 */
    private static final String DEFAULT_CATEGORY = "其他";

    /**
     * 上传电子书到书城。
     * 文件保存到 store_books/{category}/{safe_filename}，同时创建 StoreBook 记录。
     * 分类目录不存在时自动创建（管理员填写的新分类即在此落到磁盘）。
     *
     * 方法本身不标注 @Transactional：mkdirs / fos.write 磁盘 IO 放进事务会让慢盘 /
     * 大文件写入期间持续占用数据库连接。与本类 deleteUser / uploadReferenceAudio
     * 同一设计原则：耗时磁盘 IO 在事务外，仅最后的 DB 写入通过 {@link #saveStoreBookInTx}
     *（self 代理）走短事务；落库失败则尽力清理已落盘文件，不留孤儿。
     *
     * @param originalTitle 原书名（可选），仅作存档；空白视为未填写，落库为 NULL
     * @param tags          标签（可选，可多个）；为空则不建标签行
     */
    public StoreBook uploadBook(MultipartFile file, String title, String originalTitle, String author,
                                String category, String description, List<String> tags) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        int dotIndex = originalFilename.lastIndexOf('.');
        String fileExtension = dotIndex > 0 ? originalFilename.substring(dotIndex + 1).toLowerCase() : "";
        String baseName = dotIndex > 0 ? originalFilename.substring(0, dotIndex) : originalFilename;

        // 校验电子书格式
        if (!SUPPORTED_BOOK_EXTENSIONS.contains(fileExtension)) {
            throw new IllegalArgumentException("不支持的电子书格式: " + fileExtension +
                    "（仅支持 " + String.join("/", SUPPORTED_BOOK_EXTENSIONS) + "）");
        }

        // 确保分类目录存在（管理员填写的新分类在此落到磁盘；已存在则直接复用）
        String safeCategory = sanitizeDirName(category != null ? category : DEFAULT_CATEGORY);
        File categoryDir = new File(storeDir, safeCategory);
        boolean categoryCreated = !categoryDir.exists();
        if (categoryCreated && !categoryDir.mkdirs()) {
            throw new IOException("无法创建分类目录: " + categoryDir.getAbsolutePath());
        }
        if (categoryCreated) {
            log.info("书城新建分类目录: {}", categoryDir.getAbsolutePath());
        }

        // 使用 UUID + 清洗后的基本名：避免文件名冲突，并杜绝目录穿越
        String safeBase = sanitizeFileName(baseName);
        String safeFilename = UUID.randomUUID().toString().replace("-", "") + "_" + safeBase
                + (fileExtension.isEmpty() ? "" : "." + fileExtension);
        File destFile = new File(categoryDir, safeFilename);
        // 防御目录穿越：确认落盘路径确实位于书城目录内（拦截 ../、绝对路径、符号链接逃逸等）
        PathSafetyUtil.assertWithinParent(new File(storeDir), destFile);
        // 流式拷贝，避免把整本电子书读进堆内存（大书可能数十 MB，并发上传叠加易 OOM）
        try (InputStream in = file.getInputStream();
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }

        StoreBook book = new StoreBook();
        book.setTitle(title != null && !title.isBlank() ? title : baseName);
        // 原书名为可选的存档信息：空白一律落 NULL（避免库里出现 '' 与 NULL 两种"未填"），
        // 超长按列宽 200 截断 —— 前端 maxlength 可被绕过，截断比抛 SQL 异常导致整次上传失败划算
        String normalizedOriginalTitle = originalTitle != null ? originalTitle.trim() : "";
        if (!normalizedOriginalTitle.isEmpty()) {
            book.setOriginalTitle(normalizedOriginalTitle.length() > 200
                    ? normalizedOriginalTitle.substring(0, 200)
                    : normalizedOriginalTitle);
        }
        book.setAuthor(author != null && !author.isBlank() ? author : "佚名");
        book.setCategory(safeCategory);
        book.setDescription(description != null ? description : "");
        book.setFileName(safeFilename);
        book.setFileExtension(fileExtension);
        book.setFileSize(file.getSize());
        book.setCoverColor(pickCoverColor(book.getTitle()));
        // 书里前几页有图就用它当封面（落到 store_books/covers/，与手工上传封面同一目录与出口）；
        // 没有图片则保持 coverImage 为空，由 BookStoreService.generateCover 现画「书名+作者」封面。
        // 提取失败只记日志、绝不阻断上传：封面是附加信息，缺了还有文字封面兜底。
        book.setCoverImage(extractCoverFile(destFile, fileExtension));

        try {
            StoreBook saved = self.saveStoreBookInTx(book, tags);
            log.info("管理员上传了电子书: {} (category={}, file={}, tags={})",
                    saved.getTitle(), safeCategory, safeFilename, saved.getTags());
            return saved;
        } catch (RuntimeException e) {
            // 落库失败：尽力清理已落盘文件（书籍本体 + 刚提取出来的封面），避免磁盘孤儿
            if (destFile.exists() && !destFile.delete()) {
                log.warn("上传失败后清理孤儿电子书文件失败: {}", destFile.getAbsolutePath());
            }
            deleteCoverFile(book.getCoverImage());
            throw e;
        }
    }

    /**
     * 尝试把刚上传的电子书里的图片提取成封面，返回落盘后的封面文件名；无图 / 失败返回 null。
     *
     * <p>写盘在事务外完成（与本类其它磁盘 IO 口径一致）；文件名用 {@code auto_} 前缀，
     * 与管理员手工上传的 {@code cover_} 区分开，排查封面来源时一眼可辨。
     */
    private String extractCoverFile(File bookFile, String fileExtension) {
        try {
            BookCoverExtractor.CoverImage cover =
                    bookCoverExtractor.extract(bookFile, fileExtension).orElse(null);
            if (cover == null) {
                return null;
            }
            File coverDir = new File(storeDir, COVER_DIR_NAME);
            if (!coverDir.exists() && !coverDir.mkdirs()) {
                throw new IOException("无法创建封面目录: " + coverDir.getAbsolutePath());
            }
            String filename = "auto_" + UUID.randomUUID().toString().replace("-", "")
                    + cover.fileNameSuffix();
            File dest = new File(coverDir, filename);
            PathSafetyUtil.assertWithinParent(new File(storeDir), dest);
            Files.write(dest.toPath(), cover.data());
            return filename;
        } catch (Exception e) {
            log.warn("自动提取封面失败（该书将使用书名+作者封面）: {}（{}）",
                    bookFile.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 尽力删除书籍对应的封面文件（手工上传的 {@code cover_} 与自动提取的 {@code auto_} 同目录）。
     *
     * <p>封面是「一本书一个文件」：自动提取后不清理，就会随「上传 → 删除」反复堆成孤儿图片。
     * 与书籍本体一样按「事务外、尽力而为、失败只告警」处理，不阻断删除。
     * {@code coverImage} 为空（走动态文字封面）时无文件可删，直接返回。
     */
    private void deleteCoverFile(String coverImage) {
        if (coverImage == null || coverImage.isBlank()) {
            return;
        }
        File cover = new File(new File(storeDir, COVER_DIR_NAME), coverImage);
        try {
            // 文件名来自 DB，与 BookStoreService.resolveCoverFile 同口径先做越界校验再删
            PathSafetyUtil.assertWithinParent(new File(storeDir), cover);
        } catch (IOException e) {
            log.warn("封面文件路径越界，跳过删除: {}", coverImage);
            return;
        }
        if (cover.exists() && !cover.delete()) {
            log.warn("删除封面文件失败: {}", cover.getAbsolutePath());
        }
    }

    /** 短事务：仅落库新书（通过 self 代理调用，确保事务生效；磁盘 IO 已在事务外完成） */
    @Transactional
    public StoreBook saveStoreBookInTx(StoreBook book) {
        return storeBookRepository.save(book);
    }

    /**
     * 短事务：落库新书 + 写入标签。
     *
     * <p>标签与书在同一事务里落库：分两次提交时若标签写失败，会留下一本「上传成功但没标签」
     * 的书，管理员看不出哪一步错了（前端只会拿到成功响应）。一本书最多 10 行标签，
     * 放同一个事务里没有额外的锁/时长代价。
     *
     * @param tags 可为 null（不写标签）；会先经 {@code StoreBookTagService} 规范化
     */
    @Transactional
    public StoreBook saveStoreBookInTx(StoreBook book, List<String> tags) {
        StoreBook saved = storeBookRepository.save(book);
        // replaceTags 的返回值就是真正落库的规范化结果，回填到视图字段供前端直接回显
        saved.setTags(storeBookTagService.replaceTags(saved.getId(), tags));
        return saved;
    }

    /**
     * 从书城删除书籍（同时删除磁盘文件）。
     *
     * 磁盘 IO 不进事务（项目既有口径：事务内不做磁盘 IO）：在事务内执行 Files.delete，
     * 删库回滚后文件已丢，会留下「记录还在、下载已断」的脏状态。因此拆为两步：
     * 1. 事务外先删磁盘文件（尽力而为，失败仅告警不阻断）；
     * 2. 再走 {@link #deleteBookInTx} 短事务仅删除数据库记录。
     * 若第 2 步失败（文件已删、记录残留），下次启动的扫描对账会按磁盘快照自动下架，可自愈。
     */
    public void deleteBook(Long bookId) {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new RuntimeException("书籍不存在"));

        // 尝试删除磁盘文件（category 为 NULL 的历史数据按根目录查找，new File(storeDir, null) 会抛 NPE）
        String category = book.getCategory();
        File file = category != null
                ? new File(new File(storeDir, category), book.getFileName())
                : new File(storeDir, book.getFileName());
        if (!file.exists()) {
            file = new File(storeDir, book.getFileName());
        }
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                log.warn("删除书籍文件失败: {}", file.getAbsolutePath(), e);
            }
        }

        // 封面文件同样在事务外删掉，否则每删一本就留一张孤儿图（实测 auto_*.jpg 会残留）
        deleteCoverFile(book.getCoverImage());

        String title = book.getTitle();
        self.deleteBookInTx(bookId);
        log.info("管理员删除了书籍: {}", title);
    }

    /** 短事务：仅删除书籍数据库记录（通过 self 代理调用，确保事务生效；磁盘 IO 已在事务外完成） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteBookInTx(Long bookId) {
        // 先清标签再删书：标签表没有外键（与 shelf_books.store_book_id 同一风格），
        // 不显式清理就会留下指向不存在书籍的孤儿行，越积越多且会被标签栏统计进去
        storeBookTagService.deleteByBookId(bookId);
        storeBookRepository.deleteById(bookId);
    }

    /**
     * 批量删除书籍（尽力而为：单本失败不影响其余，返回实际删除条数）。
     * 每本书在 deleteBookInTx 的独立事务里提交，单本失败不会连累整批回滚。
     *
     * 本方法不开启事务：批量循环内含磁盘 IO，外层长事务全程持有数据库连接没有意义，各书独立提交即可。
     */
    public int deleteBooks(List<Long> bookIds) {
        int deleted = 0;
        for (Long bookId : bookIds) {
            try {
                deleteBook(bookId);
                deleted++;
            } catch (Exception e) {
                log.warn("批量删除书籍：单本删除失败: id={}, error={}", bookId, e.getMessage());
            }
        }
        log.info("管理员批量删除书籍: 请求 {} 本，成功 {} 本", bookIds.size(), deleted);
        return deleted;
    }

    /**
     * 编辑书城书籍信息（书名/作者/分类/简介）。
     * 传 null 的字段保持原值；书名/作者传空白串视为非法；简介允许清空。
     * 分类变更时同步把物理文件移动到新分类目录，保证下载链路不断。
     *
     * 磁盘 IO 不进事务：在 @Transactional 内执行 Files.move，写库回滚时文件已落在新分类
     * 目录，留下「DB 分类 ≠ 文件位置」的坏状态（既不自愈也不会被对账发现）。
     * 与 deleteBook / deleteUser / uploadBook 统一为「事务外零磁盘 IO」口径：
     * 1. 事务外先移动物理文件，失败直接抛错、不进事务（文件没动成，库也不能动）；
     * 2. 再走 {@link #updateBookInTx} 短事务只更新数据库字段；
     * 3. 落库失败时尽力把文件搬回原目录，杜绝反方向的同款坏状态。
     */
    public StoreBook updateBook(Long bookId, String title, String author,
                                String category, String description) throws IOException {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在"));

        String oldCategory = null;
        String newCategory = null;
        if (category != null && !category.isBlank()) {
            String sanitized = sanitizeDirName(category);
            if (!sanitized.equals(book.getCategory())) {
                oldCategory = book.getCategory();
                newCategory = sanitized;
                moveBookFile(book.getFileName(), oldCategory, newCategory);
            }
        }

        try {
            return self.updateBookInTx(bookId, title, author, newCategory, description);
        } catch (RuntimeException e) {
            // 落库失败：尽力把已移动的文件搬回原分类目录，避免「DB 分类 ≠ 文件位置」
            if (newCategory != null) {
                try {
                    moveBookFile(book.getFileName(), newCategory, oldCategory);
                } catch (IOException me) {
                    log.warn("编辑书籍落库失败后回迁文件失败: {} -> {}, error={}",
                            newCategory, oldCategory, me.getMessage());
                }
            }
            throw e;
        }
    }

    /** 短事务：仅更新书籍元数据（分类对应的物理文件已在事务外移动完成） */
    @Transactional
    public StoreBook updateBookInTx(Long bookId, String title, String author,
                                    String newCategory, String description) {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在"));

        if (title != null) {
            if (title.isBlank()) {
                throw new IllegalArgumentException("书名不能为空");
            }
            book.setTitle(title.trim());
        }
        if (author != null) {
            if (author.isBlank()) {
                throw new IllegalArgumentException("作者不能为空");
            }
            book.setAuthor(author.trim());
        }
        if (description != null) {
            book.setDescription(description);
        }
        if (newCategory != null) {
            book.setCategory(newCategory);
        }

        StoreBook saved = storeBookRepository.save(book);
        // 标签是 @Transient 视图字段，save 不会带回：不补的话这个「编辑成功」的响应里
        // tags 会是空数组，前端若用返回值就地刷新某一行，标签就会凭空消失
        storeBookTagService.attachTags(saved);
        log.info("管理员编辑了书籍: {} (id={})", saved.getTitle(), saved.getId());
        return saved;
    }

    /**
     * 全量设置某本书的标签（编辑弹窗保存时调用）。
     *
     * <p>语义是「提交即为最终集合」：未提交的标签会被删除。这样前端只需把多选框的
     * 当前值整批发上来，不必自己算增删差集（算错了还不会有任何报错，只是标签悄悄多了或少了）。
     *
     * <p>规范化（trim / 去重 / 上限）在 {@link StoreBookTagService} 里做，返回值即落库结果，
     * 直接回填到视图字段供前端回显「实际生效的标签」。
     */
    @Transactional
    public StoreBook setBookTags(Long bookId, List<String> tags) {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在"));
        book.setTags(storeBookTagService.replaceTags(bookId, tags));
        log.info("管理员更新书籍标签: {} (id={}) -> {}", book.getTitle(), bookId, book.getTags());
        return book;
    }

    /** 库里已用过的全部标签名（管理端编辑时的联想词，不做在售过滤） */
    public List<String> listBookTagNames() {
        return storeBookTagService.listAllTagNames();
    }

    /** 切换书籍的「编辑推荐」标记（书城首页编辑推荐入口用） */
    @Transactional
    public StoreBook setEditorPick(Long bookId, boolean editorPick) {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在"));
        book.setEditorPick(editorPick);
        StoreBook saved = storeBookRepository.save(book);
        // 同 updateBookInTx：返回体要带完整 tags，否则调用方就地刷新会把标签清空
        storeBookTagService.attachTags(saved);
        log.info("书籍编辑推荐状态变更: {} (id={}) -> {}", saved.getTitle(), saved.getId(), editorPick);
        return saved;
    }

    /**
     * 分类变更时把书籍物理文件从旧分类目录移动到新分类目录。
     * 兼容早期可能落在书城根目录的文件；磁盘找不到文件时仅告警、不阻断元数据更新。
     * fromCategory 为 NULL 的历史数据按根目录查找（new File(storeDir, null) 会抛 NPE）。
     */
    private void moveBookFile(String fileName, String fromCategory, String toCategory) throws IOException {
        File src = fromCategory != null
                ? new File(new File(storeDir, fromCategory), fileName)
                : new File(storeDir, fileName);
        if (!src.exists()) {
            src = new File(storeDir, fileName);
        }

        File destDir = new File(storeDir, toCategory);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建分类目录: " + destDir.getAbsolutePath());
        }
        File dest = new File(destDir, fileName);
        PathSafetyUtil.assertWithinParent(new File(storeDir), dest);

        if (src.exists()) {
            Files.move(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            log.warn("编辑分类时未找到书籍文件，仅更新分类记录: {}", fileName);
        }
    }

    /**
     * 为书城书籍上传自定义封面，覆盖动态生成的色块封面。
     *
     * 磁盘写入在事务外，仅封面字段更新走 {@link #updateCoverImageInTx}
     * 短事务；旧封面删除在事务提交成功之后——在事务内先删旧文件再写库，
     * 写库回滚时会留下「封面引用指向已删除文件」的脏状态。
     */
    public StoreBook uploadCover(Long bookId, MultipartFile file) throws IOException {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new RuntimeException("书籍不存在"));
        if (file.isEmpty()) {
            throw new IllegalArgumentException("封面文件为空");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "cover.png";
        int dotIndex = originalFilename.lastIndexOf('.');
        String ext = dotIndex > 0 ? originalFilename.substring(dotIndex).toLowerCase() : ".png";
        if (!ext.equals(".png") && !ext.equals(".jpg") && !ext.equals(".jpeg")) {
            throw new IllegalArgumentException("封面仅支持 PNG/JPG 格式");
        }

        // 封面存到 store_books/covers/ 目录
        File coverDir = new File(storeDir, "covers");
        if (!coverDir.exists() && !coverDir.mkdirs()) {
            throw new IOException("无法创建封面目录: " + coverDir.getAbsolutePath());
        }
        String filename = "cover_" + book.getId() + "_" + UUID.randomUUID().toString().replace("-", "") + ext;
        File destFile = new File(coverDir, filename);
        PathSafetyUtil.assertWithinParent(new File(storeDir), destFile);
        // 与 uploadBook 一致，流式拷贝，避免把封面图读进堆内存
        try (InputStream in = file.getInputStream();
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }

        String oldCoverImage = book.getCoverImage();

        StoreBook saved;
        try {
            saved = self.updateCoverImageInTx(bookId, filename);
        } catch (RuntimeException e) {
            // 落库失败：尽力清理刚写入的封面文件，避免磁盘孤儿
            if (destFile.exists() && !destFile.delete()) {
                log.warn("上传封面失败后清理孤儿文件失败: {}", destFile.getAbsolutePath());
            }
            throw e;
        }

        // 旧封面删除放在事务提交之后（尽力而为），杜绝「库未更新、文件先丢」的中间态
        if (oldCoverImage != null && !oldCoverImage.isEmpty()) {
            File old = new File(coverDir, oldCoverImage);
            if (old.exists() && !old.delete()) {
                log.warn("删除旧封面失败: {}", old.getAbsolutePath());
            }
        }

        log.info("管理员上传了书籍封面: {} (file={})", saved.getTitle(), filename);
        // 同 updateBookInTx：响应里的 tags 必须是完整值，不能让「换了封面」看起来像「标签被清空」
        storeBookTagService.attachTags(saved);
        return saved;
    }

    /** 短事务：更新封面文件名（通过 self 代理调用；重新按 id 加载，避免事务外实体脱管写回） */
    @Transactional
    public StoreBook updateCoverImageInTx(Long bookId, String coverFileName) {
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new RuntimeException("书籍不存在"));
        book.setCoverImage(coverFileName);
        return storeBookRepository.save(book);
    }

    /** 查询书城所有书籍（分页） */
    public Page<StoreBook> listStoreBooks(Pageable pageable) {
        return storeBookRepository.findAll(pageable);
    }

    /**
     * 列出服务器上已有的书城分类名（供管理端上传时联想，并据此判断某分类是否"新建"）。
     *
     * 取「磁盘目录」与「数据库已有分类」的并集，两边缺一不可：
     * - 只查磁盘：数据库里存在、但磁盘目录被误删的分类会漏掉（这类目录在下次上传时自动重建，
     *   漏报会让管理端把它当成全新分类，与"复用已有分类"的意图不符）；
     * - 只查数据库：手工拷入书籍、尚未入库的空目录（磁盘上确实是已有分类）会漏掉。
     * covers 是封面目录，与分类目录同级但不是分类，必须排除。
     */
    public List<String> listCategoryNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();

        File[] subDirs = new File(storeDir).listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                String name = subDir.getName();
                if (!COVER_DIR_NAME.equals(name)) {
                    names.add(name);
                }
            }
        }
        for (String name : storeBookRepository.findDistinctCategories()) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return new java.util.ArrayList<>(names);
    }

    /**
     * 根据书名/作者搜索书籍（数据库分页）。
     * 走数据库分页：全量返回命中结果再由控制器伪造 totalPages，书籍量大时既吃内存又契约失真。
     */
    public Page<StoreBook> searchBooks(String keyword, Pageable pageable) {
        // 转义用户输入的 LIKE 通配符（% / _），与 StoreBookRepository 的 ESCAPE '\\' 配套，
        // 避免管理端搜索框输入 % 返回全部书籍、输入 _ 匹配任意单字符的语义异常
        return storeBookRepository.searchByKeyword(escapeLikeKeyword(keyword.trim()), pageable);
    }

    // ---------- 参考音频管理 ----------

    /**
     * 上传参考音频到服务器。
     * 支持视频格式自动转换为 WAV。
     * 方法本身不开启事务：视频分支的 ffmpeg 流式转码最长 120 秒，属于耗时磁盘/进程 IO，
     * 不应长时间占用数据库连接与事务（与 deleteUser「耗时 IO 移出事务」同一设计原则）。
     * 仅最后的 DB 写入通过 {@link #saveReferenceAudioInTx}（代理调用）放到短事务里。
     *
     * @param topicId 所属主题 id，可为 null 表示未归类
     */
    public ReferenceAudio uploadReferenceAudio(MultipartFile file, String name, String description, Long topicId)
            throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        // 主题有效性必须在落盘【之前】校验：先写文件再发现 topicId 非法，
        // 会在上传目录留下一个没有库记录的孤儿文件（只能靠清理脚本兜底）。
        Long resolvedTopicId = topicId != null ? requireExistingTopic(topicId).getId() : null;

        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        int dotIndex = originalFilename.lastIndexOf('.');
        String extension = dotIndex > 0 ? originalFilename.substring(dotIndex) : "";
        String extForCheck = extension.startsWith(".") ? extension.substring(1) : extension;

        // 校验是否为支持的音频或视频格式
        if (!SUPPORTED_AUDIO_EXTENSIONS.contains(extForCheck)) {
            throw new IllegalArgumentException("不支持的文件格式: " + extForCheck);
        }
        // 若文件名无扩展名（理论上不会走到这里），回退为 .wav
        if (extension.isEmpty()) {
            extension = ".wav";
            extForCheck = "wav";
        }

        String filename;
        long fileSize = file.getSize();

        // 如果是视频格式，先转换为 WAV
        if (AudioVideoUtil.isVideoFormat(extForCheck)) {
            String videoExtension = extension;
            extension = ".wav";
            filename = "ref_audio_" + UUID.randomUUID().toString().replace("-", "") + extension;
            File destFile = new File(dir, filename);
            // 防御目录穿越：确认落盘路径位于上传目录内
            PathSafetyUtil.assertWithinParent(new File(uploadDir), destFile);
            // 流式转码：视频与 WAV 都不再整份读进堆内存，且底层 ffmpeg 带超时强制中断
            File tempVideoFile = File.createTempFile("admin_ref_video_", videoExtension);
            try {
                file.transferTo(tempVideoFile);
                AudioVideoUtil.convertVideoToWav(tempVideoFile, destFile);
            } finally {
                if (tempVideoFile.exists() && !tempVideoFile.delete()) {
                    log.warn("临时视频文件删除失败: {}", tempVideoFile.getAbsolutePath());
                }
            }
            fileSize = destFile.length();
        } else {
            filename = "ref_audio_" + UUID.randomUUID().toString().replace("-", "") + extension;
            File destFile = new File(dir, filename);
            // 防御目录穿越：确认落盘路径位于上传目录内
            PathSafetyUtil.assertWithinParent(new File(uploadDir), destFile);
            // 流式拷贝，避免把整段参考音频读进堆内存（与 uploadBook/uploadCover 一致）
            try (InputStream in = file.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }
        }

        ReferenceAudio entity = new ReferenceAudio();
        entity.setName(name != null && !name.isBlank() ? name : originalFilename);
        entity.setDescription(description != null ? description : "");
        entity.setFileName(filename);
        entity.setFileSize(fileSize);
        entity.setTopicId(resolvedTopicId);

        ReferenceAudio saved = self.saveReferenceAudioInTx(entity);
        log.info("管理员上传了参考音频: {} (file={})", saved.getName(), filename);
        return saved;
    }

    /** 参考音频上传的最后一步：短事务写库（通过 self 代理调用，确保事务生效） */
    @Transactional
    public ReferenceAudio saveReferenceAudioInTx(ReferenceAudio entity) {
        return referenceAudioRepository.save(entity);
    }

    /** 删除参考音频（同时删除磁盘文件） */
    public void deleteReferenceAudio(Long audioId) {
        ReferenceAudio entity = referenceAudioRepository.findById(audioId)
                .orElseThrow(() -> new RuntimeException("参考音频不存在"));

        // 顺序：先删数据库记录（短事务），成功后再尽力删磁盘文件。
        // 旧实现是先删文件、再删记录：一旦记录删除失败（连接抖动等），记录仍在但文件已丢，
        // 该条目会永久指向不存在的文件（试听 404、建任务时 resolveReferenceFileName 拿到
        // 不存在的文件）且无法自愈；反过来只留下一个孤儿文件，可由清理脚本兜底、不影响功能。
        // 与 deleteBook、AdminAppVersionController.delete 的既定顺序一致。
        self.deleteReferenceAudioInTx(audioId);

        File file = new File(uploadDir, entity.getFileName());
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                log.warn("删除参考音频文件失败: {}", file.getAbsolutePath(), e);
            }
        }

        log.info("管理员删除了参考音频: {}", entity.getName());
    }

    /** 短事务：仅删除参考音频数据库记录（通过 self 代理调用，确保事务生效；磁盘 IO 已在事务外完成） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteReferenceAudioInTx(Long audioId) {
        referenceAudioRepository.deleteById(audioId);
    }

    /**
     * 编辑参考音频信息（名称/描述/所属主题）。
     * name / description 传 null 表示保持原值；名称传空白串视为非法；描述允许清空。
     *
     * @param topicId     目标主题 id
     * @param updateTopic 是否处理所属主题。必须与 topicId 区分开三态：
     *                    false = 不传该字段，保持原主题；true 且 topicId 为 null = 显式移出主题（归为未归类）。
     *                    若只用 topicId == null 表示「不改」，前端就无法把音频移出主题。
     */
    @Transactional
    public ReferenceAudio updateReferenceAudio(Long audioId, String name, String description,
                                               Long topicId, boolean updateTopic) {
        ReferenceAudio entity = referenceAudioRepository.findById(audioId)
                .orElseThrow(() -> new IllegalArgumentException("参考音频不存在"));

        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("名称不能为空");
            }
            entity.setName(name.trim());
        }
        if (description != null) {
            entity.setDescription(description);
        }
        if (updateTopic) {
            entity.setTopicId(topicId != null ? requireExistingTopic(topicId).getId() : null);
        }

        ReferenceAudio saved = referenceAudioRepository.save(entity);
        log.info("管理员编辑了参考音频: {} (id={})", saved.getName(), saved.getId());
        return saved;
    }

    /** 查询所有参考音频 */
    public List<ReferenceAudio> listReferenceAudios() {
        return referenceAudioRepository.findAllByOrderByCreatedAtDesc();
    }

    // ---------- 参考音频主题 ----------

    /**
     * 主题列表（附每个主题下的音频数）。
     * 音频数用一次 GROUP BY 查出后在内存里合并：逐主题 count 会退化成 N+1 查询。
     */
    public List<Map<String, Object>> listReferenceAudioTopics() {
        Map<Long, Long> countByTopic = new HashMap<>();
        for (Tuple row : referenceAudioRepository.countGroupByTopicId()) {
            // 按别名取值而非下标：别名写错会在启动/查询时立刻抛异常，而不是静默拿错列
            Long topicId = row.get("topicId", Long.class);
            Long audioCount = row.get("audioCount", Long.class);
            if (topicId != null) {
                countByTopic.put(topicId, audioCount != null ? audioCount : 0L);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ReferenceAudioTopic topic : referenceAudioTopicRepository.findAllByOrderBySortOrderAscIdAsc()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", topic.getId());
            view.put("name", topic.getName());
            view.put("description", topic.getDescription());
            view.put("sortOrder", topic.getSortOrder());
            view.put("createdAt", topic.getCreatedAt());
            view.put("updatedAt", topic.getUpdatedAt());
            view.put("audioCount", countByTopic.getOrDefault(topic.getId(), 0L));
            result.add(view);
        }
        return result;
    }

    /** 创建参考音频主题 */
    @Transactional
    public ReferenceAudioTopic createReferenceAudioTopic(String name, String description, Integer sortOrder) {
        String trimmed = normalizeTopicName(name);
        assertTopicNameAvailable(trimmed, null);

        ReferenceAudioTopic topic = new ReferenceAudioTopic();
        topic.setName(trimmed);
        topic.setDescription(description != null ? description : "");
        topic.setSortOrder(sortOrder != null ? sortOrder : 0);

        try {
            ReferenceAudioTopic saved = referenceAudioTopicRepository.save(topic);
            log.info("管理员创建了参考音频主题: {} (id={})", saved.getName(), saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 并发同名创建时唯一索引 uk_ref_topic_name 会兜底拦下，
            // 直接透出会把一长串 SQL 报错甩到管理端弹窗，这里换成业务文案
            throw new IllegalArgumentException(duplicateTopicMessage(trimmed));
        }
    }

    /**
     * 编辑参考音频主题（名称/描述/排序）。
     * 三个字段均传 null 表示保持原值；名称传空白串视为非法；描述与排序允许显式置 0/清空。
     */
    @Transactional
    public ReferenceAudioTopic updateReferenceAudioTopic(Long topicId, String name, String description, Integer sortOrder) {
        ReferenceAudioTopic topic = referenceAudioTopicRepository.findById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("主题不存在"));

        if (name != null) {
            String trimmed = normalizeTopicName(name);
            if (!trimmed.equals(topic.getName())) {
                assertTopicNameAvailable(trimmed, topicId);
            }
            topic.setName(trimmed);
        }
        if (description != null) {
            topic.setDescription(description);
        }
        if (sortOrder != null) {
            topic.setSortOrder(sortOrder);
        }

        try {
            ReferenceAudioTopic saved = referenceAudioTopicRepository.save(topic);
            log.info("管理员编辑了参考音频主题: {} (id={})", saved.getName(), saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(duplicateTopicMessage(normalizeTopicName(name)));
        }
    }

    /**
     * 删除参考音频主题。
     * 主题下还有音频时**拒绝删除**而不是级联删音频：音频是管理员逐个上传的素材，
     * 一次误点「删主题」连带丢掉整部剧的台词片段，代价远大于让人先把音频移出去。
     */
    @Transactional
    public void deleteReferenceAudioTopic(Long topicId) {
        ReferenceAudioTopic topic = referenceAudioTopicRepository.findById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("主题不存在"));

        long audioCount = referenceAudioRepository.countByTopicId(topicId);
        if (audioCount > 0) {
            throw new IllegalArgumentException(nonEmptyTopicDeleteMessage(topic.getName(), audioCount));
        }

        referenceAudioTopicRepository.delete(topic);
        log.info("管理员删除了参考音频主题: {} (id={})", topic.getName(), topicId);
    }

    /** 取主题，不存在则抛业务异常（供上传/编辑音频时校验归属） */
    private ReferenceAudioTopic requireExistingTopic(Long topicId) {
        return referenceAudioTopicRepository.findById(topicId)
                .orElseThrow(() -> new IllegalArgumentException("所选主题不存在，请刷新后重试"));
    }

    /** 主题名规范化 + 非空校验（包级可见，便于单测直接断言边界） */
    static String normalizeTopicName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("主题名称不能为空");
        }
        return name.trim();
    }

    /** 主题非空时拒删的提示文案：必须带上主题名与剩余数量，否则管理员不知道该去哪儿移音频 */
    static String nonEmptyTopicDeleteMessage(String topicName, long audioCount) {
        return "主题「" + topicName + "」下还有 " + audioCount + " 个参考音频，请先移出该主题再删除";
    }

    /**
     * 主题名唯一性校验。{@code excludeId} 为 null 表示创建场景，否则为改名场景（排除自身，
     * 否则「不改名只改描述」会被自己绊倒）。
     */
    private void assertTopicNameAvailable(String name, Long excludeId) {
        Optional<ReferenceAudioTopic> existing = excludeId != null
                ? referenceAudioTopicRepository.findByNameAndIdNot(name, excludeId)
                : referenceAudioTopicRepository.findByName(name);
        if (existing.isPresent()) {
            throw new IllegalArgumentException(duplicateTopicMessage(name));
        }
    }

    /** 重名提示文案：带上管理员输入的主题名，否则他不知道撞的是哪个名字 */
    static String duplicateTopicMessage(String name) {
        return "主题名称已存在：" + name;
    }

    // ---------- 内部工具 ----------

    /**
     * 尽力清理单个合成任务的磁盘文件（与 Controller 删任务的清理逻辑一致）：
     * 任务专属音频目录 uploads/{taskId}/ + 上传的电子书源文件。
     * 任何失败只告警不抛出——删用户不应被磁盘残留阻断。
     */
    private void deleteTaskFilesQuietlyById(Long taskId) {
        try {
            File audioDir = resolveUploadFile(String.valueOf(taskId));
            if (audioDir.exists() && audioDir.isDirectory()) {
                File[] files = audioDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.delete()) {
                            log.warn("删除音频文件失败: {}", f.getAbsolutePath());
                        }
                    }
                }
                if (!audioDir.delete()) {
                    log.warn("删除音频目录失败: {}", audioDir.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            log.warn("删除任务音频目录失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * 将 uploadDir 下的相对路径解析为绝对 File。
     * 兼容 app.upload.dir 配成相对路径的情况：相对时以进程工作目录(user.dir)为基准，
     * 避免不同入口(new File(uploadDir, x) 会以 JVM 默认 CWD 解析)之间不一致导致找不到文件。
     *
     * 解析后校验规范路径仍落在 uploadDir 内，与 TtsControllerSupport.resolveUploadFile /
     * VoiceCloningTtsService.resolveUploadFile 保持同一口径：只做目录拼接、
     * 不比对规范路径，会漏掉 ../ 与符号链接逃逸。
     *
     * @throws IOException 路径解析失败，或解析结果逃逸出 uploadDir（疑似目录穿越）
     */
    private File resolveUploadFile(String relativePath) throws IOException {
        try {
            return PathSafetyUtil.resolveWithin(PathSafetyUtil.resolveBaseDir(uploadDir), relativePath);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** 封面配色（与 BookStoreService 保持一致） */
    private String pickCoverColor(String title) {
        String[] colors = {
                "#8D6E63", "#78909C", "#A1887F", "#90A4AE",
                "#7E57C2", "#5C6BC0", "#42A5F5", "#26A69A",
                "#66BB6A", "#9CCC65", "#D4E157", "#FFB300",
                "#FB8C00", "#F4511E", "#E53935", "#D81B60"
        };
        int hash = title != null ? title.hashCode() : 0;
        // Math.abs(Integer.MIN_VALUE) 仍为负数，会导致数组越界 500；
        // 改用位掩码清除符号位，取模索引恒为非负
        return colors[(hash & 0x7fffffff) % colors.length];
    }

    /**
     * 清理目录名，防止路径穿越攻击（移除 ../、/、\、控制字符等）。
     * 保留字母/数字/中文/下划线/连字符/点/空格。
     */
    private String sanitizeDirName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_CATEGORY;
        }
        // 仅保留安全字符
        String cleaned = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_\\-\\. ]", "").trim();
        // 清洗后为空（例如分类名只含 / \ : 等非法字符）时绝不能返回空串：
        // new File(storeDir, "") 会解析成书城根目录本身，书籍会平铺到根目录、
        // 与「默认分类」散书混在一起，且后续下载/对账都会走根目录兜底分支
        return cleaned.isEmpty() ? DEFAULT_CATEGORY : cleaned;
    }

    /**
     * 清理文件名，剥离路径分隔符与上级目录引用，仅保留“基本名”的安全字符。
     * 与 sanitizeDirName 不同，这里额外把 {@code /}、{@code \\} 以及 {@code ..}
     * 全部替换为下划线，确保拼接出的文件名绝不会逃逸出目标目录。
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String cleaned = name.replaceAll("[/\\\\]", "_")        // 路径分隔符 → 下划线
                             .replaceAll("\\.{2,}", "_")         // 连续点（含 ..）→ 下划线
                             .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff_.\\- ]", "");
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    /**
     * 转义 SQL LIKE 通配符（与 BookStoreService.escapeLikeKeyword 同语义）：
     * 先转义反斜杠，再转义 % 与 _，配合 StoreBookRepository 各查询的 ESCAPE '\\' 子句。
     */
    private static String escapeLikeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ---------- 书城合成语音（管理员把书城书发去 TTS 合成） ----------

    /**
     * 单书合成：在「书城管理」中点击合成语音时调用，复用 {@link TtsService#createTtsTask}
     * 全异步链路，回写 {@code store_books.task_id}。积分由 {@code CreditService.resolveCost}
     * 按 adminExempt 规则自动免扣（管理员提交）。
     *
     * <p>referenceFileName / voiceId 二选一：与 {@code TtsTaskController.from-store} 一致的设计，
     * 让管理员复用同一抽象（克隆参考音频 / 预设音色）。
     *
     * <p>磁盘 IO 与文本提取不在事务内：由 createTtsTask 自己控制事务边界；
     * 本方法不强制开事务（不写整列，只调一个会自己开事务的服务）。
     *
     * @throws IllegalArgumentException 书不存在 / 已合成（task_id 非空）/ 参数缺失
     * @throws IOException 读取书文件失败
     */
    public Long synthesizeBookForStore(Long bookId, String referenceFileName, String voiceId,
                                       String requester) throws IOException {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        boolean hasReference = referenceFileName != null && !referenceFileName.isBlank();
        boolean hasVoice = voiceId != null && !voiceId.isBlank();
        if (!hasReference && !hasVoice) {
            throw new IllegalArgumentException("必须提供参考音频文件名（referenceFileName）或预设音色 voiceId");
        }
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester（操作管理员）不能为空");
        }
        // 参考音频白名单校验：TtsTaskController.from-store 会经 support.resolveReferenceFileName
        // 校验 fileName 确实存在于服务器共享库（reference_audio 表），而 VoiceCloningTtsService.createTtsTask
        // 对 referenceFileName 不做任何存在性检查、直接写入 Task。若这里不校验，管理员打开弹窗后
        // 该参考音频在别处被删除，提交仍会建任务成功，失败被推迟到异步合成阶段，报错指向不明。
        // 与 TtsControllerSupport 同口径：server 共享库按 fileName 在 reference_audio 表定位。
        if (hasReference && referenceAudioRepository.findByFileName(referenceFileName).isEmpty()) {
            throw new IllegalArgumentException(
                    "参考音频不存在或已被删除，请重新选择：" + referenceFileName);
        }
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在: " + bookId));
        if (book.getTaskId() != null) {
            // 只有「已成功」或「仍在跑」才拒绝；failed / partial 必须允许重来。
            // 原因：task_id 是在建任务时就回绑的（早于异步合成结果），一次失败若不放开，
            // 这本书就永久卡死 —— 线上只能靠人工改库恢复。
            Task bound = taskRepository.findById(book.getTaskId()).orElse(null);
            String boundStatus = bound == null ? null : bound.getStatus();
            if (!TaskStatus.allowsResynthesis(boundStatus)) {
                throw new IllegalArgumentException(
                        "本书已合成过音频（taskId=" + book.getTaskId()
                                + "，状态=" + (boundStatus == null ? "已删除" : boundStatus)
                                + "），暂不支持重新合成");
            }
            // 解绑上一次没成功的任务（带并发闸门：只有绑定仍指向它时才解）
            if (self.clearSynthBinding(bookId, book.getTaskId()) == 0) {
                throw new IllegalArgumentException("本书的合成状态刚被其他操作改变，请刷新后重试");
            }
            log.info("上一次合成未成功（taskId={}, status={}），已解绑并重新发起: bookId={}",
                    book.getTaskId(), boundStatus, bookId);
            // 刻意**不**重新赋值 book：后面要把它捕获进匿名 MultipartFile，重新赋值会破坏
            // 「effectively final」，编译直接不过。解绑只影响 DB 上的 task_id，
            // 而后续逻辑只用 book 的 fileName/category/status（与 task_id 无关），
            // 真正的绑定并发闸门在 bindSynthTaskIfUnbound 的 WHERE 里。
        }
        if ("offshelf".equals(book.getStatus())) {
            throw new IllegalArgumentException("书籍已下架，无法合成");
        }

        // 定位书城物理文件，与 BookStoreService.resolveBookFile 口径一致
        String category = book.getCategory();
        File bookFile = category != null
                ? new File(new File(storeDir, category), book.getFileName())
                : new File(storeDir, book.getFileName());
        if (!bookFile.exists()) {
            bookFile = new File(storeDir, book.getFileName());
        }
        if (!bookFile.exists()) {
            throw new IllegalArgumentException("书籍文件缺失: " + book.getFileName());
        }
        try {
            PathSafetyUtil.assertWithinParent(new File(storeDir), bookFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("书籍文件路径非法: " + e.getMessage(), e);
        }

        long sourceSize = bookFile.length();
        // 与 TtsTaskController.from-store 同样的 size 上限自检，避免上传巨书 OOM
        long sizeLimit = 50L * 1024 * 1024L;
        if (sourceSize > sizeLimit) {
            throw new IllegalArgumentException(
                    "书籍文件过大（" + (sourceSize / 1024 / 1024) + "MB），超过 " + (sizeLimit / 1024 / 1024) + "MB 上限");
        }

        String extension = book.getFileExtension();
        if (extension == null || extension.isBlank()) {
            throw new IllegalArgumentException("书籍扩展名为空，无法合成");
        }

        // 包一层 MultipartFile：createTtsTask 全链路（建 task、落盘副本、文本提取）都按 multipart 接口设计，
        // 复用而不是破坏既有契约。注意：包了字节读路径的 readAllBytes 不会在这里被调用——下面 readAllBytes 的实现
        // 限制：sourceSize 已自检在 50MB 以下，readAllBytes 顶多 50MB 一次性载入堆，不会 OOM。
        final File sourceFile = bookFile;
        final long size = sourceSize;
        org.springframework.web.multipart.MultipartFile wrapped =
                new org.springframework.web.multipart.MultipartFile() {
            @Override public String getName() { return "book_file"; }
            @Override public String getOriginalFilename() { return book.getFileName(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public boolean isEmpty() { return size == 0; }
            @Override public long getSize() { return size; }
            @Override public byte[] getBytes() throws IOException {
                return java.nio.file.Files.readAllBytes(sourceFile.toPath());
            }
            @Override public java.io.InputStream getInputStream() throws IOException {
                return java.nio.file.Files.newInputStream(sourceFile.toPath());
            }
            @Override public void transferTo(File dest) throws IOException {
                java.nio.file.Files.copy(sourceFile.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        };

        // 复用 TtsService.createTtsTask：adminExempt=true 时不扣任何积分，
        // 任务以管理员身份入 tasks 表，但产物归 store_book.task_id 关联（即"书"拥有）。
        Long taskId = ttsService.createTtsTask(
                wrapped, null,
                hasReference ? referenceFileName : null,
                extension,
                requester,
                hasVoice ? voiceId : null,
                null,
                bookId          // 书城书 id：异步提取时据此落正文快照（W1）
        );

        // 回写 store_books.task_id + 合成审计字段：单独小事务，避免 createTtsTask 的 IO 与元数据写耦合。
        // 上面那次 book.getTaskId() 检查只是快速失败，不能当并发闸门——检查与写回之间夹着整个
        // createTtsTask（落盘 + 建任务，大书可达分钟级），所以真正的判定在条件 UPDATE 的 WHERE 里。
        if (!self.bindSynthTaskToBook(bookId, taskId, requester)) {
            // 期间已有其他请求抢先绑定：本次任务白建了，主动回收，避免留下无主任务、
            // 已级联入库的段落行，以及 createTtsTask 已落盘的书籍副本
            cleanupOrphanSynthTask(bookId, taskId);
            throw new IllegalArgumentException("本书已被其他管理员发起合成，请刷新后查看任务状态");
        }
        return taskId;
    }

    /**
     * 回收并发冲突下白建的合成任务：删任务行（audio_segments / reading_progress 由外键级联删除），
     * 并尽力清理 {@code createTtsTask} 已落盘到 uploads/ 的书籍副本。
     *
     * <p>整体 best-effort：失败只记日志，不能让它把「已被其他管理员合成」这个真正的报错信号盖掉。
     * 先取出 fileName 再删行——行删掉后就查不到副本名了。
     */
    private void cleanupOrphanSynthTask(Long bookId, Long taskId) {
        try {
            String bookFilePath = taskRepository.findById(taskId)
                    .map(com.audiobook.server.model.Task::getBookFilePath)
                    .orElse(null);
            // 删行之前先打「任务已删除」标记：createTtsTask 的 dispatchAfterCommit 早已把本任务
            // 派发进合成线程池，而工作线程只认这个内存标记（不查库）。少了这一步，工作线程会继续
            // 为已删任务插段落、逐段合成并写 uploads/{taskId}/，留下无归属的 audio_segments 孤儿行
            // 与孤儿音频目录，并白烧整本书的算力。
            // 与 deleteTask（TtsTaskController）和 deleteUserInTx（本类 210 行）保持同一口径。
            VoiceCloningTtsService.markTaskDeleted(taskId);
            taskRepository.deleteById(taskId);
            if (bookFilePath != null && !bookFilePath.isBlank()) {
                File uploaded = resolveUploadFile(bookFilePath);
                if (uploaded.exists() && !uploaded.delete()) {
                    log.warn("孤儿任务的书籍副本删除失败: {}", uploaded.getAbsolutePath());
                }
            }
            log.warn("书城合成并发冲突，已回收本次孤儿任务: bookId={}, taskId={}", bookId, taskId);
        } catch (Exception e) {
            log.error("回收孤儿合成任务失败（需人工清理）: bookId={}, taskId={}, 原因: {}",
                    bookId, taskId, e.getMessage());
        }
    }

    /**
     * 把合成任务回写到 store_books（短事务：仅一条条件 UPDATE；不与 createTtsTask 的事务重叠）。
     *
     * <p>WHERE 带 {@code taskId IS NULL} 作为并发闸门，见
     * {@link StoreBookRepository#bindSynthTaskIfUnbound}。
     *
     * @return true = 本次绑定成功；false = 已被并发请求抢先绑定（0 行受影响）
     */
    @Transactional
    public boolean bindSynthTaskToBook(Long bookId, Long taskId, String requester) {
        int updated = storeBookRepository.bindSynthTaskIfUnbound(
                bookId, taskId, LocalDateTime.now(), requester);
        if (updated > 0) {
            log.info("书城合成任务绑定: bookId={}, taskId={}, requester={}", bookId, taskId, requester);
        }
        return updated > 0;
    }

    /**
     * 解绑一次没成功的合成任务（短事务）。
     *
     * <p>单独成一个事务方法：条件 UPDATE 需要事务边界，且必须经 self 代理调用才生效。
     */
    @Transactional
    public int clearSynthBinding(Long bookId, Long taskId) {
        return storeBookRepository.clearSynthBinding(bookId, taskId);
    }

    // ---------- 书城合成语音：暂停 / 继续 / 删除语音 ----------

    /**
     * 取「这本书当前绑定的合成任务」，并把任务缺失/状态不适用挡在业务层。
     *
     * <p>三类调用方（暂停 / 继续 / 删除语音）共用同一套前置判定，避免三处各写一遍
     * 「taskId 为空 / 任务行已被删」的分支后各自漂移。
     *
     * @param requirePaused true = 仅接受 paused 状态（继续合成用）
     * @param rejectRunning true = 拒绝仍在跑的任务（删除语音用）
     */
    private Task requireStoreBookSynthTask(Long bookId, boolean requirePaused, boolean rejectRunning) {
        if (bookId == null) {
            throw new IllegalArgumentException("bookId 不能为空");
        }
        StoreBook book = storeBookRepository.findById(bookId)
                .orElseThrow(() -> new IllegalArgumentException("书籍不存在: " + bookId));
        Long taskId = book.getTaskId();
        if (taskId == null) {
            throw new IllegalArgumentException("《" + book.getTitle() + "》尚未合成语音，无需该操作");
        }
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            // 任务行没了（被别处删掉）而 task_id 还挂着：顺手把悬挂绑定解掉，
            // 否则这本书永远卡在「已合成过音频（状态=已删除），暂不支持重新合成」。
            self.clearSynthBinding(bookId, taskId);
            throw new IllegalArgumentException("本书的合成任务记录已不存在，绑定已清理，请刷新后重新发起合成");
        }
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (requirePaused) {
            if (status != TaskStatus.PAUSED) {
                throw new IllegalArgumentException(
                        "本书当前状态为「" + task.getStatus() + "」，只有已暂停的合成才能继续");
            }
        } else if (rejectRunning && status != null && status.isUnfinished()) {
            // 仍在跑的任务直接删：工作线程可能正在写第 N 段，删完目录它又把目录与
            // 段落行重建出来，留下无主 mp3 与孤儿 audio_segments。与 TtsTaskController
            // 的 deleteTask 同口径：先让管理员暂停（工作线程会在检查点收手）再删。
            throw new IllegalArgumentException("本书正在合成中，请先点「暂停」再删除语音");
        }
        return task;
    }

    /**
     * 暂停一本书的合成：记录 paused 状态并通知工作线程在下一个段落检查点收手。
     *
     * <p>「记录状态」与「通知线程」是两件事，顺序不能反：先落库 paused，
     * 再打内存标记。反过来的话，工作线程可能在两次写之间又心跳了一次，
     * 把刚落库的 paused 覆盖成 processing（心跳守卫只能挡后续那一次）。
     *
     * <p>不落终态：已完成的段与 mp3 全部保留，进度停在 completedSegments，
     * 管理员点「继续合成」即从断点续跑。
     */
    public void pauseBookSynthesis(Long bookId, String requester) {
        Task task = requireStoreBookSynthTask(bookId, false, false);
        TaskStatus status = TaskStatus.of(task.getStatus());
        if (status == null || !status.isUnfinished()) {
            throw new IllegalArgumentException(
                    "本书当前状态为「" + task.getStatus() + "」，无需暂停");
        }
        // 条件 UPDATE：派发与本请求之间任务可能已跑到终态，那时 0 行，绝不把完成的任务改写成暂停
        int updated = self.pauseTaskInDb(task.getId());
        if (updated == 0) {
            throw new IllegalArgumentException("任务状态刚刚发生变化，请刷新后重试");
        }
        VoiceCloningTtsService.markTaskPaused(task.getId());
        redisTaskService.updateTaskStatus(task.getId(), TaskStatus.PAUSED,
                task.getCompletedSegments(), task.getTotalSegments());
        log.info("管理员暂停书城合成: bookId={}, taskId={}, progress={}/{}, requester={}",
                bookId, task.getId(), task.getCompletedSegments(), task.getTotalSegments(), requester);
    }

    /** 短事务：条件 UPDATE 写 paused（见 {@link #pauseBookSynthesis}） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int pauseTaskInDb(Long taskId) {
        return taskRepository.pauseIfInStatus(taskId, TaskStatus.PAUSED.value(),
                TaskStatus.unfinishedValues());
    }

    /**
     * 继续合成：清掉暂停标记、把状态改回 queued 并重新派发。
     *
     * <p>清标记与改状态之后无条件调 {@code processTtsTaskAsync} 是刻意的：
     * 若旧工作线程还没退完（点「继续」时它正处在某一段里），它内部 runningTasks 去重会
     * 把这次派发跳过 —— 但标记已清，它本来就会在下一轮检查点继续跑完；
     * 若旧线程已退出，这次派发就是正常的新一轮。两条路径最终都会继续合成。
     *
     * <p>派发被队列拒绝（AbortPolicy）时把状态退回 paused：让界面如实显示「已暂停」，
     * 管理员稍后再点一次即可，而不是留一个永远排在队列外的 queued。
     */
    public void resumeBookSynthesis(Long bookId, String requester) {
        Task task = requireStoreBookSynthTask(bookId, true, false);
        Long taskId = task.getId();
        VoiceCloningTtsService.clearTaskPaused(taskId);
        if (self.resumeTaskInDb(taskId) == 0) {
            // 期间被并发删除或已被别的操作改状态：内存标记已清，界面刷新即可看到真实状态
            throw new IllegalArgumentException("任务状态刚刚发生变化，请刷新后重试");
        }
        redisTaskService.updateTaskStatus(taskId, TaskStatus.QUEUED,
                task.getCompletedSegments(), task.getTotalSegments());
        try {
            ttsService.processTtsTaskAsync(taskId);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.error("合成队列已满，继续合成被拒绝，退回暂停状态: taskId={}", taskId, e);
            if (taskRepository.pauseIfInStatus(taskId, TaskStatus.PAUSED.value(),
                    List.of(TaskStatus.QUEUED.value())) > 0) {
                redisTaskService.updateTaskStatus(taskId, TaskStatus.PAUSED,
                        task.getCompletedSegments(), task.getTotalSegments());
            }
            throw new IllegalArgumentException("合成队列已满，请稍后再点「继续合成」");
        }
        log.info("管理员继续书城合成: bookId={}, taskId={}, progress={}/{}, requester={}",
                bookId, taskId, task.getCompletedSegments(), task.getTotalSegments(), requester);
    }

    /** 短事务：条件 UPDATE 把 paused 改回 queued（见 {@link #resumeBookSynthesis}） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resumeTaskInDb(Long taskId) {
        return taskRepository.resumeIfPaused(taskId, TaskStatus.PAUSED.value(), TaskStatus.QUEUED.value());
    }

    /**
     * 继续合成（补齐缺失段）：partial / failed 的任务只重跑没成功的段，已有段与 mp3 原样保留。
     *
     * <p>### 为什么不能直接复用 {@link #resumeBookSynthesis}
     *
     * 那条路径只认 paused，而且对段落不做任何处理 —— 它假设「工作线程在检查点收手，
     * 状态都是干净的」。partial 完全不同：失败段在库里是 {@code failed}，
     * 不把它们重置成 {@code pending}，工作线程下一轮会把它们当作已处理跳过，
     * 任务立刻又回到 partial。管理员点一次白点一次，还会以为按钮坏了。
     *
     * <p>### 重试范围 = failed + pending
     *
     * 与用户端 {@code TtsTaskController#retryTask} 同口径。pending 段来自
     * 「合成节点不可用导致任务被中断」—— 它们没有失败、只是还没轮到；
     * 只认 failed 会让这类任务报「没有可补的片段」从而无法续跑。
     *
     * <p>### 为什么不涉及积分
     *
     * 书城任务由 {@code createTtsTask(adminExempt=true)} 创建，全链路 0 积分，
     * 因此这里没有 {@code chargeForRetry} 那套补扣与退款逻辑。
     *
     * <p>### 派发被队列拒绝时回滚
     *
     * 状态退回原来的 partial / failed（而不是停在 queued）：界面如实显示仍待补齐，
     * 管理员稍后再点一次即可。段落已被重置为 pending，仍在下次的重试范围内，不会丢工作。
     */
    public void retryBookSynthesis(Long bookId, String requester) {
        Task task = requireStoreBookSynthTask(bookId, false, false);
        String fromStatus = task.getStatus();
        if (!TaskStatus.allowsGapRetry(fromStatus)) {
            throw new IllegalArgumentException("本书当前状态为「" + fromStatus
                    + "」，只有「部分完成」或「合成失败」的合成才能继续补齐");
        }
        Long taskId = task.getId();
        // 先查有没有可补的段：状态是 partial/failed 却一段 failed/pending 都没有，
        // 说明数据自相矛盾（多半是人工改过库）。这时派发下去只会空跑一轮又回到原状态，
        // 直接拒绝并说明，比让管理员反复点一个「没反应」的按钮更有用。
        List<Long> pendingIds = audioSegmentRepository.findIdsByTaskIdAndStatusIn(
                taskId, List.of(SegmentStatus.FAILED.value(), SegmentStatus.PENDING.value()));
        if (pendingIds.isEmpty()) {
            throw new IllegalArgumentException("没有需要补合成的片段：本书所有片段都已完成，"
                    + "但任务状态仍为「" + fromStatus + "」。请刷新列表；若仍未变，请联系运维核查");
        }
        int reset = audioSegmentRepository.resetStatesByTaskId(
                taskId, SegmentStatus.PENDING.value(),
                List.of(SegmentStatus.FAILED.value(), SegmentStatus.PENDING.value()));
        if (self.requeueTaskInDb(taskId, fromStatus) == 0) {
            // 期间被并发重试/删除改过状态：段落已重置为 pending，界面刷新即可看到真实状态
            throw new IllegalArgumentException("任务状态刚刚发生变化，请刷新后重试");
        }
        redisTaskService.updateTaskStatus(taskId, TaskStatus.QUEUED,
                task.getCompletedSegments(), task.getTotalSegments());
        try {
            ttsService.processTtsTaskAsync(taskId);
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.error("合成队列已满，补齐缺失段被拒绝，状态回滚: taskId={}, status={}", taskId, fromStatus, e);
            if (taskRepository.setStatusIfInStatus(taskId, fromStatus,
                    List.of(TaskStatus.QUEUED.value())) > 0) {
                redisTaskService.updateTaskStatus(taskId, TaskStatus.of(fromStatus),
                        task.getCompletedSegments(), task.getTotalSegments());
            }
            throw new IllegalArgumentException("合成队列已满，请稍后再点「继续合成」");
        }
        log.info("管理员补齐书城合成缺失段: bookId={}, taskId={}, 重排片段={}, 进度={}/{}, requester={}",
                bookId, taskId, reset, task.getCompletedSegments(), task.getTotalSegments(), requester);
    }

    /** 短事务：条件 UPDATE 把 partial / failed 改回 queued（见 {@link #retryBookSynthesis}） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int requeueTaskInDb(Long taskId, String fromStatus) {
        return taskRepository.setStatusIfInStatus(taskId, TaskStatus.QUEUED.value(), List.of(fromStatus));
    }

    /**
     * 删除一本书已合成的语音：清音频文件、段落记录、任务行，并解绑 store_books.task_id。
     *
     * <p>顺序与 {@code TtsTaskController.deleteTask} 完全一致，且刻意把「解绑」放在最后：
     * 只有音频与任务都清干净了，书才回到「未合成」状态、可重新发起；
     * 中途失败就停在「仍绑定」上，管理员重试即可，不会出现「书显示未合成、音频却还在」的错位。
     *
     * <p>同时清掉暂停标记：删除的是这个 taskId 的全部产物，留着标记只会让
     * 自增 ID 回绕复用后的新任务被误判成暂停。
     */
    public void deleteBookAudio(Long bookId, String requester) {
        Task task = requireStoreBookSynthTask(bookId, false, true);
        Long taskId = task.getId();
        String bookFilePath = task.getBookFilePath();

        // 1) 打「已删除」标记：paused 任务的工作线程可能正处在段内，这一手保证它
        //    下一个检查点就收手，不会往即将被删的目录里继续写 mp3 / 段落行。
        VoiceCloningTtsService.markTaskDeleted(taskId);
        VoiceCloningTtsService.clearTaskPaused(taskId);
        // 2) 清 Redis 登记，避免删完仍被统计为进行中。
        //    必须用**任务所有者**而不是当前管理员：cleanupCompletedTask 会去减该用户键下的
        //    「进行中」计数桶，减错人等于只清了管理员自己的桶，原主人的计数会一直虚高到 TTL 过期。
        String ownerName = userRepository.findById(task.getUserId())
                .map(User::getUsername)
                .orElse(requester);
        redisTaskService.cleanupCompletedTask(taskId, ownerName);
        // 3) 磁盘：任务音频目录 uploads/{taskId}/ + 该任务在 uploads/ 下的书籍副本
        deleteTaskFilesQuietlyById(taskId);
        deleteBookCopyQuietly(bookFilePath);
        // 4) 数据库：先片段后任务（单事务，不依赖外键级联），见 deleteSynthTaskDataInTx
        self.deleteSynthTaskDataInTx(taskId);
        // 5) 解绑：回到「未合成」，可以重新发起
        int unbound = self.clearSynthBinding(bookId, taskId);
        if (unbound == 0) {
            // 0 = task_id 已不是这个 taskId（本来就没绑，或已被并发换绑）：
            // 换绑的情况绝不能覆盖，只需记日志让管理员确认界面状态。
            log.warn("删除语音后解绑返回 0 行（task_id 可能已指向其它任务）: bookId={}, taskId={}", bookId, taskId);
        }
        log.info("管理员删除书城语音: bookId={}, taskId={}, requester={}", bookId, taskId, requester);
    }

    /** 尽力删除任务在 uploads/ 下落盘的电子书副本（参考音频可能被多任务复用，一律保留） */
    private void deleteBookCopyQuietly(String bookFilePath) {
        if (bookFilePath == null || bookFilePath.isBlank()) {
            return;
        }
        try {
            File copy = resolveUploadFile(bookFilePath);
            if (copy.exists() && copy.isFile() && !copy.delete()) {
                log.warn("删除任务书籍副本失败: {}", copy.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("删除任务书籍副本失败: path={}, error={}", bookFilePath, e.getMessage());
        }
    }

    /** 短事务：先删段落行再删任务行，避免第一步提交后第二步失败留下「片段已删、任务残留」 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteSynthTaskDataInTx(Long taskId) {
        audioSegmentRepository.deleteByTaskId(taskId);
        taskRepository.deleteById(taskId);
    }

    /**
     * 给书城管理列表补上「合成任务的实时状态」。
     *
     * <p>列表接口此前直接返回 {@code Page<StoreBook>}，前端只能看到 {@code taskId} 非空，
     * 无法区分「合成中 / 已暂停 / 已完成」，也就没法给出对应的操作按钮。
     *
     * <p>做法：一次 {@code findAllById} 把本页涉及的任务全捞出来（避免逐本查库的 N+1），
     * 再把实体转成 Map 后追加三个字段 —— 用 Jackson 转 Map 而不是手写字段拷贝，
     * 少写一个字段就静默丢一个字段，前端还看不出是后端漏的。
     */
    public List<java.util.Map<String, Object>> toStoreBookViews(List<StoreBook> books) {
        if (books == null || books.isEmpty()) {
            return List.of();
        }
        // 标签一次 IN 查询批量补齐（与列表页同一套逻辑），不填的话管理端拿不到 tags
        storeBookTagService.attachTags(books);
        List<Long> taskIds = books.stream()
                .map(StoreBook::getTaskId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        java.util.Map<Long, Task> taskMap = taskIds.isEmpty()
                ? java.util.Map.of()
                : taskRepository.findAllById(taskIds).stream()
                        .collect(java.util.stream.Collectors.toMap(Task::getId, t -> t));
        java.util.List<java.util.Map<String, Object>> views = new java.util.ArrayList<>(books.size());
        for (StoreBook book : books) {
            java.util.Map<String, Object> view = objectMapper.convertValue(book, BOOK_VIEW_MAP_TYPE);
            Task task = book.getTaskId() == null ? null : taskMap.get(book.getTaskId());
            view.put("taskStatus", task == null ? null : task.getStatus());
            view.put("taskCompletedSegments", task == null ? null : task.getCompletedSegments());
            view.put("taskTotalSegments", task == null ? null : task.getTotalSegments());
            view.put("taskErrorMessage", task == null ? null : task.getErrorMessage());
            views.add(view);
        }
        return views;
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>
            BOOK_VIEW_MAP_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { };

    /**
     * 批量合成：bookIds 为空时扫描所有 task_id IS NULL 的书，逐本调 synthesizeBookForStore。
     * 单本失败不影响其余——返回汇总结果，由管理端弹窗展示「成功 X / 跳过 Y / 失败 Z」。
     *
     * <p>与单本端点一样：adminExempt 自动 0 积分；
     * 单用户未完成任务数上限（{@code TtsTaskController.maxUnfinishedTasksPerUser}）由 createTtsTask 内部校验，
     * 命中后会抛 IllegalStateException，本方法计入 skipped。
     *
     * <p>不批量建任务是为了复用单本端点的全部鉴权与磁盘校验，避免规则在两处漂移。
     */
    public BatchSynthResult synthesizeBooksBatch(String referenceFileName, String voiceId,
                                                String requester, java.util.List<Long> bookIds) throws IOException {
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester（操作管理员）不能为空");
        }
        boolean hasReference = referenceFileName != null && !referenceFileName.isBlank();
        boolean hasVoice = voiceId != null && !voiceId.isBlank();
        if (!hasReference && !hasVoice) {
            throw new IllegalArgumentException("必须提供参考音频文件名或预设音色 voiceId");
        }
        java.util.List<Long> targets;
        if (bookIds != null && !bookIds.isEmpty()) {
            targets = new java.util.ArrayList<>(bookIds);
        } else {
            // 扫描所有未合成书：JPQL 直接 IN 子句过滤，task_id IS NULL
            targets = storeBookRepository.findIdsByTaskIdIsNull();
        }

        java.util.List<BatchItem> submitted = new java.util.ArrayList<>();
        java.util.List<BatchItem> skipped = new java.util.ArrayList<>();
        for (Long id : targets) {
            try {
                Long taskId = synthesizeBookForStore(id, referenceFileName, voiceId, requester);
                submitted.add(new BatchItem(id, taskId, null));
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : "合成失败";
                log.warn("批量合成跳过: bookId={}, reason={}", id, reason);
                skipped.add(new BatchItem(id, null, reason));
            }
        }
        log.info("批量合成完成: requester={}, 总计 {} 本，成功 {} 本，跳过 {} 本",
                requester, targets.size(), submitted.size(), skipped.size());
        return new BatchSynthResult(submitted, skipped);
    }

    /**
     * 批量补齐「上一轮没跑完」的书：partial / failed 的任务只重跑没成功的段。
     *
     * <p>### 为什么单独一个入口，而不是并进 {@link #synthesizeBooksBatch}
     *
     * 批量合成带参考音频 / 预设音色参数，语义是「给这些书**从头**做一版音频」。
     * 如果把 partial / failed 也混进去，管理员点「批量合成」时会不知道那些书是
     * 整本重烧（丢掉已有 6513 段）还是只补 4 段 —— 同一按钮两种代价，且与所选音色
     * 冲突（补段保留旧音色、重烧用新音色）。拆成两个动作后，按钮文案即语义：
     * 「批量合成」=从零，「批量补齐」=只补缺的，都不需要管理员猜。
     *
     * <p>### 台账
     *
     * 与批量合成同构：逐本调用，单本失败只记 skipped 不中断 —— 一本书的绑定刚被
     * 别人改掉，不该让剩下 200 本全都不补。
     */
    public BatchSynthResult batchGapRetry(String requester, java.util.List<Long> bookIds) {
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester（操作管理员）不能为空");
        }
        java.util.List<Long> targets;
        if (bookIds != null && !bookIds.isEmpty()) {
            targets = new java.util.ArrayList<>(bookIds);
        } else {
            targets = storeBookRepository.findIdsByGapRetryable();
        }
        java.util.List<BatchItem> submitted = new java.util.ArrayList<>();
        java.util.List<BatchItem> skipped = new java.util.ArrayList<>();
        for (Long id : targets) {
            // 先取绑定的任务 id 用于回执：补段完成后前端要能立刻定位到那条任务，
            // 只报 bookId 的话界面还得再刷一次列表才知道对应哪条。
            Long taskId = storeBookRepository.findById(id)
                    .map(StoreBook::getTaskId).orElse(null);
            try {
                retryBookSynthesis(id, requester);
                submitted.add(new BatchItem(id, taskId, null));
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : "补齐失败";
                log.warn("批量补齐跳过: bookId={}, reason={}", id, reason);
                skipped.add(new BatchItem(id, taskId, reason));
            }
        }
        log.info("批量补齐完成: requester={}, 总计 {} 本，已重新派发 {} 本，跳过 {} 本",
                requester, targets.size(), submitted.size(), skipped.size());
        return new BatchSynthResult(submitted, skipped);
    }

    /** 批量合成中单本的回执 */
    public record BatchItem(Long bookId, Long taskId, String message) {}

    /** 批量合成汇总：成功列表 + 失败/跳过列表 */
    public record BatchSynthResult(java.util.List<BatchItem> submitted,
                                   java.util.List<BatchItem> skipped) {}
}
