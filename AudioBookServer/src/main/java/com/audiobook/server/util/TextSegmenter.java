package com.audiobook.server.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTS 文本切分工具。
 *
 * <p><b>两个层次，用途不同、不可互替</b>：
 * <ul>
 *   <li>{@link #sentences(String)} —— 把正文切成<b>完整句子</b>（<b>不限长度</b>）。
 *       这是「片段」的定义：<b>一条句子 = 一条 {@code audio_segments} + 一条音频</b>。
 *       正文提取（阅读页段落）与合成任务（片段表）**必须都调它**，否则两边粒度不同
 *       ⇒ 阅读页段落与音频段号错位（这正是本类要收敛的头号漂移点）。</li>
 *   <li>{@link #segment(String, int)} —— 把文本贪心拼装成不超过 {@code maxLength} 字符的段落。
 *       自「片段 = 整句」改造后<b>只剩一个用途</b>：<b>超长句在送引擎前拆小片</b>，
 *       由 {@link #subPieces(String)} 调用。引擎单次合成的耗时与内存由帧数决定，
 *       帧数按该次请求的字符数算出 ⇒「越长越慢越吃内存」，{@link #DEFAULT_MAX_LENGTH}
 *       因此从「片段上限」变成了「<b>引擎单次文本上限</b>」—— 这正是它注释里原本的本意。</li>
 * </ul>
 *
 * <p><b>为什么片段边界不再取 30 字</b>：那样一章 800 字会被切成 27 条互不相干的碎片，
 * 播放/高亮/断点续播/目录跳转的单位都是碎片级，与用户认知（「一句一句读」）不符；
 * 改成整句后，存储与播放的单位与认知一致。引擎侧的成本护栏由
 * {@link #subPieces(String)} 保证 —— <b>引擎看到的文本仍然每片 ≤30 字</b>，
 * 只是这些片会被拼回成一条与整句对应的音频。
 *
 * <p>单句本身超长时（小说/PDF 提取里常见「一百多字一逗到底」的长句），
 * 不是简单地每 {@code maxLength} 个字符砍一刀 —— 那会把正好跨在刀口上的词劈成两半
 * （实测线上：《潜规则》里「…下放劳动，和农」／「民的孩…」把「农民」拆开，
 * 「老师的带」／「领下」把「带领」拆开），而每段是**独立合成**成一段 MP3 的，
 * 播放到边界就成了「和农」+ 停顿 +「民的孩」，语义被切断。
 * 因此改为**优先在次级停顿标点处落刀**：在窗口内从右往左找最近的
 * 「，、；：,;」，取其后为本段结尾；找不到（或标点太靠左）才按上限硬切。
 *
 * <p>为什么回退范围要设下限（见 {@link #BREAK_FALLBACK_MIN_RATIO}）：不设的话，
 * 逗号密集的长句会被切得极碎、段数上涨，而段数是 TTS 成本的直接乘数
 * （每段 = 一次请求 + 一个 MP3 + 一份参考音频上传）。
 *
 * <p>收敛了原先 {@code BookContentController} 与 {@code VoiceCloningTtsService}
 * 中两份逐行同构的实现——两处分别带「与对方保持一致」的注释，属高风险漂移点。
 */
public final class TextSegmenter {

    /**
     * 引擎单次合成的文本长度上限（即 {@link #subPieces(String)} 的分片阈值）。
     *
     * <p><b>它已不再是「片段上限」</b>：片段的定义改成了完整句子（见 {@link #sentences(String)}），
     * 本值现在只决定「一个长句要拆成几次请求」。别再把片段长度与它挂钩 ——
     * 片段长度由原文决定，可以远超本值。
     *
     * <p><b>为什么是 45 而不是 30（2026-10-17 真机实测后从 30 上调）</b>：
     * 拆片是句内韵律不一致的根源 —— 真机配对统计证明引擎**不会**通过 {@code prompt_audio}
     * 传递韵律（下一片的开头音高与上一片结尾音高的相关系数 r=0.00），
     * 所以「减少接缝」是改善句内语调唯一有效的方向，而接缝数直接由本值决定。
     *
     * <p>约束条件是节点内存（`tts-node.example.com`：`MemoryMax=3.2G`、无 swap、常驻约 1.93 GB）。
     * 实测（2 s 尾巴 / 6.5 s 参考音频两种 prompt，请求中每 1.2 s 采一次 cgroup 峰值）：
     * <table>
     *   <tr><th>文本长度</th><th>生成音频</th><th>cgroup 峰值</th></tr>
     *   <tr><td>29 字</td><td>6.5 s</td><td>1.94~2.05 GB</td></tr>
     *   <tr><td>45 字</td><td>7.8 s</td><td>2.02~2.14 GB</td></tr>
     *   <tr><td>52 字</td><td>10.5 s</td><td>2.48 GB</td></tr>
     *   <tr><td>61 字</td><td>16.1 s</td><td><b>3.09 GB</b>（距上限仅 0.11 GB，已触及危险区）</td></tr>
     * </table>
     * 可见峰值跟的是**实际生成时长**（≈0.07 GB/秒），而不是下发时给的
     * {@code max_new_frames} 上限 —— 同一段 29 字文本把 cap 设成 150/210/270，
     * 输出都是 6.48 s、峰值都是 ~2.05 GB（EOS 提前触发）。
     *
     * <p>45 字对应的最坏情况（慢速朗读 ≈11 s 音频）峰值约 2.3 GB，留 ~0.9 GB 余量；
     * 60 字以上则可能撞上 3.09 GB 那一档，故<b>不要</b>继续上调，除非换更大的机器。
     *
     * <p>收益（`LenStats` 实测，生产切分口径）：《安徒生童话》需要拆片的句子
     * 由 9/38 降到 2/38；总请求数 48 → 40。也就是**约 4/5 的长句不再有句内接缝**。
     *
     * <p>代价是单个请求更长（45 字约 15~20 s），但**一句话的总耗时基本不变**
     * （总字数不变，只是落刀点变少），且失败重试的粒度变粗。
     *
     * <p>另外，{@code moss.tts.frames.max=375}（≈30 秒音频）与「整句直接送引擎」仍不相容：
     * 85 字的句子已需 370 帧（贴边）、200 字的句子必然被截断。所以超长句**必须**走
     * {@link #subPieces(String)} 拆片，这不是优化而是必需。
     */
    public static final int DEFAULT_MAX_LENGTH = 45;

    /**
     * 尾片并入前一片的阈值（字符数）。
     *
     * <p>拆片时最后一片可能只剩一两个字（实测《沉重的翅膀-节选》里 85 字的句子拆出
     * {@code [28, 26, 30, 1]}，尾片是孤零零一个「。」）。这种片单独发一次请求要花约 10 秒，
     * 换回来的却是一段近乎静音、只念一个标点的音频 —— 所以把它并进前一片。
     *
     * <p>并入后该片会略超 {@link #DEFAULT_MAX_LENGTH}（最坏 30 + 3 = 33 字），
     * 对应帧数 30 + 4×33 = 162，仍远低于引擎上限 375，安全。
     */
    public static final int SUB_PIECE_MIN_TAIL = 4;

    /**
     * 句末标点 + **紧跟其后的收尾符号**（闭引号/闭括号/书名号）+ 其后的空白。
     *
     * <p><b>为什么要把收尾符号一起切进去</b>：中文里「…。」「…」」这种「句号后紧跟闭引号」
     * 极常见。只认句号的话，闭引号会被甩到下一个片段的开头，甚至自己独占一个片段 ——
     * 实测「他说：「你来了。」她说：「嗯。」」会切出 {@code 」} 这个**单字符片段**，
     * 而片段是**独立合成**成一条音频的，用户会听到一段只念一个引号的声音（还各花一次请求）。
     *
     * <p><b>为什么用 {@link Matcher} 而不是 {@code String.split}</b>：{@code split} 会丢弃
     * 分隔符，而这里的分隔符**含有要保留的字符**（收尾符号），必须按 {@code m.end()} 手动切。
     * 分隔符里也含空白（切完丢弃），与改造前的 {@code \s*} 行为一致 ——
     * 非空白字符一个都不会丢（见 {@code TextSegmenterTest#neverLosesCharacters}）。
     *
     * <p>注意本模式必须能匹配到**至少一个字符**（首字符是句末标点），否则 {@code find()}
     * 会空匹配并死循环。
     *
     * <p><b>ASCII 句点 {@code .} 为什么要加约束（2026-09-26 线上事故）</b>：
     * 从 Word 导出的中文 PDF，目录页普遍用**点线引导**（{@code （一）序言 ..........}）。
     * 无约束地把 {@code .} 当句末标点，会把每条点线当成几十上百条「句子」——
     * 实测《中国的官办经济》：36 条点线（长 79~159 个点）切出 **4371 条内容为单个 {@code .} 的片段**，
     * 占全书 7756 段的 **56%**，正文从第 4407 段才开始。后果是双份的：
     * 阅读页打开就是几千个「.」（用户报「基本没有显示出正确的内容」），
     * 且这 4371 段各发一次 TTS 请求、各产出一段近乎静音的音频（用户报「合成的音频也不正确」）。
     *
     * <p>约束规则（只作用于 ASCII {@code .}，中文 {@code 。} 与 ASCII {@code !?} 行为不变）：
     * 前后**紧邻**的位置是另一个 {@code .} 或数字时不算句末标点。于是：
     * <ul>
     *   <li>{@code .......}（点线）不再逐个切分，整条点线留在原句里；</li>
     *   <li>{@code 3.14}、{@code 1. 说明} 这类小数点/序号不再被切开；</li>
     *   <li>句末的孤立句点 {@code Hello world.} 仍然照切。</li>
     * </ul>
     * 已知不覆盖：{@code U.S.} 这类缩写仍会切（英文缩写本来就无法靠字符规则可靠区分，
     * 且中文电子书里极少）。切分点只往后移、不丢字符，故
     * {@code neverLosesCharacters} 不变式仍然成立。
     */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?:[。！？]|[!?]|(?<![.\\d])\\.(?![.\\d]))[\\s」』”’\\)\\]）】》]*");

    /**
     * 超长句硬切时的「回退落刀点」候选：次级停顿标点。
     *
     * <p>不含句末标点（。！？.!?）—— 那些已由 {@link #SENTENCE_END} 处理，
     * 长句里本来就没有，列进来只会增加误命中的噪音。
     */
    private static final String BREAK_FALLBACK_CHARS = "，、；：,;";

    /**
     * 回退落刀点的下限比例：只有落在 {@code [maxLength × 本值, maxLength)} 区间里的标点才会被选中，
     * 否则按 {@code maxLength} 硬切。
     *
     * <p>取 0.6 是「不劈词」与「不切碎」之间的折中：
     * <ul>
     *   <li>太高（如 0.9）：稍有变动就退化成硬切，还是可能劈词；</li>
     *   <li>太低（如 0.3）：逗号密集的长句会碎成 10~18 字一段，段数明显上涨。
     *       段数是 TTS 成本的直接乘数（每段 = 一次请求 + 一个 MP3 + 一份参考音频上传流量），
     *       而 30 字上限本身就是为了压单段最坏情况才收紧的，不该被切碎反噬。</li>
     * </ul>
     * 0.6 对应 30 字上限下的下限 18 字，实测《潜规则》那段 113 字长句切成 4 段（与改前段数相同）。
     */
    private static final double BREAK_FALLBACK_MIN_RATIO = 0.6;

    /**
     * 英文单词被换行劈开时（{@code hello\nworld}）用**一个空格**接上。
     *
     * <p>直接删会把两个词粘成一个（{@code helloworld}），读出来是个怪词。
     */
    private static final Pattern ASCII_WORD_BREAK = Pattern.compile(
            "(?<=[0-9A-Za-z])[ \\t\\u3000]*\\n[ \\t\\u3000]*(?=[0-9A-Za-z])");

    /**
     * 其余换行（中文等）连同两侧空白一起删掉：于是「《汉\n书》」变成「《汉书》」。
     */
    private static final Pattern INNER_NEWLINE = Pattern.compile("[ \\t\\u3000]*\\n[ \\t\\u3000]*");

    private TextSegmenter() {
    }

    /**
     * 去掉句子<b>内部</b>的换行（连同紧邻的空白）。
     *
     * <p><b>为什么必须清</b>：PDF / EPUB / MOBI 抽出来的正文里，换行常常只是<b>版面换行</b>，
     * 会把词或书名劈成两半：
     *
     * <pre>
     * 当记者后啃过《汉
     * 书》和《后汉书》……
     * </pre>
     *
     * <p>切句时换行不算句边界（跨行对话仍算一句），所以这个换行会<b>留在句子里</b>；
     * 而送进 TTS 的文本里带换行 ⇒ 引擎在换行处停顿 ⇒ 听感上像多了一个断句
     * （线上反馈：「《汉书》中间有个换行符，导致合成音频时有停顿」）。
     *
     * <p><b>规则</b>（与本地工具 {@code app/core/segmenter.py} 的同名函数逐字一致）：
     * <ul>
     *   <li>换行两侧都是 ASCII 字母/数字 ⇒ 换成<b>一个空格</b>（英文单词不能粘连）；</li>
     *   <li>其余（中文、书名号、标点）⇒ 连同换行两侧的空白<b>一起删掉</b>。</li>
     * </ul>
     *
     * <p>只处理句内换行：句末标点之后的换行本来就属于「句末空白」，
     * 在切句时就已经被 {@link #SENTENCE_END} 的尾部字符集吃掉了。
     *
     * <p><b>不变式</b>：本方法只删/换<b>空白</b>，不动任何实字 ——
     * {@code TextSegmenterSentenceTest#neverLosesCharacters}（「去空白后拼接 == 原文去空白」）
     * 因此依然成立；{@code BookTextExtractor#locateIgnoringBlank} 依赖的
     * 「段落文本与原文只相差空白」也不变式同样不受影响。
     */
    private static String cleanInnerNewlines(String s) {
        if (s == null || (s.indexOf('\n') < 0 && s.indexOf('\r') < 0)) {
            return s;
        }
        String t = s.replace("\r\n", "\n").replace('\r', '\n');
        t = ASCII_WORD_BREAK.matcher(t).replaceAll(" ");
        return INNER_NEWLINE.matcher(t).replaceAll("");
    }

    /**
     * 为一段超长文本挑选「本段取多少个字符」。
     *
     * <p>只在 {@code [maxLength × ratio, maxLength)} 区间内从右往左找最近的次级停顿标点，
     * 返回该标点的**后一个位置**（标点留在段尾），从而保证词不被劈开；
     * 区间内没有标点则退回 {@code maxLength} 纯硬切。
     *
     * @param text      待切分文本，调用方保证其长度大于 {@code maxLength}
     * @param maxLength 单段字符数上限
     * @return 本段应截取的字符数，恒在 {@code [floor(maxLength × ratio) + 1, maxLength]} 内
     */
    private static int chooseBreakPoint(String text, int maxLength) {
        int limit = Math.min(maxLength, text.length());
        int floor = (int) (maxLength * BREAK_FALLBACK_MIN_RATIO);
        for (int i = limit - 1; i >= floor; i--) {
            if (BREAK_FALLBACK_CHARS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return maxLength;
    }

    /**
     * 把正文切成<b>完整句子</b>：按句末标点（{@link #SENTENCE_END}）切分，
     * 每句原样保留，<b>不打包、不裁切、不限长度</b>。
     *
     * <p><b>这是「片段」的唯一来源</b>：正文提取（阅读页段落）与合成任务（片段表）
     * 必须都调本方法。两边若各自维护一份（历史上就是两份逐行同构的实现），
     * 切分口径会漂移，表现为「阅读页段落」与「音频段号」对不上，用户跟读时文字与声音错位。
     *
     * <p><b>句子可能很长</b>（小说/PDF 里「一百多字一逗到底」很常见）。引擎侧的 30 字上限
     * 由 {@link #subPieces(String)} 在<b>合成时</b>负责，与本方法无关 —— 别为了让句子短一点
     * 而在这里打包或硬切，那正是被废弃的旧行为（也是本方法存在的理由）。
     *
     * <p><b>换行不算句边界</b>（与 {@link #SENTENCE_END} 的定义一致）：一行原文若是
     * 「他说：「……」」这种跨行对话，整段仍算一句。刻意把换行也算成边界会产出
     * {@code ”}、{@code 一} 这种独占一行的单字段（实测《沉重的翅膀-节选》出现 3 处），
     * TTS 会为标点单独合成一次，反而更差。
     *
     * <p><b>但句子内部的换行会被去掉</b>（见 {@link #cleanInnerNewlines}）：
     * 「不算边界」不等于「留着不管」—— PDF 抽取的版面换行会把《汉书》这类书名劈成两半，
     * 留着送进 TTS 就会在那里停顿，听感上像多了一个断句。
     *
     * <p>改本方法属于「切段口径」变更：**已入库的旧片段不会被自动重切**，但客户端仍自洽 ——
     * 阅读页的「段号 ↔ 行号」映射取自本地 {@code audio_segments} 的文本，而该文本与音频
     * 是同一次下载写入的。**唯一例外是书城书**（mp3 来自 zip、文本来自本接口的 {@code paragraphs}，
     * 两端不同源），换口径后必须重新合成，否则新下载的用户会正文/音频错位。
     * 改完请重跑 {@code deploy/verify_audio_chain.py} 校验「正文段落数 == 片段数」与逐段文本一致。
     *
     * @param text 待切分文本；为 null/空时返回空列表
     */
    public static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        collectSentences(text, result);
        return result;
    }

    /**
     * 按 {@link #SENTENCE_END} 把 {@code text} 切成句子（已 trim、已丢弃空串），追加进 {@code out}。
     *
     * <p>{@link #sentences(String)} 与 {@link #segment(String, int)} 共用本方法 ——
     * 两处若各写一份切句逻辑，就是 {@link TextSegmenter} 类注释里点名的那个漂移点。
     */
    private static void collectSentences(String text, List<String> out) {
        Matcher matcher = SENTENCE_END.matcher(text);
        int start = 0;
        while (matcher.find()) {
            addTrimmed(out, text.substring(start, matcher.end()));
            start = matcher.end();
        }
        addTrimmed(out, text.substring(start));
    }

    private static void addTrimmed(List<String> out, String raw) {
        String s = cleanInnerNewlines(raw.trim());
        if (!s.isEmpty()) {
            out.add(s);
        }
    }

    /**
     * 把一个（可能超长的）句子拆成「送引擎的小片」。
     *
     * <p>这是「超长句分别合成、再拼回一条音频」的前半步：拆分优先落在句子内部的次级停顿
     * 标点（，、；：,;）之后，找不到才按上限硬切 —— 复用 {@link #segment(String, int)}
     * 的落刀逻辑，避免把词劈成两半。
     *
     * <p>不超过 {@link #DEFAULT_MAX_LENGTH} 的句子直接原样返回单元素列表：绝大多数句子走这条路，
     * 合成路径与改造前逐字节一致。
     *
     * @param sentence 句子文本（{@link #sentences(String)} 的一条产物）
     * @return 小片列表；每片 ≤30 字，尾片并入后最坏 33 字；null/空输入返回空列表
     */
    public static List<String> subPieces(String sentence) {
        List<String> pieces = new ArrayList<>();
        if (sentence == null) {
            return pieces;
        }
        String s = sentence.trim();
        if (s.isEmpty()) {
            return pieces;
        }
        if (s.length() <= DEFAULT_MAX_LENGTH) {
            pieces.add(s);
            return pieces;
        }

        pieces.addAll(segment(s, DEFAULT_MAX_LENGTH));

        // 尾片过短则并入前一片：单字/双字的片单独发一次请求，代价（约 10 秒）
        // 远高于收益（换回一段只念一个标点的近乎静音）。并入后最坏 33 字 ⇒ 162 帧。
        if (pieces.size() > 1) {
            int last = pieces.size() - 1;
            String tail = pieces.get(last);
            if (tail.length() < SUB_PIECE_MIN_TAIL) {
                pieces.set(last - 1, pieces.get(last - 1) + tail);
                pieces.remove(last);
            }
        }
        return pieces;
    }

    /**
     * 该小片是否含有「可朗读内容」（至少一个字母或数字）。
     *
     * <p><b>为什么需要这道过滤（2026-09-27 线上事故）</b>：引擎在合成前会做一轮鲁棒性
     * 归一化（上游 {@code tts_robust_normalizer_single_script.normalize_tts_text}），
     * 它把普通文本里的 {@code _} 一律转成空格、再 {@code strip()} —— 于是一片<b>纯下划线</b>
     * 会被清成<b>空字符串</b>；空文本让模型输出 <b>0 帧</b>音频，引擎把 0 帧写进 WAV 时
     * 抛 {@code wave.Error: '# channels not specified'}（这句是 Python 标准库 {@code wave.py}
     * 的措辞，出自 {@code app.py::_audio_to_wav_bytes} 的 {@code setnchannels(0)}）
     * → 节点回 <b>500</b> → 段级重试耗尽后该段被固化成 {@code failed}。
     *
     * <p>实测《潜规则》段 1（书名页有一条 <b>63 字符下划线分隔线</b>）即此因：切小片后每片
     * 都是纯 {@code _}，每次尝试都 500。**与 {@link #SENTENCE_END} 注释里那次「目录点线引导」
     * 事故同源** —— 都是排版装饰线，只是 {@code .} 被上游转成句号（不空、能合成），
     * 而 {@code _} 被直接清空。
     *
     * <p><b>为什么用「存在字母或数字」而不是「排除下划线」</b>：标点、空白、下划线、零宽字符
     * 都不算可朗读，于是连 markdown 前缀行（{@code #}、{@code >}）、孤立引号、纯点线这些
     * 上游同样会清成空的情况也一并覆盖，不必逐条复刻上游正则。代价是「纯标点的片」不再送引擎
     * —— 它们本来就只换回一段近乎静音的音频（与 {@link #SUB_PIECE_MIN_TAIL} 的取舍同向），
     * 还省下一次请求。
     *
     * <p>注意：本方法只用于<b>送引擎前的过滤</b>，不参与段落切分 —— 改切分口径会改变段号，
     * 那意味着整本书必须重合成。
     */
    public static boolean hasSpeakableContent(String piece) {
        if (piece == null) {
            return false;
        }
        for (int i = 0; i < piece.length(); i++) {
            if (Character.isLetterOrDigit(piece.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按 {@link #DEFAULT_MAX_LENGTH} 切段（**现在只被 {@link #subPieces(String)} 调用**，
     * 用途是「超长句拆小片」，不再是「把正文切成片段」）。
     */
    public static List<String> segment(String text) {
        return segment(text, DEFAULT_MAX_LENGTH);
    }

    /**
     * 把文本贪心拼装成不超过 {@code maxLength} 字符的段落；单段本身超长时优先在次级停顿标点处
     * 落刀（见 {@link #chooseBreakPoint(String, int)}），找不到才硬切。
     *
     * <p><b>现在的唯一调用方是 {@link #subPieces(String)}</b>（超长句拆小片）。
     * 正文本身的片段切分请用 {@link #sentences(String)} —— 两者混用会让阅读页段落与
     * 音频段号错位。
     *
     * @param text      待切分文本；为 null/空时返回空列表
     * @param maxLength 单段最大字符数
     */
    public static List<String> segment(String text, int maxLength) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return paragraphs;
        }

        List<String> sentences = new ArrayList<>();
        collectSentences(text, sentences);
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // 当前段落加上新句子会超长，先落盘当前段落
            if (current.length() + trimmed.length() > maxLength && current.length() > 0) {
                paragraphs.add(current.toString().trim());
                current.setLength(0);
            }

            // 单个句子就超长，按上限强制截断（优先落在标点处，避免劈开词）
            if (trimmed.length() > maxLength) {
                if (current.length() > 0) {
                    paragraphs.add(current.toString().trim());
                    current.setLength(0);
                }
                while (trimmed.length() > maxLength) {
                    int cut = chooseBreakPoint(trimmed, maxLength);
                    // 这里**不能** trim：段落坐标改由 BookTextExtractor 在原文中**反查真实下标**
                    // （locateIgnoringBlank）得到，该反查依赖「段落文本与原文只相差空白」这一
                    // 不变式 —— 硬切段保留原样即可满足，顺带避免静默改变喂给 TTS 的文本。
                    // （早前这里写的原因是「BookTextExtractor 用 charOffset += para.length()
                    // 累加偏移」，那套压缩坐标已废弃，正是目录跳错段落的根因。）
                    paragraphs.add(trimmed.substring(0, cut));
                    trimmed = trimmed.substring(cut);
                }
                current.append(trimmed);
            } else {
                current.append(trimmed);
            }
        }

        if (current.length() > 0) {
            paragraphs.add(current.toString().trim());
        }
        return paragraphs;
    }
}
