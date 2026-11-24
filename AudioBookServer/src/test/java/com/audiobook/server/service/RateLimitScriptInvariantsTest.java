package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流 Lua 脚本的不变量守卫。
 *
 * <p>退还脚本是整个「失败次数」语义的地基，但它有两处一改就静默出错的写法，
 * 而且错了之后**功能照样跑**、只是限流被悄悄绕过或计数被永久打偏：
 *
 * <ol>
 *   <li><b>不得 DEL / EXPIRE</b>：把「减到 0 就删键」当成优化写进去之后，
 *       下一个请求 INCR 得到 1 并重新 EXPIRE，等于把窗口起点往后推 ——
 *       攻击者在失败次数用满前穿插一次成功登录就能无限延长窗口，
 *       彻底绕过暴力破解限流。</li>
 *   <li><b>不得对不存在的键 DECR</b>：Redis 把不存在的键当 0，DECR 会建出一个
 *       -1 的键。此后每次 INCR 都要先把负数填平，窗口计数被永久打偏。</li>
 * </ol>
 *
 * <p>因此这里把脚本源码读出来做静态断言（剥掉注释再判，避免注释里提到的
 * 关键词造成误报）。这类「资源文件的内容约束」没有别的守卫手段：
 * 单测不启动 Spring 上下文，连不上 Redis，跑不了脚本本身。
 */
class RateLimitScriptInvariantsTest {

    private static String code(String resource) throws Exception {
        String raw = new String(new ClassPathResource(resource).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        // 剥掉 "--" 行内注释与整行注释：注释里提到 INCR/DEL 是正常的说明文字
        return Arrays.stream(raw.split("\n", -1))
                .map(line -> {
                    int i = line.indexOf("--");
                    return i >= 0 ? line.substring(0, i) : line;
                })
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("两个 Lua 脚本都必须打进包内（ClassPathResource 找得到）")
    void scriptsArePackaged() {
        assertThat(new ClassPathResource("ratelimit.lua").exists()).isTrue();
        assertThat(new ClassPathResource("ratelimit_release.lua").exists()).isTrue();
    }

    @Test
    @DisplayName("退还脚本：只 GET + DECR，绝不 DEL/EXPIRE/INCR/SET")
    void releaseScriptMustNotRebuildOrResetKey() throws Exception {
        String src = code("ratelimit_release.lua");

        assertThat(src).contains("redis.call('GET'");
        assertThat(src).contains("redis.call('DECR'");

        // 这四条任意一条出现都会静默破坏限流语义（见类注释）
        assertThat(src).doesNotContain("DEL");
        assertThat(src).doesNotContain("EXPIRE");
        assertThat(src).doesNotContain("INCR");
        assertThat(src).doesNotContain("SET");
    }

    @Test
    @DisplayName("计算脚本：只在首次计数时设置 TTL（current == 1 分支）")
    void countingScriptKeepsTtlOnFirstIncrement() throws Exception {
        String src = code("ratelimit.lua");

        assertThat(src).contains("redis.call('INCR'");
        // 漏掉 EXPIRE 会让键永不过期，窗口再也不会重置（等于永久封禁该 IP）
        assertThat(src).contains("redis.call('EXPIRE'");
        assertThat(src).contains("current == 1");
    }
}
