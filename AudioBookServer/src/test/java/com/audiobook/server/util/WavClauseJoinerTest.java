package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WavClauseJoiner} 的单测 —— 锁住「一句话内部各子句的节奏/音量一致」这条承诺。
 *
 * <p><b>为什么必须有</b>：这套接缝整形做的是三件<b>听感上直接可辨</b>、但代码里完全看不见的事：
 * 裁掉多少静音、插入多长停顿、把每片放大/缩小多少 dB。任何一处算错都不会抛异常、
 * 任务照样 completed，只是用户听到的句子变得「一逗一停」或者「忽大忽小」。
 * 所以每条用例都断言到<b>采样级</b>的事实（帧数、静音区间、RMS），而不是只断言「没崩」。
 */
class WavClauseJoinerTest {

    /** 22050 Hz / 16 bit / 单声道 —— 与引擎输出一致。 */
    private static final int SAMPLE_RATE = 22050;

    private static final int FRAME_BYTES = 2;

    /** 有声音的振幅：远高于 trim-threshold(300)，保证被判为「有效发声」。 */
    private static final int VOICE_AMPLITUDE = 4000;

    private static WavClauseJoiner.Options options(boolean loudnessMatch) {
        return new WavClauseJoiner.Options(true,
                300,        // trimThreshold
                25,         // headKeep
                60,         // tailKeep
                260,        // ，停顿（+头尾保留 85ms ⇒ 345ms，与 internalPauseCap 同量级）
                70,         // 、停顿
                300,        // ；：停顿
                40,         // 硬切停顿
                loudnessMatch,
                6.0,        // maxGainDb
                15,         // jointFade
                220,        // internalPauseMin：短于它的静音是发音缝隙，不动
                380);       // internalPauseCap：片内巨停顿封顶
    }

    // ------------------------------------------------------------------ 构造素材

    /**
     * 造一片「[头部静音][等幅有声体][尾部静音]」的 WAV。
     *
     * <p>声体用恒定直流（全采样 = {@code amplitude}）而不是正弦：直流下
     * 「有效发声 RMS」与「峰值」都恰好等于 {@code amplitude}，断言可以写到整数级，
     * 不必为一两个采样的浮动留容差。真实语音当然不是直流，但本类只关心能量口径。
     */
    private static byte[] piece(int headSilenceMillis, int bodyMillis, int tailSilenceMillis, int amplitude) {
        int headFrames = SAMPLE_RATE * headSilenceMillis / 1000;
        int bodyFrames = SAMPLE_RATE * bodyMillis / 1000;
        int tailFrames = SAMPLE_RATE * tailSilenceMillis / 1000;
        byte[] pcm = new byte[(headFrames + bodyFrames + tailFrames) * FRAME_BYTES];
        int offset = headFrames * FRAME_BYTES;
        for (int i = 0; i < bodyFrames; i++) {
            putSample(pcm, offset + i * FRAME_BYTES, amplitude);
        }
        return wrapWav(pcm);
    }

    /** 从 WAV 里取 PCM 负载（测试里直接复用被测代码的解析，避免另写一份 RIFF 解析）。 */
    private static WavConcat.Chunks chunks(byte[] wav) {
        return WavConcat.parse(wav, 0);
    }

    /**
     * 造一片「[头部静音][弱起音 attackAmp][等幅有声体][尾部静音]」的 WAV，用于验起音保护。
     *
     * <p>{@code attackAmp} 刻意低于 trim-threshold(300)：模拟塞音（b/d/g…）片首的成阻/除阻，
     * 旧实现会把它连同头部静音一起当「静音」裁掉，令字头含糊（线上《测试》task31 第23段「本人仍然是」）。
     */
    private static byte[] pieceWithSoftAttack(int headSilenceMillis, int attackMillis, int attackAmp,
                                              int bodyMillis, int tailSilenceMillis, int amplitude) {
        int headFrames = SAMPLE_RATE * headSilenceMillis / 1000;
        int attackFrames = SAMPLE_RATE * attackMillis / 1000;
        int bodyFrames = SAMPLE_RATE * bodyMillis / 1000;
        int tailFrames = SAMPLE_RATE * tailSilenceMillis / 1000;
        byte[] pcm = new byte[(headFrames + attackFrames + bodyFrames + tailFrames) * FRAME_BYTES];
        int offset = headFrames * FRAME_BYTES;
        for (int i = 0; i < attackFrames; i++) {
            putSample(pcm, offset + i * FRAME_BYTES, attackAmp);
        }
        offset += attackFrames * FRAME_BYTES;
        for (int i = 0; i < bodyFrames; i++) {
            putSample(pcm, offset + i * FRAME_BYTES, amplitude);
        }
        return wrapWav(pcm);
    }

