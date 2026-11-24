package com.audiobook.server.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「限流路径正则三处必须同步」的断言化回归测试。
 *
 * <p><b>为什么需要它（v5 / S1 的缺陷）</b>：同一条正则抄在三个地方 ——
 * {@code application.yml}、{@code application-prod.yml}、以及
 * {@link RateLimitFilter#DEFAULT_LIMITED_PATHS_PATTERN}。注释里写着「三处必须同步」，
 * 但**没有任何机制保证它**：实测 yml 两份都漏了 {@code posts}，而过滤器「yml 非空即优先」
 * ⇒ POST /api/posts 永不限流，连 {@code app.rate-limit.posts.*} 那套配额也变成死配置。
 *
 * <p>本测试把「三处一致」和「关键端点确实被覆盖」变成可执行断言：
 * 以后谁改了一处忘了另外两处，CI 立刻红，而不是等到线上被打。
 */
class RateLimitPathsConfigTest {

    private static final List<String> CONFIG_FILES = List.of("application.yml", "application-prod.yml");

    /** 从 yml 里取出 {@code ${ENV:默认值}} 的「默认值」部分（与 Spring 占位符语义一致）。 */
    @SuppressWarnings("unchecked")
    private static String defaultOf(String configFile) throws Exception {
        try (InputStream in = RateLimitPathsConfigTest.class.getClassLoader().getResourceAsStream(configFile)) {
            assertThat(in).as("classpath 上应能找到 %s", configFile).isNotNull();
            Map<String, Object> root = (Map<String, Object>) new Yaml().load(in);
            Map<String, Object> app = (Map<String, Object>) root.get("app");
            Map<String, Object> rateLimit = (Map<String, Object>) app.get("rate-limit");
            Object raw = rateLimit.get("limited-paths");
            assertThat(raw).as("%s 必须显式配置 app.rate-limit.limited-paths", configFile).isNotNull();

            String text = String.valueOf(raw).trim();
            int colon = text.indexOf(':', text.indexOf("${") + 2);
            assertThat(colon)
                    .as("%s 的 limited-paths 应写成 ${ENV:默认值} 形式，便于运维按环境覆盖", configFile)
                    .isGreaterThan(0);
            return text.substring(colon + 1, text.lastIndexOf('}'));
        }
    }

    @Test
    @DisplayName("两份 yml 的默认正则必须与过滤器内置默认值逐字一致")
    void ymlDefaultsMatchBuiltInDefault() throws Exception {
        for (String file : CONFIG_FILES) {
            assertThat(defaultOf(file))
                    .as("%s 的 limited-paths 默认值与 RateLimitFilter.DEFAULT_LIMITED_PATHS_PATTERN 不一致 —— "
                            + "「yml 非空即优先」意味着这个差异会静默生效：被漏掉的端点永不限流。", file)
                    .isEqualTo(RateLimitFilter.DEFAULT_LIMITED_PATHS_PATTERN);
        }
    }

    @Test
    @DisplayName("重型端点逐个必须命中（posts 曾漏配，这里单独钉一条）")
    void heavyEndpointsAreCovered() throws Exception {
        for (String file : CONFIG_FILES) {
            Pattern pattern = Pattern.compile(defaultOf(file));
            assertThat(pattern.matcher("/api/posts").matches())
                    .as("%s：POST /api/posts 必须被限流（发帖是写库 + 建索引）", file)
                    .isTrue();
            assertThat(pattern.matcher("/api/auth/login").matches()).isTrue();
            assertThat(pattern.matcher("/api/auth/register").matches()).isTrue();
            assertThat(pattern.matcher("/api/tts/clone").matches()).isTrue();
            assertThat(pattern.matcher("/api/tts/extract-content").matches()).isTrue();
            assertThat(pattern.matcher("/api/tts/task/12/toc").matches()).isTrue();
            assertThat(pattern.matcher("/api/store/book/7/content").matches()).isTrue();
            assertThat(pattern.matcher("/api/app/dict/download").matches()).isTrue();
            // 读接口不该被限流（GET /api/posts 在过滤器里有单独的早退分支）
            assertThat(pattern.matcher("/api/store/books").matches())
                    .as("%s：书城列表是轻量读接口，不该占用配额", file)
                    .isFalse();
        }
    }
}
