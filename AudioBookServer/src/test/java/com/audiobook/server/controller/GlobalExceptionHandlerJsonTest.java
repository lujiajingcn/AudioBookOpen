package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「请求体不是合法 JSON」必须返回 400，不能落进兜底 500。
 *
 * <p><b>背景（生产部署验收实测）</b>：给 JSON 端点 POST 一个畸形 body，Spring 在进
 * Controller 之前抛 {@code HttpMessageNotReadableException}。此前
 * {@link GlobalExceptionHandler} 没有专用分支，被 {@code @ExceptionHandler(Exception.class)}
 * 的兜底接住，对外只剩「服务器开小差了，请稍后重试」—— 排查方向被带成服务端故障。
 * 这与 {@code HttpMediaTypeNotSupportedException}（415）是同一类「请求格式错」问题。
 *
 * <p><b>为什么需要 MockMvc 那组用例</b>：直接调 handler 方法只能证明「方法返回 400」，
 * 证明不了 Spring 的 {@code ExceptionHandlerExceptionResolver} 真会选它 ——
 * 而兜底分支存在时选错 handler 正是这个 bug 的根因。standaloneSetup 不启动 Spring
 * 上下文、不连 MySQL/Redis，但会走完整的 HttpMessageConverter → @ControllerAdvice 链路。
 */
class GlobalExceptionHandlerJsonTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 只收 JSON 的探针端点：让请求真的经过 Jackson 反序列化。 */
    @RestController
    public static class ProbeController {
        @PostMapping("/probe")
        public Map<String, Object> probe(@RequestBody ProbeDto dto) {
            return Map.of("ok", dto.getCount());
        }
    }

    /** int 字段用于区分「合法 JSON」与「字段类型不匹配」。 */
    public static class ProbeDto {
        private int count;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    /**
     * 只注册 Jackson 转换器，保证测试不依赖 Spring Boot 自动配置；
     * 挂上真实的 GlobalExceptionHandler（含兜底分支），才能复现「选错 handler」的场景。
     */
    private static MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static String messageOf(ResponseEntity<Map<String, Object>> response) {
        Object message = response.getBody() == null ? null : response.getBody().get("message");
        return message == null ? null : String.valueOf(message);
    }

    /**
     * 用非过时构造器造异常：Spring 6.1 起 {@code HttpMessageNotReadableException(String)}
     * 已标记 @Deprecated（丢了 HttpInputMessage），补一个空的输入消息既消掉编译警告，
     * 也避免将来升级 Spring 时测试直接编译失败。
     */
    private static HttpMessageNotReadableException notReadable(String message) {
        return new HttpMessageNotReadableException(message, new MockHttpInputMessage(new byte[0]));
    }

    // ---------- 第一层：直接调用 handler，钉死状态码与文案契约 ----------

    @Test
    @DisplayName("畸形 JSON → 400 + 可操作文案，Jackson 原始诊断不外显")
    void malformedJsonReturns400WithoutLeakingInternals() {
        String jacksonDiagnostic = "JSON parse error: Cannot deserialize value of type "
                + "`com.audiobook.server.dto.Foo` from String \"abc\"";

        ResponseEntity<Map<String, Object>> response =
                handler.handleMessageNotReadable(notReadable(jacksonDiagnostic));

        assertThat(response.getStatusCode())
                .as("请求体写错是客户端问题，必须是 400，不能是兜底 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(messageOf(response))
                .contains("合法的 JSON")
                .doesNotContain("服务器开小差了")
                .as("Jackson 诊断含 DTO 全限定类名/字段类型，属内部细节，只能进日志")
                .doesNotContain("com.audiobook.server.dto.Foo")
                .doesNotContain("JSON parse error");
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    @DisplayName("缺少 @RequestBody → 单独提示「请求体不能为空」")
    void missingBodyGetsDedicatedHint() {
        String raw = "Required request body is missing: "
                + "public java.util.Map com.audiobook.server.controller.ProbeController.probe(ProbeDto)";

        ResponseEntity<Map<String, Object>> response =
                handler.handleMessageNotReadable(notReadable(raw));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(messageOf(response)).isEqualTo("请求体不能为空");
    }

    @Test
    @DisplayName("异常消息为 null 时也不会拼出 'null' 之类怪文案")
    void nullMessageStillHasReadableHint() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleMessageNotReadable(notReadable(null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(messageOf(response))
                .contains("合法的 JSON")
                .doesNotContain("null");
    }

    // ---------- 第二层：真实 MVC 链路，证明选中的是新分支而非兜底 500 ----------
    // 断言只用状态码与 $.success / $.message 是否存在，避开 MockHttpServletResponse
    // 默认 ISO-8859-1 读中文乱码的问题；中文文案由上面那组精确校验。

    @Test
    @DisplayName("截断的 JSON：Spring 必须走新分支（400 + 标准错误体），而不是兜底 500")
    void truncatedJsonGoesThroughNewHandler() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("字段类型不匹配（int 收到字符串）同样是 400")
    void typeMismatchReturns400() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("空请求体：400 且错误体格式一致")
    void emptyBodyReturns400() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("合法 JSON 仍然 200：证明前三条 400 不是转换器/探针本身坏了")
    void validJsonStillSucceeds() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(7));
    }

    @Test
    @DisplayName("新增分支不抢走 415：Content-Type 不匹配仍返回 415")
    void unsupportedMediaTypeStillReturns415() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false));
    }
}
