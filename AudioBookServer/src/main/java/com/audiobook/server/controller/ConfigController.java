package com.audiobook.server.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局配置下发：前端（如上传大小限制）统一以本接口为准，
 * 避免前后端阈值漂移导致「前端放行、后端拒绝」或「前端误拦」等不一致。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    /** 单段合成平均耗时（秒），供前端预估总耗时/剩余时间 */
    @Value("${app.tts.avg-segment-seconds:8}")
    private int avgSegmentSeconds;

    /** TTS 并发数 */
    @Value("${app.tts.concurrency:2}")
    private int ttsConcurrency;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> response = new HashMap<>();
        try {
            long maxFileSizeBytes = DataSize.parse(maxFileSize.trim()).toBytes();
            response.put("success", true);
            response.put("maxFileSize", maxFileSizeBytes);
            // 耗时预估参数
            response.put("avgSegmentSeconds", avgSegmentSeconds);
            response.put("ttsConcurrency", ttsConcurrency);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "配置解析失败，请稍后重试");
            return ResponseEntity.status(500).body(response);
        }
    }
}
