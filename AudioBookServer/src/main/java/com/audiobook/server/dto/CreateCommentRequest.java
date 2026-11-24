package com.audiobook.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发表评论/回复请求体。
 *
 * content 必填；parentId 为空表示对帖子的一级评论，非空表示对另一条评论的嵌套回复。
 */
public class CreateCommentRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容不能超过 500 字")
    private String content;

    /** 父评论 id；为空表示对帖子的一级评论 */
    private Long parentId;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }
}