    private static int frames(byte[] wav) {
        return chunks(wav).data.length / FRAME_BYTES;
    }

    private static int sampleAt(byte[] wav, int frame) {
        byte[] data = chunks(wav).data;
        return (short) ((data[frame * FRAME_BYTES] & 0xFF) | (data[frame * FRAME_BYTES + 1] << 8));
    }

    /** 某一帧区间内所有采样绝对值都 <= threshold（用于断言「这段确实是静音」）。 */
    private static boolean allSilent(byte[] wav, int fromFrame, int toFrame, int threshold) {
        for (int f = fromFrame; f < toFrame; f++) {
            if (Math.abs(sampleAt(wav, f)) > threshold) {
                return false;
            }
        }
        return true;
    }

    /** 某一帧区间的 RMS（有效发声口径之外，专门用来验响度对齐的结果）。 */
    private static double rms(byte[] wav, int fromFrame, int toFrame) {
        double sum = 0;
        int n = 0;
        for (int f = fromFrame; f < toFrame; f++) {
            int v = sampleAt(wav, f);
            sum += (double) v * v;
            n++;
        }
        return Math.sqrt(sum / Math.max(1, n));
    }

    private static void putSample(byte[] pcm, int offset, int value) {
        pcm[offset] = (byte) (value & 0xFF);
        pcm[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /** 把若干 WAV 的 PCM 首尾相接（不做任何淡化/整形），用来拼出「片内含停顿」的素材。 */
    private static byte[] concatPcm(List<byte[]> wavs) {
        int total = 0;
        for (byte[] w : wavs) {
            total += chunks(w).data.length;
        }
        byte[] pcm = new byte[total];
        int p = 0;
        for (byte[] w : wavs) {
            byte[] d = chunks(w).data;
            System.arraycopy(d, 0, pcm, p, d.length);
            p += d.length;
        }
        return wrapWav(pcm);
    }

    private static byte[] wrapWav(byte[] pcm) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int byteRate = SAMPLE_RATE * FRAME_BYTES;
        int riffSize = 36 + pcm.length;
        o.writeBytes(new byte[]{'R', 'I', 'F', 'F'});
        o.writeBytes(intLE(riffSize));
        o.writeBytes(new byte[]{'W', 'A', 'V', 'E'});
        o.writeBytes(new byte[]{'f', 'm', 't', ' '});
        o.writeBytes(intLE(16));            // PCM 子块大小
        o.writeBytes(shortLE(1));           // format = PCM
        o.writeBytes(shortLE(1));           // 单声道
        o.writeBytes(intLE(SAMPLE_RATE));
        o.writeBytes(intLE(byteRate));
        o.writeBytes(shortLE(FRAME_BYTES)); // blockAlign
        o.writeBytes(shortLE(16));          // bitsPerSample
        o.writeBytes(new byte[]{'d', 'a', 't', 'a'});
        o.writeBytes(intLE(pcm.length));
        o.writeBytes(pcm);
        return o.toByteArray();
    }

    private static byte[] intLE(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private static byte[] shortLE(int v) {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) v).array();
    }

    private static int millisToFrames(int millis) {
        return SAMPLE_RATE * millis / 1000;
    }

    /**
     * 停顿的帧数必须与 {@link WavClauseJoiner} 内部同口径（{@code Math.round}），
     * 不能用整数除法：130 ms @22050 Hz = 2866.5 帧，四舍五入是 2867、截断是 2866，
     * 差这一帧会让「总帧数 = 片长 + 停顿」的断言永远差 1。
     */
    private static int pauseFrames(int millis) {
        return (int) Math.round(millis * SAMPLE_RATE / 1000.0);
    }

    // ------------------------------------------------------------------ 静音归一化

