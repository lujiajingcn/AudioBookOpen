package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TextSegmenter} 切段测试。
 *
 * <p>核心守住两条不变式：
 * <ul>
 *   <li><b>不丢字</b>：所有段落去掉空白后拼起来 == 原文去掉空白。
 *       切段是「正文段落 == 音频片段」一一对应的锚点，丢一个字就是整本书的错位；</li>
 *   <li><b>不劈词</b>：超长句在窗口内若有次级停顿标点，段落必须落在标点之后，
 *       不能出现「和农」／「民的孩」这种把词劈开的结果。</li>
 * </ul>
 *
 * <p>同时守住成本约束：段落数不能因为「落刀点回退」而上涨 —— 段数是 TTS 成本的直接乘数。
 */
class TextSegmenterTest {

    /**
     * 线上真实数据（task_id=347《潜规则》segment_number 16~20 拼回原文）。
     *
     * <p>注意保留了 PDF 提取留下的 {@code \n} 与「1968 年」中间的空格 ——
     * 它们同样占用 {@code maxLength} 配额，正是它们把刀口推到了「农」上。
     */
    private static final String REAL_BOOK_TEXT =
            "阅读嗜好大概\n就是这么养成的。"
                    + "1968 年随母亲去河北省文安县商业部五七干校下放劳动，和农民的孩子一起读书、种地、"
                    + "放羊、捉蚂蚱、游泳，后来，在老师的带领下，下放\n干部的孩子们一起打夯、盖房、刷墻，"
                    + "平地建起了一所小学，一边读书一\n边种菜。这段经历大约有两年。";

