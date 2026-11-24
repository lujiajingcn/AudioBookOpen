package com.audiobook.server.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code pocket.tts.voices} 配置的单一解析口径。
 *
 * <p>该配置同时被两处消费：{@code VoiceCloningTtsService.resolveEngineType} 靠它判断
 * 「某个 voiceId 是否属于 Pocket 引擎」，{@code TtsTaskController.getVoices} 靠它渲染下拉清单。
 * 两处若各写一份解析逻辑，很容易在格式微调时走偏（一边认 {@code id:name}、一边只认 {@code id}），
 * 表现为「下拉里能选到的音色，合成时却被判成 MOSS」。故在此集中一份实现。
 *
 * <p>格式：逗号分隔，每项 {@code id} 或 {@code id:显示名}。显示名缺省时回退为 id 本身。
 * 保留配置顺序（用 {@link LinkedHashMap}），下拉与选点看到的次序与运维填写的一致。
 */
public final class PocketVoiceConfig {

    private PocketVoiceConfig() {
    }

    /**
     * 解析配置为 {@code id -> 显示名} 的有序映射。
     *
     * @param rawConfig 原始配置串，允许为 null/空（返回空映射）
     */
    public static Map<String, String> parse(String rawConfig) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawConfig == null || rawConfig.isBlank()) {
            return result;
        }
        for (String item : rawConfig.split(",")) {
            String entry = item.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            String id;
            String name;
            if (colon < 0) {
                id = entry;
                name = entry;
            } else {
                id = entry.substring(0, colon).trim();
                name = entry.substring(colon + 1).trim();
                if (name.isEmpty()) {
                    name = id;
                }
            }
            if (id.isEmpty()) {
                continue;
            }
            result.put(id, name);
        }
        return result;
    }
}
