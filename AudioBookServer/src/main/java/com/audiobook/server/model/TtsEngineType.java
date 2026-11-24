package com.audiobook.server.model;

import java.util.Locale;

/**
 * TTS 引擎类型。
 *
 * <p>历史背景：整个合成链路原本只认 MOSS-TTS-Nano——服务端 {@code tts_nodes} 表里
 * 每一条地址都默认是 MOSS 的 {@code /api/generate}，节点表与请求 DTO 里都没有
 * 「这是哪种引擎」这一维度。要接入 Pocket TTS（负责中文以外的多语言）这类协议不同
 * 的引擎，就必须给节点与音色补上引擎标识，否则新节点一旦登记，中文合成也可能被
 * 派发到它而直接 400。
 *
 * <p>取值约定：<b>MOSS 为默认与向后兼容值</b>。数据库里 {@code tts_nodes.engine_type}
 * 为空/未知一律按 MOSS 处理（见 {@link TtsNode#engineTypeValue()}），因此加列之前的
 * 存量节点无需数据迁移即可继续按 MOSS 调度。
 */
public enum TtsEngineType {
    /** MOSS-TTS-Nano：中文主力，协议为 multipart {@code /api/generate} + demo_id + prompt_audio，响应 JSON(base64 WAV)。 */
    MOSS,
    /** Pocket TTS：中文以外的多语言（英/法/德/葡/意/西/荷…），协议为 multipart {@code POST /tts} + voice_url，响应为裸 WAV 字节流。 */
    POCKET;

    /** 宽容解析：null / 空 / 无法识别 → {@link #MOSS}（保持默认与向后兼容）。 */
    public static TtsEngineType fromString(String raw) {
        if (raw == null) {
            return MOSS;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return MOSS;
        }
        try {
            return TtsEngineType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return MOSS;
        }
    }
}
