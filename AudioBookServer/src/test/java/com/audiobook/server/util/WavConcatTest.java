package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WavConcat} 的回归测试。
 *
 * <p>本类存在的理由：拼接是「超长句拆片合成」的最后一步，一旦它悄悄坏掉，
 * 表现是「音频能播但少了一半」—— 用户听到半句话，而日志里每一步都成功。
 * 所以既要锁「字节级顺序正确」，也要锁「格式不一致必须抛」，
 * 后者是**刻意不做兼容**的判据（见 {@link WavConcat} 类注释）。
 */
class WavConcatTest {

    private static final int RATE = 22050;

    @Test
    @DisplayName("按顺序拼接：data 长度 = 各段之和，内容逐字节相接")
    void concatenatesInOrder() {
        byte[] a = wav(100, RATE, 1, 16, 0);
        byte[] b = wav(50, RATE, 1, 16, 200);

        // 关闭淡出：本用例锁的是「顺序与长度」，逐字节相接的断言不该被接缝处理干扰
        byte[] out = WavConcat.concat(List.of(a, b), 0);

        assertThat(dataOf(out)).hasSize(dataOf(a).length + dataOf(b).length);
        // 顺序：不是「a 覆盖 b」，也不是「b 在前」
        assertThat(Arrays.copyOfRange(dataOf(out), 0, dataOf(a).length)).isEqualTo(dataOf(a));
        assertThat(Arrays.copyOfRange(dataOf(out), dataOf(a).length, dataOf(out).length))
                .isEqualTo(dataOf(b));
        // fmt 必须原样保留（采样率/声道/位深一个都不能动）
        assertThat(fmtOf(out)).isEqualTo(fmtOf(a));
        assertThat(rateOf(out)).isEqualTo(RATE);
        assertThat(channelsOf(out)).isEqualTo(1);
        assertThat(bitsOf(out)).isEqualTo(16);
    }

    @Test
    @DisplayName("只有一段时原样返回（不重写头部，避免无意义的字节变动）")
    void singleInputIsReturnedAsIs() {
        byte[] a = wav(10, RATE, 1, 16, 0);

        assertThat(WavConcat.concat(List.of(a))).isSameAs(a);
    }

    @Test
    @DisplayName("拼接结果本身仍可再次拼接（偶数化补位正确）")
    void outputIsStillValidInput() {
        byte[] a = wav(100, RATE, 1, 16, 0);
        byte[] b = wav(50, RATE, 1, 16, 200);

        byte[] twice = WavConcat.concat(List.of(WavConcat.concat(List.of(a, b)), a));

        assertThat(dataOf(twice)).hasSize(250 * 2);
    }

    @Test
    @DisplayName("奇数长度的 data 补一个填充字节，声明长度仍为真实长度")
    void padsOddDataLength() {
        // 8 bit 单声道：3 + 2 = 5 字节，奇数 ⇒ RIFF 要求补到偶数
        byte[] a = wav(3, RATE, 1, 8, 0);
        byte[] b = wav(2, RATE, 1, 8, 0);

        byte[] out = WavConcat.concat(List.of(a, b));

        assertThat(dataOf(out)).hasSize(5);
        // 补位只影响文件总长，不能把填充字节算进 data（否则尾部多一个静音采样）
        assertThat(declaredDataSize(out)).isEqualTo(5);
        // 8('RIFF'+size) + 4('WAVE') + (8+16) + (8+5+1)
        // （曾经写作 12 + 4：RIFF_HEADER_BYTES 已含 'WAVE'，再加 4 就重复了，
        //   旧断言把这个「文件尾多 4 字节」的缺陷一起锁住了。）
        assertThat(out).hasSize(8 + 4 + (8 + fmtOf(out).length) + (8 + 5 + 1));
    }

