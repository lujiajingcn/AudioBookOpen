package com.audiobook.server.controller;

import com.audiobook.server.dto.SyncUserSettingsRequest;
import com.audiobook.server.dto.UserSettingItemRequest;
import com.audiobook.server.model.User;
import com.audiobook.server.model.UserSetting;
import com.audiobook.server.service.UserSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨端「用户级单值偏好」接口（LWW，身份 = userId + settingKey）。
 *
 * <p>路径 {@code /api/user-settings/**} 由 SecurityConfig 的
 * {@code anyRequest().authenticated()} 兜底，无需新增放行规则。
 *
 * <p><b>一次往返即完成拉 + 推</b>：PUT 上报本机全部白名单设置的现值 + 各自版本，
 * 服务端逐项 LWW 合并后返回权威全量（含每项服务端裁决出的新版本），
 * 客户端直接用响应覆盖本地水位，不必再发一次 GET。
 *
 * <p>GET 供只读拉取（未登录引导页、诊断工具），响应结构与 PUT 完全一致。
 */
@RestController
@RequestMapping("/api/user-settings")
public class UserSettingsController {

    private final TtsControllerSupport support;
    private final UserSettingsService settingsService;

    public UserSettingsController(TtsControllerSupport support, UserSettingsService settingsService) {
        this.support = support;
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(response(settingsService.list(user.getId())));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> sync(@Valid @RequestBody SyncUserSettingsRequest request,
                                                    Authentication authentication) {
        User user = support.currentUser(authentication);
        if (user == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(response(settingsService.sync(user.getId(), request.getItems())));
    }

    private Map<String, Object> response(List<UserSetting> rows) {
        List<UserSettingItemRequest> items = new ArrayList<>();
        long version = 0L;
        for (UserSetting row : rows) {
            if (!UserSettingsService.ALLOWED_KEYS.contains(row.getSettingKey())) {
                continue;
            }
            items.add(new UserSettingItemRequest(
                    row.getSettingKey(), row.getSettingValue(), row.getUpdatedAt()));
            version = Math.max(version, row.getUpdatedAt());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("settings", settingsService.asMap(rows));
        body.put("items", items);
        body.put("version", version);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户未认证"));
    }
}
