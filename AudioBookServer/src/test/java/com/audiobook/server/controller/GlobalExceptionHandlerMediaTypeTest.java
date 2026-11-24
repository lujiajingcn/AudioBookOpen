package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「Content-Type 不受支持」必须返回 415，不能落进兜底 500。
 *
 * <p><b>背景（真实线上事故）</b>：PC 端发帖时把图片用 {@code multipart/form-data}
 * 直接 POST 到 {@code /api/posts}，而该端点只接受 JSON（{@code @RequestBody}）。
 * Spring 在进入 Controller 之前抛 {@code HttpMediaTypeNotSupportedException}，
 * 落进 {@link GlobalExceptionHandler#handleGeneric} 后对外只剩一句
 * 「服务器开小差了，请稍后重试」—— 完全看不出是请求格式不对，
 * 排查时极易误判为服务端故障。
 *
 * <p>本测试守住「415 + 明确文案」这条契约。
 */
class GlobalExceptionHandlerMediaTypeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private static String messageOf(ResponseEntity<Map<String, Object>> response) {
        Object message = response.getBody() == null ? null : response.getBody().get("message");
        return message == null ? null : String.valueOf(message);
    }

    @Test
    @DisplayName("multipart 打 JSON 端点 → 415（不是兜底 500），文案点明格式问题")
    void multipartToJsonEndpointReturns415() {
        // 构造器 (MediaType, List<MediaType>)：第一个是客户端实际发来的 Content-Type，
        // 第二个是端点支持的类型列表。getContentType() 取的是**第一个**。
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.MULTIPART_FORM_DATA,
                List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map<String, Object>> response = handler.handleMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode())
                .as("Content-Type 不支持是客户端请求格式问题，必须是 415，不能是 500")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(messageOf(response))
                .as("文案要能让人一眼看出是格式问题，而不是「服务器开小差了」")
                .contains("请求格式不支持")
                .contains("multipart/form-data")
                .doesNotContain("服务器开小差了");
    }

    @Test
    @DisplayName("未指定 Content-Type 时也给出可读文案（不输出 null）")
    void missingContentTypeStillHasReadableMessage() {
        // 构造器只有 (String, List<MediaType>) 和 (MediaType, List<MediaType>) 两种；
        // 用 (MediaType)null + 支持列表来表达「客户端没带 Content-Type」。
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                (MediaType) null, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map<String, Object>> response = handler.handleMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(messageOf(response))
                .as("Content-Type 为 null 时不能拼出 'null' 之类的怪文案")
                .isEqualTo("请求格式不支持：未指定 Content-Type");
    }
}
