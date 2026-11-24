package com.audiobook.server.filter;

import com.audiobook.server.model.User;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.util.AuthCookieService;
import com.audiobook.server.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final AuthCookieService authCookieService;
    private final ObjectMapper objectMapper;

    /**
     * 用户快照缓存的存活时长（毫秒）。
     * 每个请求都 findByUsername 查库一次是封禁/版本号实时校验的有意设计，
     * 但高并发下同一活跃用户的连续请求全部打到数据库，成为认证链路的吞吐瓶颈。
     * 这里对「用户名 → 用户快照」做极短 TTL 的进程内缓存：
     * - 封禁 / 改密（tokenVersion 递增）最多延迟一个 TTL 生效（默认 2s），
     *   相比零延迟仅放宽到秒级，安全语义基本不变；
     * - 配置 <= 0 时完全禁用缓存，退回每请求查库。
     */
    @Value("${app.auth.user-cache-ttl-millis:2000}")
    private long userCacheTtlMillis;

    /** 缓存条目上限：防御海量用户名（配合无效 token 刷接口）撑爆堆内存 */
    private static final int MAX_CACHED_USERS = 10_000;

    /** 缓存条目：用户快照 + 绝对过期时刻（毫秒） */
    private record CacheEntry(User user, long expiresAt) {}

    // 进程内单例缓存（过滤器为 @Component 单例，置为 static 仅是为 evict 提供静态入口，
    // 行为不变）。键为用户名，值为用户快照 + 过期时刻。
    private static final ConcurrentHashMap<String, CacheEntry> userCache = new ConcurrentHashMap<>();

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserRepository userRepository,
                                   AuthCookieService authCookieService,
                                   ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.authCookieService = authCookieService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 凭据解析：优先取 Authorization: Bearer 头（Android/第三方 API），
        // 没有再回退到 httpOnly 认证 Cookie（Web/管理后台浏览器端）。
        // 浏览器端 token 不再暴露给 JS，XSS 无法窃取凭据。
        String token = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            token = authCookieService.readToken(request).orElse(null);
        }

        if (token != null) {
            // 捕获令牌解析异常：过期 token 抛 ExpiredJwtException、伪造/畸形 token 抛
            // Malformed/SignatureException，异常从本过滤器（位于 ExceptionTranslationFilter
            // 之前）穿透过滤链，会被容器返回 500 而非 401——三端"401 → 跳登录页"逻辑
            // 全部失效，用户 token 过期后被静默困死。
            // 解析失败一律按「未认证」处理：不设置 SecurityContext，继续放行到
            // 授权层，由 SecurityConfig 的 authenticationEntryPoint 统一回 401 + JSON。
            boolean tokenValid;
            try {
                tokenValid = jwtUtil.validateToken(token);
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("JWT 校验失败（过期/畸形/签名不符），按未认证处理: {}", e.getMessage());
                tokenValid = false;
            }

            if (tokenValid) {
                String username = jwtUtil.getUsernameFromToken(token);

                // 经短 TTL 缓存加载用户（封禁/版本号校验语义不变，
                // 最多延迟一个 TTL 生效），避免每个请求都查一次库。
                Optional<User> userOpt = findUserCached(username);
                if (userOpt.isEmpty()) {
                    // 用户已被删除，拒绝访问。
                    // 只返回裸 401 无响应体会与三端约定的
                    // {success, message} 结构不一致，前端读不到提示文案、
                    // 只能走兜底话术。与下方版本号/封禁分支保持同款写法。
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "账号不存在或已被注销，请重新登录");
                    return;
                }

                User user = userOpt.get();

                // 比对 token 内版本号与库内当前版本。
                // 改密 / 管理员重置密码会递增库内版本，此前签发的所有旧 token
                // （携带旧版本号）在此被立即拒绝，不再等到 24h 自然过期。
                // 旧版本签发的 token 无版本号 claim，按 0 处理，与存量用户默认版本一致。
                if (jwtUtil.getTokenVersionFromToken(token) != user.getTokenVersion()) {
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "密码已修改，请重新登录");
                    return;
                }

                // 被封禁且在封禁期内 → 拒绝所有业务请求
                boolean bannedActive = user.getBannedUntil() != null
                        && user.getBannedUntil().isAfter(LocalDateTime.now());
                if (bannedActive) {
                    writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                            "账号已被封禁，禁止操作");
                    return; // 不继续放行
                }

                boolean isAdmin = Boolean.TRUE.equals(user.getIsAdmin());

                List<SimpleGrantedAuthority> authorities;
                if (isAdmin) {
                    authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN"));
                } else {
                    authorities = Collections.emptyList();
                }

                UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                        username, null, authorities
                    );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 带短 TTL 的用户加载。
     * 缓存命中直接返回快照；未命中/过期则查库并写回缓存（懒清理过期条目）。
     * 用户已被删除时同步清掉缓存，避免陈旧条目干扰下次判断。
     * 并发说明：同一用户名过期瞬间多线程同时查库是良性的（结果一致，多查一次而已），
     * 不加锁保持过滤链路径零锁竞争。
     */
    private Optional<User> findUserCached(String username) {
        if (userCacheTtlMillis <= 0) {
            return userRepository.findByUsername(username);
        }
        long now = System.currentTimeMillis();
        CacheEntry entry = userCache.get(username);
        if (entry != null && entry.expiresAt() > now) {
            return Optional.of(entry.user());
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            if (entry != null) {
                userCache.remove(username);
            }
            return Optional.empty();
        }

        if (userCache.size() >= MAX_CACHED_USERS) {
            userCache.values().removeIf(e -> e.expiresAt() <= now);
            if (userCache.size() >= MAX_CACHED_USERS) {
                // 清理后仍超限（极端情况：全部条目都未过期）整表清空，
                // 代价只是短时间内回到每请求查库，内存始终有界
                userCache.clear();
            }
        }
        userCache.put(username, new CacheEntry(userOpt.get(), now + userCacheTtlMillis));
        return userOpt;
    }

    /**
     * 主动失效某用户的缓存条目。
     * 在「封禁/解封用户」「修改密码（tokenVersion 变更）」「管理员重置密码」等会让
     * 旧缓存快照失效的操作成功后调用，避免缓存保持秒级陈旧，导致封禁/改密延迟一个
     * TTL（默认 2s）才对线上请求生效。仅删对应条目，其他用户不受影响。
     *
     * @param username 需失效的用户名；为 null/空时直接忽略
     */
    public static void evict(String username) {
        if (username != null && !username.isEmpty()) {
            userCache.remove(username);
        }
    }

    /**
     * 统一输出 {success, message} 结构的 JSON 错误响应。
     * 各分支手写 JSON 字符串与项目其余位置经 ObjectMapper
     * 序列化的惯例不一致，且文案一旦含引号/换行需手工转义易出错。
     */
    private void writeJsonError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(Map.of("success", false, "message", message)));
    }
}