    @Test
    @DisplayName("输出文件长度与 RIFF 声明的 size 一致：尾部不得多出填充字节")
    void fileLengthMatchesDeclaredRiffSize() {
        // data 取偶数长度（2+2 字节）⇒ 尾部没有 RIFF 补位字节，文件最后 4 字节必然来自
        // data 区，多出来的零填充无处可藏；两段的 seed 也不同，顺带能看出拼接顺序。
        byte[] a = wav(2, RATE, 1, 8, 0);
        byte[] b = wav(2, RATE, 1, 8, 5);

        byte[] out = WavConcat.concat(List.of(a, b));

        // RIFF size 字段的定义是「'WAVE' 之后到文件末尾的字节数」⇒ 文件长 == 8 + size。
        // 这里曾经是 out.length == size + 12：riffSize 与输出数组长度把 'WAVE' 各算一次，
        // 文件尾多出 4 个零字节。size 字段本身是对的，所以 ffmpeg 一直容忍，
        // 只有拿「长度 - 8」当断言才会暴露。
        assertThat(readIntLe(out, 4)).isEqualTo(out.length - 8);
        byte[] data = dataOf(out);
        assertThat(Arrays.copyOfRange(out, out.length - 4, out.length))
                .isEqualTo(Arrays.copyOfRange(data, data.length - 4, data.length));
    }

    // ---------- 拼接点淡出 ----------

    /** 22050 Hz 下 10 ms 的帧数（= 221），与 {@link WavConcat} 内的换算保持同一算式 */
    private static final int FADE_MS = 10;
    private static final int FADE_FRAMES = (int) Math.round(FADE_MS * RATE / 1000.0);

