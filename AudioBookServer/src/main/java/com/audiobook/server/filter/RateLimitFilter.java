package com.audiobook.server.filter;

import com.audiobook.server.dto.AuthResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.audiobook.server.service.RateLimitService;
import com.audiobook.server.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 登录/注册 与 同步 TTS 合成接口的速率限制过滤器。
 *
 * 限流维度：客户端 IP（已认证请求按 username 计桶）
 * 策略     ：固定窗口计数器（窗口大小和最大次数通过 application.properties 配置）
 * 被限流时返回 HTTP 429 (Too Many Requests)
 *
 * 计数语义分两类：
 *  - 登录/注册 = 【失败次数】。放行后若本次请求成功，退还刚占用的那一次
 *    （见 {@link RateLimitService#release}）。原先按「请求次数」计，
 *    成功登录也吃配额，用户连错几次密码就撞 429 而密码本身是对的。
 *  - 同步合成 / 参考音频上传 / 发帖 = 【请求次数】。这些接口每次调用都真实
 *    消耗算力或存储，成功同样要占配额，不能退还。
 *
 * /api/tts/clone 与 /api/tts/synthesize 是同步阻塞调用上游 TTS 引擎的重接口，
 * 完全不限流时，任何账号循环调用即可占满 Tomcat 线程池并把引擎打满，
 * 属于零成本的拒绝服务入口，因此这里一并纳入限流。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String PREFIX_LOGIN   = "rate_limit:login:";
    private static final String PREFIX_REGISTER = "rate_limit:register:";
    private static final String PREFIX_TTS_SYNC = "rate_limit:tts_sync:";
    private static final String PREFIX_TTS_UPLOAD = "rate_limit:tts_upload:";
    private static final String PREFIX_POSTS = "rate_limit:posts:";
    private static final String PREFIX_DICT = "rate_limit:dict:";

    /**
     * 限流路径正则可配置（{@code app.rate-limit.limited-paths}）。
     * 这是内置缺省值；新增重型端点（同步引擎调用 /
     * ffmpeg 转码 / 大文件读取等）时通过配置追加即可，不必改代码重新发版，
     * 降低「新增接口忘加限流」的漏配概率。
     *
     * <p><b>包内可见</b>（而非 private）：{@code RateLimitPathsConfigTest} 要拿它和
     * {@code application.yml} / {@code application-prod.yml} 里的默认值逐字比对 ——
     * 「三处必须同步」这句注释只有变成断言才不会漂移（历史上就漏过 {@code posts}）。
     */
    static final String DEFAULT_LIMITED_PATHS_PATTERN =
            "^/api/(auth/(login|register)|tts/(clone|synthesize|extract-content|upload-reference|references(/\\d+/update)?|task/\\d+/toc)|store/book/\\d+/content|posts|app/dict/download)$";

    /** 需要限流的路径（启动时编译；配置非法直接启动失败，绝不允许带病运行） */
    private final java.util.regex.Pattern limitedPaths;

    private final RateLimitService rateLimitService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    private final int    loginMaxAttempts;
    private final long   loginWindowSeconds;
    private final int    registerMaxAttempts;
    private final long   registerWindowSeconds;
    private final int    ttsMaxAttempts;
    private final long   ttsWindowSeconds;
    private final int    ttsUploadMaxAttempts;
    private final long   ttsUploadWindowSeconds;
    private final int    postsMaxAttempts;
    private final long   postsWindowSeconds;
    private final int    dictMaxAttempts;
    private final long   dictWindowSeconds;

    /**
     * 可信反向代理白名单（IP 或 CIDR）。只有直连来源在这里时，才解析
     * X-Forwarded-For/X-Real-IP；否则一律用 TCP 直连地址，杜绝伪造头绕过限流。
     * 未配置（默认）= 不信任任何转发头。
     */
    private final List<IpAddressMatcher> trustedProxies;

    public RateLimitFilter(
            RateLimitService rateLimitService,
            JwtUtil jwtUtil,
            ObjectMapper objectMapper,

            // 登录配额 10 次/60 秒：与 application.yml / application-prod.yml 三处保持一致。
            // 语义是【失败次数】——成功登录会退还本次占用，正常使用永不消耗额度；
            // 只有失败的尝试才累积，上限 10 次/分钟。
            @Value("${app.rate-limit.login.max-attempts:10}") int loginMaxAttempts,
            @Value("${app.rate-limit.login.window-seconds:60}") long loginWindowSeconds,
            // 注册配额 3 次/60 秒，同样是【失败次数】语义（成功注册退还占用）。
            // 注册失败通常是「用户名已存在 / 密码太弱」，用户会立刻改了重试，
            // 所以这里同样只统计失败次数，避免正常用户改两次用户名就被锁 60 秒。
            @Value("${app.rate-limit.register.max-attempts:3}") int registerMaxAttempts,
            @Value("${app.rate-limit.register.window-seconds:60}") long registerWindowSeconds,
            @Value("${app.rate-limit.tts.max-attempts:10}") int ttsMaxAttempts,
            @Value("${app.rate-limit.tts.window-seconds:60}") long ttsWindowSeconds,
            // 上传配额独立于合成配额：上传（含视频转码）与合成是两类资源，共用
            // 一个桶会互相挤占——上传几个参考音频就耗尽自己的合成次数
            @Value("${app.rate-limit.tts-upload.max-attempts:20}") int ttsUploadMaxAttempts,
            @Value("${app.rate-limit.tts-upload.window-seconds:60}") long ttsUploadWindowSeconds,
            @Value("${app.rate-limit.posts.max-attempts:10}") int postsMaxAttempts,
            @Value("${app.rate-limit.posts.window-seconds:60}") long postsWindowSeconds,
            // 离线词典下载（23.1MB，且**免鉴权** ⇒ 任何人都能并发拉）独立配额。
            // 阈值比 TTS 宽：客户端一次续传只发1 个请求，但要给弱网反复重试留余量。
            @Value("${app.rate-limit.dict.max-attempts:20}") int dictMaxAttempts,
            @Value("${app.rate-limit.dict.window-seconds:60}") long dictWindowSeconds,
            @Value("${app.rate-limit.limited-paths:}") String limitedPathsConfig,
            @Value("${app.rate-limit.trusted-proxies:}") String trustedProxiesConfig) {

        this.rateLimitService = rateLimitService;
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;

        this.loginMaxAttempts = loginMaxAttempts;
        this.loginWindowSeconds = loginWindowSeconds;
        this.registerMaxAttempts = registerMaxAttempts;
        this.registerWindowSeconds = registerWindowSeconds;
        this.ttsMaxAttempts = ttsMaxAttempts;
        this.ttsWindowSeconds = ttsWindowSeconds;
        this.ttsUploadMaxAttempts = ttsUploadMaxAttempts;
        this.ttsUploadWindowSeconds = ttsUploadWindowSeconds;
        this.postsMaxAttempts = postsMaxAttempts;
        this.postsWindowSeconds = postsWindowSeconds;
        this.dictMaxAttempts = dictMaxAttempts;
        this.dictWindowSeconds = dictWindowSeconds;

        // 限流路径正则：配置为空用内置默认；配置存在则编译期校验，
        // 非法正则直接阻断启动——限流规则失效等于防线消失，必须快速失败
        String patternSource = (limitedPathsConfig == null || limitedPathsConfig.isBlank())
                ? DEFAULT_LIMITED_PATHS_PATTERN
                : limitedPathsConfig.trim();
        try {
            this.limitedPaths = java.util.regex.Pattern.compile(patternSource);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalStateException(
                    "app.rate-limit.limited-paths 正则非法，拒绝启动: " + e.getMessage(), e);
        }

        this.trustedProxies = Arrays.stream(trustedProxiesConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();

        log.info("RateLimitFilter 初始化: login={}/{}s, register={}/{}s, tts={}/{}s, tts-upload={}/{}s, posts={}/{}s, dict={}/{}s, 限流路径={}, 可信代理={}",
                loginMaxAttempts, loginWindowSeconds,
                registerMaxAttempts, registerWindowSeconds,
                ttsMaxAttempts, ttsWindowSeconds,
                ttsUploadMaxAttempts, ttsUploadWindowSeconds,
                postsMaxAttempts, postsWindowSeconds,
                dictMaxAttempts, dictWindowSeconds,
                patternSource,
                trustedProxies.isEmpty() ? "未配置（不信任 X-Forwarded-For）" : trustedProxiesConfig);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Spring MVC 默认 removeSemicolonContent=true：/api/auth/login;a=1（含 %3B 编码变体）
        // 会被正常路由到登录处理器。若这里用原始 URI 做正则匹配，攻击者只需在路径末尾
        // 拼接 ;参数即可让过滤器不命中，从而完全绕过登录/注册/TTS/上传限流。
        // 因此先剥离 ;matrix 参数并 URL 解码，得到与 MVC 路由语义一致的规范化路径
        String path = normalizePath(request.getRequestURI());

        // 仅对登录、注册和同步 TTS 合成接口做速率限制
        if (!limitedPaths.matcher(path).matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 参考音频接口只有上传（POST）才涉及 ffmpeg 转码等重操作，
        // 同路径的 GET（列表查询）是轻量读接口，不应占用上传配额
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (path.endsWith("/upload-reference") || path.endsWith("/references"))) {
            filterChain.doFilter(request, response);
            return;
        }

        // 帖子接口：仅 POST（发帖是写库 + 建索引）需要限流；GET 列表是轻量读，不占用配额
        if ("GET".equalsIgnoreCase(request.getMethod()) && path.equals("/api/posts")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = getClientIp(request);
        // 已认证请求按 username 计桶（缓解反向代理部署下所有用户共享代理出口 IP
        // 导致的「全员 429 误伤」）；无 Token / Token 非法时回退到 clientIp。
        String userBucket = resolveUserBucket(request);
        boolean allowed;

        // 本次请求占用的配额桶键。退还时复用这一个变量，而不是在 doFilter 之后
        // 按 path 重算一遍——重算要重新解析 IP / Token，既多一次出错机会，
        // 也可能算出与本次计数不同的桶（那样就会把另一条桶的计数减掉）。
        String bucketKey;
        // 本次请求成功后是否退还配额。只有登录/注册的语义是【失败次数】；
        // 其余桶（同步合成 / 参考音频上传 / 发帖）计的是真实算力与存储消耗，
        // 请求成功同样消耗了资源，必须占用配额，不退还。
        boolean refundOnSuccess;

        if (path.endsWith("/login")) {
            bucketKey = PREFIX_LOGIN + clientIp;
            refundOnSuccess = true;
            allowed = rateLimitService.isAllowed(bucketKey, loginMaxAttempts, loginWindowSeconds);
        } else if (path.endsWith("/register")) {
            bucketKey = PREFIX_REGISTER + clientIp;
            refundOnSuccess = true;
            allowed = rateLimitService.isAllowed(bucketKey, registerMaxAttempts, registerWindowSeconds);
        } else if (path.contains("/upload-reference") || path.contains("/references")) {
            // 上传（含视频转码）与合成使用独立配额桶，避免互相挤占
            bucketKey = PREFIX_TTS_UPLOAD + (userBucket != null ? userBucket : clientIp);
            refundOnSuccess = false;
            allowed = rateLimitService.isAllowed(
                    bucketKey, ttsUploadMaxAttempts, ttsUploadWindowSeconds);
        } else if (path.equals("/api/posts")) {
            // 发帖是写库 + 建索引的写入口，独立配额桶（仅 POST，GET 已在上面放行）
            bucketKey = PREFIX_POSTS + (userBucket != null ? userBucket : clientIp);
            refundOnSuccess = false;
            allowed = rateLimitService.isAllowed(bucketKey, postsMaxAttempts, postsWindowSeconds);
        } else if (path.startsWith("/api/app/dict/")) {
            // ⚠️ 这条 else if 必须显式存在：词典是**免鉴权**端点（本地读书也要能装），
            //   任何人无需 token 就能并发拉 23MB。若漏了它，路径会掉进下面的 TTS 桶，
            //   既限错了地方（下载词典消耗的是带宽不是引擎算力），
            //   又让装词典和合成互相挤占配额。
            // 该端点只计请求数、不退还（成功也消耗带宽）。
            bucketKey = PREFIX_DICT + clientIp;
            refundOnSuccess = false;
            allowed = rateLimitService.isAllowed(bucketKey, dictMaxAttempts, dictWindowSeconds);
        } else {
            // /clone 与 /synthesize 共用同一个配额：两者消耗的都是同一份 TTS 引擎算力
            bucketKey = PREFIX_TTS_SYNC + (userBucket != null ? userBucket : clientIp);
            refundOnSuccess = false;
            allowed = rateLimitService.isAllowed(bucketKey, ttsMaxAttempts, ttsWindowSeconds);
        }

        if (!allowed) {
            log.warn("请求被限流: ip={}, path={}", clientIp, path);
            sendRateLimitResponse(response, path);
            return;
        }

        // 放行后按【实际结果】决定是否退还本次占用的配额：成功(2xx/3xx)退还，
        // 失败(4xx/5xx)保留 —— 这就是「失败次数」语义的落点。
        //
        // 用 downstreamCompleted 标志而不是在 finally 里直接看 status：
        // 下游抛异常时响应还没有状态（默认 200），只看 status 会把它误判成成功
        // 而退还配额，等于给「打崩接口」这条路开了免费额度。
        boolean downstreamCompleted = false;
        try {
            filterChain.doFilter(request, response);
            downstreamCompleted = true;
        } finally {
            if (downstreamCompleted && refundOnSuccess && isSuccessStatus(response.getStatus())) {
                rateLimitService.release(bucketKey);
            }
        }
    }

    /**
     * 本次请求是否「成功」（退还配额的条件）。
     *
     * <p>2xx/3xx 视为成功；任何 4xx/5xx 都算失败并保留配额 ——
     * 密码错(400)、账号被封(403)、服务端故障(500) 都属于「有人在试」，
     * 必须计入失败次数，否则限流就失去意义。
     *
     * <p>注意 400 是登录失败的**主路径**（AuthService 抛 IllegalArgumentException →
     * 400「用户名或密码错误」），不是异常情况。
     */
    private static boolean isSuccessStatus(int status) {
        return status >= 200 && status < 400;
    }

    /**
     * 规范化路径：URL 解码 + 剥离 ;matrix 参数，与 Spring MVC（removeSemicolonContent=true）
     * 的路由语义对齐，防止用「;参数 / %3B 编码」变体绕过限流匹配。
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String p = path;
        try {
            p = java.net.URLDecoder.decode(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法编码序列按原样处理，由后续正则不匹配自然放行
        }
        int semi = p.indexOf(';');
        if (semi >= 0) {
            p = p.substring(0, semi);
        }
        return p;
    }

    /**
     * 返回 429 响应体（与 AuthResponse 结构一致）。
     */
    private void sendRateLimitResponse(HttpServletResponse response, String path) throws IOException {
        String message;
        if (path.endsWith("/login")) {
            message = "登录请求过于频繁，请稍后再试";
        } else if (path.endsWith("/register")) {
            message = "注册请求过于频繁，请稍后再试";
        } else if (path.contains("/upload-reference") || path.contains("/references")) {
            message = "上传请求过于频繁，请稍后再试";
        } else if (path.equals("/api/posts")) {
            message = "发帖过于频繁，请稍后再试";
        } else {
            message = "语音合成请求过于频繁，请稍后再试";
        }

        AuthResponse authResponse = new AuthResponse(null, null, false, message);

        response.setStatus(429); // SC_TOO_MANY_REQUESTS
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), authResponse);
    }

    /**
     * 从 Authorization 头解析已校验的用户名，作为限流计桶键的一部分。
     *
     * 仅对携带 Bearer Token 的已认证请求生效；无 Token 或 Token 非法时返回 null，
     * 调用方回退到 clientIp 计桶（该请求后续由 JwtAuthenticationFilter 以 401 拒绝，不影响限流）。
     *
     * 缓解「反向代理部署下所有用户共享代理出口 IP → 按 IP 计桶导致全员 429 误伤」：
     * 登录用户改用各自 username 计桶，互不挤占。本过滤器位于 JwtAuthenticationFilter 之前执行，
     * 故此处独立做一次带签名校验的解析（{@code jwtUtil.extractUsername} 内部已 verify 签名），
     * 不依赖 SecurityContext。
     */
    private String resolveUserBucket(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        String token = auth.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            return null;
        }
        try {
            return jwtUtil.extractUsername(token);
        } catch (Exception e) {
            // Token 伪造 / 过期 / 签名不符：回退到 IP 计桶，由下游鉴权拒绝
            return null;
        }
    }

    /**
     * 获取客户端 IP（安全版）。
     *
     * 无条件信任 X-Forwarded-For/X-Real-IP 的第一个值时，任何直连客户端都能
     * 随意伪造这两个头，轮换假 IP 即可完全绕过登录/注册限流。
     *
     * 规则：
     *  1) 未配置可信代理，或直连来源（TCP 层 remoteAddr）不在白名单 → 只用 remoteAddr，
     *     任何转发头都不看；
     *  2) 直连来源是可信代理（如本机 Nginx）→ 从 X-Forwarded-For 链【右往左】找第一个
     *     不在可信列表里的地址（右边是代理追加的、越靠左越接近真实客户端，但左侧可被
     *     客户端预置伪造，必须以代理追加的最右可信段为界）。
     */
    private String getClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        if (trustedProxies.isEmpty() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (hop.isEmpty() || "unknown".equalsIgnoreCase(hop)) {
                    continue;
                }
                if (!isTrustedProxy(hop)) {
                    return hop;
                }
            }
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank() && !"unknown".equalsIgnoreCase(realIp)) {
            return realIp.trim();
        }

        return remoteAddr;
    }

    /** 判断某个 IP 是否在可信代理白名单内（支持精确 IP 与 CIDR） */
    private boolean isTrustedProxy(String ip) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(ip)) {
                    return true;
                }
            } catch (Exception e) {
                // 非法 IP 字符串（伪造头里什么都有），按不可信处理
            }
        }
        return false;
    }
}
