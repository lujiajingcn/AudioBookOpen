package com.audiobook.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 客户端发布版本（Android APK 分发）。
 *
 * 一张表同时容纳多平台（当前只有 android），以 platform 区分；同一平台内
 * version_code 唯一，避免重复上传同一个版本出现两条互相打架的记录。
 *
 * released 是「对外是否可见」的开关：上传后默认 released=false（草稿），
 * 管理员确认无误再发布。发布（{@code /api/admin/app-version/{id}/release}）
 * 会把同平台其它记录全部下线，因此「当前对外版本」恒为
 * {@code platform + released=true + version_code 最大} 的唯一一条。
 *
 * 注意：versionCode / versionName 由管理员上传时手填，服务端不做 APK 解析
 * （解析 APK 的二进制 AndroidManifest 需要引入 aapt/apkanalyzer 一类重依赖，
 * 不值得）。为避免手填出错导致客户端永远检查不到更新，上传接口会校验
 * versionCode 必须大于当前已发布版本，否则拒收。
 */
@Entity
@Table(
    name = "app_versions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_app_version_platform_code", columnNames = {"platform", "version_code"})
    },
    indexes = {
        // 客户端检查更新：WHERE platform=? AND released=1 ORDER BY version_code DESC LIMIT 1
        @Index(name = "idx_app_version_lookup", columnList = "platform, released, version_code")
    }
)
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 平台标识，当前固定 android（预留 iOS / Web） */
    @Column(name = "platform", nullable = false, length = 32)
    private String platform = "android";

    /** 客户端 BuildConfig.VERSION_CODE，比较大小的唯一依据（versionName 只用于展示） */
    @Column(name = "version_code", nullable = false)
    private Integer versionCode;

    /** 展示用版本号，如 1.2.0 */
    @Column(name = "version_name", nullable = false, length = 64)
    private String versionName;

    /** APK 在上传目录下的相对路径（uploads/app/ 子目录），复用 Task.referenceAudioPath 的语义 */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** APK 的 MD5，客户端下载完成后校验，防止下载被截断/代理篡改后安装损坏包 */
    @Column(name = "md5", length = 64)
    private String md5;

    /** 更新说明（多行文本），客户端在确认弹窗中展示 */
    @Column(name = "changelog", columnDefinition = "TEXT")
    private String changelog;

    /** 是否强制更新：true 时客户端弹窗不可取消，不更新无法进入应用 */
    @Column(name = "force_update", nullable = false)
    private Boolean forceUpdate = Boolean.FALSE;

    /** 是否已发布（对外可见） */
    @Column(name = "released", nullable = false)
    private Boolean released = Boolean.FALSE;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    public AppVersion() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Integer getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(Integer versionCode) {
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public String getChangelog() {
        return changelog;
    }

    public void setChangelog(String changelog) {
        this.changelog = changelog;
    }

    public Boolean getForceUpdate() {
        return forceUpdate;
    }

    public void setForceUpdate(Boolean forceUpdate) {
        this.forceUpdate = forceUpdate;
    }

    public boolean isForceUpdate() {
        return Boolean.TRUE.equals(forceUpdate);
    }

    public Boolean getReleased() {
        return released;
    }

    public void setReleased(Boolean released) {
        this.released = released;
    }

    public boolean isReleased() {
        return Boolean.TRUE.equals(released);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }
}
