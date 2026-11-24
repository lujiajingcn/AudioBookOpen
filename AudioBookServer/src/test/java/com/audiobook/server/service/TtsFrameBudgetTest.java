package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code max_new_frames} 预算算法测试。
 *
 * <p>这套算法把手写死的「一律 375 帧（≈30 秒音频）」换成按文本长度算出的值，
 * 因此测试要守住两条边界，任一条破了都会造成线上事故：
 * <ul>
 *   <li><b>下限</b>：短句不能被压到几帧 —— 那样生成出来的音频会缺头少尾；</li>
 *   <li><b>上限</b>：任何长度都不得超过引擎默认值 375 帧 —— 保证生成的音频长度
 *       不会比改造前更短，绝不能出现「长文本被截断」的回归。</li>
 * </ul>
 * 同时验证「给足余量」：正常语速下估算值必须高于按实际语速所需的帧数。
 */
class TtsFrameBudgetTest {

    /** 与 application.yml 的 moss.tts.frames 默认值保持一致 */
    private static final double PER_CHAR = 4.0;
    private static final int BASE = 30;
    private static final int MIN = 60;
    private static final int MAX = 375;

    /** 引擎帧率：375 帧 ≈ 30 秒 */
    private static final double FPS = VoiceCloningTtsService.ENGINE_FRAMES_PER_SECOND;

    /** 上限开始生效的字符数：30 + 4×chars > 375 → chars > 86.25 */
    private static final int FIRST_CAPPED_LENGTH = 87;

    private static int budget(int textLength) {
        long desired = VoiceCloningTtsService.estimateFrames(textLength, PER_CHAR, BASE);
        return VoiceCloningTtsService.clampFrames(desired, MIN, MAX);
    }

    @Test
    @DisplayName("估算值 = base + 字符数 × per-char，向上取整")
    void estimateFollowsFormula() {
        assertThat(VoiceCloningTtsService.estimateFrames(0, PER_CHAR, BASE)).isEqualTo(BASE);
        assertThat(VoiceCloningTtsService.estimateFrames(10, PER_CHAR, BASE)).isEqualTo(70);
        assertThat(VoiceCloningTtsService.estimateFrames(62, PER_CHAR, BASE)).isEqualTo(278);
        // 取整方向必须是向上：少给帧 = 截断音频，多给帧只是多花点时间
        assertThat(VoiceCloningTtsService.estimateFrames(1, 3.5, 30)).isEqualTo(34);
    }

    @Test
    @DisplayName("下限生效：空文本与极短句都拿到 MIN 帧，不会被压到缺头少尾")
    void shortTextNeverFallsBelowMinimum() {
        assertThat(budget(0)).isEqualTo(MIN);
        assertThat(budget(1)).isEqualTo(MIN);
        assertThat(budget(5)).isEqualTo(MIN);
    }

    @Test
    @DisplayName("上限生效：任意长度都不超过引擎默认值 375 帧（不引入长文本截断回归）")
    void neverExceedsEngineDefault() {
        assertThat(budget(87)).isEqualTo(MAX);
        assertThat(budget(100)).isEqualTo(MAX);
        assertThat(budget(500)).isEqualTo(MAX);
        assertThat(budget(100_000)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("帧数随文本长度线性增长，并在 375 帧处夹取（含远超上限的极端值）")
    void realisticSegments() {
        // 20 字短句：110 帧（≈8.8 秒）——比原来的 375 帧省掉约 70% 的生成量
        assertThat(budget(20)).isEqualTo(110);
        // 62 字（线上日志里出现过的真实段落）：278 帧（≈22 秒），比原来的 375 帧省约 26%
        assertThat(budget(62)).isEqualTo(278);
        // 100 字（远超单次上限 30 的极端值，只用于验证夹取）：430 → 夹到 375
        assertThat(budget(100)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("余量充足：5 字/秒（偏快）下 1~100 字全部够用")
    void leavesHeadroomOverFastSpeakingRate() {
        for (int chars = 1; chars <= 100; chars++) {
            assertHeadroom(chars, 5.0);
        }
    }

    @Test
    @DisplayName("余量充足：3.2 字/秒（偏慢）下，上限未生效的区间全部够用")
    void leavesHeadroomOverSlowSpeakingRate() {
        for (int chars = 1; chars < FIRST_CAPPED_LENGTH; chars++) {
            assertHeadroom(chars, 3.2);
        }
    }

    @Test
    @DisplayName("上限生效的最坏情况就是改造前的行为（不会更差）")
    void cappedWorstCaseEqualsLegacyBehavior() {
        // 100 字按 3.2 字/秒（很慢的语速）需要 390 帧，超过 375 的上限 ——
        // 这种情况改造前同样是 375 帧，因此最多只是「和以前一样可能偏短」，绝不会更短。
        // 算法在这里只能把它记进日志提醒调大上限，不能悄悄把上限抬高
        // （抬高上限会同步抬高单段耗时与内存，那是要人工评估的取舍）。
        double neededFrames = 12.5 * 100 / 3.2;
        assertThat(neededFrames).isGreaterThan(MAX);
        assertThat(budget(100)).isEqualTo(MAX);
    }

    /** 断言 chars 个字符按 charsPerSecond 的语速朗读时，批准帧数折算出的时长够用 */
    private static void assertHeadroom(int chars, double charsPerSecond) {
        double neededSeconds = chars / charsPerSecond;
        double grantedSeconds = budget(chars) / FPS;
        assertThat(grantedSeconds)
                .as("%d 字按 %.1f 字/秒需 %.2f 秒，批准了 %.2f 秒", chars, charsPerSecond, neededSeconds, grantedSeconds)
                .isGreaterThanOrEqualTo(neededSeconds);
    }
}
