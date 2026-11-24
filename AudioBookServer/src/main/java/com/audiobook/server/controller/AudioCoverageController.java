package com.audiobook.server.controller;

import com.audiobook.server.dto.SyncAudioCoverageRequest;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserAudioCoverage;
import com.audiobook.server.service.AudioCoverageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 有声覆盖图 REST 接口（Phase3 跨端同步，仅元数据、不含音频字节）。
 *
 * <p>路径 {@code /api/audio-coverage/**} —— 与 {@code /api/favorites}、{@code /api/word-book}
 * 一样由 SecurityConfig 的 {@code anyRequest().authenticated()} 兜底。身份 = {@code (userId, bookKey, source)}。
 *
 * <p>两端点：
 * <ol>
 *   <li>{@code GET  /api/audio-coverage}：拉全量覆盖图（PC/安卓进书架/同步时触发）。
 *       <b>P1 #3 起支持条件 GET</b>：客户端可带 {@code If-None-Match: <etag>}，
 *       若服务端当前 ETag 相同则返回 {@code 304 Not Modified}（无响应体），
 *       大幅节省「本机无音频的新设备每次开书架都全量拉」的流量。
 *       所有 200 响应都带 {@code ETag} 头供客户端缓存。</li>
 *   <li>{@code POST /api/audio-coverage/sync}：批量并集合并 + 返回合并后全量（本机合成覆盖上报）。
 *       响应也带 {@code ETag}，客户端下次可用作 {@code If-None-Match} 值。</li>
 * </ol>
 *
 * <p>ETag 是强验证器，格式见 {@link AudioCoverageService#computeEtag(Long, List)}——
 * 拼入 userId 保证跨用户永不撞，客户端切换账号时缓存自动失效。
 *
 * <p>响应体与收藏/生词本同族（{@code success/items/total}），客户端解析可复用一条链路。
 */
@RestController
@RequestMapping("/api/audio-coverage")
public class AudioCoverageController {

    private final TtsControllerSupport support;
    private final AudioCoverageService coverageService;

    public AudioCoverageController(TtsControllerSupport support, AudioCoverageService coverageService) {
        this.support = support;
        this.coverageService = coverageService;
    }

    /**
     * GET /api/audio-coverage — 我的有声覆盖图（全量，支持条件 GET）。
     *
     * <p>若 {@code If-None-Match} 头与当前 ETag 匹配，返回 304 无 body；
     * 否则返回 200 + ETag 头 + 全量 items。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            Authentication authentication,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        List<UserAudioCoverage> rows = coverageService.list(user.getId());
        String etag = AudioCoverageService.computeEtag(user.getId(), rows);
        if (etagMatches(ifNoneMatch, etag)) {
            // 304 不带 body，但仍需回 ETag（RFC 7232 §4.1）
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .body(listResponse(rows));
    }

    /** POST /api/audio-coverage/sync — 批量并集合并 + 返回合并后全量（带 ETag 供下次条件 GET） */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(@Valid @RequestBody SyncAudioCoverageRequest request,
                                                    Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        List<UserAudioCoverage> merged = coverageService.sync(user.getId(), request.getItems());
        String etag = AudioCoverageService.computeEtag(user.getId(), merged);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, etag)
                .body(listResponse(merged));
    }

    /**
     * If-None-Match 匹配判定（RFC 7232 §3.2）：
     * <ul>
     *   <li>{@code *} → 只要资源存在就算匹配（覆盖图始终存在，即便空也返 304）</li>
     *   <li>逗号分隔的多个 ETag → 任一匹配即算匹配</li>
     *   <li>{@code W/} 前缀的弱验证器 → 剥掉前缀后按强验证器比较（服务端只发强的，
     *       客户端可能因代理层弱化过）</li>
     * </ul>
     * 不做null/空字符串检查之外的容错——格式非法就当不匹配，走 200 全量。
     */
    private static boolean etagMatches(String ifNoneMatch, String currentEtag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank() || currentEtag == null) {
            return false;
        }
        String trimmed = ifNoneMatch.trim();
        if ("*".equals(trimmed)) {
            return true;
        }
        for (String candidate : trimmed.split(",")) {
            String c = candidate.trim();
            if (c.startsWith("W/") || c.startsWith("w/")) {
                c = c.substring(2).trim();
            }
            if (c.equals(currentEtag)) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户未认证"));
    }

    private Map<String, Object> listResponse(List<UserAudioCoverage> rows) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("items", coverageService.toItems(rows));
        response.put("total", rows.size());
        return response;
    }
}