    @Test
    @DisplayName("归一化：头尾多余静音被切掉，只留 head-keep/tail-keep")
    void normalizeKeepsOnlyNaturalBreath() {
        byte[] wav = piece(200, 300, 500, VOICE_AMPLITUDE);
        byte[] trimmed = WavClauseJoiner.normalize(wav, options(false));

        // 期望：25 ms 头静音 + 300 ms 声体 + 60 ms 尾静音
        assertThat(frames(trimmed))
                .as("归一化后帧数应 = headKeep(25ms) + 声体(300ms) + tailKeep(60ms)")
                .isEqualTo(millisToFrames(25) + millisToFrames(300) + millisToFrames(60));

        assertThat(allSilent(trimmed, 0, millisToFrames(25), 0))
                .as("头部的 25 ms 必须是静音")
                .isTrue();
        assertThat(sampleAt(trimmed, millisToFrames(25)))
                .as("声体第一帧必须是有声采样（多裁一帧就会把首字起音切掉）")
                .isEqualTo(VOICE_AMPLITUDE);
        assertThat(allSilent(trimmed, frames(trimmed) - millisToFrames(60), frames(trimmed), 0))
                .as("尾部的 60 ms 必须是静音")
                .isTrue();
    }

    @Test
    @DisplayName("归一化：引擎没给足静音时补齐到固定长度（这是「停顿可复现」的关键）")
    void normalizePadsWhenEngineGaveTooLittleSilence() {
        // 引擎只给了 0 ms 头静音 + 0 ms 尾静音 —— 抽样里 10 段有 2 段是这样
        byte[] wav = piece(0, 300, 0, VOICE_AMPLITUDE);
        byte[] normalized = WavClauseJoiner.normalize(wav, options(false));

        assertThat(frames(normalized))
                .as("不足则补齐：帧数仍必须是 headKeep + 声体 + tailKeep")
                .isEqualTo(millisToFrames(25) + millisToFrames(300) + millisToFrames(60));
        assertThat(allSilent(normalized, 0, millisToFrames(25), 0))
                .as("补出来的 25 ms 头静音必须真的在片头")
                .isTrue();
        assertThat(allSilent(normalized, frames(normalized) - millisToFrames(60), frames(normalized), 0))
                .as("补出来的 60 ms 尾静音必须真的在片尾")
                .isTrue();
        assertThat(sampleAt(normalized, millisToFrames(25)))
                .isEqualTo(VOICE_AMPLITUDE);
    }

    @Test
    @DisplayName("归一化：片首塞音的弱起音(低于阈值)必须保留，不能被当静音裁掉(线上「本人仍然是」字头被吃)")
    void normalizePreservesSubThresholdAttack() {
        // 素材：50ms 真静音 + 20ms 弱起音(振幅150 < trim-threshold 300) + 300ms 声体 + 200ms 尾静音。
        // 模拟「本(běn)」这类塞音片首：成阻/除阻振幅低于阈值，旧实现会连同头部静音一起裁掉，
        // 正文从元音中间开始 ⇒ 字头含糊。
        byte[] wav = pieceWithSoftAttack(50, 20, 150, 300, 200, VOICE_AMPLITUDE);
        byte[] normalized = WavClauseJoiner.normalize(wav, options(false));

        // 头部总长恒为 headKeep(25ms)：接缝停顿节奏与整片时长不因起音保护而改变。
        assertThat(frames(normalized))
                .as("头部总长仍是 headKeep(25ms)，起音保护不改变接缝节奏")
                .isEqualTo(millisToFrames(25) + millisToFrames(300) + millisToFrames(60));

        // 关键回归点：这 25ms 里必须能读到弱起音(150)，而不是被替换成纯静音(0)。
        int headMax = 0;
        for (int f = 0; f < millisToFrames(25); f++) {
            headMax = Math.max(headMax, Math.abs(sampleAt(normalized, f)));
        }
        assertThat(headMax)
                .as("片头 headKeep 内应保留弱起音振幅(150)；旧实现会归零 ⇒ 塞音字头被吃掉")
                .isEqualTo(150);
        // 保留下来的起音仍低于 trim-threshold —— 我们没有误把噪声抬成「有声」。
        assertThat(headMax).isLessThan(300);
    }

