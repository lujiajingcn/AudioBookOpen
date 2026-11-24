package com.audiobook.server.controller;

import com.audiobook.server.dto.AuthResponse;
import com.audiobook.server.dto.LoginRequest;
import com.audiobook.server.dto.RegisterRequest;
import com.audiobook.server.service.AuthService;
import com.audiobook.server.util.AuthCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;
    private final AuthCookieService authCookieService;

    public AuthController(AuthService authService, AuthCookieService authCookieService) {
        this.authService = authService;
        this.authCookieService = authCookieService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest,
                                                 HttpServletResponse response) {
        try {
            log.info("收到注册请求: username={}", request.getUsername());
            String token = authService.register(request);
            // 浏览器端凭据改由 httpOnly Cookie 承载（JS 不可读，XSS 无法窃取）；
            // 响应体里的 token 保留，仅供 Android 端使用。
            // 应用标识取自请求头，使 Web 与 Manager 各自使用专属 Cookie 名，互不覆盖。
            authCookieService.writeAuthCookie(response, token, authCookieService.resolveAppId(httpRequest));
            AuthResponse body = new AuthResponse(token, request.getUsername(), true, "注册成功");
            body.setIsAdmin(false); // 注册用户默认不是管理员
            log.info("注册成功: username={}", request.getUsername());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("注册校验失败: username={}, reason={}", request.getUsername(), e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new AuthResponse(null, null, false, e.getMessage()));
        } catch (Exception e) {
            log.error("注册异常: username={}", request.getUsername(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AuthResponse(null, null, false, "注册失败，请稍后重试"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse response) {
        try {
            log.info("收到登录请求: username={}", request.getUsername());
            AuthService.LoginResult result = authService.login(request);
            // 同上，下发认证 Cookie（按端隔离）；响应体 token 仅供 Android 端
            authCookieService.writeAuthCookie(response, result.token(), authCookieService.resolveAppId(httpRequest));
            AuthResponse body = new AuthResponse(result.token(), request.getUsername(), true, "登录成功");
            body.setIsAdmin(result.isAdmin());
            log.info("登录成功: username={}, isAdmin={}", request.getUsername(), result.isAdmin());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            log.warn("登录校验失败: username={}, reason={}", request.getUsername(), e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new AuthResponse(null, null, false, e.getMessage()));
        } catch (Exception e) {
            // 兜底分支只承接真正的服务端故障（DB 不可用、JWT 签发失败、自动解封写库冲突等）：
            // 「用户不存在 / 密码错误 / 账号封禁」都由 AuthService 抛 IllegalArgumentException，
            // 走上一条 400 分支，防账号枚举的目的在那边已经达成。
            //
            // 此处原先返回 401 + "用户名或密码错误"，等于把服务端故障伪装成「密码输错了」：
            // 用户会反复改密码重试（越试越撞登录限流），运维也拿不到 5xx 信号去定位。
            // 改为 500 + 中性文案，与 register / changePassword 的兜底分支保持一致。
            log.error("登录异常: username={}", request.getUsername(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AuthResponse(null, null, false, "登录失败，请稍后重试"));
        }
    }

    /**
     * 校验当前凭据是否有效，并回传**服务端认定**的角色。
     *
     * 返回结构由裸 boolean 改为 {@code {valid, isAdmin}}：前端 bootstrap 与「取消自身管理员」
     * 分支都依赖回传的 isAdmin 覆盖 localStorage 里的角色估计值（本地存储可被伪造，也会因
     * 跨端口 Cookie 被别的账号覆盖而失真）。此前后端只回 boolean，该覆盖逻辑实际从未生效——
     * 表现为浏览器已持有普通账号凭据、前端却仍自认管理员，进后台每个请求都撞 403。
     *
     * 兼容性：两端（Web / Manager）的 auth-api.ts 已按
     * {@code typeof d === 'boolean' ? {valid:d} : {valid, isAdmin}} 做归一化，旧前端无需改动；
     * Android 端不调用本接口。
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        // 凭据来源与过滤器一致：请求头优先（Android），否则读 httpOnly Cookie（浏览器）
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            token = authCookieService.readToken(request).orElse(null);
        }
        if (token == null) {
            return ResponseEntity.ok(validateBody(false, null));
        }
        try {
            return ResponseEntity.ok(authService.resolveAdminFlag(token)
                    .map(isAdmin -> validateBody(true, isAdmin))
                    .orElseGet(() -> validateBody(false, null)));
        } catch (Exception e) {
            // 过期/畸形/签名不符的 token 一律视为无效，而非 500
            return ResponseEntity.ok(validateBody(false, null));
        }
    }

    /** /validate 的响应体：valid 恒返回；isAdmin 仅在凭据有效、能定位到归属用户时给出 */
    private Map<String, Object> validateBody(boolean valid, Boolean isAdmin) {
        Map<String, Object> body = new HashMap<>();
        body.put("valid", valid);
        if (isAdmin != null) {
            body.put("isAdmin", isAdmin);
        }
        return body;
    }

    /**
     * 登出：清除认证 Cookie。
     * 放在 /api/auth/** permitAll 之下——即使 token 已过期也能清掉本地 Cookie，
     * 避免「登出按钮点了没反应、刷新后仍是登录态」的体验断裂。
     * 只清本端（按应用标识区分的）Cookie，另一个端口的站点登录态保持不变。
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest httpRequest,
                                                      HttpServletResponse response) {
        authCookieService.clearAuthCookie(response, authCookieService.resolveAppId(httpRequest));
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "已退出登录");
        return ResponseEntity.ok(body);
    }

    /** 修改密码（需登录态，校验旧密码，成功后下发新 token） */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody Map<String, Object> body,
            org.springframework.security.core.Authentication authentication,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        Map<String, Object> responseBody = new HashMap<>();
        try {
            // 该端点已在 SecurityConfig 中显式要求 authenticated（不再落入 /api/auth/** 通配），
            // 匿名请求会被安全层直接 401。这里保留防御性校验：匿名 token 的
            // isAuthenticated() 返回 true，不能仅靠它判断登录态。
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                responseBody.put("success", false);
                responseBody.put("message", "用户未认证");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(responseBody);
            }
            // 不能用 String.valueOf(body.get(...)) 取值——键缺失时会得到字符串 "null"，
            // 以「合法密码」形态进入 AuthService 绕过空值校验。改为仅接受真实的字符串类型，
            // 缺失/类型不符时为 null，在控制器层直接拦截。
            String oldPassword = asString(body.get("oldPassword"));
            String newPassword = asString(body.get("newPassword"));
            if (oldPassword == null || oldPassword.isEmpty()) {
                responseBody.put("success", false);
                responseBody.put("message", "缺少旧密码");
                return ResponseEntity.badRequest().body(responseBody);
            }
            String newToken = authService.changePassword(authentication.getName(), oldPassword, newPassword);
            // 改密会递增 tokenVersion 使旧凭据立即失效：必须同步轮换 Cookie，
            // 否则浏览器端下一个请求仍带旧 token 被 401 踢出。仍按端写入专属 Cookie 名。
            authCookieService.writeAuthCookie(response, newToken, authCookieService.resolveAppId(httpRequest));
            responseBody.put("success", true);
            // 响应体里的新 token 仅供 Android 端替换本地存储
            responseBody.put("token", newToken);
            responseBody.put("message", "密码修改成功");
            return ResponseEntity.ok(responseBody);
        } catch (IllegalArgumentException e) {
            responseBody.put("success", false);
            responseBody.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(responseBody);
        } catch (Exception e) {
            log.error("修改密码异常", e);
            responseBody.put("success", false);
            responseBody.put("message", "修改失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(responseBody);
        }
    }

    /** 仅当值是字符串时返回原值，否则（含键缺失）返回 null，绝不产生 "null" 字符串 */
    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
