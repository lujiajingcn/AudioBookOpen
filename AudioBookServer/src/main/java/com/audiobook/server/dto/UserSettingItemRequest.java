package com.audiobook.server.dto;

/**
 * 单条用户设置（PUT /api/user-settings 的 items 元素，也是响应元素）。
 *
 * <p>{@code updatedAt} 是客户端该项<b>最后一次被用户修改</b>的时刻（毫秒 epoch）；
 * 若该项来自上一次服务端响应，则原样回传服务端给的单调版本号。
 * 服务端据此做「版本大者胜」的 LWW 裁决，见 {@code UserSettingsService}。
 */
public class UserSettingItemRequest {

    private String key;
    private String value;
    private Long updatedAt;

    public UserSettingItemRequest() {
    }

    public UserSettingItemRequest(String key, String value, Long updatedAt) {
        this.key = key;
        this.value = value;
        this.updatedAt = updatedAt;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
