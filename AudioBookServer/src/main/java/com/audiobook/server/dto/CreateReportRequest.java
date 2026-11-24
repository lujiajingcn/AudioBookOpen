package com.audiobook.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 举报请求体。
 *
 * targetType 取值 POST / COMMENT；targetId 为被举报对象的主键；reason 为举报理由。
 */
public class CreateReportRequest {

    @NotBlank(message = "举报类型不能为空")
    private String targetType;

    @NotNull(message = "举报目标 id 不能为空")
    private Long targetId;

    @NotBlank(message = "举报理由不能为空")
    @Size(max = 500, message = "举报理由不能超过 500 字")
    private String reason;

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
