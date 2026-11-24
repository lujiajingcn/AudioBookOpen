package com.audiobook.server.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 听书收藏请求体（新增 / 批量同步共用）。
 *
 * bookName + segmentNumber 为唯一身份；content 为正文快照；taskId 可空（深链辅助）。
 */
public class FavoriteItemRequest {

    @NotBlank(message = "书名不能为空")
    @Size(max = 255, message = "书名过长")
    private String bookName;

    /**
     * 段落号。全文口径是 **1-based**（ReadingProgress、TextFavorite 的注释与唯一键
     * (user_id, book_name, segment_number) 都把段号当作身份的一部分）。
     * 允许 0 会写入一条「第 0 段」的收藏，与「第 1 段」在唯一键上不相等，
     * 跨端同步/去重永远合并不掉，留下两行指向同一段的记录。
     */
    @NotNull(message = "段落号不能为空")
    @Min(value = 1, message = "段落号必须从 1 开始")
    private Integer segmentNumber;

    @NotBlank(message = "收藏内容不能为空")
    @Size(max = 20000, message = "收藏内容过长")
    private String content;

    /** 该收藏所属的 TTS 任务 id，可为空 */
    private Long taskId;

    public String getBookName() {
        return bookName;
    }

    public void setBookName(String bookName) {
        this.bookName = bookName;
    }

    public Integer getSegmentNumber() {
        return segmentNumber;
    }

    public void setSegmentNumber(Integer segmentNumber) {
        this.segmentNumber = segmentNumber;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }
}
