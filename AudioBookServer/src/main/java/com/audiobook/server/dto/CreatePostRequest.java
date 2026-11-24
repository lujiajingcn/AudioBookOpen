package com.audiobook.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布帖子请求体。
 *
 * 必须用强类型 DTO + Bean Validation：空串/超长由 {@code GlobalExceptionHandler}
 * 统一映射为 400，与项目其他接口口径一致。
 *
 * content 必填（正文）；title / imageUrls / refAudioId / mentions 均为可选扩展：
 * - title：帖子标题（纯文字帖可留空）
 * - imageUrls：图片 URL 列表（由 /api/posts/upload-image 上传后得到）
 * - refAudioId：嵌入的分享参考音频 id（直接分享好声音）
 * - mentions：@提醒的用户名列表；若为空，服务端会从 content 里解析 @用户名 自动填充
 */
public class CreatePostRequest {

    /**
     * 帖子标题（可选）。
     * 必须给长度上限：实体 Post.title 是 length = 200、DDL 是 VARCHAR(200)，
     * 而 PostService.createPost 只做 trim 不截断——超长标题会在 save 时抛
     * DataIntegrityViolationException（MySQL 严格模式 Data too long），被全局异常处理器
     * 兜底成 500，而不是可读的 400 参数错误。
     */
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    @NotBlank(message = "帖子内容不能为空")
    @Size(max = 500, message = "帖子内容不能超过 500 字")
    private String content;

    /**
     * 图片 URL 列表（可选）。
     * 条数与单条长度都要设限：该字段最终序列化写入 posts.image_urls TEXT（64KB），
     * 不限则超长内容同样变成 500 而非 400。
     */
    @Size(max = 9, message = "最多上传 9 张图片")
    private List<@Size(max = 500, message = "图片地址过长") String> imageUrls;

    /** 嵌入的分享参考音频 id（可选） */
    private Long refAudioId;

    /** @提醒用户名列表（可选，缺省时由服务端从正文解析） */
    private List<String> mentions;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public void setImageUrls(List<String> imageUrls) {
        this.imageUrls = imageUrls;
    }

    public Long getRefAudioId() {
        return refAudioId;
    }

    public void setRefAudioId(Long refAudioId) {
        this.refAudioId = refAudioId;
    }

    public List<String> getMentions() {
        return mentions;
    }

    public void setMentions(List<String> mentions) {
        this.mentions = mentions;
    }
}
