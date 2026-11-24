package com.audiobook.server.util;

/**
 * LWW（Last-Write-Wins）单调版本号裁决，供 {@code user_settings} / {@code user_book_meta} 共用。
 *
 * <p><b>为什么需要单独一个工具</b>：现有 9 类跨端同步都是集合型（add-no-delete 并集），
 * 单值偏好套并集会变成「旧值永远赢、用户改了设置同步不过去」的静默 bug。
 * 两张新表必须共享同一套版本语义，否则一端按版本、一端按时间，冲突时会互相回退。
 *
 * <p><b>版本不是数据库时间</b>，而是服务端裁决出的单调整数（毫秒 epoch 起点）：
 * <ul>
 *   <li>客户端上报的 {@code updatedAt} = 该项在本地最后一次被用户修改的时刻；
 *       若该项来自上次服务端响应，则原样回传服务端给的版本。</li>
 *   <li>客户端只在「本地版本严格大于服务端版本」时才有资格覆盖。</li>
 *   <li>服务端把新版本抬到 {@code max(上报版本, 旧版本+1)}，首次插入再取
 *       {@code max(上报版本, now)} ⇒ 严格递增，天然免疫同毫秒并发与客户端时钟回拨。</li>
 * </ul>
 * 客户端在本地产生新修改时应取 {@code max(now, 本地水位+1)}，保证严格大于水位。
 */
public final class LwwVersions {

    private LwwVersions() {
    }

    /** 客户端上报的版本是否有资格覆盖服务端当前版本。 */
    public static boolean shouldOverwrite(Long incoming, long existingVersion) {
        long in = incoming == null ? 0L : Math.max(0L, incoming);
        return in > existingVersion;
    }

    /** 已有行：裁决出新版本（严格大于旧版本，也严格不小于客户端上报值）。 */
    public static long nextVersion(Long incoming, long existingVersion) {
        long in = incoming == null ? 0L : Math.max(0L, incoming);
        return Math.max(in, existingVersion + 1);
    }

    /** 首次插入：以当前时钟为下界，避免客户端上报 0 时版本号停在 0。 */
    public static long firstVersion(Long incoming) {
        long in = incoming == null ? 0L : Math.max(0L, incoming);
        return Math.max(in, System.currentTimeMillis());
    }
}
