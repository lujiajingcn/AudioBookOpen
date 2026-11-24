package com.audiobook.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 有声覆盖图批量同步请求体（POST /api/audio-coverage/sync）。
 *
 * <p>客户端把本机当前所有"有声覆盖图"条目一次性上报（首次迁移 / 换设备 / 离线补传），
 * 服务端按 (userId, bookKey, source) 对 doneRanges 做**并集**合并后返回全量。
 * 纯元数据感知层，无音频字节，故不存在收藏那种"护栏防误删"风险（不删本地音频）。
 *
 * <p>护栏：条数上限 5000（一本书通常 1~2 条：server 源 + local 源；数千本书才可能触顶），
 * doneRanges 总字符上限 400 万。真实远小于此，上限仅防错乱/恶意批量。
 */
public class SyncAudioCoverageRequest {

    private static final long MAX_TOTAL_RANGE_CHARS = 4_000_000L;

    @NotNull(message = "items 不能为空")
    @Size(max = 5000, message = "单次同步最多 5000 条")
    @Valid
    private List<AudioCoverageItemRequest> items;

    @AssertTrue(message = "单次同步的覆盖串总量过大，请分批上报")
    public boolean isTotalRangesWithinLimit() {
        if (items == null) {
            return true;
        }
        long total = 0;
        for (AudioCoverageItemRequest it : items) {
            if (it == null || it.getDoneRanges() == null) {
                continue;
            }
            total += it.getDoneRanges().length();
            if (total > MAX_TOTAL_RANGE_CHARS) {
                return false;
            }
        }
        return true;
    }

    public List<AudioCoverageItemRequest> getItems() {
        return items;
    }

    public void setItems(List<AudioCoverageItemRequest> items) {
        this.items = items;
    }
}
