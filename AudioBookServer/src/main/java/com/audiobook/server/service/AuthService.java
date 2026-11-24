package com.audiobook.server.service;

import com.audiobook.server.dto.LoginRequest;
import com.audiobook.server.dto.RegisterRequest;
import com.audiobook.server.filter.JwtAuthenticationFilter;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.UserRepository;
import com.audiobook.server.util.JwtUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    /** 登录结果：token + 是否是管理员 */
    public record LoginResult(String token, boolean isAdmin) {}

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final CreditService creditService;

    public AuthService(UserRepository userRepository, JwtUtil jwtUtil, CreditService creditService,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.creditService = creditService;
        this.passwordEncoder = passwordEncoder;
    }

    public String register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            // 业务校验异常必须用 IllegalArgumentException：AuthController 只 catch 它并
            // 以 400 + 原始消息返回。抛 RuntimeException 会掉进 500 分支，
            // 用户看到的是无意义的"注册失败，请稍后重试"。
            // 不返回"用户名已存在"这类可被用来枚举账号的精确提示，
            // 改用与 login 一致的模糊话术。
            throw new IllegalArgumentException("注册失败，请稍后重试或联系管理员");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setIsAdmin(false);

        // existsByUsername 与 save 之间存在并发窗口（两请求都通过前置检查），
        // DB 唯一约束会拒绝第二个 save 并抛 DataIntegrityViolationException。
        // 该异常被 AuthController 的 catch(Exception) 兜底成 500 + "注册失败，请稍后重试"时，
        // 用户看不到"用户名已存在"类的友好文案，且与 existsByUsername 命中分支的 400 文案不一致。
        // 显式翻译为 IllegalArgumentException，走与前置检查同一条 400 路径，
        // 文案刻意与 existsByUsername 命中分支保持一致（防账号枚举）。
        // 注意：故意不加 @Transactional —— grantRegisterBonus 必须独立事务（注册赠送失败不能让
        // 账号创建回滚），所以此处 catch 单独兜 save 异常。
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("注册并发撞名（DB 唯一约束兜底）: username={}", request.getUsername());
            throw new IllegalArgumentException("注册失败，请稍后重试或联系管理员");
        }

        // 注册赠送积分：单独 try 包裹，赠送失败绝不能让注册整体失败
        // （账号已创建，此时抛异常用户会以为注册没成功而反复重试，反而更糟）。
        try {
            int bonus = creditService.grantRegisterBonus(user.getId());
            if (bonus > 0) {
                log.info("新用户注册赠送积分: user={}, bonus={}", user.getUsername(), bonus);
            }
        } catch (Exception e) {
            log.error("⚠️ 注册赠送积分失败，需人工补发: user={}, 原因: {}", user.getUsername(), e.getMessage(), e);
        }

        return jwtUtil.generateToken(user.getUsername(), false, user.getTokenVersion());
    }

    @Transactional
    public LoginResult login(LoginRequest request) {
        Optional<User> userOpt = userRepository.findByUsername(request.getUsername());

        // 用户不存在与密码错误对外统一话术（防账号枚举），但都走 IllegalArgumentException
        // 返回 400，让前端拿到确定性的业务错误而不是 500/401 兜底。
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        User user = userOpt.get();

        // 检查是否被封禁
        if (user.getBannedUntil() != null && LocalDateTime.now().isBefore(user.getBannedUntil())) {
            String remaining = java.time.Duration.between(LocalDateTime.now(), user.getBannedUntil())
                    .toMinutes() + " 分钟";
            throw new IllegalArgumentException("该账号已被封禁，剩余 " + remaining);
        }

        // 如果封禁期已过，自动解封
        if (user.getBannedUntil() != null && LocalDateTime.now().isAfter(user.getBannedUntil())) {
            user.setBannedUntil(null);
            userRepository.save(user);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        boolean isAdmin = Boolean.TRUE.equals(user.getIsAdmin());
        String token = jwtUtil.generateToken(user.getUsername(), isAdmin, user.getTokenVersion());
        return new LoginResult(token, isAdmin);
    }

    public boolean validateToken(String token) {
        return jwtUtil.validateToken(token);
    }

    /**
     * 解析 token 归属用户，并返回其**当前**的管理员标记（一律以库内 is_admin 为准）。
     *
     * 存在的必要性：/api/auth/validate 原先只返回裸 boolean，前端拿不到「服务端认定我是谁、是
     * 什么角色」，只能沿用 localStorage 里的旧角色估计。一旦浏览器携带的 Cookie 已被另一个
     * 非管理员账号覆盖（Web 与 Manager 同 host 不同端口，Cookie 不按端口隔离），前端仍以为自己
     * 还是管理员 —— 于是停在后台页面上、每个 /api/admin/** 请求都撞 403「没有权限执行此操作」，
     * 用户完全无从判断应该重新登录。AdminUsers.vue 里「取消自身管理员后刷新本地角色」同理。
     *
     * 判定比 {@link #validateToken} 严一档：除有效期外，还要求 token 内的 tokenVersion 与库内
     * 一致（改密/重置密码会让此前签发的 token 立即失效），与
     * {@code JwtAuthenticationFilter} 的口径保持一致。
     *
     * @return 凭据有效时给出该用户当前的 isAdmin；token 过期/伪造、版本号不匹配、用户已删除时返回 empty
     */
    public Optional<Boolean> resolveAdminFlag(String token) {
        if (!jwtUtil.validateToken(token)) {
            return Optional.empty();
        }
        String username = jwtUtil.getUsernameFromToken(token);
        if (username == null) {
            return Optional.empty();
        }
        return userRepository.findByUsername(username)
                .filter(user -> jwtUtil.getTokenVersionFromToken(token) == user.getTokenVersion())
                .map(user -> Boolean.TRUE.equals(user.getIsAdmin()));
    }

    /**
     * 修改密码：校验旧密码正确后更新为新密码，并返回以新版本号签发的新 token。
     *
     * 返回新 token 的原因：tokenVersion 递增会让此前签发的所有旧 token 立即失效，
     * 若不下发新 token，前端改密成功后下一个请求就会被 401 踢出（契约断裂）。
     */
    @Transactional
    public String changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.trim().isEmpty()) {
            throw new IllegalArgumentException("新密码不能为空");
        }
        if (newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度至少为 6 位");
        }
        User found = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        // 行锁读取：save 走 merge 会整行写回，若读取与写回之间有并发积分变动
        //（充值回调入账/退款/扣费），旧快照会覆盖对方结果造成资损。
        User user = userRepository.findByIdForUpdate(found.getId()).orElse(found);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        // 改密即递增 token 版本号，此前签发的所有旧 token 立即失效
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        // 主动失效该用户的进程内快照缓存，避免封禁/改密状态延迟一个 TTL 才生效
        JwtAuthenticationFilter.evict(user.getUsername());
        return jwtUtil.generateToken(user.getUsername(), Boolean.TRUE.equals(user.getIsAdmin()), user.getTokenVersion());
    }
}
