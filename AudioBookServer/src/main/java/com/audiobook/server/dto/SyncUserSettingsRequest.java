package com.audiobook.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * PUT /api/user-settings 请求体：客户端上报本机全部白名单设置的现值 + 各自版本。
 *
 * <p>一次往返同时完成「拉 + 推」：服务端逐项 LWW 合并后返回<b>权威全量</b>，
 * 客户端直接用响应覆盖本地水位，不需要再发一次 GET。
 *
 * <p>护栏：最多 32 项（实际只有 playback_speed / default_voice_id 两项），
 * 防错乱客户端或恶意批量灌入。非白名单 key 由 Service 静默忽略（老客户端多传字段不炸）。
 */
public class SyncUserSettingsRequest {

    @NotNull(message = "items 不能为空")
    @Size(max = 32, message = "单次同步最多 32 项设置")
    @Valid
    private List<UserSettingItemRequest> items;

    public List<UserSettingItemRequest> getItems() {
        return items;
    }

    public void setItems(List<UserSettingItemRequest> items) {
        this.items = items;
    }
}
