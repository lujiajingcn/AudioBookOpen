package com.audiobook.server.dto;

import java.time.LocalDateTime;

public class TtsTaskResponse {
    private Long taskId;
    private String status;
    private Integer totalSegments;
    private Integer completedSegments;
    private LocalDateTime createdAt;
    private String originalBookName;
    /** 任务失败原因（failed 状态时前端直接展示，避免用户对着"失败"二字不知所措） */
    private String errorMessage;
    /** 本次合成实际消耗的积分（免费试用时为 0），前端用于「本次消耗 X 积分」明细展示 */
    private Integer creditsCost;

    public TtsTaskResponse() {
    }

    public TtsTaskResponse(Long taskId, String status, Integer totalSegments, Integer completedSegments, LocalDateTime createdAt) {
        this.taskId = taskId;
        this.status = status;
        this.totalSegments = totalSegments;
        this.completedSegments = completedSegments;
        this.createdAt = createdAt;
    }

    public TtsTaskResponse(Long taskId, String status, Integer totalSegments, Integer completedSegments,
                           LocalDateTime createdAt, String originalBookName) {
        this.taskId = taskId;
        this.status = status;
        this.totalSegments = totalSegments;
        this.completedSegments = completedSegments;
        this.createdAt = createdAt;
        this.originalBookName = originalBookName;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTotalSegments() {
        return totalSegments;
    }

    public void setTotalSegments(Integer totalSegments) {
        this.totalSegments = totalSegments;
    }

    public Integer getCompletedSegments() {
        return completedSegments;
    }

    public void setCompletedSegments(Integer completedSegments) {
        this.completedSegments = completedSegments;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getOriginalBookName() {
        return originalBookName;
    }

    public void setOriginalBookName(String originalBookName) {
        this.originalBookName = originalBookName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getCreditsCost() {
        return creditsCost;
    }

    public void setCreditsCost(Integer creditsCost) {
        this.creditsCost = creditsCost;
    }
}
