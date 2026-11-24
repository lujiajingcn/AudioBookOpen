package com.audiobook.server.model;

import java.io.File;

public class Request {
    private String text;            // 需要合成的文本
    private String voiceId;         // 预置音色 ID（可选）
    private byte[] referenceAudio;  // 参考音频数据（可选，用于实时克隆）
    /**
     * 参考音频的磁盘文件（可选，与 referenceAudio 二选一）。
     * /clone 的参考音频可达 50MB，若先 getBytes() 再转码回 byte[]，
     * 单次请求堆内会同时驻留两份大数组。文件形式可直接流式上传给引擎，堆内零副本。
     * doSynthesize 优先使用本字段，为空时才回退 referenceAudio 字节数组。
     */
    private File referenceAudioFile;

    /**
     * 本次合成允许引擎生成的最大音频帧数（可选）。
     *
     * <p>MOSS-TTS-Nano 的 {@code /api/generate} 默认 {@code max_new_frames=375}，
     * 按 12.5 帧/秒约等于 30 秒音频 —— 而每次请求的文本本来只有 ≤30 字（超长句在小片那一层就拆开了），按 30 秒去生成是严重过量，
     * 而生成耗时随帧数增长（实测 12 s prompt 下 375 帧 14.7 s）。
     * 由 {@code VoiceCloningTtsService} 按文本长度算出合适值后填入；
     * 为 null 时不下发该字段，引擎沿用自身默认值（保持旧行为）。
     */
    private Integer maxNewFrames;

    /**
     * 本书自动识别出的语种（{@code zh/en/fr/de/es/it/pt/nl/other}），来自 {@code Task.language}，
     * 批量逐段合成时透传。选引擎的<b>主判据</b>：中文/other→MOSS，
     * Pocket 支持且运营已配音色的拉丁语→Pocket。为空时上层即时按文本兜底识别。
     */
    private String language;

    // getters & setters
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getVoiceId() { return voiceId; }
    public void setVoiceId(String voiceId) { this.voiceId = voiceId; }
    public byte[] getReferenceAudio() { return referenceAudio; }
    public void setReferenceAudio(byte[] referenceAudio) { this.referenceAudio = referenceAudio; }
    public File getReferenceAudioFile() { return referenceAudioFile; }
    public void setReferenceAudioFile(File referenceAudioFile) { this.referenceAudioFile = referenceAudioFile; }
    public Integer getMaxNewFrames() { return maxNewFrames; }
    public void setMaxNewFrames(Integer maxNewFrames) { this.maxNewFrames = maxNewFrames; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
}