    @Test
    @DisplayName("归一化：整片静音不处理（裁成 0 长度会破坏下游 MP3 编码契约）")
    void normalizeNeverEmptiesPureSilence() {
        byte[] silent = piece(100, 0, 100, 0);
        byte[] trimmed = WavClauseJoiner.normalize(silent, options(false));
        assertThat(frames(trimmed))
                .as("整片静音必须原样保留，否则下游会拿到 0 帧音频")
                .isEqualTo(frames(silent));
    }

    @Test
    @DisplayName("归一化：关闭开关/非法输入一律透传，绝不破坏主链路")
    void normalizeDegradesSafely() {        byte[] wav = piece(200, 300, 500, VOICE_AMPLITUDE);
        assertThat(WavClauseJoiner.normalize(wav, WavClauseJoiner.Options.disabled()))
                .as("enabled=false 必须逐字节透传（A/B 基线）")
                .isSameAs(wav);
        assertThat(WavClauseJoiner.normalize(wav, null)).isSameAs(wav);

        byte[] garbage = new byte[]{1, 2, 3, 4};
        assertThat(WavClauseJoiner.normalize(garbage, options(false)))
                .as("非法 WAV 原样返回，由调用方后续逻辑处理")
                .isSameAs(garbage);
        assertThat(WavClauseJoiner.normalize(null, options(false))).isNull();
    }

    // ---------------------------------------------------------------- 停顿映射表

    @Test
    @DisplayName("片内停顿封顶：巨停顿压到上限，短停顿与发音缝隙不动")
    void internalPauseIsCapped() {
        // 一片里含两段发声：中间先一个 150 ms 的短停顿（不动），再一个 1200 ms 的巨停顿（压到 380）
        byte[] body1 = piece(0, 300, 0, VOICE_AMPLITUDE);
        byte[] gapShort = piece(150, 0, 0, 0);
        byte[] body2 = piece(0, 300, 0, VOICE_AMPLITUDE);
        byte[] gapHuge = piece(1200, 0, 0, 0);
        byte[] body3 = piece(0, 300, 0, VOICE_AMPLITUDE);
        byte[] wav = concatPcm(List.of(body1, gapShort, body2, gapHuge, body3));

        byte[] out = WavClauseJoiner.normalize(wav, options(false));

        // 期望：headKeep(25) + 300 + 150 + 300 + 380 + 300 + tailKeep(60)
        assertThat(frames(out))
                .as("1200 ms 的巨停顿应被封顶到 380 ms，150 ms 的短停顿保持原样")
                .isEqualTo(millisToFrames(25) + millisToFrames(300) + millisToFrames(150)
                        + millisToFrames(300) + pauseFrames(380) + millisToFrames(300)
                        + millisToFrames(60));
    }

