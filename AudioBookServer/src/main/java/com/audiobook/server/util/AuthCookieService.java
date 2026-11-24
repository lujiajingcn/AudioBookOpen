package com.audiobook.server.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 认证 Cookie 工具（Web/管理后台 JWT 不存 localStorage）。
 *
 * 凭据一律不落前端可读存储：localStorage 中的 JWT 会被任何 XSS 直接读走。
 * 登录/注册/改密成功后由服务端下发
 * {@code HttpOnly + SameSite=Lax + (生产)Secure} 的 Cookie，
 * JS 完全接触不到凭据，XSS 只能利用会话而无法窃取凭据。
 *
 * CSRF 防护依赖：SameSite=Lax（跨站的 POST/PUT/DELETE 不携带 Cookie）
 * + SecurityConfig 的严格 CORS 来源白名单（不允许通配符），
 * 因此 csrf() 保持关闭不会产生新的攻击面。
 *
 * <h3>按端隔离（本类存在的第二个理由）</h3>
 * Cookie 的作用域**不包含端口**：Web 站点在 :80、管理后台在 :3001，两者 host 相同
 * （都是服务器 IP），因此历史上共用同一个 {@code AUTH_TOKEN}。后果是任一端登录都会
 * 覆盖另一端的凭据——在 :80 用普通账号登录后，:3001 上的管理员凭据即被顶掉，
 * 管理端界面仍显示管理员用户名但每个 /api/admin 请求都返回 403，极具误导性。
 *
 * 现在各端在请求上带 {@link #APP_ID_HEADER} 标识自己的身份，服务端据此读写
 * **该端专属**的 Cookie 名（{@code AUTH_TOKEN_WEB} / {@code AUTH_TOKEN_MANAGER}），
 * 两个 Cookie 同名不同键、互不覆盖，登录/登出彻底隔离。
 *
 * 迁移兼容（老浏览器里还留着历史共享 Cookie，且线上可能仍在跑旧前端包）：
 * - 读取顺序：本端专属 Cookie 命中即用；未命中再回退到历史共享 {@code AUTH_TOKEN}，
 *   避免升级瞬间把所有在线用户踢下线；
 * - 写入/清除本端专属 Cookie 时，顺带把历史共享 {@code AUTH_TOKEN} 置为过期，
 *   让它在各端首次登录/登出后自然消失，不留「看似隔离、实则可被另一端读到」的暗门；
 * - 请求不带 {@link #APP_ID_HEADER}（Android、旧前端包、curl）时行为与改造前完全一致，
 *   只读写历史共享名。
 *
 * 注意：带标识时**不会**去读另一端的专属 Cookie——那正是本次要切断的串号路径。
 *
 * Android 端不受影响：响应体仍返回 token，移动端继续走 Authorization: Bearer 头，
 * {@link com.audiobook.server.filter.JwtAuthenticationFilter} 两者都接受，请求头优先。
 */
@Component
public class AuthCookieService {

    /**
     * 客户端应用标识请求头（值是应用自己的短标识，如 {@code web} / {@code manager}）。
     * 服务端据此为该端选用专属 Cookie 名；缺失则退回历史共享行为。
     */
    public static final String APP_ID_HEADER = "X-Client-App";

    /**
     * 合法应用标识：小写字母、数字、下划线、短横线，长度 1~16。
     * 标识会被拼进 Cookie 名，必须严格校验——否则请求头里的
     * 任意字符（分号、空格、换行）可注入额外的 Set-Cookie 属性。
     */
    private static final Pattern APP_ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,16}");

    /** 历史共享 Cookie 名，同时用作「带标识时」专属 Cookie 名的前缀 */
    private final String cookieName;
    private final boolean secure;
    private final String sameSite;
    private final Duration maxAge;

    public AuthCookieService(
            @Value("${app.auth.cookie.name:AUTH_TOKEN}") String cookieName,
            // 注解默认值为 true（fail-secure）：配置缺失时默认下发 Secure Cookie，
            // 杜绝生产忘记覆盖导致凭据走明文链路。
            // dev/prod 的 yml 已分别显式声明 false / ${AUTH_COOKIE_SECURE:true}，正常部署行为不变。
            @Value("${app.auth.cookie.secure:true}") boolean secure,
            @Value("${app.auth.cookie.same-site:Lax}") String sameSite,
            @Value("${jwt.expiration:86400000}") long expirationMillis) {
        this.cookieName = cookieName;
        this.secure = secure;
        this.sameSite = sameSite;
        this.maxAge = Duration.ofMillis(expirationMillis);
    }

    /** 登录/注册/改密成功后下发认证 Cookie（无应用标识，历史共享行为） */
    public void writeAuthCookie(HttpServletResponse response, String token) {
        writeAuthCookie(response, token, null);
    }

    /**
     * 登录/注册/改密成功后下发认证 Cookie，有效期与 JWT 过期时间一致。
     * 带应用标识时写入该端专属 Cookie，并把历史共享 Cookie 立即置过期以完成迁移。
     */
    public void writeAuthCookie(HttpServletResponse response, String token, String appId) {
        String name = cookieNameFor(appId);
        response.addHeader(HttpHeaders.SET_COOKIE,
                builder(name, token).maxAge(maxAge).build().toString());
        expireLegacyCookie(response, name);
    }

    /** 登出：下发立即过期的同名 Cookie 将其清除（无应用标识，历史共享行为） */
    public void clearAuthCookie(HttpServletResponse response) {
        clearAuthCookie(response, null);
    }

    /**
     * 登出：清除该端专属 Cookie + 历史共享 Cookie。
     * 只清本端的专属 Cookie——另一端（另一个端口上的站点）的登录态必须原样保留，
     * 这正是按端隔离的目的。
     */
    public void clearAuthCookie(HttpServletResponse response, String appId) {
        String name = cookieNameFor(appId);
        response.addHeader(HttpHeaders.SET_COOKIE,
                builder(name, "").maxAge(Duration.ZERO).build().toString());
        expireLegacyCookie(response, name);
    }

    /**
     * 从请求中解析客户端应用标识；缺失或非法（不符合 {@link #APP_ID_PATTERN}）返回 null。
     * 非法值一律按「无标识」处理，绝不把它拼进 Cookie 名或参与查找。
     */
    public String resolveAppId(HttpServletRequest request) {
        String raw = request.getHeader(APP_ID_HEADER);
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return APP_ID_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    /**
     * 从请求 Cookie 中读取认证令牌；不存在或为空返回 empty。
     *
     * 读取顺序：本端专属 Cookie（有标识时）→ 历史共享 Cookie。
     * 明确**不**回退到另一端的专属 Cookie：跨端读取会让「:80 的普通用户凭据」
     * 在 :3001 被当作管理员会话使用，正是本次要修掉的缺陷。
     */
    public Optional<String> readToken(HttpServletRequest request) {
        String appId = resolveAppId(request);
        if (appId != null) {
            Optional<String> scoped = readCookie(request, cookieNameFor(appId));
            if (scoped.isPresent()) {
                return scoped;
            }
        }
        return readCookie(request, cookieName);
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isEmpty()) ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** 无标识 → 历史共享名；有标识 → 历史共享名 + 下划线 + 大写标识 */
    private String cookieNameFor(String appId) {
        return appId == null ? cookieName : cookieName + "_" + appId.toUpperCase(Locale.ROOT);
    }

    /**
     * 写入专属 Cookie 时顺带清除历史共享 Cookie（{@link #expireLegacyCookie}）。
     * 传入的 name 已经是历史共享名时不做任何事，避免同一个 Set-Cookie 重复下发。
     */
    private void expireLegacyCookie(HttpServletResponse response, String currentName) {
        if (cookieName.equals(currentName)) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                builder(cookieName, "").maxAge(Duration.ZERO).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder builder(String name, String value) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/");
    }
}