    private static String squeeze(String s) {
        return s.replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("真实长句：不再把「农民」「带领」劈开，且每段都落在标点后")
    void realWorldLongSentenceIsNotBrokenMidWord() {
        List<String> segments = TextSegmenter.segment(REAL_BOOK_TEXT);

        // 上限从 30 抬到 45 后段数从 5 变 4（每段更长、接缝更少）——段数由本常量决定，
        // 这条断言的作用是「改动常量时立刻看见影响」，不是「永远等于 5」
        assertThat(segments).hasSize(4);

        // 改前这两句分别是「…放劳动，和农」与「农民…」——被劈开的直接证据
        assertThat(segments).noneMatch(s -> s.endsWith("和农"));
        assertThat(segments).noneMatch(s -> s.startsWith("民的孩"));
        assertThat(segments).noneMatch(s -> s.endsWith("老师的带"));

        // 每一段都以标点收尾（句末标点或次级停顿标点）
        assertThat(segments).allMatch(s -> "。！？.!?，、；：,;".indexOf(s.charAt(s.length() - 1)) >= 0);

        // 不丢字、不改字：拆段只换落刀点
        assertThat(squeeze(String.join("", segments))).isEqualTo(squeeze(REAL_BOOK_TEXT));

        // 这一段原先断言「第 1/2 段的具体边界」，但边界由 DEFAULT_MAX_LENGTH 决定：
        // 上限从 30 抬到 45 后边界必然右移。真正的意图是「不劈开词、落刀点在标点上」，
        // 已由上面的 noneMatch + 标点收尾两条锁住，故不再写死具体下标。
    }

    @Test
    @DisplayName("不丢字：所有段落去空白后拼接 == 原文去空白")
    void neverLosesCharacters() {
        assertThat(squeeze(String.join("", TextSegmenter.segment(REAL_BOOK_TEXT))))
                .isEqualTo(squeeze(REAL_BOOK_TEXT));

        String noPunctuation = "甲".repeat(70);
        assertThat(squeeze(String.join("", TextSegmenter.segment(noPunctuation))))
                .isEqualTo(noPunctuation);

        String mixed = "长短不一。这是一句很长很长的话，里面有好几个逗号，依旧超过上限需要被切开，"
                + "切成若干段才符合引擎要求。最后一句收尾。";
        assertThat(squeeze(String.join("", TextSegmenter.segment(mixed))))
                .isEqualTo(squeeze(mixed));
    }

    @Test
    @DisplayName("任何段落长度都不超过上限")
    void neverExceedsMaxLength() {
        for (String s : TextSegmenter.segment(REAL_BOOK_TEXT)) {
            assertThat(s.length()).isLessThanOrEqualTo(TextSegmenter.DEFAULT_MAX_LENGTH);
        }
        String longWord = "乙".repeat(301);
        for (String s : TextSegmenter.segment(longWord)) {
            assertThat(s.length()).isLessThanOrEqualTo(TextSegmenter.DEFAULT_MAX_LENGTH);
        }
    }

    @Test
    @DisplayName("窗口内没有标点时可退回硬切，不会死循环、也不会把段落切成 0 字")
    void fallsBackToHardCutWhenNoPunctuationInWindow() {
        // 文本长度按常量构造：2 段满 + 10 字尾段 ⇒ 无论常量调到多少，都应恰好切出 3 段
        int max = TextSegmenter.DEFAULT_MAX_LENGTH;
        List<String> segments = TextSegmenter.segment("丙".repeat(max * 2 + 10));
        assertThat(segments).hasSize(3);
        assertThat(segments).allMatch(s -> !s.isEmpty());
        assertThat(segments.get(0)).hasSize(max);
        assertThat(segments.get(1)).hasSize(max);
        assertThat(segments.get(2)).hasSize(10);
    }

    @Test
    @DisplayName("标点太靠左（早于上限的 60%）时按上限硬切，避免把段落切得过碎")
    void ignoresTooEarlyPunctuation() {
        // 逗号在第 3 个字符处，远早于 30×0.6=18 —— 不应在那儿落刀，否则长句会碎成一堆短段
        String text = "甲，乙" + "丙".repeat(60);
        List<String> segments = TextSegmenter.segment(text);
        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0).length())
                .as("首段不该只切出 3 个字")
                .isGreaterThan((int) (TextSegmenter.DEFAULT_MAX_LENGTH * 0.6));
    }

    @Test
    @DisplayName("短句不动：贪心拼装与句末标点切分行为保持不变")
    void shortSentencesArePackedAsBefore() {
        assertThat(TextSegmenter.segment("")).isEmpty();
        assertThat(TextSegmenter.segment(null)).isEmpty();
        assertThat(TextSegmenter.segment("第一句。第二句。"))
                .containsExactly("第一句。第二句。");
        // 超过上限才拆，刚好等于上限时不拆
        String justFits = "丁".repeat(TextSegmenter.DEFAULT_MAX_LENGTH);
        assertThat(TextSegmenter.segment(justFits)).containsExactly(justFits);
    }

    @Test
    @DisplayName("自定义上限同样生效（落刀点下限按比例缩放）")
    void honoursCustomMaxLength() {
        List<String> segments = TextSegmenter.segment("戊".repeat(45), 10);
        assertThat(segments).allMatch(s -> s.length() <= 10);
        assertThat(squeeze(String.join("", segments))).isEqualTo("戊".repeat(45));

        // 上限 10 时下限是 6：逗号在第 8 位，应当在此落刀
        List<String> withPunct = TextSegmenter.segment("己".repeat(7) + "，" + "庚".repeat(20), 10);
        assertThat(withPunct.get(0)).isEqualTo("己".repeat(7) + "，");

        // 取区间内**最靠右**的标点：逗号在第 8、10 位，应选第 10 位（把段落喂满）
        List<String> rightMost = TextSegmenter.segment("辛".repeat(7) + "，辛，辛" + "辛".repeat(20), 10);
        assertThat(rightMost.get(0)).isEqualTo("辛".repeat(7) + "，辛，");
    }

    @Test
    @DisplayName("hasSpeakableContent：只认字母与数字，下划线/空白/纯标点都不算")
    void detectsSpeakableContent() {
        // 事故输入：书名页那条 63 字符下划线分隔线被切出来的小片
        assertThat(TextSegmenter.hasSpeakableContent("_".repeat(30))).isFalse();
        assertThat(TextSegmenter.hasSpeakableContent("_".repeat(63))).isFalse();
        assertThat(TextSegmenter.hasSpeakableContent(" ".repeat(30))).isFalse();
        assertThat(TextSegmenter.hasSpeakableContent("\u3000")).isFalse();   // 全角空格
        assertThat(TextSegmenter.hasSpeakableContent("")).isFalse();
        assertThat(TextSegmenter.hasSpeakableContent(null)).isFalse();
        // 纯装饰线/纯标点：上游要么清成空（点线），要么只换回一声「。」，都不该送引擎
        assertThat(TextSegmenter.hasSpeakableContent(".".repeat(40))).isFalse();
        assertThat(TextSegmenter.hasSpeakableContent("——")).isFalse();
        assertThat(TextSegmenter.hasSpeakableContent("，。！？")).isFalse();

        // 正常文本一律认得：汉字／假名／拉丁／数字（含全角）
        assertThat(TextSegmenter.hasSpeakableContent("潜规则")).isTrue();
        assertThat(TextSegmenter.hasSpeakableContent("吴思，男，1957 年 5 月生于北京。")).isTrue();
        assertThat(TextSegmenter.hasSpeakableContent("ひらがな")).isTrue();
        assertThat(TextSegmenter.hasSpeakableContent("TTS")).isTrue();
        assertThat(TextSegmenter.hasSpeakableContent("１２３")).isTrue();
        // 片内只要存在一个可朗读字符就保留 —— 过滤不能把夹在标点里的正文一起丢掉
        assertThat(TextSegmenter.hasSpeakableContent("___吴___")).isTrue();
    }

    @Test
    @DisplayName("事故复现：书名页下划线分隔线切出的小片，无一含可朗读内容")
    void everyPieceOfUnderscoreRulerIsUnspeakable() {
        List<String> pieces = TextSegmenter.subPieces("_".repeat(63));
        assertThat(pieces).isNotEmpty();
        assertThat(pieces).allMatch(piece -> !TextSegmenter.hasSpeakableContent(piece));
    }
}
