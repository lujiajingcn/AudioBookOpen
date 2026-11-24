package com.audiobook.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 段级音频包络整形：在「引擎返回的 WAV」与「{@code convertWavToMp3}」之间做四件事 ——
 * <b>裁头尾静音 → 端点淡化 → 头部补齐 → 尾部补齐</b>。
 *
 * <p><b>为什么要有它（用户抱怨「一句话开始和结束的很突兀」的根因）</b>：
 * MOSS-TTS 每句都是一次<b>独立</b>冷启动合成，段与段之间没有任何跨句上下文，
 * 引擎自己给的头尾静音长度<b>不一致</b>（{@link WavConcat} 类注释里的抽样：10 段里 2 段尾静音为 0）。
 * 这直接造成两类可听问题：
 * <ul>
 *   <li><b>「啪」声</b>：末帧为非零采样就编进 MP3，播放器一响就是阶跃失真；</li>
 *   <li><b>段间停顿忽长忽短</b>：不同段的头尾静音长度从 0 到几百毫秒都有，
 *       叠加 MP3 编码器每段独立的 priming/padding（~26 ms），听感就是「顿一下、又顿一下」。</li>
 * </ul>
 * 本类把这两类问题在服务器端一并解决：
 * <ol>
 *   <li>裁掉引擎给的头尾静音，但保留 {@code headKeepMillis} / {@code tailKeepMillis}
 *       毫秒作为自然呼吸（裁到 0 会让咬字听起来被切掉）；</li>
 *   <li>对首末各做一次短淡化，把非零端点抹平到 0 —— 消除「啪」；</li>
 *   <li>头部统一补 {@code leadMillis} 毫秒静音、尾部统一补 {@code tailMillis} 毫秒静音
 *       —— 段间停顿等长，播放器插入的 MP3 priming/padding 也落在这段静音里，不再可闻。</li>
 * </ol>
 *
 * <p><b>与 {@link WavConcat#applyJointFades} 的分工</b>：{@code WavConcat} 只做「同一段内部
 * 小片接缝」的淡化（超长句拆片合成时才用得上）；本类做的是「整段的首末端点」。两者互补，
 * 都是纯服务端后处理，引擎不参与，也不需要重合成已入库任务 —— 只影响<b>新任务</b>。
 *
 * <p><b>失败即透传</b>：非法 WAV / 非 16 bit PCM 一律 warn 并原样返回。宁可少一层美化，
 * 也不能把音频本身搞坏。策略与 {@link WavConcat#applyJointFades} 一致。
 *
 * <p><b>不改采样率/声道/位深</b>：所有操作都在原始 PCM 采样点上进行，
 * {@code fmt } chunk 原样保留 —— 下游 {@code convertWavToMp3} 拿到的输入格式与改造前一致。
 */
public final class WavEnvelope {

    private static final Logger log = LoggerFactory.getLogger(WavEnvelope.class);

    private WavEnvelope() {
    }

    /**
     * 段级包络整形参数（毫秒为单位）。任一维度设为 {@code <= 0} 即关闭该维度的处理。
     *
     * @param trimThreshold   静音判定的采样绝对值阈值；某帧所有声道样本 {@code |v| <= 阈值} 视为静音。
     *                        16 bit PCM 满幅为 32767，取 300 ≈ -40 dBFS，足以避开本底噪声又不误伤轻辅音。
     *                        {@code <= 0} 表示不裁剪（保留引擎给的原始头尾静音）。
     * @param headKeepMillis  裁剪时头部保留的自然呼吸长度（毫秒）。裁到 0 会让首字听起来被切掉，
     *                        30 ms 是听感上「不急促、也不拖沓」的下限。
     * @param tailKeepMillis  裁剪时尾部保留的自然呼吸长度（毫秒）。尾字收音需要更长的余量，
     *                        80 ms 接近一次自然停顿。
     * @param leadMillis      头部统一补齐的静音长度（毫秒）。作用是「让所有段以等长静音起头」，
     *                        播放器插入的 MP3 priming 落在这里就不会被听见。40 ms 是听感上的舒适下限。
     * @param tailMillis      尾部统一补齐的静音长度（毫秒）。决定段间停顿的<b>基准</b>长度 ——
     *                        150 ms ≈ 一次自然句读，比大部分引擎给的尾静音更整齐。
     * @param fadeInMillis    头端淡入时长（毫秒）。5 ms 足以把首帧非零采样抹平，再长会让首字音量明显偏低。
     * @param fadeOutMillis   尾端淡出时长（毫秒）。15 ms 与 {@link WavConcat#DEFAULT_JOINT_FADE_MILLIS}
     *                        同量级 —— 短于 10 ms 压不住半周期的阶跃，长于 20 ms 会被听成一次可辨的音量凹陷。
     */
    public record Options(int trimThreshold,
                          int headKeepMillis,
                          int tailKeepMillis,
                          int leadMillis,
                          int tailMillis,
                          int fadeInMillis,
                          int fadeOutMillis) {

        /**
         * 全部关闭：输出与「不经本类」逐字节一致，用于 A/B 对比。
         */
        public static Options disabled() {
            return new Options(0, 0, 0, 0, 0, 0, 0);
        }

        /**
         * 只要所有可调维度都关闭，就等价于「透传」—— 调用方可以在这里短路，
         * 省掉一次 WAV 解析。
         */
        public boolean isDisabled() {
            return trimThreshold <= 0 && leadMillis <= 0 && tailMillis <= 0
                    && fadeInMillis <= 0 && fadeOutMillis <= 0;
        }
    }

    /**
     * 对一段 WAV 应用包络整形。任何异常/不支持的输入都退化为「原样返回」，不抛。
     *
     * @param wav  引擎返回的原始 WAV（或 {@link WavConcat#concat} 拼接后的 WAV）
     * @param opts 参数；{@code null} 或 {@link Options#isDisabled()} 为 true 时原样返回
     * @return 整形后的 WAV；输入不合法或非 16 bit PCM 时原样返回
     */
    public static byte[] apply(byte[] wav, Options opts) {
        if (wav == null || wav.length == 0 || opts == null || opts.isDisabled()) {
            return wav;
        }

        WavConcat.Chunks chunks;
        try {
            // 复用 WavConcat 的 RIFF chunk 链解析：不能假设 44 字节头，引擎多写一个 LIST/fact
            // 就会把 chunk 头当成 PCM（那条坑在 WavConcat.parse 的 javadoc 里）
            chunks = WavConcat.parse(wav, 0);
        } catch (IllegalArgumentException e) {
            log.warn("段级包络整形跳过：WAV 解析失败（{}）", e.getMessage());
            return wav;
        }

        WavConcat.PcmFormat pcm = WavConcat.PcmFormat.parse(chunks.fmt);
        if (pcm == null) {
            // 与 WavConcat.applyJointFades 同策略：非 16 bit PCM 属于「没被测过就不要上线」的路径，
            // 直接跳过而不是硬撑；音频本身仍被下游正确转码。
            log.warn("段级包络整形跳过：仅支持 16 bit PCM（音频仍会正常转 MP3，只是不做头尾整形）");
            return wav;
        }

        byte[] data = chunks.data;
        int originalFrames = data.length / pcm.blockAlign();

        // 1) 裁头尾静音（保留 head-keep / tail-keep 的自然呼吸）
        if (opts.trimThreshold() > 0) {
            data = trimSilence(data, pcm, opts);
        }

        // 2) 端点淡化：抹平「首帧/末帧非零采样」引起的爆音
        //    必须在裁剪之后做，否则淡化的可能是引擎自己给的一段静音（等于没淡）
        if (opts.fadeInMillis() > 0) {
            fadeIn(data, pcm, opts.fadeInMillis());
        }
        if (opts.fadeOutMillis() > 0) {
            fadeOut(data, pcm, opts.fadeOutMillis());
        }

        // 3) 头尾统一补齐：让段间停顿等长；MP3 priming/padding 也落在这段静音里
        List<byte[]> parts = new ArrayList<>(3);
        int leadBytes = silenceBytes(pcm, opts.leadMillis());
        int tailBytes = silenceBytes(pcm, opts.tailMillis());
        if (leadBytes > 0) {
            parts.add(new byte[leadBytes]);
        }
        parts.add(data);
        if (tailBytes > 0) {
            parts.add(new byte[tailBytes]);
        }
        int total = leadBytes + data.length + tailBytes;

        if (log.isDebugEnabled()) {
            int finalFrames = total / pcm.blockAlign();
            log.debug("段级包络整形完成：{} 帧 → {} 帧（{} Hz / {} 声道 / {} bit；trim<={} lead={}ms tail={}ms fade={}/{}ms）",
                    originalFrames, finalFrames, pcm.sampleRate(), pcm.channels(), pcm.bitsPerSample(),
                    opts.trimThreshold(), opts.leadMillis(), opts.tailMillis(),
                    opts.fadeInMillis(), opts.fadeOutMillis());
        }
        return WavConcat.assemble(chunks.fmt, parts, total);
    }

    /**
     * 裁掉头尾低于阈值的静音，但保留 headKeepMillis / tailKeepMillis 的自然呼吸。
     *
     * <p>「全段都是静音」时不裁（返回原样）：这种段来自 {@link WavConcat#silentWav()}
     * 的占位路径，本来就代表「无可朗读内容」，再裁就变成 0 长度，下游 MP3 编码会失败。
     */
    private static byte[] trimSilence(byte[] data, WavConcat.PcmFormat pcm, Options opts) {
        int frameBytes = pcm.blockAlign();
        int frames = data.length / frameBytes;
        if (frames == 0) {
            return data;
        }
        int threshold = opts.trimThreshold();

        int head = 0;
        while (head < frames && frameIsSilent(data, head * frameBytes, frameBytes, threshold)) {
            head++;
        }
        int tail = frames - 1;
        while (tail >= head && frameIsSilent(data, tail * frameBytes, frameBytes, threshold)) {
            tail--;
        }
        if (tail < head) {
            // 整段静音：不裁（裁完就是 0 长度，会破坏下游 MP3 编码契约）
            return data;
        }
        if (head == 0 && tail == frames - 1) {
            // 头尾本来就没有可裁的静音：避免无谓的数组复制
            return data;
        }

        int headKeep = millisToFrames(opts.headKeepMillis(), pcm.sampleRate());
        int tailKeep = millisToFrames(opts.tailKeepMillis(), pcm.sampleRate());
        int start = Math.max(0, head - headKeep);
        int end = Math.min(frames - 1, tail + tailKeep);
        if (start == 0 && end == frames - 1) {
            return data;
        }
        return Arrays.copyOfRange(data, start * frameBytes, (end + 1) * frameBytes);
    }

    /** 一帧内所有声道样本绝对值都 {@code <= threshold} 才算静音（任一声道响就不静音） */
    private static boolean frameIsSilent(byte[] data, int off, int frameBytes, int threshold) {
        for (int b = 0; b < frameBytes; b += 2) {
            int v = (short) ((data[off + b] & 0xFF) | (data[off + b + 1] << 8));
            if (Math.abs(v) > threshold) {
                return false;
            }
        }
        return true;
    }

    /**
     * 头端淡入：第 {@code m} 帧增益 {@code (m+1)/fadeFrames}，末帧恰为 1.0，与淡入区之后的原波形无缝。
     * 淡入长度夹到「不超过总帧数的一半」，避免超短段被自己压成静音（与 {@link WavConcat} 同策略）。
     */
    private static void fadeIn(byte[] data, WavConcat.PcmFormat pcm, int millis) {
        int totalFrames = data.length / pcm.blockAlign();
        int fadeFrames = Math.min(millisToFrames(millis, pcm.sampleRate()), totalFrames / 2);
        if (fadeFrames <= 0) {
            return;
        }
        for (int m = 0; m < fadeFrames; m++) {
            scaleFrame(data, m * pcm.blockAlign(), pcm.blockAlign(), (double) (m + 1) / fadeFrames);
        }
    }

    /**
     * 尾端淡出：第 {@code m} 帧增益 {@code (fadeFrames-m)/fadeFrames}，首帧恰为 1.0，与淡出区之前的原波形无缝。
     * 长度夹取同 {@link #fadeIn}。
     */
    private static void fadeOut(byte[] data, WavConcat.PcmFormat pcm, int millis) {
        int totalFrames = data.length / pcm.blockAlign();
        int fadeFrames = Math.min(millisToFrames(millis, pcm.sampleRate()), totalFrames / 2);
        if (fadeFrames <= 0) {
            return;
        }
        int start = (totalFrames - fadeFrames) * pcm.blockAlign();
        for (int m = 0; m < fadeFrames; m++) {
            scaleFrame(data, start + m * pcm.blockAlign(), pcm.blockAlign(),
                    (double) (fadeFrames - m) / fadeFrames);
        }
    }

    /**
     * 按增益缩放一个采样帧里的每个 16 bit 声道样本。
     *
     * <p>与 {@link WavConcat#scaleFrame} 逐字节等价（那里是 private，跨类复用会破坏封装，
     * 只好在本类保留一份）；两处若漂移，用 {@link WavEnvelopeTest} 的等增益断言即可发现。
     */
    private static void scaleFrame(byte[] data, int offset, int blockAlign, double gain) {
        for (int b = 0; b < blockAlign; b += 2) {
            int v = (short) ((data[offset + b] & 0xFF) | (data[offset + b + 1] << 8));
            int scaled = (int) Math.round(v * gain);
            data[offset + b] = (byte) (scaled & 0xFF);
            data[offset + b + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
    }

    /** 毫秒 → 帧数（四舍五入）；负值/0 一律返回 0，避免负数组长度。 */
    private static int millisToFrames(int millis, int sampleRate) {
        if (millis <= 0) {
            return 0;
        }
        return (int) Math.round((long) millis * sampleRate / 1000.0);
    }

    /** 一段静音对应的字节数（帧数 × 块对齐）；毫秒 {@code <= 0} 时返回 0，调用方据此不添加。 */
    private static int silenceBytes(WavConcat.PcmFormat pcm, int millis) {
        return millisToFrames(millis, pcm.sampleRate()) * pcm.blockAlign();
    }
}
