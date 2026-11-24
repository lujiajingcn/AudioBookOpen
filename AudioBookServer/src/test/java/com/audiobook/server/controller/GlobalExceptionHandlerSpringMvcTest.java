package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC 标准「客户端错误」不能被 {@code @ExceptionHandler(Exception.class)} 兜底压成 500。
 *
 * <p><b>为什么单列一组</b>：{@code ExceptionHandlerExceptionResolver} 优先级高于
 * {@code DefaultHandlerExceptionResolver}，只要 advice 里存在兜底分支，
 * 框架本该返回 405/406/400 的标准异常就会先被它接住。
 * 这类错误和「畸形 JSON→400」「Content-Type→415」同源：客户端写错请求，
 * 服务端却回「服务器开小差了」，排查方向直接被带偏。
 *
 * <p>用 standaloneSetup 挂真实 advice 走完整 MVC 链路，不启动 Spring 上下文、不连库。
 */
class GlobalExceptionHandlerSpringMvcTest {

    @RestController
    public static class ProbeController {
        /** 带 produces 才能触发 406。 */
        @PostMapping(path = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, Object> probe(@RequestBody ProbeDto dto) {
            return Map.of("ok", dto.getCount());
        }

        @PostMapping("/upload")
        public Map<String, Object> upload(@RequestPart("file") MultipartFile file) {
            return Map.of("ok", file.getOriginalFilename());
        }
    }

    public static class ProbeDto {
        private int count;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    private static MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("405：GET 打到只收 POST 的端点 → 405 + 标准错误体")
    void methodNotSupportedReturns405() throws Exception {
        mockMvc().perform(get("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("406：Accept 与 produces 不匹配 → 406 + 标准错误体")
    void notAcceptableReturns406() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}")
                        .accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("400：multipart 缺 file part → 400 + 标准错误体")
    void missingPartReturns400() throws Exception {
        mockMvc().perform(multipart("/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("对照：合法请求仍 200（前三条失败不是探针本身坏了）")
    void validRequestsStillSucceed() throws Exception {
        mockMvc().perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(1));

        mockMvc().perform(multipart("/upload").file(new MockMultipartFile(
                        "file", "a.txt", MediaType.TEXT_PLAIN_VALUE,
                        "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("a.txt"));
    }
}