    @Test
    @DisplayName("片内停顿封顶：上限配 0 即整项关闭")
    void internalPauseCapCanBeDisabled() {
        byte[] body1 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] gap = piece(1200, 0, 0, 0);
        byte[] body2 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] wav = concatPcm(List.of(body1, gap, body2));

        WavClauseJoiner.Options off = new WavClauseJoiner.Options(true, 300, 25, 60, 130, 80, 190, 40,
                false, 6.0, 15, 220, 0);
        // 只有 head/tail 归一化会改变长度，巨停顿必须原样留存
        byte[] out = WavClauseJoiner.normalize(wav, off);
        assertThat(frames(out))
                .as("internal-pause-cap-millis=0 ⇒ 关闭封顶，1200 ms 停顿必须原样保留")
                .isEqualTo(millisToFrames(25) + 2 * millisToFrames(100) + millisToFrames(1200)
                        + millisToFrames(60));
    }

    @Test
    @DisplayName("接缝停顿按「片尾标点」取，硬切时不加停顿")
    void pauseFollowsBoundaryPunctuation() {
        WavClauseJoiner.Options o = options(false);
        assertThat(WavClauseJoiner.pauseMillisFor("他说，", o)).isEqualTo(260);
        assertThat(WavClauseJoiner.pauseMillisFor("他说,", o)).isEqualTo(260);
        assertThat(WavClauseJoiner.pauseMillisFor("甲、", o)).isEqualTo(70);
        assertThat(WavClauseJoiner.pauseMillisFor("第一；", o)).isEqualTo(300);
        assertThat(WavClauseJoiner.pauseMillisFor("如下：", o)).isEqualTo(300);
        assertThat(WavClauseJoiner.pauseMillisFor("三十字硬切", o))
                .as("片尾不是停顿标点 ⇒ 切点落在词中间，加停顿只会把词劈得更明显")
                .isEqualTo(40);
        assertThat(WavClauseJoiner.pauseMillisFor("  ，  ", o))
                .as("首尾空白要先 strip 再取末字符")
                .isEqualTo(260);
        assertThat(WavClauseJoiner.pauseMillisFor(null, o)).isEqualTo(40);
        assertThat(WavClauseJoiner.pauseMillisFor("   ", o)).isEqualTo(40);
    }

    // ---------------------------------------------------------------------- 拼接

    @Test
    @DisplayName("拼接：片间按标点插入等长停顿，总时长可逐帧复算")
    void joinInsertsPausePerBoundary() {
        // 三片都没有静音（不需要裁剪），这样总帧数只由「片长 + 停顿」决定
        byte[] p1 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] p2 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] p3 = piece(0, 100, 0, VOICE_AMPLITUDE);

        byte[] joined = WavClauseJoiner.join(List.of(p1, p2, p3),
                List.of("第一句，", "第二句、", "第三句"), options(false));

        int expected = 3 * millisToFrames(100) + pauseFrames(260) + pauseFrames(70);
        assertThat(frames(joined))
                .as("总帧数 = 3 片声体 + 「，」停顿 + 「、」停顿（停顿必须等长可复算）")
                .isEqualTo(expected);

        // 第一段声体之后立刻是 130 ms 的静音
        int p1End = millisToFrames(100);
        assertThat(allSilent(joined, p1End + 2, p1End + pauseFrames(260) - 2, 0))
                .as("「，」之后必须是一段真正的静音（接缝淡化的边缘除外）")
                .isTrue();
    }

    @Test
    @DisplayName("拼接：响度对齐把各片 RMS 拉到同一水平（同一句话内不再忽大忽小）")
    void joinEqualisesLoudness() {
        // 两片内容时长相同、音量差 6 dB（1000 vs 2000）—— 正是「一句之内突然变响」的形态
        byte[] quiet = piece(0, 200, 0, 1000);
        byte[] loud = piece(0, 200, 0, 2000);

        byte[] joined = WavClauseJoiner.join(List.of(quiet, loud),
                List.of("前半句，", "后半句"), options(true));

        int bodyFrames = millisToFrames(200);
        int pauseFrames = pauseFrames(260);
        // 取各段「避开接缝淡化」的中段来量 RMS
        double rmsQuiet = rms(joined, 1000, bodyFrames - 1000);
        double rmsLoud = rms(joined, bodyFrames + pauseFrames + 1000, bodyFrames + pauseFrames + bodyFrames - 1000);

        assertThat(rmsQuiet)
                .as("两片对齐后 RMS 应基本相等（实测 quiet=%s / loud=%s）", rmsQuiet, rmsLoud)
                .isCloseTo(rmsLoud, org.assertj.core.data.Offset.offset(30.0));
        assertThat(rmsQuiet)
                .as("目标 = 两片中位数 1500，两片都应对齐到它附近")
                .isCloseTo(1500.0, org.assertj.core.data.Offset.offset(30.0));
    }

    @Test
    @DisplayName("拼接：响度对齐关闭时保留各片原始音量（A/B 对比用）")
    void joinKeepsOriginalLoudnessWhenDisabled() {
        byte[] quiet = piece(0, 200, 0, 1000);
        byte[] loud = piece(0, 200, 0, 2000);
        byte[] joined = WavClauseJoiner.join(List.of(quiet, loud),
                List.of("前半句，", "后半句"), options(false));

        int bodyFrames = millisToFrames(200);
        int pauseFrames = pauseFrames(260);
        assertThat(rms(joined, 1000, bodyFrames - 1000)).isCloseTo(1000.0,
                org.assertj.core.data.Offset.offset(10.0));
        assertThat(rms(joined, bodyFrames + pauseFrames + 1000,
                bodyFrames + pauseFrames + bodyFrames - 1000)).isCloseTo(2000.0,
                org.assertj.core.data.Offset.offset(10.0));
    }

    @Test
    @DisplayName("拼接：只有一片时原样返回；关闭开关时与 WavConcat.concat 逐字节一致")
    void joinDegradesToPlainConcat() {
        byte[] p1 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] p2 = piece(0, 100, 0, VOICE_AMPLITUDE);

        assertThat(WavClauseJoiner.join(List.of(p1), List.of("只有一片"), options(true)))
                .as("单片没有接缝，原样返回")
                .isSameAs(p1);

        byte[] baseline = WavConcat.concat(List.of(p1, p2), 15);
        assertThat(WavClauseJoiner.join(List.of(p1, p2), List.of("甲，", "乙"),
                WavClauseJoiner.Options.disabled()))
                .as("enabled=false 必须逐字节等于改造前的裸拼接（A/B 基线）")
                .isEqualTo(baseline);
    }

    @Test
    @DisplayName("拼接：各片格式不一致时退化为裸拼接（由 WavConcat 报出同一条异常）")
    void joinRejectsMixedFormatsTheSameWayAsConcat() {
        byte[] p1 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] p2 = otherSampleRatePiece(16000, 100, VOICE_AMPLITUDE);

        assertThatThrownBy(() -> WavClauseJoiner.join(List.of(p1, p2),
                List.of("甲，", "乙"), options(true)))
                .as("格式不一致必须是同一个 IllegalArgumentException（错误文案沿用 WavConcat，"
                        + "避免两套措辞让线上排查多一次猜测）")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式");
    }

    /** 造一片采样率与主用例不同的 WAV，用于验证「格式不一致」的降级路径。 */
    private static byte[] otherSampleRatePiece(int sampleRate, int bodyMillis, int amplitude) {
        int frames = sampleRate * bodyMillis / 1000;
        byte[] pcm = new byte[frames * FRAME_BYTES];
        for (int i = 0; i < frames; i++) {
            putSample(pcm, i * FRAME_BYTES, amplitude);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int byteRate = sampleRate * FRAME_BYTES;
        o.writeBytes(new byte[]{'R', 'I', 'F', 'F'});
        o.writeBytes(intLE(36 + pcm.length));
        o.writeBytes(new byte[]{'W', 'A', 'V', 'E'});
        o.writeBytes(new byte[]{'f', 'm', 't', ' '});
        o.writeBytes(intLE(16));
        o.writeBytes(shortLE(1));
        o.writeBytes(shortLE(1));
        o.writeBytes(intLE(sampleRate));
        o.writeBytes(intLE(byteRate));
        o.writeBytes(shortLE(FRAME_BYTES));
        o.writeBytes(shortLE(16));
        o.writeBytes(new byte[]{'d', 'a', 't', 'a'});
        o.writeBytes(intLE(pcm.length));
        o.writeBytes(pcm);
        return o.toByteArray();
    }

    @Test
    @DisplayName("拼接：空列表是编程错误，必须显式抛出而不是返回空音频")
    void joinRejectsEmptyInput() {
        assertThatThrownBy(() -> WavClauseJoiner.join(List.of(), List.of(), options(true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("拼接：texts 为 null / 长度不足时按硬切处理，不越界")
    void joinToleratesMissingTexts() throws IOException {
        byte[] p1 = piece(0, 100, 0, VOICE_AMPLITUDE);
        byte[] p2 = piece(0, 100, 0, VOICE_AMPLITUDE);

        byte[] noTexts = WavClauseJoiner.join(List.of(p1, p2), null, options(false));
        assertThat(frames(noTexts))
                .as("没有文本可判标点时按硬切停顿（40 ms）")
                .isEqualTo(2 * millisToFrames(100) + pauseFrames(40));

        byte[] shortTexts = WavClauseJoiner.join(List.of(p1, p2), List.of("只有第一片有文本，"), options(false));
        assertThat(frames(shortTexts))
                .as("texts 比 pieces 短时，缺的那一侧按硬切处理，不得越界")
                .isEqualTo(2 * millisToFrames(100) + pauseFrames(260));
    }
}
