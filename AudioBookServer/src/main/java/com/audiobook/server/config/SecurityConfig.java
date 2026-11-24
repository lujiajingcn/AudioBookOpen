package com.audiobook.server.config;

import com.audiobook.server.filter.JwtAuthenticationFilter;
import com.audiobook.server.filter.RateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** 未配置来源时的保底白名单：只放行本地开发端口，绝不通配。 */
    private static final List<String> DEV_FALLBACK_ORIGINS = List.of(
            "http://localhost:3000", "http://localhost:3001",
            "http://127.0.0.1:3000", "http://127.0.0.1:3001");

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final String allowedOrigins;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            ObjectMapper objectMapper,
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF 保持关闭的安全依据（引入 httpOnly Cookie 认证后的评估）：
            // 1) Cookie 携带 SameSite=Lax——跨站页面发起的 POST/PUT/DELETE 一律不附带
            //    认证 Cookie，经典表单型 CSRF 无法触达任何写接口；
            // 2) CORS 白名单为精确来源列表（禁止通配符）且 allowCredentials=true，
            //    攻击者站点无法通过 fetch/XHR 携带凭据跨域调用；
            // 3) 写接口均为 JSON 请求体，跨站表单提交 Content-Type 受限会被预检拦截。
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // 异步/错误重派发放行：StreamingResponseBody 单段音频下载与
                // SseEmitter 任务进度推送都是异步响应。响应在异步线程写出过程中一旦出错，
                // 容器会把错误页以 ASYNC/ERROR 的 DispatcherType 重新走一遍安全过滤链。
                // 但 JwtAuthenticationFilter 是 OncePerRequestFilter，默认不在 ASYNC/ERROR
                // 派发上重跑，异步线程上也就没有 SecurityContext；AuthorizationFilter 据此
                // 二次鉴权必然失败，抛出 AccessDeniedException，并因「response already
                // committed」而无法回写 JSON，最终把真正的错误掩盖成一条安全异常堆栈。
                // 这些内部重派发不是用户请求（用户在首次 REQUEST 派发上已通过鉴权），
                // 放行绝不绕过面向用户的鉴权，只是不再对容器内部重派发重复鉴权。
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                // 若该端点落在 /api/auth/** 的 permitAll 通配下，
                // 安全层不做拦截，仅靠 Controller 自查 AnonymousAuthenticationToken 兜底。
                // 匿名 token 的 isAuthenticated() 恒为 true，一旦漏判即形成越权入口，
                // 因此在通配之前显式要求认证，由 Security 层直接 401。
                .requestMatchers("/api/auth/change-password").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                // 支付平台异步通知：请求来自支付宝/微信的服务器，不可能携带我方 JWT，
                // 必须放行。安全性由渠道验签（RSA2 / SHA256-RSA + AES-GCM 解密）保证，
                // 验签失败的报文在 Provider 层就被拒绝，不会触达记账逻辑。
                .requestMatchers("/api/payment/notify/**").permitAll()
                // 客户端版本更新：客户端在启动页就检查更新，此刻可能未登录或 token 已过期。
                // 若要求鉴权，未登录用户永远收不到（尤其是强制）更新提示。
                // 接口只返回「已发布」版本的公开元信息与 APK 本身，不含任何用户数据。
                .requestMatchers("/api/app/version/**").permitAll()
                // 离线词典（ECDICT）分发：与账号无关的静态资源。
                // 客户端在阅读页长按取词时才触发下载，而阅读**本地导入的书**不需要登录
                // ——要求鉴权会让「离线读书的人永远装不上词典」。
                // 内容是MIT 许可的公开词库，不含任何用户数据。
                .requestMatchers("/api/app/dict/**").permitAll()
                // 书城（store）：让**未登录用户也能浏览与在线阅读**，与「离线读书」同一产品取向。
                // 这些只读端点仅返回公开书目/正文/片段元数据/单段音频流，且一律只按「书是否上架」
                // 校验，绝不引用当前用户（详见 BookStoreController / StoreReadingController），
                // 因此放行匿名请求不会泄露任何用户数据。
                // ⚠️ 顺序敏感：下面这几条「写/私有」路径必须在批量 permitAll 之前**显式要求认证**，
                //   否则会被通配漏放：
                //    - /progress（GET/PUT）按 user_id 读写阅读进度；
                //    - /download 下载整本电子书文件、/audio/download 打包下载整本音频，
                //      属于「下载入书架/离线留存」，仍要求登录（在线阅读用的是 /content 与
                //      /segments/{id}/audio 流式端点，已在 permitAll 之列，游客可直接读、边听边取）。
                //   匿名 AuthenticationToken 的 isAuthenticated() 恒为 true，故不能只靠
                //   Controller 自查 principal，必须由安全层在这几条上直接 401。
                .requestMatchers(
                        "/api/store/book/*/progress",
                        "/api/store/book/*/download",
                        "/api/store/book/*/audio/download")
                    .authenticated()
                .requestMatchers(
                        "/api/store/home",
                        "/api/store/books",
                        "/api/store/categories",
                        // 标签栏（标签名 + 在售书籍数）：与分类同级的公开元数据，游客也要能按标签筛选
                        "/api/store/tags",
                        "/api/store/editor-picks",
                        "/api/store/book/*",
                        "/api/store/book/*/cover",
                        "/api/store/book/*/content",
                        "/api/store/book/*/segments",
                        "/api/store/book/*/toc",
                        "/api/store/book/*/segments/*/audio")
                    .permitAll()
                // 发现社区（帖子 + 参考音频榜单）：让**未登录用户也能浏览**，与书城同一产品取向。
                // 只放行 GET 读端点；一切写入用户状态的操作（发帖、评论、收藏、举报、
                // 上传/分享参考音频、把分享音频导入本地库、上报使用量）仍要求认证。
                // ⚠️ 顺序敏感：PathPattern 的 `*` 不跨段，`/api/posts/*` 只匹配 `/api/posts/{id}`，
                //   不会误命中 `/api/posts/favorites/mine`（用户私有的「我的收藏」）；
                //   `/api/posts/image/*` 单独放行，帖子图片对游客也可见。
                //   `/api/tts/shared-references/*/audio` 是试听流，与榜单配套放行；
                //   同路径的 POST `/use`（上报使用量）由 anyRequest().authenticated() 兜住。
                //   `/api/tts/voices` 是**音色清单**（配置项 moss.tts.voices + 固定中文名），
                //   不含任何用户数据 —— 与书城/发现同一取向：游客也该看得见有哪些音色可选，
                //   否则合成页的「预设音色」下拉对未登录用户是一个永远空着的框
                //   （实测游客请求该接口原本返回 401）。真正要积分、要账号的是**建任务**，
                //   那条链路仍然要求登录。
                .requestMatchers(HttpMethod.GET,
                        "/api/posts",
                        "/api/posts/*",
                        "/api/posts/image/*",
                        "/api/tts/voices",
                        "/api/tts/shared-references",
                        "/api/tts/shared-references/*",
                        "/api/tts/shared-references/*/audio")
                    .permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
            // 未认证/token 过期时返回 401 + JSON（返回空 body 的 403 时前端无法区分
            // "未登录"与"无权限"，用户被静默踢出）。Security 过滤链的异常发生在 MVC 之前，
            // GlobalExceptionHandler 接不住，必须在这里配置。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        writeJson(response, HttpServletResponse.SC_FORBIDDEN, "没有权限执行此操作"))
            );

        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 复用 Spring 容器注入的共享 ObjectMapper（避免每次未认证响应都 new 一个实例，
        // 与 TtsControllerSupport 注释中「不再每次 new 一个实例」的口径一致）
        response.getWriter().write(objectMapper.writeValueAsString(
                java.util.Map.of("success", false, "message", message)));
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        List<String> origins = this.allowedOrigins == null ? List.of()
                : Arrays.stream(this.allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

        // 安全收紧：origins 为空时若回退到 AllowedOriginPatterns("*") +
        // allowCredentials(true)，生产忘配 CORS_ALLOWED_ORIGINS 即等于允许任意网站携带
        // 用户凭据发起跨域请求。因此：
        //  1) 显式配置 "*" 直接启动失败：通配与 allowCredentials(true) 组合既违反 CORS
        //     规范（Spring 6 请求期会抛异常导致 CORS 全挂），也是明确的安全事故；
        //  2) 未配置时降级为仅本地开发白名单并打醒目警告，而不是通配放行。
        if (origins.contains("*")) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins 不允许配置通配符 \"*\"（与 allowCredentials=true 组合存在凭据泄露风险），请配置具体的来源列表");
        }
        if (origins.isEmpty()) {
            log.warn("未配置 app.cors.allowed-origins，CORS 仅放行本地开发来源: {}。生产部署请务必设置 CORS_ALLOWED_ORIGINS！",
                    DEV_FALLBACK_ORIGINS);
            origins = DEV_FALLBACK_ORIGINS;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
