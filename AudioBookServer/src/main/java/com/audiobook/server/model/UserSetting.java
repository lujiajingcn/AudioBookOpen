package com.audiobook.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用户级跨端单值偏好（LWW）。
 *
 * <p><b>与现有 9 类同步数据的根本区别</b>：那些是集合型，合并语义是 add-no-delete 并集；
 * 本表是<b>单值覆盖型</b> —— 用户把倍速从 1.0 改成 1.5 必须能覆盖旧值。
 * 因此用单调递增的 {@code updatedAt} 版本号做 LWW（版本大者胜），
 * 见 {@link com.audiobook.server.service.UserSettingsService}。
 *
 * <p>值统一存字符串：避免为每种偏好加一列，也避免 Float/String 混存带来的精度口径问题。
 * 键由服务端白名单校验（{@code UserSettingsService.ALLOWED_KEYS}），
 * 客户端不能借本表写入任意数据。
 */
@Entity
@Table(name = "user_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_us_user_key", columnNames = {"user_id", "setting_key"}),
        indexes = {
                @Index(name = "idx_us_user", columnList = "user_id")
        })
public class UserSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "setting_key", nullable = false, length = 64)
    private String settingKey;

    @Column(name = "setting_value", length = 512)
    private String settingValue;

    /** LWW 单调版本号（毫秒 epoch，由服务端裁决，不是数据库时间） */
    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    public UserSetting() {
    }

    public UserSetting(Long userId, String settingKey, String settingValue, long updatedAt) {
        this.userId = userId;
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSettingKey() {
        return settingKey;
    }

    public void setSettingKey(String settingKey) {
        this.settingKey = settingKey;
    }

    public String getSettingValue() {
        return settingValue;
    }

    public void setSettingValue(String settingValue) {
        this.settingValue = settingValue;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
