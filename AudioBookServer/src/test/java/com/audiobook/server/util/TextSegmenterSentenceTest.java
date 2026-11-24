package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新切段口径（**片段 = 完整句子**）的回归测试。
 *
 * <p>与 {@link TextSegmenterTest} 的分工：那边锁的是 {@link TextSegmenter#segment} 的
 * 「贪心打包 + 超长句优先在次级标点落刀」行为（现在只剩「拆引擎小片」一个用途，逻辑未改）；
 * 这边锁的是新加的两个方法，以及「旧口径不得复活」这件事。
 *
 * <p><b>为什么要单独锁「不打包、不裁切」</b>：旧口径会把相邻短句贪心拼成 ≤30 字的片段，
 * 这段历史很长（{@code DEFAULT_MAX_LENGTH} 从 100 收到 30），很容易在某次「顺手优化」里
 * 被复活 —— 而它的后果是静默的：句子被拼在一起、音频与文本仍一一对应，看不出坏，
 * 直到用户发现「一句话被切成两段读」或「半句话单独成段」。
 *
 * <p>真实长句夹具取自线上那本书（《沉重的翅膀-节选》），由生成器逐字节写入，
 * 不是手抄 —— 手抄错一个全角标点就会让期望的拆片长度整体不对，
 * 那种失败极易被误判成算法缺陷。
 */
class TextSegmenterSentenceTest {

    /** 线上真实长句：<b>不含</b>句末标点内部的句子（含「……」与「；」），整句 85 字 */
    private static final String REAL_LONG_SENTENCE = "比方说，一个好天气；一封盼望已久的来信；看了一部好电影；电车上有个吊儿郎当的小青年给老太太让了座……现在呢，只是因为这晴朗的天；病后的好胃口；莫征周到而又不露形迹的关切。";

    // ---------- sentences：只切句，不打包、不裁切 ----------

    @Test
    @DisplayName("短句不被合并：3 句就是 3 条（旧口径会贪心拼成 1 条）")
    void shortSentencesAreNotPacked() {
        List<String> s = TextSegmenter.sentences("第一句。第二句？第三句！");

        assertThat(s).containsExactly("第一句。", "第二句？", "第三句！");
    }

    @Test
    @DisplayName("超长句原样保留：不裁切、不拆片（拆片是合成时的事）")
    void longSentenceIsKeptIntact() {
        List<String> s = TextSegmenter.sentences(REAL_LONG_SENTENCE);

        assertThat(s).containsExactly(REAL_LONG_SENTENCE);
        // 明确锁住「不限长度」：这一句远超引擎单次上限
        assertThat(s.get(0).length()).isGreaterThan(TextSegmenter.DEFAULT_MAX_LENGTH);
    }

    @Test
    @DisplayName("换行不是句边界：跨行对话仍是一句，但句内换行会被去掉")
    void lineBreakIsNotASentenceBoundary() {
        List<String> s = TextSegmenter.sentences("他说：\n「你来了。」\n她说：\n「嗯。」");

        assertThat(s).hasSize(2);
        // 换行不算边界（仍是两句），但句内换行不留 —— 留着送进 TTS 会在那里停顿
        assertThat(s.get(0)).isEqualTo("他说：「你来了。」");
        assertThat(s.get(1)).isEqualTo("她说：「嗯。」");
    }

    @Test
    @DisplayName("句内换行被去掉：版面换行把书名劈开时不留换行（避免 TTS 在那里停顿）")
    void innerLineBreakIsRemoved() {
        List<String> s = TextSegmenter.sentences("当记者后啃过《汉\n书》和《后汉书》 ，完全被淹没了。");

        assertThat(s).containsExactly("当记者后啃过《汉书》和《后汉书》 ，完全被淹没了。");
    }

    @Test
    @DisplayName("英文单词被换行劈开时换成空格（不能粘成一个词）")
    void asciiWordBrokenByLineBreakKeepsSpace() {
        List<String> s = TextSegmenter.sentences("hello\nworld. 你好。");

        assertThat(s).containsExactly("hello world.", "你好。");
    }

    @Test
    @DisplayName("句末标点后紧跟的闭引号留在句内：不产生「」这种单字符片段")
    void closingQuoteStaysInsideSentence() {
        // 中文里「…。」「…」」这种「句号后紧跟闭引号」极常见。只按句号切的话，闭引号会被
        // 甩到下一句开头、甚至自己独占一段 —— 而一段是**独立合成**一条音频的，
        // 用户会听到一段只念一个引号的声音（还各花一次请求）。
        List<String> s = TextSegmenter.sentences("他说：「你来了。」她说：「嗯。」");

        assertThat(s).containsExactly("他说：「你来了。」", "她说：「嗯。」");
        assertThat(s).allSatisfy(x -> assertThat(x).isNotEqualTo("」"));

        // 闭引号被换行顶到下一行开头时，同样必须留在上一句句尾
        List<String> wrapped = TextSegmenter.sentences("他说：「你来了。\n」她说：「嗯。」");

        assertThat(wrapped).hasSize(2);
        assertThat(wrapped.get(0)).endsWith("」");
        assertThat(wrapped.get(1)).isEqualTo("她说：「嗯。」");
    }

    @Test
    @DisplayName("不丢字：所有句子去空白后拼接 == 原文去空白")
    void neverLosesCharacters() {
        String text = "第一句。 第二句？\n\n" + REAL_LONG_SENTENCE + "  \n最后一句没有标点";

        assertThat(squeeze(String.join("", TextSegmenter.sentences(text))))
                .isEqualTo(squeeze(text));
    }

    @Test
    @DisplayName("空输入返回空列表（null / 空串 / 纯空白）")
    void handlesEmptyInput() {
        assertThat(TextSegmenter.sentences(null)).isEmpty();
        assertThat(TextSegmenter.sentences("")).isEmpty();
        assertThat(TextSegmenter.sentences("   \n\t  ")).isEmpty();
    }

    // ---------- ASCII 句点的约束（2026-09-26 线上事故：目录点线炸成 4371 条碎片） ----------

    @Test
    @DisplayName("目录点线不再被逐个点切成碎片（线上事故：一条 159 点的点线 = 159 条「.」片段）")
    void dotLeaderIsNotSplitPerDot() {
        String leader = "（一）序言 " + ".".repeat(159);

        List<String> s = TextSegmenter.sentences(leader);

        assertThat(s).containsExactly(leader.trim());
        // 反向锁：绝不允许再产出「内容是一个点」的片段
        assertThat(s).noneMatch(x -> x.chars().allMatch(c -> c == '.'));
    }

    @Test
    @DisplayName("小数与序号里的点不算句末：3.14 / 1. 说明 不被切开")
    void dotsBetweenDigitsOrListNumberAreNotBoundaries() {
        assertThat(TextSegmenter.sentences("圆周率是 3.14159 左右。")).hasSize(1);
        assertThat(TextSegmenter.sentences("1. Strenth 搞经济的话，中国政府很有优势。"))
                .containsExactly("1. Strenth 搞经济的话，中国政府很有优势。");
    }

    @Test
    @DisplayName("句末孤立句点照旧切（约束只针对「紧邻点或数字」的点）")
    void standalonePeriodStillTerminates() {
        assertThat(TextSegmenter.sentences("Hello world. And more."))
                .containsExactly("Hello world.", "And more.");
    }

    @Test
    @DisplayName("点线场景也不丢字")
    void neverLosesCharactersWithDotLeaders() {
        String text = "（一）序言 " + ".".repeat(120) + " 1\n正文第一句。正文第二句。";

        assertThat(squeeze(String.join("", TextSegmenter.sentences(text))))
                .isEqualTo(squeeze(text));
    }

    // ---------- subPieces：超长句拆小片（合成路径用） ----------

    @Test
    @DisplayName("不超上限的句子原样成单片（合成路径与改造前逐字节一致）")
    void shortSentenceBecomesSinglePiece() {
        String s = "她高兴。";

        assertThat(TextSegmenter.subPieces(s)).containsExactly(s);
        // 恰好等于上限也要落在单片里（不能因为「>」写成「>=」就多切一片）
        String exact = "的".repeat(TextSegmenter.DEFAULT_MAX_LENGTH);
        assertThat(TextSegmenter.subPieces(exact)).containsExactly(exact);
    }

    @Test
    @DisplayName("真实长句拆片：片数与长度锁定，且每片不超上限+尾片阈值")
    void realLongSentenceIsSplitAsExpected() {
        List<String> pieces = TextSegmenter.subPieces(REAL_LONG_SENTENCE);

        // 片数刻意写死：它是「上限被改动」的显式告警点，而不是"跟着常量自动变"。
        // 该句 91 字：上限 30 时 3 片、上限 45 时 3 片（落刀点右移，但尾片并入规则让它仍是 3）
        assertThat(pieces).hasSize(3);
        assertThat(pieces).allSatisfy(p -> assertThat(p.length())
                .isLessThanOrEqualTo(TextSegmenter.DEFAULT_MAX_LENGTH
                        + TextSegmenter.SUB_PIECE_MIN_TAIL - 1));
        // 不丢字：拆片只是换了个落刀点
        assertThat(squeeze(String.join("", pieces))).isEqualTo(squeeze(REAL_LONG_SENTENCE));
    }

    @Test
    @DisplayName("优先在句子内部的标点处落刀（不劈开词）")
    void prefersPunctuationInsideSentence() {
        // 按常量构造：逗号落在「回退窗口」内（≥ 上限×0.6），且整句超过上限 ⇒ 必然在逗号处落刀
        int max = TextSegmenter.DEFAULT_MAX_LENGTH;
        int first = max * 2 / 3;
        String s = "甲".repeat(first) + "，" + "乙".repeat(max - first) + "。";

        List<String> pieces = TextSegmenter.subPieces(s);

        assertThat(pieces).hasSize(2);
        // 第一片必须收在逗号处：若按位置硬切，就会把「甲」串劈成两截
        assertThat(pieces.get(0)).isEqualTo("甲".repeat(first) + "，");
    }

    @Test
    @DisplayName("尾片过短时并入前一片（不为一两个字单独发一次请求）")
    void shortTailIsMergedIntoPreviousPiece() {
        // 31 字、逗号在索引 29 ⇒ 分段结果为 30 + 1；那个 1 字的尾片必须被并掉
        String s = "丙".repeat(29) + "，丁";

        List<String> pieces = TextSegmenter.subPieces(s);

        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0)).isEqualTo(s);
        assertThat(pieces.get(0).length()).isEqualTo(31);
    }

    @Test
    @DisplayName("拆片不会产生空片；空输入返回空列表")
    void neverProducesEmptyPiece() {
        assertThat(TextSegmenter.subPieces(null)).isEmpty();
        assertThat(TextSegmenter.subPieces("")).isEmpty();
        assertThat(TextSegmenter.subPieces("   ")).isEmpty();

        List<String> pieces = TextSegmenter.subPieces("戊".repeat(95));
        assertThat(pieces).isNotEmpty().allSatisfy(p -> assertThat(p).isNotEmpty());
    }

    /** 去掉所有空白（含换行/制表符/全角空格）：空白本来就是允许有差异的部分 */
    private static String squeeze(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u3000') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
