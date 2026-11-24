package com.audiobook.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WAV 拼接：把同一个引擎、同一批参数生成的若干段 WAV 拼成一段 WAV。
 *
 * <p><b>用途</b>：一个「完整文本句子」若超过 {@link TextSegmenter#DEFAULT_MAX_LENGTH} 字，
 * 会被拆成若干小片分别送引擎（见 {@link TextSegmenter#subPieces(String)}），
 * 再把返回的音频拼成<b>一条</b>与整句对应的音频。
 *
 * <p><b>为什么在 WAV 层拼，而不是拼 MP3</b>：
 * <ul>
 *   <li>MP3 是带帧头与比特池的有损格式，直接首尾相接会得到「能播但时长/位率元数据不对」的
 *       文件，播放器按帧边界的时长计算会漂移；要拼得干净必须解码→重编码，而重编码是一次
 *       额外的有损转换。</li>
 *   <li>WAV 的负载就是裸 PCM，同格式拼接是纯字节复制，<b>零损失、零重采样</b>；
 *       拼完再走一次既有的 {@code convertWavToMp3}（与单段路径完全同一个出口）。</li>
 * </ul>
 *
 * <p><b>格式必须一致，不一致直接抛</b>：本类刻意<b>不做</b>重采样/声道混音/静音补齐等
 * 「尽力而为」的兼容 —— 那会把「引擎返回了不同采样率的音频」这种真实故障，
 * 静默变成「用户听到一段速度不对的音频」。不同采样率/位深/声道一律抛
 * {@link IllegalArgumentException}，让该段按既有的段级重试重发。
 *
 * <p>只保留 {@code fmt } 与 {@code data} 两个 chunk：其它 chunk（LIST/fact/bext 等）是
 * 元信息，拼接后语义不明确，丢弃比拼接更安全。
 *
 * <p><b>拼接点做淡出/淡入</b>：拼接是「把两条独立生成的波形首尾相贴」，若前片末帧与
 * 后片首帧之间没有共同的近似零点，接缝处就会出现一个阶跃 —— 听起来是一声轻微的「啪」。
 * 大多数情况下小片自己就带 ~50 ms 的首尾静音（引擎停止生成后波形自然衰减），接缝落在
 * 静音里，本来不响；但实测确实存在尾静音为 0 的片（抽样 10 段里有 2 段），所以这里做一道
 * 保险：在每个接缝的两侧各做一次短淡出/淡入（见 {@link #DEFAULT_JOINT_FADE_MILLIS}）。
 */
public final class WavConcat {

    private static final Logger log = LoggerFactory.getLogger(WavConcat.class);

    /**
     * 拼接点默认淡出/淡入时长（毫秒）。
     *
     * <p>取值区间 10~20 ms 是权衡结果：短于 10 ms 对「末帧停在半周期」这种阶跃压不住；
     * 长于 20 ms 就会被人耳听成一次可辨的音量凹陷（尤其接缝两侧本来就是语声时）。
     * 15 ms 在 22.05 kHz 下是 331 帧、在 48 kHz 下是 720 帧，均远小于任何一个小片
     * （一个小片至少一个字，约 100 ms 量级）。
     *
     * <p>只在<b>接缝</b>上做：整段的第 1 帧与最后一帧保持原样，否则就等于把开头和结尾削掉了。
     */
    public static final int DEFAULT_JOINT_FADE_MILLIS = 15;

    /** RIFF 头：'RIFF' + 文件长度(4) + 'WAVE' */
    private static final int RIFF_HEADER_BYTES = 12;

    /**
     * 'RIFF' + size 字段 —— 即 {@link #RIFF_HEADER_BYTES} 去掉 'WAVE' 的部分。
     *
     * <p>算输出数组长度时用它、而不是 {@code RIFF_HEADER_BYTES}：{@code riffSize} 里
     * 已经含 'WAVE' 那 4 字节，用 12 会把 'WAVE' 计两次 ⇒ 文件尾多出 4 个零字节。
     */
    private static final int RIFF_PREFIX_BYTES = RIFF_HEADER_BYTES - 4;

    /** 单个 chunk 头：id(4) + size(4) */
    private static final int CHUNK_HEADER_BYTES = 8;

    private static final String ID_RIFF = "RIFF";
    private static final String ID_WAVE = "WAVE";
    private static final String ID_FMT = "fmt ";
    private static final String ID_DATA = "data";

    private WavConcat() {
    }

    /**
     * 按顺序把若干段 WAV 拼成一段，拼接点按 {@link #DEFAULT_JOINT_FADE_MILLIS} 做淡出/淡入。
     *
     * @param wavs 依次排列的 WAV 字节；至少一个，格式必须完全一致
     * @return 拼接后的 WAV；只有一段时原样返回
     * @throws IllegalArgumentException 输入为空、不是合法 RIFF/WAVE、缺少 fmt/data，
     *                                  或各段 fmt 不一致（详见类注释：刻意不静默降级）
     */
    public static byte[] concat(List<byte[]> wavs) {
        return concat(wavs, DEFAULT_JOINT_FADE_MILLIS);
    }

    /**
     * 与 {@link #concat(List)} 相同，但淡出时长可控。
     *
     * @param jointFadeMillis 每个接缝单侧的淡出时长（毫秒）；{@code <= 0} 表示不做淡出，
     *                        此时输出与逐字节首尾相接完全一致（保留该开关是为了让
     *                        「淡出是否引入了新的可听问题」可以用同一份音频 A/B 对比）
     */
    public static byte[] concat(List<byte[]> wavs, int jointFadeMillis) {
        if (wavs == null || wavs.isEmpty()) {
            throw new IllegalArgumentException("没有可拼接的音频段");
        }
        if (wavs.size() == 1) {
            // 没有接缝就没有可淡化的边界；原样返回，不做无意义的字节改写
            return wavs.get(0);
        }

        byte[] fmt = null;
        List<byte[]> datas = new ArrayList<>(wavs.size());
        long totalData = 0;
        for (int i = 0; i < wavs.size(); i++) {
            Chunks c = parse(wavs.get(i), i);
            if (fmt == null) {
                fmt = c.fmt;
            } else if (!Arrays.equals(fmt, c.fmt)) {
                // 把两边的 fmt 原样打出来：采样率/声道/位深的具体差异直接可见，
                // 比「格式不一致」这种笼统提示省一次线上排查
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 段音频格式与前一段不一致，无法拼接。前段 fmt="
                                + hex(fmt) + "，本段 fmt=" + hex(c.fmt)
                                + "（这通常意味着 TTS 引擎在不同请求间返回了不同采样率/声道/位深）");
            }
            datas.add(c.data);
            totalData += c.data.length;
            if (totalData > Integer.MAX_VALUE - 1024) {
                throw new IllegalArgumentException("拼接后音频过大，超出单个字节数组上限");
            }
        }

        if (jointFadeMillis > 0) {
            applyJointFades(datas, fmt, jointFadeMillis);
        }
        return assemble(fmt, datas, (int) totalData);
    }

    /**
     * 在每个拼接点上做「前片尾淡出 + 后片头淡入」。
     *
     * <p><b>改的是 {@link #parse} 用 {@code Arrays.copyOfRange} 造出的副本</b>，
     * 不是调用方传进来的原片字节 —— 这一点是硬要求：{@code VoiceCloningTtsService}
     * 会把已成功的小片在段级重试之间缓存复用（{@code donePieces}），若就地改原片，
     * 每重试一次就多衰减一次，最终把音频啃成静音。
     *
     * <p>淡出区与外部波形无缝：淡出起点、淡入终点的增益都<b>恰好是 1.0</b>，
     * 所以边界只出现在接缝正中（两侧增益同为 {@code 1/fadeFrames}），而不是淡出区的两端。
     *
     * <p>只支持 16 bit PCM：其余位深（8/24 bit、32 bit float）需要按各自的表示缩放，
     * 属于「没被测过就不要上线」的路径，故跳过并留下一条可定位的 warn。
     * 跳过的代价只是这一处没有淡出保险，音频本身仍被正确拼接。
     */
    private static void applyJointFades(List<byte[]> datas, byte[] fmt, int jointFadeMillis) {
        PcmFormat pcm = PcmFormat.parse(fmt);
        if (pcm == null) {
            log.warn("拼接点淡出已跳过：仅支持 16 bit PCM，fmt={}（音频仍正常拼接，只是不做淡出）",
                    hex(fmt));
            return;
        }
        int requestedFrames = (int) Math.round(jointFadeMillis * pcm.sampleRate() / 1000.0);
        if (requestedFrames <= 0) {
            return;
        }

        int n = datas.size();
        int edges = 0;
        for (int i = 0; i < n; i++) {
            byte[] data = datas.get(i);
            int frames = data.length / pcm.blockAlign();
            // 上限取半：中间片既要做头淡入又做尾淡出，两者重叠会把整片压成静音
            int fadeFrames = Math.min(requestedFrames, frames / 2);
            if (fadeFrames <= 0) {
                continue;
            }
            if (i > 0) {
                fadeIn(data, pcm.blockAlign(), fadeFrames);
                edges++;
            }
            if (i < n - 1) {
                fadeOut(data, pcm.blockAlign(), fadeFrames);
                edges++;
            }
        }
        log.debug("拼接点淡出完成：{} 个接缝边 × {} 帧（{} ms @ {} Hz / {} 声道 / {} bit）",
                edges, requestedFrames, jointFadeMillis, pcm.sampleRate(), pcm.channels(),
                pcm.bitsPerSample());
    }

    /**
     * 头部淡入：淡入区第 {@code m} 帧增益 {@code (m+1)/fadeFrames}。
     * 末帧增益恰为 1.0，与淡入区之后的原始波形无缝衔接。
     */
    private static void fadeIn(byte[] data, int blockAlign, int fadeFrames) {
        for (int m = 0; m < fadeFrames; m++) {
            scaleFrame(data, m * blockAlign, blockAlign, (double) (m + 1) / fadeFrames);
        }
    }

    /**
     * 尾部淡出：淡出区第 {@code m} 帧增益 {@code (fadeFrames-m)/fadeFrames}。
     * 首帧增益恰为 1.0，与淡出区之前的原始波形无缝衔接。
     */
    private static void fadeOut(byte[] data, int blockAlign, int fadeFrames) {
        int start = (data.length / blockAlign - fadeFrames) * blockAlign;
        for (int m = 0; m < fadeFrames; m++) {
            scaleFrame(data, start + m * blockAlign, blockAlign,
                    (double) (fadeFrames - m) / fadeFrames);
        }
    }

    /**
     * 按增益缩放一个采样帧（块）里的每个 16 bit 声道样本。
     *
     * <p>增益 {@code <= 1.0}，所以 {@code Math.round(v * gain)} 不会溢出 16 bit：
     * {@code v} 的极值 ±32768 乘 1.0 仍在 short 范围内。
     */
    private static void scaleFrame(byte[] data, int offset, int blockAlign, double gain) {
        for (int b = 0; b < blockAlign; b += 2) {
            int v = (short) ((data[offset + b] & 0xFF) | (data[offset + b + 1] << 8));
            int scaled = (int) Math.round(v * gain);
            data[offset + b] = (byte) (scaled & 0xFF);
            data[offset + b + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
    }

    /**
     * 组装输出 WAV。
     *
     * <p>数据长度为奇数时补一个 0 字节：RIFF 规定 chunk 长度为奇数时需填充到偶数，
     * 不补的话部分解析器会把填充字节算进 data，尾部多出一个噪声采样。
     *
     * <p>包内可见：{@link WavEnvelope} 复用它把「补齐 + 裁剪 + 淡化」后的 PCM 重新装回 WAV，
     * 避免两处各写一份 RIFF 组装逻辑（历史上就出过「'WAVE' 被算两次导致尾部多 4 字节」的坑，
     * 见 {@link #RIFF_PREFIX_BYTES} 注释）。
     */
    static byte[] assemble(byte[] fmt, List<byte[]> datas, int totalData) {
        int fmtPad = fmt.length % 2;
        int dataPad = totalData % 2;
        // RIFF 的 size 字段 = 'WAVE' 之后到文件末尾的字节数，故这里的 4 是 'WAVE' 本身，
        // 后面才是两个 chunk（含各自的 8 字节 chunk 头与奇偶补位）。
        int riffSize = 4                                   // 'WAVE'
                + CHUNK_HEADER_BYTES + fmt.length + fmtPad
                + CHUNK_HEADER_BYTES + totalData + dataPad;

        // 文件总长 = 8（'RIFF' + size 字段）+ riffSize。别写成 RIFF_HEADER_BYTES ——
        // 那样 'WAVE' 会被算两次，尾部凭空多出 4 个零字节（见 RIFF_PREFIX_BYTES 的注释）。
        byte[] out = new byte[RIFF_PREFIX_BYTES + riffSize];
        int p = 0;
        p = writeAscii(out, p, ID_RIFF);
        p = writeIntLe(out, p, riffSize);
        p = writeAscii(out, p, ID_WAVE);

        p = writeAscii(out, p, ID_FMT);
        p = writeIntLe(out, p, fmt.length);
        System.arraycopy(fmt, 0, out, p, fmt.length);
        p += fmt.length + fmtPad;

        p = writeAscii(out, p, ID_DATA);
        p = writeIntLe(out, p, totalData);
        for (byte[] d : datas) {
            System.arraycopy(d, 0, out, p, d.length);
            p += d.length;
        }
        return out;
    }

    /** 静音占位的采样率（与 MP3 出口一致：{@code convertWavToMp3} 输出 22050 Hz） */
    private static final int SILENT_SAMPLE_RATE = 22050;

    /** 静音占位的时长（毫秒）—— 够播放器拿到一个合法非空文件，又不至于让人等 */
    private static final int SILENT_MILLIS = 300;

    /**
     * 生成一段静音 WAV（16 bit PCM / 单声道），用作「整段都没有可朗读内容」的占位。
     *
     * <p><b>为什么要有它</b>：段文本若整段都是排版装饰线（下划线、点线）或空白，
     * 过滤掉无朗读内容的小片后就没有任何东西可送引擎了（见
     * {@link TextSegmenter#hasSpeakableContent(String)}）。而「每段一个 MP3」是上层
     * （DB 一行、客户端一段、播放器一次）依赖的契约 —— 缺文件会让下载/播放链路出现空洞，
     * 所以这里不抛异常、也不留空路径，而是给一段静音：用户读到那里等于无感跳过。
     *
     * <p>走 {@link #assemble} 组装是为了与 {@link #concat} 的输出格式逐字节同构
     * （同样的 chunk 顺序与奇数长度补齐），这样下游 {@code convertWavToMp3} 只有一条出口。
     */
    public static byte[] silentWav() {
        int frames = SILENT_SAMPLE_RATE * SILENT_MILLIS / 1000;

        byte[] fmt = new byte[16];
        writeShortLe(fmt, 0, (short) 1);                    // PCM
        writeShortLe(fmt, 2, (short) 1);                    // 单声道
        writeIntLe(fmt, 4, SILENT_SAMPLE_RATE);             // 采样率
        writeIntLe(fmt, 8, SILENT_SAMPLE_RATE * 2);         // 字节率 = 采样率 × 块对齐
        writeShortLe(fmt, 12, (short) 2);                   // 块对齐 = 声道数 × 位深/8
        writeShortLe(fmt, 14, (short) 16);                  // 位深

        byte[] data = new byte[frames * 2];                 // 全 0 ⇒ 静音
        return assemble(fmt, List.of(data), data.length);
    }

    /** 小端写入 16 位，返回下一个写入位置 */
    private static int writeShortLe(byte[] out, int p, short v) {
        out[p] = (byte) (v & 0xFF);
        out[p + 1] = (byte) ((v >>> 8) & 0xFF);
        return p + 2;
    }

    /**
     * 一个 WAV 里我们关心的两个 chunk。
     *
     * <p>包内可见：{@link WavEnvelope} 复用 {@link #parse(byte[], int)} 的解析结果，
     * 需要读到这里面的 {@code fmt}/{@code data}。外部包无访问必要 —— 保持包内可见
     * 就够了，别一路放到 public 让它变成隐式 API。
     */
    static final class Chunks {
        final byte[] fmt;
        final byte[] data;

        Chunks(byte[] fmt, byte[] data) {
            this.fmt = fmt;
            this.data = data;
        }
    }

    /**
     * 解析一段 WAV，取出 {@code fmt } 与 {@code data} 的负载。
     *
     * <p>必须真正走 chunk 链表，不能假设「header 44 字节 + 数据」：44 字节只是
     * 最简 WAV 的形状，引擎只要多写一个 LIST/fact chunk，固定偏移就会把 chunk 头当成 PCM。
     *
     * <p><b>对流式 WAV 的容错</b>：部分引擎（实测 Pocket-TTS）边生成边写 {@code data} chunk，
     * 收尾时不回填 {@code data} 的 size 字段，头部因此留着占位哨兵值（如 2000000000 / 0xFFFFFFFF），
     * 声明长度远大于真实负载。这类「data 声明越界」按剩余字节截断取 PCM（data 本就是负载终点，
     * 其后不会再有别的 chunk），并 warn 一条日志。其它任何 chunk 的越界 / 负长度仍如实抛，
     * 以免把「引擎返回了损坏音频」这种真实故障静默吞掉。
     *
     * @param wav   待解析字节
     * @param index 段序号（仅用于错误信息）
     */
    static Chunks parse(byte[] wav, int index) {
        if (wav == null || wav.length < RIFF_HEADER_BYTES + CHUNK_HEADER_BYTES) {
            throw new IllegalArgumentException("第 " + (index + 1) + " 段音频过短，不是合法 WAV");
        }
        if (!ID_RIFF.equals(ascii(wav, 0)) || !ID_WAVE.equals(ascii(wav, 8))) {
            throw new IllegalArgumentException(
                    "第 " + (index + 1) + " 段音频不是 RIFF/WAVE 格式（头部为 "
                            + ascii(wav, 0) + "/" + ascii(wav, 8) + "）");
        }

        byte[] fmt = null;
        byte[] data = null;
        int p = RIFF_HEADER_BYTES;
        while (p + CHUNK_HEADER_BYTES <= wav.length) {
            String id = ascii(wav, p);
            long size = readIntLe(wav, p + 4);
            int body = p + CHUNK_HEADER_BYTES;
            if (size < 0) {
                throw new IllegalArgumentException(
                        "第 " + (index + 1) + " 段音频的 " + id + " chunk 长度为负（声明 " + size + "）");
            }
            if (body + size > wav.length) {
                // 声明长度越界。绝大多数越界都是真实的损坏，必须抛（保持本类
                // 「格式不一致直接抛、不尽力而为」的契约）。唯一例外是流式 WAV：
                // Pocket-TTS 这类引擎边生成边写 data chunk，来不及回填 data 的 size 字段，
                // 于是头部留着一个占位哨兵值（实测 2000000000，也见过 0xFFFFFFFF），
                // 而真正的 PCM 就是 data body 到文件末尾这一段。data 在定义上就是负载的
                // 终点，其后不会再有别的 chunk，所以这里按剩余字节 clamp 是安全的。
                if (ID_DATA.equals(id)) {
                    int avail = wav.length - body;
                    if (avail <= 0) {
                        throw new IllegalArgumentException(
                                "第 " + (index + 1) + " 段音频的 data chunk 声明 " + size
                                        + " 字节，实际只剩 " + avail + "（无可读负载）");
                    }
                    log.warn("第 {} 段音频的 data chunk 声明 {} 字节、越出缓冲（流式 WAV 未回填头部）"
                                    + "⇒ 按剩余 {} 字节截断取 PCM",
                            index + 1, size, avail);
                    data = Arrays.copyOfRange(wav, body, body + avail);
                    break;
                }
                throw new IllegalArgumentException(
                        "第 " + (index + 1) + " 段音频的 " + id + " chunk 长度越界（声明 "
                                + size + " 字节，实际只剩 " + (wav.length - body) + "）");
            }
            if (ID_FMT.equals(id)) {
                fmt = Arrays.copyOfRange(wav, body, body + (int) size);
            } else if (ID_DATA.equals(id)) {
                data = Arrays.copyOfRange(wav, body, body + (int) size);
            }
            // chunk 长度为奇数时后面有一个填充字节
            p = body + (int) size + ((int) size % 2);
        }

        if (fmt == null || data == null || data.length == 0) {
            throw new IllegalArgumentException(
                    "第 " + (index + 1) + " 段音频缺少 fmt/data chunk 或 data 为空");
        }
        return new Chunks(fmt, data);
    }

    private static String ascii(byte[] b, int off) {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4 && off + i < b.length; i++) {
            sb.append((char) (b[off + i] & 0xFF));
        }
        return sb.toString();
    }

    /** RIFF 全部是小端序 */
    private static long readIntLe(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    private static int readShortLe(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /**
     * 做淡化/裁剪所需的 16 bit PCM 参数，从 {@code fmt } 负载解析。
     *
     * <p>额外校验 {@code blockAlign == channels × 2}：块长与声道数不符说明这份 fmt 自己就
     * 前后矛盾（或用了 WAVE_FORMAT_EXTENSIBLE），此时按「声道数」去缩放会切错样本边界，
     * 宁可整体跳过。
     *
     * <p>包内可见：{@link WavEnvelope} 也走这条判定，两个类共用同一份「什么算 16 bit PCM」的口径。
     */
    record PcmFormat(int channels, int sampleRate, int blockAlign, int bitsPerSample) {

        /** 非 16 bit PCM 返回 {@code null}（调用方据此跳过淡出并留下日志） */
        static PcmFormat parse(byte[] fmt) {
            if (fmt == null || fmt.length < 16) {
                return null;
            }
            int audioFormat = readShortLe(fmt, 0);
            int channels = readShortLe(fmt, 2);
            int sampleRate = (int) readIntLe(fmt, 4);
            int blockAlign = readShortLe(fmt, 12);
            int bits = readShortLe(fmt, 14);
            if (audioFormat != 1 || bits != 16 || channels <= 0
                    || blockAlign != channels * 2 || sampleRate <= 0) {
                return null;
            }
            return new PcmFormat(channels, sampleRate, blockAlign, bits);
        }
    }

    private static int writeAscii(byte[] out, int p, String s) {
        for (int i = 0; i < s.length(); i++) {
            out[p + i] = (byte) s.charAt(i);
        }
        return p + s.length();
    }

    private static int writeIntLe(byte[] out, int p, int v) {
        out[p] = (byte) (v & 0xFF);
        out[p + 1] = (byte) ((v >> 8) & 0xFF);
        out[p + 2] = (byte) ((v >> 16) & 0xFF);
        out[p + 3] = (byte) ((v >> 24) & 0xFF);
        return p + 4;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