    @Test
    @DisplayName("淡出只作用于接缝：整段首帧与末帧保持原样")
    void fadeAppliesToJointOnly() {
        byte[] a = tone(1000, 1, RATE, 30000);
        byte[] b = tone(1000, 1, RATE, 30000);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b), FADE_MS));

        // 整段第 1 帧、最后 1 帧不在任何淡出区里，必须原封不动 —— 否则就是削掉了开头和结尾
        assertThat(sampleAt(data, 0)).isEqualTo(30000);
        assertThat(sampleAt(data, 1999)).isEqualTo(30000);
        // 淡出区首帧、淡入区末帧的增益都恰为 1.0 ⇒ 与淡出区外的原波形无缝
        assertThat(sampleAt(data, 1000 - FADE_FRAMES)).isEqualTo(30000);
        assertThat(sampleAt(data, 1000 + FADE_FRAMES - 1)).isEqualTo(30000);
        // 接缝两侧各降到 1/fadeFrames：阶跃被抹平，而两端并不为零（不会听出静音坑）
        assertThat(sampleAt(data, 999)).isEqualTo((int) Math.round(30000.0 / FADE_FRAMES));
        assertThat(sampleAt(data, 1000)).isEqualTo((int) Math.round(30000.0 / FADE_FRAMES));
    }

    @Test
    @DisplayName("接缝包络单调：前片尾部只降不升，后片头部只升不降")
    void jointEnvelopeIsMonotonic() {
        byte[] a = tone(1000, 1, RATE, 30000);
        byte[] b = tone(1000, 1, RATE, 30000);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b), FADE_MS));

        for (int f = 1000 - FADE_FRAMES; f < 999; f++) {
            assertThat(sampleAt(data, f + 1))
                    .as("淡出区第 %s 帧不应高于前一帧", f + 1)
                    .isLessThanOrEqualTo(sampleAt(data, f));
        }
        for (int f = 1000; f < 1000 + FADE_FRAMES - 1; f++) {
            assertThat(sampleAt(data, f + 1))
                    .as("淡入区第 %s 帧不应低于前一帧", f + 1)
                    .isGreaterThanOrEqualTo(sampleAt(data, f));
        }
    }

    @Test
    @DisplayName("片短于两倍淡出时长时按半片收敛，不会被自己压成静音")
    void fadeIsClampedToHalfOfShortChunk() {
        // 10 帧的片 + 221 帧的淡出 ⇒ 实际只淡 5 帧，前 5 帧仍是原波形
        byte[] a = tone(10, 1, RATE, 30000);
        byte[] b = tone(10, 1, RATE, 30000);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b), FADE_MS));

        assertThat(sampleAt(data, 0)).isEqualTo(30000);
        assertThat(sampleAt(data, 4)).isEqualTo(30000);            // 淡出区首帧，增益 1.0
        assertThat(sampleAt(data, 5)).isEqualTo(30000);
        assertThat(sampleAt(data, 9)).isEqualTo(6000);             // 增益 1/5
        assertThat(sampleAt(data, 10)).isEqualTo(6000);            // 下片淡入首帧，增益 1/5
        assertThat(sampleAt(data, 14)).isEqualTo(30000);           // 增益 5/5
        assertThat(sampleAt(data, 19)).isEqualTo(30000);           // 整段末帧不动
    }

    @Test
    @DisplayName("三片拼接：中间片两端都淡，两段淡出区不相互重叠")
    void middleChunkFadesBothEnds() {
        byte[] a = tone(1000, 1, RATE, 30000);
        byte[] b = tone(1000, 1, RATE, 30000);
        byte[] c = tone(1000, 1, RATE, 30000);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b, c), FADE_MS));
        int low = (int) Math.round(30000.0 / FADE_FRAMES);

        assertThat(sampleAt(data, 0)).isEqualTo(30000);
        assertThat(sampleAt(data, 999)).isEqualTo(low);
        assertThat(sampleAt(data, 1000)).isEqualTo(low);
        assertThat(sampleAt(data, 1000 + FADE_FRAMES - 1)).isEqualTo(30000);
        assertThat(sampleAt(data, 2000 - FADE_FRAMES)).isEqualTo(30000);  // 中间片淡出区首帧
        assertThat(sampleAt(data, 1999)).isEqualTo(low);
        assertThat(sampleAt(data, 2000)).isEqualTo(low);
        assertThat(sampleAt(data, 2999)).isEqualTo(30000);
    }

    @Test
    @DisplayName("淡出时长为 0 或负数时输出与纯字节相接完全一致（保留 A/B 对比开关）")
    void disabledFadeKeepsBytesIdentical() {
        byte[] a = tone(500, 1, RATE, 30000);
        byte[] b = tone(500, 1, RATE, 30000);

        assertThat(dataOf(WavConcat.concat(List.of(a, b), 0))).isEqualTo(rawConcat(a, b));
        assertThat(dataOf(WavConcat.concat(List.of(a, b), -5))).isEqualTo(rawConcat(a, b));
    }

    @Test
    @DisplayName("不传时长时按 DEFAULT_JOINT_FADE_MILLIS 生效")
    void defaultFadeMillisIsApplied() {
        byte[] a = tone(2000, 1, RATE, 30000);
        byte[] b = tone(2000, 1, RATE, 30000);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b)));
        int frames = (int) Math.round(WavConcat.DEFAULT_JOINT_FADE_MILLIS * RATE / 1000.0);
        int low = (int) Math.round(30000.0 / frames);

        assertThat(sampleAt(data, 1999)).isEqualTo(low);
        assertThat(sampleAt(data, 2000)).isEqualTo(low);
        assertThat(sampleAt(data, 2000 + frames - 1)).isEqualTo(30000);
    }

    @Test
    @DisplayName("立体声：同一帧左右声道等增益，不会只衰减一边")
    void stereoChannelsFadeTogether() {
        byte[] a = tone(600, 2, RATE, 30000);
        byte[] b = tone(600, 2, RATE, 30000);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b), FADE_MS));

        for (int f : new int[]{600 - FADE_FRAMES, 599, 600, 600 + FADE_FRAMES - 1}) {
            assertThat(sampleAt(data, f, 1))
                    .as("第 %s 帧左右声道应等增益", f)
                    .isEqualTo(sampleAt(data, f, 0));
        }
        // 上一条在「两边都没动」时也会通过，故再确认淡出区确实降下来了
        assertThat(sampleAt(data, 599, 0)).isLessThan(30000);
    }

    @Test
    @DisplayName("非 16 bit PCM（本例 8 bit）跳过淡出，音频仍被正确拼接")
    void nonPcm16SkipsFade() {
        byte[] a = wav(400, RATE, 1, 8, 0);
        byte[] b = wav(400, RATE, 1, 8, 7);

        byte[] data = dataOf(WavConcat.concat(List.of(a, b), FADE_MS));

        assertThat(data).isEqualTo(rawConcat(a, b));
        assertThat(data).hasSize(800);
    }

    @Test
    @DisplayName("不改动传入的片段字节（调用方跨重试复用同一份缓存，改了会逐次多衰减一遍）")
    void doesNotMutateInputPieces() {
        byte[] a = tone(500, 1, RATE, 30000);
        byte[] b = tone(500, 1, RATE, 30000);
        byte[] aBefore = a.clone();
        byte[] bBefore = b.clone();

        WavConcat.concat(List.of(a, b), FADE_MS);

        assertThat(a).isEqualTo(aBefore);
        assertThat(b).isEqualTo(bBefore);
    }

    @Test
    @DisplayName("空输入抛 IllegalArgumentException")
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> WavConcat.concat(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可拼接");
        assertThatThrownBy(() -> WavConcat.concat(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("采样率/声道/位深不一致时抛异常，且报错里带 fmt 便于定位")
    void rejectsFormatMismatch() {
        byte[] base = wav(10, RATE, 1, 16, 0);

        assertThatThrownBy(() -> WavConcat.concat(List.of(base, wav(10, 44100, 1, 16, 0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式与前一段不一致");
        assertThatThrownBy(() -> WavConcat.concat(List.of(base, wav(10, RATE, 2, 16, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WavConcat.concat(List.of(base, wav(10, RATE, 1, 8, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非 RIFF/WAVE 或缺少 chunk 时抛异常（不静默产出坏文件）")
    void rejectsGarbage() {
        byte[] base = wav(10, RATE, 1, 16, 0);

        // 注意长度要 > 20，否则会先撞「过短」那条分支，就测不到 RIFF 标识校验
        assertThatThrownBy(() -> WavConcat.concat(
                List.of(base, "not a wav file at all, really not".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 RIFF/WAVE");
        assertThatThrownBy(() -> WavConcat.concat(List.of(base, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0})))
                .isInstanceOf(IllegalArgumentException.class);
        // 只有 fmt 没有 data
        assertThatThrownBy(() -> WavConcat.concat(List.of(base, headerOnly(16))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 fmt/data");
        // data 声明越界不再是硬错误（流式 WAV 未回填头部，见下面专测）
    }

    // ---------- 流式 WAV 的 data 哨兵容错 ----------

    @Test
    @DisplayName("流式 WAV：data 声明哨兵值(2000000000 / 0xFFFFFFFF)越界 ⇒ 按真实剩余字节取 PCM，不抛")
    void toleratesStreamedDataSentinel() {
        byte[] real = wav(200, RATE, 1, 16, 3);   // 真实 400 字节 PCM
        byte[] streamed = withDataSize(real, 2_000_000_000);

        WavConcat.Chunks c = WavConcat.parse(streamed, 0);
        assertThat(c.data).isEqualTo(dataOf(real));   // clamp 到实际负载，逐字节等于真实 PCM
        assertThat(c.fmt).isEqualTo(fmtOf(real));     // fmt 不受影响，原样取到

        // 另一种常见哨兵（全 F：writer 根本不 seek 回填）同样按剩余截断
        byte[] streamed2 = withDataSize(real, 0xFFFF_FFFFL);
        assertThat(WavConcat.parse(streamed2, 0).data).isEqualTo(dataOf(real));
    }

    @Test
    @DisplayName("流式小片能正常参与拼接：哨兵 data 尺寸不影响 PCM 取用")
    void streamedPieceConcatenates() {
        byte[] a = wav(100, RATE, 1, 16, 0);
        byte[] bReal = wav(50, RATE, 1, 16, 200);
        byte[] b = withDataSize(bReal, 2_000_000_000);   // 与 bReal PCM 相同，仅 data size 未回填

        byte[] out = WavConcat.concat(List.of(a, b), 0);

        // 期望长度取原始 bReal 的真实负载：dataOf() 会信 declared size，
        // 直接喂流式 buffer 会把哨兵值当长度算出天文数字（生产侧已 clamp，是对的）
        assertThat(dataOf(out)).hasSize(dataOf(a).length + dataOf(bReal).length);
        assertThat(Arrays.copyOfRange(dataOf(out), dataOf(a).length, dataOf(out).length))
                .isEqualTo(dataOf(bReal));
    }

    @Test
    @DisplayName("非 data chunk(fmt) 越界仍如实抛：没有把真实损坏静默吞掉")
    void stillThrowsOnNonDataChunkOverrun() {
        byte[] base = wav(10, RATE, 1, 16, 0);
        byte[] corruptFmt = wav(10, RATE, 1, 16, 0);
        // 'fmt ' 的 size 字段在 offset 16（RIFF12 + 'fmt '4），原为 16 ⇒ 改成越界值
        writeIntAt(corruptFmt, 16, 100_000);

        assertThatThrownBy(() -> WavConcat.concat(List.of(base, corruptFmt)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk 长度越界");
    }

    // ---------- 夹具与解析 ----------

    /**
     * 造一段最小合法 WAV：{@code fmt }(16) + {@code data}。
     *
     * <p>PCM 内容按 {@code seed} 变化，这样「拼接顺序错」与「整段复制错」都能被字节比较抓到 ——
     * 若两段内容相同，顺序写反也测不出来。
     */
    private static byte[] wav(int samples, int rate, int channels, int bits, int seed) {
        int blockAlign = channels * bits / 8;
        int dataLen = samples * blockAlign;
        byte[] data = new byte[dataLen];
        for (int i = 0; i < dataLen; i++) {
            data[i] = (byte) ((i + seed) & 0xFF);
        }
        return wavFromData(data, rate, channels, bits);
    }

    /**
     * 造一段 16 bit PCM 的「纯音」：所有样本等于同一振幅。
     *
     * <p>淡出测试必须用恒定振幅 —— 用随机字节当 PCM 的话，「增益是否真的施加了」
     * 与「随机字节碰巧变小」分不开，断言就只能写成宽松的范围判断。
     */
    private static byte[] tone(int frames, int channels, int rate, int amplitude) {
        int blockAlign = channels * 2;
        byte[] data = new byte[frames * blockAlign];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int off = f * blockAlign + c * 2;
                data[off] = (byte) (amplitude & 0xFF);
                data[off + 1] = (byte) ((amplitude >> 8) & 0xFF);
            }
        }
        return wavFromData(data, rate, channels, 16);
    }

    /** 用现成的 PCM 负载封一个最小合法 WAV（{@code fmt } 16 字节 + {@code data}） */
    private static byte[] wavFromData(byte[] data, int rate, int channels, int bits) {
        int blockAlign = channels * bits / 8;
        int riffSize = 4 + (8 + 16) + (8 + data.length);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeAscii(o, "RIFF");
        writeIntLe(o, riffSize);
        writeAscii(o, "WAVE");
        writeAscii(o, "fmt ");
        writeIntLe(o, 16);
        writeShortLe(o, 1);                       // PCM
        writeShortLe(o, channels);
        writeIntLe(o, rate);
        writeIntLe(o, rate * blockAlign);         // byteRate
        writeShortLe(o, blockAlign);
        writeShortLe(o, bits);
        writeAscii(o, "data");
        writeIntLe(o, data.length);
        o.write(data, 0, data.length);
        return o.toByteArray();
    }

    /** 参考实现：不做任何淡化的纯字节首尾相接（只在 data 负载层比较） */
    private static byte[] rawConcat(byte[] a, byte[] b) {
        byte[] da = dataOf(a);
        byte[] db = dataOf(b);
        byte[] out = Arrays.copyOf(da, da.length + db.length);
        System.arraycopy(db, 0, out, da.length, db.length);
        return out;
    }

    /** 读单声道 16 bit PCM 的第 frame 个样本（帧长 2 字节） */
    private static int sampleAt(byte[] data, int frame) {
        int off = frame * 2;
        return (short) ((data[off] & 0xFF) | (data[off + 1] << 8));
    }

    /** 读立体声 16 bit PCM 第 frame 帧第 channel 个声道的样本（帧长 4 字节） */
    private static int sampleAt(byte[] data, int frame, int channel) {
        int off = frame * 4 + channel * 2;
        return (short) ((data[off] & 0xFF) | (data[off + 1] << 8));
    }

    /** 只有 RIFF/WAVE + fmt（无 data），用于「缺 chunk」用例 */
    private static byte[] headerOnly(int samples) {
        byte[] base = wav(samples, RATE, 1, 16, 0);
        return Arrays.copyOf(base, 12 + 8 + 16);
    }

    private static void writeAscii(ByteArrayOutputStream o, String s) {
        for (int i = 0; i < s.length(); i++) {
            o.write(s.charAt(i));
        }
    }

    private static void writeIntLe(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    private static void writeShortLe(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >>> 8) & 0xFF);
    }

    /** 就地覆盖绝对偏移处的 4 字节小端整数（用于把某个 chunk 的 size 字段改成越界值） */
    private static void writeIntAt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    /**
     * 造「流式 WAV」：复制一份最小合法 WAV，把 {@code data} chunk 的 size 字段（offset 40 =
     * RIFF12 + fmt块24 + data头4）改成一个从未回填的哨兵值（真实 PCM 仍在其后直到文件末尾）。
     */
    private static byte[] withDataSize(byte[] wav, long sentinel) {
        byte[] copy = wav.clone();
        writeIntAt(copy, 40, (int) sentinel);
        return copy;
    }

    @Test
    @DisplayName("silentWav：合法 WAV，单声道 16 bit 与引擎同采样率，数据区全为 0")
    void silentWavIsAValidSilentWave() {
        byte[] wav = WavConcat.silentWav();

        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(rateOf(wav)).isEqualTo(RATE);
        assertThat(channelsOf(wav)).isEqualTo(1);
        assertThat(bitsOf(wav)).isEqualTo(16);

        byte[] data = dataOf(wav);
        assertThat(data).isNotEmpty();
        assertThat(data.length % 2).isZero();
        // 真静音：整段数据区里一个非零采样都没有（不是「接近静音」）
        assertThat(data).containsOnly((byte) 0);
        // 数据区长度 = 采样率 × 时长 × 2 字节（16 bit 单声道）
        assertThat(data.length).isEqualTo(RATE * 300 / 1000 * 2);
    }

    @Test
    @DisplayName("silentWav 可作为普通一段参与拼接（格式自洽、数据区按时长累加）")
    void silentWavCanBeConcatenated() {
        byte[] alone = WavConcat.silentWav();
        byte[] joined = WavConcat.concat(List.of(alone, WavConcat.silentWav()));

        assertThat(channelsOf(joined)).isEqualTo(1);
        assertThat(rateOf(joined)).isEqualTo(RATE);
        assertThat(declaredDataSize(joined)).isEqualTo(declaredDataSize(alone) * 2);
    }

    // ---------- 输出解析（独立实现，不复用被测代码的内部解析） ----------

    private static int chunkBody(byte[] wav, String id) {
        int p = 12;
        while (p + 8 <= wav.length) {
            String cur = new String(wav, p, 4, StandardCharsets.US_ASCII);
            int size = readIntLe(wav, p + 4);
            if (cur.equals(id)) {
                return p + 8;
            }
            p += 8 + size + (size % 2);
        }
        throw new AssertionError("输出里找不到 chunk: " + id);
    }

    private static byte[] fmtOf(byte[] wav) {
        int start = chunkBody(wav, "fmt ");
        int size = readIntLe(wav, start - 4);
        return Arrays.copyOfRange(wav, start, start + size);
    }

    private static byte[] dataOf(byte[] wav) {
        int start = chunkBody(wav, "data");
        return Arrays.copyOfRange(wav, start, start + declaredDataSize(wav));
    }

    private static int declaredDataSize(byte[] wav) {
        return readIntLe(wav, chunkBody(wav, "data") - 4);
    }

    private static int rateOf(byte[] wav) {
        return readIntLe(wav, chunkBody(wav, "fmt ") + 4);
    }

    private static int channelsOf(byte[] wav) {
        return readShortLe(wav, chunkBody(wav, "fmt ") + 2);
    }

    private static int bitsOf(byte[] wav) {
        return readShortLe(wav, chunkBody(wav, "fmt ") + 14);
    }

    private static int readIntLe(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readShortLe(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }
}
