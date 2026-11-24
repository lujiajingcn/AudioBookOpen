package com.audiobook.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量同步请求体：客户端把本地（离线 / 升级前）已存在的收藏一次性上报，
 * 服务端按 (userId, bookName, segmentNumber) 与已有记录合并去重，返回合并后的全量列表。
 *
 * 用于「收藏上云」的首次迁移与离线补传，避免只上云新收藏、老收藏永远留在一台设备上。
 */
public class SyncFavoritesRequest {

    /**
     * 总量护栏：单条 content 上限 20000 字、条数上限 2000，两者相乘的理论单请求体量
     * 可达 4000 万字；而 JSON 请求体不受 multipart 的 max-request-size 约束
     * （那个配置只管 multipart），Jackson 会在写库前把所有条目全部实例化进堆——
     * 单个已登录用户提交一个超大 body 即可把服务打爆（OOM）。
     * 这里按「总字符数」设上限（200 万字符 ≈ 6MB UTF-8），远大于真实同步量
     * （客户端只在首次上云 / 离线补传时批量上报）。
     */
    private static final long MAX_TOTAL_CONTENT_CHARS = 2_000_000L;

    @NotNull(message = "favorites 不能为空")
    @Size(max = 2000, message = "单次同步最多 2000 条")
    @Valid
    private List<FavoriteItemRequest> favorites;

    /** 见 {@link #MAX_TOTAL_CONTENT_CHARS}；正常请求不受影响，超限由全局异常处理器映射为 400 */
    @AssertTrue(message = "单次同步的内容总量过大，请分批上报")
    public boolean isTotalContentWithinLimit() {
        if (favorites == null) {
            return true;
        }
        long total = 0;
        for (FavoriteItemRequest f : favorites) {
            if (f == null || f.getContent() == null) {
                continue;
            }
            total += f.getContent().length();
            if (total > MAX_TOTAL_CONTENT_CHARS) {
                return false;
            }
        }
        return true;
    }

    public List<FavoriteItemRequest> getFavorites() {
        return favorites;
    }

    public void setFavorites(List<FavoriteItemRequest> favorites) {
        this.favorites = favorites;
    }
}
