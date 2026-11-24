package com.audiobook.server.service;

import com.audiobook.server.dto.UserSettingItemRequest;
import com.audiobook.server.model.UserSetting;
import com.audiobook.server.repository.UserSettingRepository;
import com.audiobook.server.util.LwwVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户级跨端单值偏好的 LWW 合并。
 *
 * <p><b>键白名单是刻意设计</b>：本表是通用 kv 结构，但不接受任意 key ——
 * 否则客户端 bug 或恶意请求能把本表当免费 KV 存储用。新增可同步偏好时，
 * 必须同时在此登记并补校验（见 {@link #sanitize}）。
 *
 * <p>目前只同步两项：
 * <ul>
 *   <li>{@code playback_speed} —— 播放倍速，两端同名同值域（Android
 *       {@code PlaybackService.KEY_SPEED} Float；PC {@code config.playback_speed} float）。</li>
 *   <li>{@code default_voice_id} —— 默认音色（纯文本标识）。</li>
 * </ul>
 * 明确不同步：夜间模式（Android 三态 -1/1/2 vs PC bool）、字号（相对倍数 vs 绝对 pt）、
 * 以及任何设备相关配置（MOSS 本机路径、仅 WiFi 下载、窗口布局、token）。
 */
@Service
public class UserSettingsService {

    private static final Logger log = LoggerFactory.getLogger(UserSettingsService.class);

    public static final String KEY_PLAYBACK_SPEED = "playback_speed";
    public static final String KEY_DEFAULT_VOICE_ID = "default_voice_id";

    /** 允许同步的键。新增偏好必须先登记，否则服务端静默忽略。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(KEY_PLAYBACK_SPEED, KEY_DEFAULT_VOICE_ID);

    private static final int MAX_VALUE_LENGTH = 512;
    private static final double MIN_SPEED = 0.5d;
    private static final double MAX_SPEED = 3.0d;

    private final UserSettingRepository repository;

    public UserSettingsService(UserSettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<UserSetting> list(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return repository.findByUserId(userId);
    }

    /** 便于只读取值（响应里附一份扁平 settings map）。 */
    public Map<String, String> asMap(List<UserSetting> rows) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (UserSetting row : rows) {
            if (ALLOWED_KEYS.contains(row.getSettingKey())) {
                out.put(row.getSettingKey(), row.getSettingValue());
            }
        }
        return out;
    }

    /**
     * 一次往返完成「拉 + 推」：逐项 LWW 合并后返回权威全量。
     * 上报项版本不高于服务端版本时不动库（避免每轮同步把版本号无限抬高）。
     */
    @Transactional
    public List<UserSetting> sync(Long userId, List<UserSettingItemRequest> items) {
        if (userId == null) {
            return List.of();
        }
        if (items != null) {
            // 同一批次里同一个 key 只保留最后一条，避免一次请求内自我覆盖
            Set<String> seen = new LinkedHashSet<>();
            List<UserSettingItemRequest> ordered = new ArrayList<>(items);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                UserSettingItemRequest it = ordered.get(i);
                if (it == null || it.getKey() == null || !seen.add(it.getKey())) {
                    ordered.remove(i);
                }
            }
            for (UserSettingItemRequest item : ordered) {
                String value = sanitize(item.getKey(), item.getValue());
                if (value == null) {
                    continue;
                }
                mergeOne(userId, item.getKey(), value, item.getUpdatedAt());
            }
        }
        return list(userId);
    }

    private void mergeOne(Long userId, String key, String value, Long incoming) {
        try {
            mergeOneOnce(userId, key, value, incoming);
        } catch (DataIntegrityViolationException e) {
            // 并发新建撞 uk_us_user_key：回查后重跑一次合并（此时行必已存在）
            log.debug("用户设置并发插入撞唯一约束，回查重合并 userId={} key={}", userId, key);
            mergeOneOnce(userId, key, value, incoming);
        }
    }

    private void mergeOneOnce(Long userId, String key, String value, Long incoming) {
        UserSetting row = repository.findByUserIdAndSettingKey(userId, key).orElse(null);
        if (row == null) {
            UserSetting fresh = new UserSetting(userId, key, value, LwwVersions.firstVersion(incoming));
            repository.save(fresh);
            return;
        }
        if (!LwwVersions.shouldOverwrite(incoming, row.getUpdatedAt())) {
            return;
        }
        row.setSettingValue(value);
        row.setUpdatedAt(LwwVersions.nextVersion(incoming, row.getUpdatedAt()));
        repository.save(row);
    }

    /**
     * 校验并规范化值；返回 null 表示该项应被忽略（不落库、不报错）。
     * 非白名单 key、超长值、非法倍速都走这里，保证老客户端多传字段不会让整个请求 400。
     */
    static String sanitize(String key, String value) {
        if (key == null || !ALLOWED_KEYS.contains(key) || value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() > MAX_VALUE_LENGTH) {
            return null;
        }
        if (KEY_PLAYBACK_SPEED.equals(key)) {
            try {
                double speed = Double.parseDouble(v);
                if (!Double.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
                    return null;
                }
                // 统一成一位小数以内的最短表示，避免 "1" / "1.0" / "1.00" 造成无意义覆盖
                return String.valueOf(Math.round(speed * 100.0d) / 100.0d);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // default_voice_id：纯文本标识，去掉控制字符即可（长度已限）
        return v.replaceAll("[\\p{Cntrl}]", "");
    }
}
