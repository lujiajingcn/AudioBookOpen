package com.audiobook.server.filter;

import com.audiobook.server.service.RateLimitService;
import com.audiobook.server.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「限流按失败次数计数」的回归测试。
 *
 * <p>语义：登录/注册放行后，若本次请求成功（status &lt; 400）就退还刚占用的那一次；
 * 失败则保留。非登录/注册的重接口（同步合成 / 上传 / 发帖）计的是真实资源消耗，
 * 成功同样占配额、不退还。
 *
 * <p>用 Mockito 直接驱动过滤器，不启动 Spring 上下文 —— 与项目里
 * {@code ShelfServiceTest} / {@code TtsLoadBalancerTest} 保持同一形态。
 */
class RateLimitFilterRefundTest {

    private static final String CLIENT_IP = "10.0.0.1";

    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final RateLimitFilter filter = new RateLimitFilter(
            rateLimitService,
            mock(JwtUtil.class),
            new ObjectMapper(),
            10, 60,   // login
            3, 60,    // register
            10, 60,   // tts
            20, 60,   // tts-upload
            10, 60,   // posts
            20, 60,   // dict（离线词典下载，免鉴权大文件）
            "",       // limited-paths：空 = 用内置默认正则
            "");      // trusted-proxies：空 = 不信任转发头，按 remoteAddr 计桶

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(CLIENT_IP);
        return req;
    }

    /** 下游返回指定状态码的链 */
    private static FilterChain returning(int status) {
        return (req, res) -> ((MockHttpServletResponse) res).setStatus(status);
    }

    // ---------- 1. 成功退还（本次修复的核心） ----------

    @Test
    @DisplayName("登录成功(200) → 退还本次占用的配额，且用的是同一个桶键")
    void loginSuccessRefunds() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/auth/login"), res, returning(200));

        // 计数用的键与退还用的键必须完全一致：若此处不一致，退还就会减到别的桶上
        // （那才是真正的隐患——正常用户的额度没还回来，反而把攻击者的桶清了）
        ArgumentCaptor<String> counted = ArgumentCaptor.forClass(String.class);
        verify(rateLimitService).isAllowed(counted.capture(), anyInt(), anyLong());
        verify(rateLimitService).release(counted.getValue());

        assertThat(counted.getValue()).isEqualTo("rate_limit:login:" + CLIENT_IP);
    }

    @Test
    @DisplayName("注册成功(200) → 同样退还配额")
    void registerSuccessRefunds() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        filter.doFilter(request("POST", "/api/auth/register"),
                new MockHttpServletResponse(), returning(200));

        verify(rateLimitService).release("rate_limit:register:" + CLIENT_IP);
    }

    // ---------- 2. 失败保留配额（限流防线不能被削弱） ----------

    @Test
    @DisplayName("登录失败(400 密码错) → 不退还，失败尝试必须累积")
    void loginBadPasswordKeepsQuota() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        filter.doFilter(request("POST", "/api/auth/login"),
                new MockHttpServletResponse(), returning(400));

        verify(rateLimitService, never()).release(anyString());
    }

    @Test
    @DisplayName("登录服务端故障(500) → 不退还")
    void loginServerErrorKeepsQuota() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        filter.doFilter(request("POST", "/api/auth/login"),
                new MockHttpServletResponse(), returning(500));

        verify(rateLimitService, never()).release(anyString());
    }

    @Test
    @DisplayName("下游抛异常 → 不退还（此时 response 还是默认 200，只看状态码会误判成成功）")
    void downstreamExceptionDoesNotRefund() {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);
        FilterChain boom = (req, res) -> {
            throw new ServletException("模拟下游崩溃");
        };
        MockHttpServletRequest req = request("POST", "/api/auth/login");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(req, res, boom))
                .isInstanceOf(ServletException.class);

        // 若不看「下游是否正常跑完」，这里会因为 status 仍是默认 200 而退还配额，
        // 等于给「把接口打崩」这条路开免费额度
        assertThat(res.getStatus()).isEqualTo(200);
        verify(rateLimitService, never()).release(anyString());
    }

    // ---------- 3. 被限流的请求 ----------

    @Test
    @DisplayName("已被限流(429) → 不调用下游、不退还")
    void rateLimitedRequestShortCircuits() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("POST", "/api/auth/login"), res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(rateLimitService, never()).release(anyString());
    }

    // ---------- 4. 其余桶不退还（成功也占配额） ----------

    @Test
    @DisplayName("TTS 合成成功(200) → 不退还：每次调用都真实消耗算力")
    void ttsSynthesisDoesNotRefund() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        filter.doFilter(request("POST", "/api/tts/synthesize"),
                new MockHttpServletResponse(), returning(200));

        verify(rateLimitService, never()).release(anyString());
    }

    // ---------- 5. 不受限路径完全不被碰 ----------

    @Test
    @DisplayName("非受限路径 → 既不计数也不退还")
    void unlimitedPathIsUntouched() throws Exception {
        filter.doFilter(request("GET", "/api/store/books"),
                new MockHttpServletResponse(), returning(200));

        verify(rateLimitService, never()).isAllowed(anyString(), anyInt(), anyLong());
        verify(rateLimitService, never()).release(anyString());
    }

    @Test
    @DisplayName("GET /api/posts（轻量读）→ 不占用发帖配额")
    void postsListIsExempt() throws Exception {
        filter.doFilter(request("GET", "/api/posts"),
                new MockHttpServletResponse(), returning(200));

        verify(rateLimitService, never()).isAllowed(anyString(), anyInt(), anyLong());
        verify(rateLimitService, never()).release(anyString());
    }

    // ---------- 6. 离线词典：独立桶，不与 TTS 挤占 ----------

    @Test
    @DisplayName("词典下载 → 走 dict 独立桶（不是 tts_sync 桶）")
    void dictDownloadUsesItsOwnBucket() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt(), anyLong())).thenReturn(true);

        filter.doFilter(request("GET", "/api/app/dict/download"),
                new MockHttpServletResponse(), returning(200));

        // ⚠️ 这条断言锁的是「独立桶」：漏了 else if 分支就会掉进 tts_sync，
        //   于是装词典和合成互相挤占配额（且词典是免鉴权的，桶按 IP 计）。
        verify(rateLimitService).isAllowed("rate_limit:dict:" + CLIENT_IP, 20, 60L);
        // 成功也不退还：下载消耗的是真实带宽
        verify(rateLimitService, never()).release(anyString());
    }

    @Test
    @DisplayName("GET /api/app/dict/info（轻量读元信息）→ 不限流")
    void dictInfoIsExempt() throws Exception {
        filter.doFilter(request("GET", "/api/app/dict/info"),
                new MockHttpServletResponse(), returning(200));

        verify(rateLimitService, never()).isAllowed(anyString(), anyInt(), anyLong());
        verify(rateLimitService, never()).release(anyString());
    }
}
