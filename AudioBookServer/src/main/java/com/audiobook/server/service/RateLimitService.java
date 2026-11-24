package com.audiobook.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 基于 Redis 的接口速率限制服务。
 * 使用 Lua 脚本保证 GET + INCR + EXPIRE 的原子性，避免并发下的竞态条件。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    private final DefaultRedisScript<Long> rateLimitReleaseScript;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        // Lua 脚本：原子化计数 + 过期设置
        // KEYS[1] = 限流 key
        // ARGV[1] = 窗口内最大请求数
        // ARGV[2] = 窗口时长（秒）
        // 返回 1 = 放行，0 = 拒绝
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setLocation(new ClassPathResource("ratelimit.lua"));
        this.rateLimitScript.setResultType(Long.class);

        // Lua 脚本：原子化退还一次计数（把配额语义从「请求次数」修正为「失败次数」）
        // KEYS[1] = 限流 key
        // 返回 1 = 已退还，0 = 无可退还（键不存在 / 计数已为 0）
        // 该脚本的约束（不得 DEL、不得补记负数）写在 ratelimit_release.lua 头部注释里
        this.rateLimitReleaseScript = new DefaultRedisScript<>();
        this.rateLimitReleaseScript.setLocation(new ClassPathResource("ratelimit_release.lua"));
        this.rateLimitReleaseScript.setResultType(Long.class);
    }

    /**
     * 判断请求是否被允许。
     *
     * @param key     限流标识（例如 "rate_limit:login:192.168.1.1"）
     * @param maxAttempts 窗口内允许的最大请求次数
     * @param windowSeconds 窗口时长（秒）
     * @return true 表示允许，false 表示超过限制
     */
    public boolean isAllowed(String key, int maxAttempts, long windowSeconds) {
        try {
            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(key),
                    String.valueOf(maxAttempts),
                    String.valueOf(windowSeconds)
            );
            boolean allowed = result != null && result == 1L;
            if (!allowed) {
                log.warn("速率限制拦截: key={}, limit={}, window={}s", key, maxAttempts, windowSeconds);
            }
            return allowed;
        } catch (Exception e) {
            // 失败即拒绝（fail-closed）：Redis 异常时"降级放行"会让攻击者只要打挂 Redis
            //（或等它故障）就能无限制暴力破解登录。Redis 不可用期间
            // 登录/注册会被 429 挡下，宁可短时影响登录，也不放开暴力破解通道。
            log.error("速率限制检查失败（Redis 不可用？），安全起见拒绝该请求: key={}, error={}",
                    key, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 退还一次配额（本次请求成功时调用）。
     *
     * <p>把登录/注册的配额语义从「请求次数」修正为「失败次数」：调用方在放行后
     * 若发现本次请求成功（HTTP status &lt; 400），就把刚占用的那一次还回去。
     * 这样正常登录/注册永不消耗额度，而失败尝试仍被限制在 N 次/窗口内。
     *
     * <p>与 {@link #isAllowed} 的 fail-closed 相反：**这里失败只记日志、绝不抛出**。
     * 退还失败最坏的结果是少还一次配额（用户可能比预期早一点撞 429），
     * 若因此抛异常会把一次已经成功的登录变成 5xx，代价完全不对等。
     *
     * @param key 与 {@link #isAllowed} 完全相同的限流键（调用方必须复用同一个键，
     *            不要事后按路径重算——重算要重新解析 IP/Token，可能算出不同的桶）
     * @return true 表示确实退还了一次；false 表示无可退还或退还失败
     */
    public boolean release(String key) {
        try {
            Long result = redisTemplate.execute(
                    rateLimitReleaseScript,
                    Collections.singletonList(key)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("退还限流配额失败（忽略，不影响本次已成功的响应）: key={}, error={}",
                    key, e.getMessage());
            return false;
        }
    }
}
