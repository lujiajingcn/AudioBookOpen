package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 书城书籍实体。
 * 与 TTS 任务的 Task 不同，这是面向所有用户的在线书籍目录，
 * 书籍文件统一存放在服务端 store_books/<分类>/ 目录下。
 */
@Entity
@Table(name = "store_books",
        indexes = @jakarta.persistence.Index(name = "idx_store_book_task_id", columnList = "task_id"))
public class StoreBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 书名 */
    @Column(nullable = false, length = 200)
    private String title;

    /**
     * 原书名（可选）。管理端上传时的附加信息，用于记录这本书在上架命名之前的原始名称
     * （如译名对照、系列内原始标题、PDF 版权页名称等）。
     * 与 {@link #title} 无关：title 是上架展示名，此处仅作存档，NULL 表示未填写。
     */
    @Column(name = "original_title", length = 200)
    private String originalTitle;

    /** 作者 */
    @Column(length = 100)
    private String author;

    /** 分类（对应 store_books 下的子目录名） */
    @Column(length = 50)
    private String category;

    /** 简介 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 书籍文件名（store_books/<category>/ 下的实际文件名） */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /** 文件扩展名（txt/pdf/epub/mobi） */
    @Column(name = "file_extension", length = 10)
    private String fileExtension;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** 封面背景色（由书名 hash 生成，用于动态封面） */
    @Column(name = "cover_color", length = 10)
    private String coverColor;

    /** 自定义封面图片路径（上传后覆盖动态封面；为空则用 coverColor 动态生成） */
    @Column(name = "cover_image", length = 500)
    private String coverImage;

    /** 下载次数 */
    @Column(name = "download_count")
    private Integer downloadCount;

    /**
     * 是否编辑推荐（书城首页「编辑推荐 / 热门书单」入口展示）。
     * 默认 false；由管理端 {@code POST /api/admin/store/books/{id}/editor-pick} 切换。
     */
    @Column(name = "editor_pick")
    private Boolean editorPick = false;

    /**
     * 关联的合成任务 id：管理员在「书城管理」对该书发起 TTS 合成后填写，用于用户端
     * 「下载音频」（{@code GET /api/store/book/{id}/audio/download}）时定位 AudioSegment 列表。
     * NULL 表示尚未合成。合成后由 {@code AdminService.synthesizeBookForStore} 回填。
     *
     * 与 {@code tasks.user_id} 不同：合成任务由管理员以管理员身份提交（adminExempt 免积分），
     * 但其产物属于「这本书」而非管理员本人。下载鉴权走书级路径，不校验任务归属。
     */
    @Column(name = "task_id")
    private Long taskId;

    /**
     * 合成完成时间（最新一次合成成功落库的时间），NULL 表示尚未合成。
     * 仅展示用，不参与业务判定；用户端「是否有音频」优先看 {@link #taskId}。
     */
    @Column(name = "synthesized_at")
    private LocalDateTime synthesizedAt;

    /**
     * 触发该合成任务的管理员用户名（审计用），NULL 表示尚未合成。
     */
    @Column(name = "synthesized_by", length = 100)
    private String synthesizedBy;

    /**
     * 上架状态：onshelf=在售（默认），offshelf=已下架（磁盘文件缺失）。
     * 扫描时若远程（磁盘）文件已删除，则把本地对应书置为 offshelf，与类注释“文件被删除的下架”一致。
     */
    @Column(length = 20)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 标签（一本书可有多个）。**不落库**：真实数据在 {@code store_book_tags} 关联表里，
     * 这里只是一个随响应一起返回的只读视图字段。
     *
     * <p>为什么做成 @Transient 而不是 {@code @ManyToMany}：书城列表一次分页只取 20~24 本，
     * 标签由 {@code StoreBookTagService.attachTags} 用一次 {@code IN} 查询批量补齐；
     * 建关系后 Hibernate 每次加载书都会顺带抓标签，要么 N+1，要么与分页查询产生
     * 集合抓取/笛卡尔积问题。写入一律走 {@code StoreBookTagService}，不要直接改这个字段。
     *
     * <p>默认空列表而非 null：前端可以直接 {@code book.tags.length}，不必到处判空。
     */
    @Transient
    private List<String> tags = new ArrayList<>();

    public StoreBook() {
        this.downloadCount = 0;
        this.status = "onshelf";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---------- Getters & Setters ----------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public void setOriginalTitle(String originalTitle) {
        this.originalTitle = originalTitle;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getCoverColor() {
        return coverColor;
    }

    public void setCoverColor(String coverColor) {
        this.coverColor = coverColor;
    }

    public String getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(String coverImage) {
        this.coverImage = coverImage;
    }

    public Integer getDownloadCount() {
        return downloadCount;
    }

    public void setDownloadCount(Integer downloadCount) {
        this.downloadCount = downloadCount;
    }

    public Boolean getEditorPick() {
        return editorPick;
    }

    public void setEditorPick(Boolean editorPick) {
        this.editorPick = editorPick;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public LocalDateTime getSynthesizedAt() {
        return synthesizedAt;
    }

    public void setSynthesizedAt(LocalDateTime synthesizedAt) {
        this.synthesizedAt = synthesizedAt;
    }

    public String getSynthesizedBy() {
        return synthesizedBy;
    }

    public void setSynthesizedBy(String synthesizedBy) {
        this.synthesizedBy = synthesizedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 标签列表（只读视图，写入走 StoreBookTagService；null 归一为空列表） */
    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }
}
