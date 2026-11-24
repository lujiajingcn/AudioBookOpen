package com.audiobook.server.dto;

import com.audiobook.server.util.DoneRanges;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 有声覆盖图单条请求体（POST /api/audio-coverage/sync 内嵌列表项）。
 *
 * <p>身份 = {@code bookKey}（客户端生成，前缀 s:/t:/n: 保证三端一致）+ {@code source}（server/local）。
 * 服务端**不重算 bookKey**，只搬运并按 service 的规则对 {@code doneRanges} 做并集。
 *
 * <p>{@code doneRanges} 是 1-based 闭区间压缩串（{@code "1-40,41,45-50"}，单点写 {@code "41"}），
 * 只含数字、逗号、连字符；单条上限 8192 字符（≈ 数千段的覆盖也够）。
 */
public class AudioCoverageItemRequest {

    @NotBlank(message = "bookKey 不能为空")
    @Size(max = 255, message = "bookKey 过长")
    private String bookKey;

    @NotBlank(message = "source 不能为空")
    @Pattern(regexp = "server|local", message = "source 只能是 server 或 local")
    private String source;

    @Size(max = 8192, message = "doneRanges 过长")
    @Pattern(regexp = "[0-9,\\-]*", message = "doneRanges 只能含数字/逗号/连字符")
    private String doneRanges;

    private Integer totalSegments;
    private Long storeBookId;
    private Long serverTaskId;

    /** doneRanges 结构校验：每个 token 要么 "n" 要么 "a-b"（a≤b，均 ≥1）；空串视为合法（无完成段） */
    @AssertTrue(message = "doneRanges 格式非法")
    public boolean isDoneRangesWellFormed() {
        return DoneRanges.isValid(doneRanges);
    }

    public String getBookKey() {
        return bookKey;
    }

    public void setBookKey(String bookKey) {
        this.bookKey = bookKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDoneRanges() {
        return doneRanges;
    }

    public void setDoneRanges(String doneRanges) {
        this.doneRanges = doneRanges;
    }

    public Integer getTotalSegments() {
        return totalSegments;
    }

    public void setTotalSegments(Integer totalSegments) {
        this.totalSegments = totalSegments;
    }

    public Long getStoreBookId() {
        return storeBookId;
    }

    public void setStoreBookId(Long storeBookId) {
        this.storeBookId = storeBookId;
    }

    public Long getServerTaskId() {
        return serverTaskId;
    }

    public void setServerTaskId(Long serverTaskId) {
        this.serverTaskId = serverTaskId;
    }
}
