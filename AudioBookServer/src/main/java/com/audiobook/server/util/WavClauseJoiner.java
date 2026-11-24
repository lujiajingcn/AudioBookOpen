package com.audiobook.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「一句话内部各子句」的接缝整形：把 k 个<b>各自独立合成</b>出来的小片拼成听感上的一句话。
 *
 * <p><b>为什么需要它（用户抱怨「一个句子里各个子句的语调、节奏不一致」）</b>：
 * 片段 = 完整句子（见 {@link TextSegmenter#sentences(String)}），但引擎单次只吃得下
 * {@link TextSegmenter#DEFAULT_MAX_LENGTH} 个字，于是长句要拆成若干小片
 * （{@link TextSegmenter#subPieces(String)}）分别合成、再拼回一条音频。
 * <b>每一片都是一次独立的冷启动生成</b>，于是同一句话内部出现三类可听的不一致：
 * <ol>
 *   <li><b>节奏（停顿）不一致</b>：引擎给每一片的头尾静音长度是随机的
 *       （抽样：10 段里 2 段尾静音为 0，另一些则有几百毫秒）。改造前只对<b>拼接后</b>的整段
 *       做一次 {@link WavEnvelope} 整形，<b>每片自带的头尾静音原样叠加在接缝上</b> ——
 *       于是「，」后的停顿忽长忽短，同一句话里像换了个人在念。
 *       实测（2026-10-12 对照实验）：拆片段停顿数中位 23 / 未拆片段 7。</li>
 *   <li><b>音量不一致</b>：节点不做响度归一化（见 {@code DETAILS.md}），
 *       同一句话的两片可以相差好几个 dB，听感上是「声音突然变大/变小」。</li>
 *   <li><b>接缝处的硬切</b>：两片直接首尾相贴、只靠 {@link WavConcat} 的 15 ms 淡化保险，
 *       没有任何「此处是逗号、该停多久」的语义。</li>
 * </ol>
 *
 * <p><b>本类做的事</b>（三项都是纯服务端后处理，引擎不参与、不需要重合成旧任务）：
 * <ol>
 *   <li>{@link #normalize(byte[])} —— 每片进拼接前把<b>头尾静音归一化</b>成固定长度
 *       （超出则裁、不足则补），让接缝停顿只由标点决定；</li>
 *   <li>{@link #join} —— 按<b>片尾标点</b>插入等长的接缝停顿（「、」短、「，」中、「；：」长、
 *       硬切无停顿），再对每片做<b>响度对齐</b>（把每片的有效发声 RMS 拉到一个共同目标，
 *       增益夹在 ±{@code maxGainDb} 内、并按峰值上限收口）；</li>
 *   <li>接缝淡化沿用 {@link WavConcat#concat(List, int)} —— 不另写一份。</li>
 * </ol>
 *
 * <p><b>与另外两个工具类的分工</b>（三者都在同一层，但作用面不同，别混用）：
 * <ul>
 *   <li>{@link WavClauseJoiner}（本类）：<b>子句接缝</b> —— 一句话内部的节奏/音量一致性；</li>
 *   <li>{@link WavEnvelope}：<b>整段端点</b> —— 段首尾的静音/淡化/补齐，管「段与段之间」；</li>
 *   <li>{@link WavConcat}：<b>裸拼接 + 接缝淡化</b> —— 本类与 {@link WavEnvelope} 都复用它。</li>
 * </ul>
 *
 * <p>原先还有第三套「跨段/句内滚动 prompt」（用上一段的尾巴做下一段的 {@code prompt_audio}），
 * 已于 2026-10-30 连同 {@code ProsodyContinuation} / {@code SubclauseProsody} / {@code WavTail}
 * 一并删除 —— 实测引擎不经 {@code prompt_audio} 传韵律，只传音色，滚动只会缩短音色信息。
 *
 * <p><b>失败即降级</b>：非法 WAV / 非 16 bit PCM / 各片 fmt 不一致时，
 * {@link #normalize(byte[])} 返回原字节、{@link #join} 退化为
 * {@link WavConcat#concat(List, int)}（即改造前的行为），不抛异常。
 * 策略与 {@link WavEnvelope} 一致 —— 接缝整形是锦上添花，不能把主链路搞挂。
 */
public final class WavClauseJoiner {

    private static final Logger log = LoggerFactory.getLogger(WavClauseJoiner.class);

    /** 16 bit PCM 满幅；峰值收口留 1% 余量，避免增益后削顶 */
    private static final int PEAK_LIMIT = 32_400;

    /** 增益下限/上限的安全夹取（dB），与 {@link Options#maxGainDb()} 取更小者 */
    private static final double ABSOLUTE_MAX_GAIN_DB = 12.0;

    private WavClauseJoiner() {
    }

    /**
     * 接缝整形参数。所有时长单位为毫秒；任一维度 {@code <= 0} 即关闭该项。
     *
     * @param enabled                   总开关。{@code false} 时 {@link #normalize} 原样返回、
     *                                  {@link #join} 直接走 {@link WavConcat#concat(List, int)} ——
     *                                  输出与改造前逐字节一致（A/B 对比用）。
     * @param trimThreshold             静音判定阈值（采样绝对值）。与
     *                                  {@link WavEnvelope.Options#trimThreshold()} 同口径：
     *                                  300 ≈ -40 dBFS。{@code <= 0} 关闭裁剪。
     * @param headKeepMillis            归一化后片头固定的静音长度。片段开头多是接续上一片的语气词，
     *                                  25 ms 免得首字被「切掉起音」；不足则补、超出则裁。
     * @param tailKeepMillis            归一化后片尾固定的静音长度。尾字收音需要余量，60 ms 接近一次
     *                                  极短停顿；真正的句读长度由下面几个 pause 参数统一决定。
     *                                  两者必须固定（而不是「最多保留多少」），否则接缝停顿会带上随机量。
     * @param commaPauseMillis          「，」后的接缝停顿。中文朗读里逗号停顿约 150~250 ms；
     *                                  叠加头尾保留（60+25 ms）后总间隔约 215 ms，落在自然区间内。
     * @param enumerationPauseMillis    「、」后的接缝停顿。顿号是并列项之间最短的停顿，80 ms 足够
     *                                  分辨、又不至于把「甲、乙」念成两句。
     * @param strongPauseMillis         「；」「：」后的接缝停顿。分号/冒号是句子内部最强的停顿，
     *                                  190 ms 让「分句」听起来是分句、而不是句号。
     * @param hardCutPauseMillis        硬切（片尾不是任何停顿标点）时的接缝停顿。
     *                                  这时切点落在词中间，<b>加停顿只会把词劈得更明显</b>，
     *                                  故取 40 ms（几乎是纯接缝淡化，不额外加静音）。
     * @param loudnessMatch             是否做响度对齐。关掉即保留各片原始音量（A/B 用）。
     * @param maxGainDb                 单片的响度增益上限（±dB）。语音响度差超过 6 dB 时，
     *                                  问题已经不只是「音量」而是录音/引擎异常，
     *                                  硬拉会引入可闻的失真与齿音，故夹住。
     * @param jointFadeMillis           接缝单侧淡化时长（沿用 {@code app.tts.joint-fade-millis}）。
     *                                  接缝两侧都落在静音里时它是 no-op；只有当某片没有尾静音时
     *                                  才真正起作用 —— 这正是它该起作用的场合。
     * @param internalPauseMinMillis    「算得上停顿」的最短静音长度（毫秒）。低于它的静音是发音
     *                                  缝隙（塞音闭塞、换气），不能动 —— 动了会把字咬碎。
     * @param internalPauseCapMillis    片内停顿的长度上限（毫秒）。<b>这是听感上最关键的一项</b>：
     *                                  实测该引擎在逗号处会插 0.7~2.0 s 的静音（`agent_output/prosody_ab`
     *                                  的真机数据），而每片最多 45 字，这些"巨停顿"全落在片<b>内部</b>，
     *                                  接缝整形根本管不到。封顶后句内停顿才有统一的上限。
     *                                  {@code <= minMillis} 表示关闭该处理。
     */
    public record Options(boolean enabled,
                          int trimThreshold,
                          int headKeepMillis,
                          int tailKeepMillis,
                          int commaPauseMillis,
                          int enumerationPauseMillis,
                          int strongPauseMillis,
                          int hardCutPauseMillis,
                          boolean loudnessMatch,
                          double maxGainDb,
                          int jointFadeMillis,
                          int internalPauseMinMillis,
                          int internalPauseCapMillis) {

        /** 全关：{@link #normalize} 透传，{@link #join} 等价于 {@link WavConcat#concat(List, int)}。 */
        public static Options disabled() {
            return new Options(false, 0, 0, 0, 0, 0, 0, 0, false, 0,
                    WavConcat.DEFAULT_JOINT_FADE_MILLIS, 0, 0);
        }
    }

    /**
     * 把一片音频的头尾静音<b>归一化</b>成固定长度，返回新的合法 WAV（格式不变）。
     *
     * <p><b>为什么不是单纯的「裁剪」</b>：只裁不补的话，接缝停顿 = 「本片剩下的尾静音」
     * + 「标点停顿」+ 「下一片剩下的头静音」，而「本片剩下的尾静音」最多等于 tail-keep、
     * 但引擎若本来只给了 20 ms（抽样里很常见，甚至有 0），裁完就只有 20 ms ——
     * 于是同一句话里的停顿变成「标点停顿 + 一个 0~85 ms 的随机量」，节奏还是不齐。
     * 本方法因此做的是<b>归一化</b>：不足则补齐、超出则裁掉，让每片的头尾恰好是
     * head-keep / tail-keep 长度的静音。这样接缝停顿就<b>只由片尾标点决定</b>，
     * 完全可复现 —— 这是「节奏一致」的前提。
     *
     * <p><b>只归一化头尾，不补齐段端点</b>：给整段补 lead/tail 是 {@link WavEnvelope} 的事；
     * 若这里也补，接缝停顿会变成「随机值 + 固定值」，节奏依旧不齐。
     *
     * <p><b>补静音是安全的</b>：多出的 25 ms/60 ms 静音落在波形两端，不会切到任何音素；
     * 与 {@link WavEnvelope} 的 {@code lead}/{@code tail} 补齐是同一个思路。
     *
     * <p>失败即原样返回（解析失败 / 非 16 bit PCM / 整片静音）。整片静音不处理是硬要求：
     * 裁成 0 长度会破坏下游 MP3 编码的「每段非空」契约。
     *
     * @param wav  引擎返回的一片 WAV
     * @param opts 参数；{@code null} 或 {@code enabled=false} 时透传
     * @return 归一化后的 WAV，或原字节（失败/整片静音）
     */
    public static byte[] normalize(byte[] wav, Options opts) {
        if (wav == null || wav.length == 0 || opts == null || !opts.enabled()) {
            return wav;
        }
        WavConcat.Chunks chunks;
        WavConcat.PcmFormat pcm;
        try {
            chunks = WavConcat.parse(wav, 0);
            pcm = WavConcat.PcmFormat.parse(chunks.fmt);
        } catch (IllegalArgumentException e) {
            log.debug("子句静音归一化跳过：WAV 解析失败（{}）", e.getMessage());
            return wav;
        }
        if (pcm == null) {
            log.debug("子句静音归一化跳过：仅支持 16 bit PCM");
            return wav;
        }

        byte[] normalized = normalizeSilence(chunks.data, pcm, opts.trimThreshold(),
                opts.headKeepMillis(), opts.tailKeepMillis());
        byte[] capped = capInternalPauses(normalized, pcm, opts.trimThreshold(),
                opts.internalPauseMinMillis(), opts.internalPauseCapMillis());
        if (normalized == chunks.data && capped == normalized) {
            return wav;
        }
        return WavConcat.assemble(chunks.fmt, List.of(capped), capped.length);
    }

    /**
     * 把已经在同一个句子里、按顺序排好的子句小片拼成一条 WAV。
     *
     * <p><b>拼接顺序 = 文本顺序</b>：{@code pieces} 与 {@code texts} 必须一一对应、
     * 长度相同（{@code texts.get(i)} 是第 i 片要念的文字，仅用于取片尾标点）。
     *
     * <p><b>响度对齐的口径</b>：不用整片 RMS（会被长短不一的尾静音稀释），
     * 而是只用「有效发声帧」（{@code |v| > trimThreshold} 的帧）算 RMS，
     * 再以各片的中位数为目标。这样「一片末尾多留了 300 ms 静音」不会让它的增益被压低。
     *
     * @param pieces 已按 {@link #normalize(byte[], Options)} 归一化过头尾静音的小片 WAV，≥1 个
     * @param texts  与 {@code pieces} 一一对应的文本，仅用于判定片尾标点；可为 null（全按硬切处理）
     * @param opts   参数；{@code null}/未启用/只有一片时退化为 {@link WavConcat#concat(List, int)}
     * @return 拼接后的 WAV
     */
    public static byte[] join(List<byte[]> pieces, List<String> texts, Options opts) {
        if (pieces == null || pieces.isEmpty()) {
            throw new IllegalArgumentException("没有可拼接的子句音频");
        }
        if (opts == null || !opts.enabled() || pieces.size() == 1) {
            return WavConcat.concat(pieces, opts == null ? 0 : opts.jointFadeMillis());
        }

        // 1) 解析出 fmt/data，并校验各片格式一致（不一致就让 WavConcat.concat 抛出同一条异常，
        //    保持「错误信息与改造前一致」，不在这里另造一套措辞）
        List<WavConcat.Chunks> parsed = new ArrayList<>(pieces.size());
        byte[] fmt = null;
        for (int i = 0; i < pieces.size(); i++) {
            WavConcat.Chunks c = WavConcat.parse(pieces.get(i), i);
            if (fmt == null) {
                fmt = c.fmt;
            } else if (!Arrays.equals(fmt, c.fmt)) {
                log.warn("子句接缝整形跳过：第 {} 片格式与前片不一致，退化为裸拼接", i + 1);
                return WavConcat.concat(pieces, opts.jointFadeMillis());
            }
            parsed.add(c);
        }
        WavConcat.PcmFormat pcm = WavConcat.PcmFormat.parse(fmt);
        if (pcm == null) {
            log.warn("子句接缝整形跳过：仅支持 16 bit PCM（音频仍正常拼接，只是不做静音归一化/响度对齐/停顿插入）");
            return WavConcat.concat(pieces, opts.jointFadeMillis());
        }

        // 2) 响度对齐：目标 = 各片有效发声 RMS 的中位数
        List<byte[]> datas = new ArrayList<>(parsed.size());
        for (WavConcat.Chunks c : parsed) {
            // parse 已经返回副本，但这里再复制一份是刻意的：调用方会把原始小片缓存进
            // donePieces 供段级重试复用，就地改增益会让「重试一次、再衰减一次」复现
            // （与 WavConcat.applyJointFades 注释里那条坑同源）。
            datas.add(Arrays.copyOf(c.data, c.data.length));
        }
        if (opts.loudnessMatch()) {
            applyLoudnessMatch(datas, pcm, opts);
        }

        // 3) 按片尾标点插入等长停顿，交给 WavConcat 做接缝淡化与 RIFF 组装。
        //    停顿也做成 WAV 塞进同一个列表里，是为了复用 WavConcat.concat 的淡化实现
        //    —— 接缝两侧都落在静音里时淡化是 no-op，某片尾静音为 0 时才真正生效。
        List<byte[]> withPauses = new ArrayList<>(datas.size() * 2 - 1);
        for (int i = 0; i < datas.size(); i++) {
            if (i > 0) {
                int pause = pauseMillisFor(textOf(texts, i - 1), opts);
                if (pause > 0) {
                    withPauses.add(silenceWav(fmt, pcm, pause));
                }
            }
            withPauses.add(WavConcat.assemble(fmt, List.of(datas.get(i)), datas.get(i).length));
        }
        byte[] joined = WavConcat.concat(withPauses, opts.jointFadeMillis());

        if (log.isDebugEnabled()) {
            int joinedFrames = WavConcat.parse(joined, 0).data.length / Math.max(1, pcm.blockAlign());
            log.debug("子句接缝整形完成：{} 片 → {} 帧（{} Hz / {} 声道；片间停顿 {} ms；响度对齐={}）",
                    datas.size(), joinedFrames, pcm.sampleRate(), pcm.channels(),
                    describePauses(texts, opts), opts.loudnessMatch());
        }
        return joined;
    }

    /**
     * 片尾标点 → 接缝停顿时长（毫秒）。
     *
     * <p>只认 {@link TextSegmenter#subPieces(String)} 真正会落刀的那几个次级停顿标点
     * （{@code ，、；：,;}）：别的标点出现在片尾，说明这一刀是「上限硬切」
     * （见 {@code TextSegmenter.chooseBreakPoint}），切点落在词中间，加停顿只会把词劈得更开。
     *
     * <p>包内可见是为了让单测直接打表验证映射，不必构造音频。
     */
    static int pauseMillisFor(String precedingText, Options opts) {
        if (precedingText == null || precedingText.isBlank()) {
            return opts.hardCutPauseMillis();
        }
        String s = precedingText.strip();
        if (s.isEmpty()) {
            return opts.hardCutPauseMillis();
        }
        char last = s.charAt(s.length() - 1);
        return switch (last) {
            case '，', ',' -> opts.commaPauseMillis();
            case '、' -> opts.enumerationPauseMillis();
            case '；', ';', '：', ':' -> opts.strongPauseMillis();
            // 句末标点出现在片尾，只可能是「句子本身就以「」』”这类收尾符号结尾」而被
            // subPieces 的上限切开了 —— 语义上它仍是句内停顿，按强停顿处理最稳。
            case '。', '！', '？', '!', '?' -> opts.strongPauseMillis();
            default -> opts.hardCutPauseMillis();
        };
    }

    // ------------------------------------------------------------------ 响度对齐

    /**
     * 把每片的有效发声 RMS 拉到同一目标（各片的中位数），增益夹在 ±{@code maxGainDb} 内，
     * 并按 {@link #PEAK_LIMIT} 收口防削顶。
     *
     * <p><b>为什么要夹增益</b>：两片相差 20 dB 时，多半不是「引擎音量抖动」而是某一片
     * 本身出了问题（截断、混入噪声、静音）。硬拉到一致会把那次异常放大成刺耳的失真，
     * 不如保持原样让人听得出异常。
     */
    private static void applyLoudnessMatch(List<byte[]> datas, WavConcat.PcmFormat pcm, Options opts) {
        double[] rms = new double[datas.size()];
        for (int i = 0; i < datas.size(); i++) {
            rms[i] = activeRms(datas.get(i), pcm, opts.trimThreshold());
        }
        List<Double> valid = new ArrayList<>(rms.length);
        for (double v : rms) {
            if (v > 0) {
                valid.add(v);
            }
        }
        if (valid.size() < 2) {
            // 只有一片有有效发声（其余整片静音/极轻）⇒ 没有「对齐」的语义，
            // 强行拉会把那一片之外的静音片拉到目标音量（放大的只有底噪）
            return;
        }
        double target = median(valid);
        double maxGainDb = Math.min(Math.max(opts.maxGainDb(), 0), ABSOLUTE_MAX_GAIN_DB);
        for (int i = 0; i < datas.size(); i++) {
            if (rms[i] <= 0) {
                continue;
            }
            double gainDb = 20 * Math.log10(target / rms[i]);
            double gain = Math.pow(10, Math.max(-maxGainDb, Math.min(maxGainDb, gainDb)) / 20.0);
            int peak = peakAbs(datas.get(i));
            if (peak > 0 && peak * gain > PEAK_LIMIT) {
                gain = (double) PEAK_LIMIT / peak;
            }
            if (Math.abs(gain - 1.0) < 0.01) {
                continue;
            }
            scaleAll(datas.get(i), gain);
            log.debug("子句响度对齐：第 {} 片 RMS={} → 目标 {}，增益 {} dB（峰值 {}）",
                    i + 1, Math.round(rms[i]), Math.round(target),
                    String.format("%.2f", 20 * Math.log10(gain)), peak);
        }
    }

    /**
     * 一片音频的「有效发声」RMS：只统计至少有一个声道样本 {@code |v| > threshold} 的帧。
     *
     * <p>不用整片 RMS 的原因见 {@link #join} 的注释：尾静音长度随机，会稀释分母。
     */
    private static double activeRms(byte[] data, WavConcat.PcmFormat pcm, int threshold) {
        int frameBytes = pcm.blockAlign();
        int frames = data.length / frameBytes;
        double sum = 0;
        int used = 0;
        for (int f = 0; f < frames; f++) {
            int off = f * frameBytes;
            if (frameIsSilent(data, off, frameBytes, threshold)) {
                continue;
            }
            for (int b = 0; b < frameBytes; b += 2) {
                int v = (short) ((data[off + b] & 0xFF) | (data[off + b + 1] << 8));
                sum += (double) v * v;
            }
            used++;
        }
        if (used == 0) {
            return 0;
        }
        return Math.sqrt(sum / ((double) used * (frameBytes / 2)));
    }

    /** 整片峰值（绝对值），用于增益后的削顶保护。 */
    private static int peakAbs(byte[] data) {
        int peak = 0;
        for (int b = 0; b + 1 < data.length; b += 2) {
            int v = (short) ((data[b] & 0xFF) | (data[b + 1] << 8));
            int a = Math.abs(v);
            if (a > peak) {
                peak = a;
            }
        }
        return peak;
    }

    /** 按增益缩放整片（就地），末帧不做渐变 —— 端点淡化由接缝淡化负责。 */
    private static void scaleAll(byte[] data, double gain) {
        for (int b = 0; b + 1 < data.length; b += 2) {
            int v = (short) ((data[b] & 0xFF) | (data[b + 1] << 8));
            int scaled = (int) Math.round(v * gain);
            if (scaled > Short.MAX_VALUE) {
                scaled = Short.MAX_VALUE;
            } else if (scaled < Short.MIN_VALUE) {
                scaled = Short.MIN_VALUE;
            }
            data[b] = (byte) (scaled & 0xFF);
            data[b + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        return (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    // ------------------------------------------------------------------ 静音裁剪

    /**
     * 头尾静音归一化：输出恒为「{@code headKeep} 帧片头 + 原始发声段 + {@code tailKeep} 帧静音」，
     * 其中片头这 {@code headKeep} 帧是「回退保留的真实起音 + 不足时补的静音」（见方法内起音保护注释），
     * 而非一律的纯静音 —— 那样会把塞音（b/d/g…）低于阈值的成阻段当静音裁掉，令片首字字头含糊。
     *
     * <p>与 {@link WavEnvelope} 的裁剪逻辑同口径（那边是「整段」的端点，这里是「子句」的端点；
     * WavEnvelope 用 {@code start=max(0, head-headKeep)} 同样保留了起音），
     * 两份实现各自独立：一处改「整段」口径不该连带改变「子句」口径。
     *
     * @return 归一化后的 PCM（新数组）；整片静音时<b>原样返回入参</b>（调用方据此免去一次组装）
     */
    private static byte[] normalizeSilence(byte[] data, WavConcat.PcmFormat pcm, int threshold,
                                           int headKeepMillis, int tailKeepMillis) {
        int frameBytes = pcm.blockAlign();
        int frames = data.length / frameBytes;
        if (frames == 0) {
            return data;
        }
        int head = 0;
        while (head < frames && frameIsSilent(data, head * frameBytes, frameBytes, threshold)) {
            head++;
        }
        int tail = frames - 1;
        while (tail >= head && frameIsSilent(data, tail * frameBytes, frameBytes, threshold)) {
            tail--;
        }
        if (tail < head) {
            // 整片静音：不处理（0 长度会破坏下游「每段非空」的契约，补静音也没有意义）
            return data;
        }

        int headKeep = millisToFrames(Math.max(0, headKeepMillis), pcm.sampleRate());
        int tailKeep = millisToFrames(Math.max(0, tailKeepMillis), pcm.sampleRate());

        // ⭐ 起音保护（2026-10-20，线上《测试》task31 第 23 段「本人仍然是」字头被吃）：
        // head 是「首个振幅超过 trimThreshold 的帧」。但塞音（b/d/g/p/t/k…）的成阻段与弱除阻
        //  burst 振幅常低于阈值（300 ≈ -40 dBFS），会被 frameIsSilent 当成静音一起丢掉，
        // 于是正文从**元音中间**才开始 —— 听感就是「字头含糊/被切掉」。拆片的第二片及以后
        // 尤其明显：片首常以这类软声母字接续（「本(b)」「其(q)」…）。
        //
        // 修法与 WavEnvelope.trimSilence 的 start=max(0, head-headKeep) 同口径：正文起点从 head
        // **向前回退 headKeep 帧**，把这段真实（多为低电平）的起音保留下来；只有当片头本就贴近
        // 起音（head < headKeep）、回退不够时，才在前面补静音凑满 headKeep。
        //
        // 关键不变式：**头部总长度恒为 headKeep 帧**（回退拿到的真实起音 + 补齐的静音），
        // 因此接缝停顿节奏、整片时长与改造前完全一致 —— 变的只是那 headKeep 毫秒的内容：
        // 从「纯静音」换成「引擎原始起音」。pre-head 本就是静音时（含全部单测素材），
        // 回退取到的正是那段 0，输出与改造前逐字节相同。
        int bodyStart = Math.max(0, head - headKeep);
        int leadSilence = headKeep - (head - bodyStart);   // == max(0, headKeep - head)
        int bodyFrames = tail - bodyStart + 1;
        byte[] out = new byte[(leadSilence + bodyFrames + tailKeep) * frameBytes];
        // 头部 leadSilence 帧保持全 0（new byte[] 已是 0），其余拷入「含起音的正文 + 尾部余量」
        System.arraycopy(data, bodyStart * frameBytes, out, leadSilence * frameBytes, bodyFrames * frameBytes);
        return out;
    }

    /**
     * 片内停顿封顶：把片<b>内部</b>过长的静音压到上限。
     *
     * <p><b>为什么需要它（真机数据说的）</b>：在 tts-node.example.com 上对真实长句做 A/B 时发现，
     * MOSS-TTS-Nano 在逗号处会插入 <b>0.7~2.0 s</b> 的静音（同一句里既有 141 ms 也有 1959 ms）。
     * 而一片最多 45 字 ⇒ 这些"巨停顿"绝大多数落在片<b>内部</b>，接缝整形完全管不到；
     * 若不处理，同一句里就会出现「逗号停 0.2 s、逗号停 1.9 s」的节奏跳变 ——
     * 这正是用户说的「节奏不一致」，而且它跟拆不拆片无关。
     *
     * <p><b>为什么用「封顶」而不是「统一改写」</b>：短停顿（150~300 ms）是正常的句读呼吸，
     * 统一改写会把自然停顿也一起抹平；封顶只动那些明显异常的巨停顿，保留自然的部分。
     * 下限 {@code minMillis} 是硬要求：低于它的静音是塞音闭塞/换气缝隙，动了会把字咬碎。
     *
     * <p>{@code capMillis <= minMillis} 表示关闭（返回原数组引用，调用方据此判断"没有变化"）。
     *
     * @return 封顶后的 PCM；无需改动（全是短停顿 / 参数关闭）时<b>原样返回入参</b>
     */
    private static byte[] capInternalPauses(byte[] data, WavConcat.PcmFormat pcm, int threshold,
                                            int minMillis, int capMillis) {
        int frameBytes = pcm.blockAlign();
        int frames = data.length / frameBytes;
        if (frames == 0) {
            return data;
        }
        int minFrames = millisToFrames(Math.max(0, minMillis), pcm.sampleRate());
        int capFrames = millisToFrames(Math.max(0, capMillis), pcm.sampleRate());
        if (minFrames <= 0 || capFrames <= 0) {
            // 任一维度 <= 0 即关闭本项（cap 可以大于 min：那样只有超过 cap 的巨停顿才会被压）
            return data;
        }

        // 只在「首末发声帧之间」找内部停顿：头尾的静音刚刚被归一化过，不归本方法管
        int head = 0;
        while (head < frames && frameIsSilent(data, head * frameBytes, frameBytes, threshold)) {
            head++;
        }
        int tail = frames - 1;
        while (tail >= head && frameIsSilent(data, tail * frameBytes, frameBytes, threshold)) {
            tail--;
        }
        if (tail <= head) {
            return data;
        }

        int newFrames = 0;
        List<int[]> runs = new ArrayList<>();   // {start, len, shrunkLen}
        int i = head;
        boolean changed = false;
        while (i <= tail) {
            if (frameIsSilent(data, i * frameBytes, frameBytes, threshold)) {
                int start = i;
                while (i <= tail && frameIsSilent(data, i * frameBytes, frameBytes, threshold)) {
                    i++;
                }
                int len = i - start;
                int shrunk = (len >= minFrames && len > capFrames) ? capFrames : len;
                changed |= shrunk != len;
                runs.add(new int[]{start, len, shrunk});
                newFrames += shrunk;
            } else {
                int start = i;
                while (i <= tail && !frameIsSilent(data, i * frameBytes, frameBytes, threshold)) {
                    i++;
                }
                int len = i - start;
                runs.add(new int[]{start, len, len});
                newFrames += len;
            }
        }
        if (!changed) {
            return data;
        }

        byte[] out = new byte[(head + newFrames + (frames - 1 - tail)) * frameBytes];
        // 头部静音原样保留
        System.arraycopy(data, 0, out, 0, head * frameBytes);
        int p = head * frameBytes;
        for (int[] run : runs) {
            int keepFrames = run[2];
            int copyFrames = Math.min(run[1], keepFrames);
            System.arraycopy(data, run[0] * frameBytes, out, p, copyFrames * frameBytes);
            p += keepFrames * frameBytes;   // 被压缩掉的部分是静音，保持 0 即可
        }
        System.arraycopy(data, (tail + 1) * frameBytes, out, p, (frames - 1 - tail) * frameBytes);
        return out;
    }

    /** 一帧内所有声道样本绝对值都 {@code <= threshold} 才算静音 */
    private static boolean frameIsSilent(byte[] data, int off, int frameBytes, int threshold) {
        for (int b = 0; b < frameBytes; b += 2) {
            int v = (short) ((data[off + b] & 0xFF) | (data[off + b + 1] << 8));
            if (Math.abs(v) > threshold) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 小工具

    /** 构造一段全零静音的 WAV（fmt 与子句片完全一致，才能被 WavConcat 拼进去）。 */
    private static byte[] silenceWav(byte[] fmt, WavConcat.PcmFormat pcm, int millis) {
        int frames = millisToFrames(millis, pcm.sampleRate());
        byte[] data = new byte[frames * pcm.blockAlign()];
        return WavConcat.assemble(fmt, List.of(data), data.length);
    }

    private static String textOf(List<String> texts, int index) {
        if (texts == null || index < 0 || index >= texts.size()) {
            return null;
        }
        return texts.get(index);
    }

    private static String describePauses(List<String> texts, Options opts) {
        if (texts == null || texts.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size() - 1; i++) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(pauseMillisFor(texts.get(i), opts));
        }
        return sb.toString();
    }

    private static int millisToFrames(int millis, int sampleRate) {
        if (millis <= 0) {
            return 0;
        }
        return (int) Math.round((long) millis * sampleRate / 1000.0);
    }
}
