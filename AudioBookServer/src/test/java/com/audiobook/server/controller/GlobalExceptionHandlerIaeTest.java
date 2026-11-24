package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「业务文案回显 / 技术文案不外显」的边界测试（v5 / N1）。
 *
 * <p><b>为什么必须有</b>：{@code GlobalExceptionHandler} 对
 * {@code IllegalArgumentException} 原本是无条件 {@code e.getMessage()} 回显 ——
 * 业务提示（前端靠它 toast）与技术文案（JDK 解码器/解析器）走同一条路。
 * 后者会把内部实现细节透给调用方，前者如果一并吞掉又会伤到前端提示。
 * 这个测试同时守住两侧，防止以后有人"一刀切"改成通用文案。
 */
class GlobalExceptionHandlerIaeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private static String messageOf(ResponseEntity<Map<String, Object>> response) {
        Object message = response.getBody() == null ? null : response.getBody().get("message");
        return message == null ? null : String.valueOf(message);
    }

    @Test
    @DisplayName("技术性 IAE（JDK 解码器/解析器）→ 400 + 通用文案，原文不外显")
    void technicalMessagesAreSanitised() {
        String[] technical = {
                "URLDecoder: Illegal hex characters in escape (%) pattern - Error at index 5 in: \"zz\"",
                "For input string: \"abc\"",
                "Illegal base64 character 20",
                "Character out of range: -1",
                "No enum constant com.audiobook.server.model.SegmentStatus.DONE",
                "",          // 空消息对客户端无意义
        };
        for (String raw : technical) {
            ResponseEntity<Map<String, Object>> response =
                    handler.handleIllegalArgument(new IllegalArgumentException(raw));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(messageOf(response))
                    .as("技术性消息 %s 不应回显给客户端", raw)
                    .isEqualTo("请求参数格式不正确");
            assertThat(GlobalExceptionHandler.isTechnicalIaeMessage(raw)).isTrue();
        }
    }

    @Test
    @DisplayName("业务性 IAE 原样回显（前端依赖它做提示，不能被一刀切吞掉）")
    void businessMessagesAreEchoed() {
        String[] business = {
                "书籍不存在",
                "电子书内容为空，无法合成",
                "积分不足",
                "不支持的文件格式: .rtf",
        };
        for (String raw : business) {
            ResponseEntity<Map<String, Object>> response =
                    handler.handleIllegalArgument(new IllegalArgumentException(raw));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(messageOf(response)).as("业务文案 %s 必须原样回显", raw).isEqualTo(raw);
            assertThat(GlobalExceptionHandler.isTechnicalIaeMessage(raw)).isFalse();
        }
    }

    @Test
    @DisplayName("技术性判定是前缀匹配：以业务词开头的长消息不会被误判")
    void prefixMatchDoesNotOverreach() {
        // "URL 解析失败" 以 "URL" 开头但**不是** "URLDecoder:" —— 不能误伤
        assertThat(GlobalExceptionHandler.isTechnicalIaeMessage("URL 解析失败，请检查网络")).isFalse();
        assertThat(GlobalExceptionHandler.isTechnicalIaeMessage("For 循环参数非法")).isFalse();
    }
}
