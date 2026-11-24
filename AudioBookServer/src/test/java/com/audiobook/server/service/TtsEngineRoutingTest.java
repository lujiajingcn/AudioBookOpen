package com.audiobook.server.service;

import com.audiobook.server.model.TtsEngineType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引擎路由判定测试（{@link VoiceCloningTtsService#decideEngineType}）。
 *
 * <p>守住的核心回归：<b>中文书配了 Pocket 音色时必须仍走 MOSS</b>。
 *
 * <p>起因：{@code voiceId} 判据原本写在语种判据之后，是为了给运营留「不依赖语种识别、
 * 手动指定音色」的逃生门。但运营把 Pocket 音色（{@code alba}）配进下拉后，用户给一本中文书
 * 选上它，整本书就会绕过语种判定被送进<b>英文模型</b>：音频念不出字、积分照扣，而任务状态
 * 还是 {@code completed} —— 事后从数据上完全看不出异常。所以「中文 → MOSS」必须短路在
 * voiceId 判据之前，且这条顺序不能靠注释维持，要用测试钉住。
 *
 * <p>同时验证 {@link VoiceCloningTtsService#mossVoiceFor}：中文改走 MOSS 后，那个 Pocket
 * 音色 id 不能原样下发 —— 线上 MOSS 对未知 {@code demo_id} 直接 400
 * （{@code {"error":"Unknown demo_id: alba"}}），否则「产出乱码音频」会被换成「整本每段都失败」。
 */
class TtsEngineRoutingTest {

    private static final Set<String> POCKET_VOICES = Set.of("alba", "marius", "jean");
    /** 运营已为英文配好 Pocket 音色（线上现配：en:alba） */
    private static final Map<String, String> LANG_VOICES_EN = Map.of("en", "alba");

    private static TtsEngineType decide(boolean pocketEnabled, String lang, Map<String, String> langVoices,
                                        Set<String> pocketVoices, String voiceId) {
        return VoiceCloningTtsService.decideEngineType(pocketEnabled, lang, langVoices, pocketVoices, voiceId);
    }

    @Test
    @DisplayName("Pocket 总开关关闭时，任何语种/音色都走 MOSS")
    void disabledPocketAlwaysFallsBackToMoss() {
        assertThat(decide(false, "en", LANG_VOICES_EN, POCKET_VOICES, "alba")).isEqualTo(TtsEngineType.MOSS);
        assertThat(decide(false, "zh", Map.of(), POCKET_VOICES, "alba")).isEqualTo(TtsEngineType.MOSS);
        assertThat(decide(false, "zh", Map.of(), POCKET_VOICES, "demo-1")).isEqualTo(TtsEngineType.MOSS);
    }

    @Test
    @DisplayName("核心回归：中文 + Pocket 音色 → 必须仍走 MOSS（voiceId 不得绕过语种判定）")
    void chineseWithPocketVoiceStaysOnMoss() {
        assertThat(decide(true, "zh", LANG_VOICES_EN, POCKET_VOICES, "alba")).isEqualTo(TtsEngineType.MOSS);
        // 大小写/空白不影响判定（客户端可能原样回传用户输入）
        assertThat(decide(true, "zh", LANG_VOICES_EN, POCKET_VOICES, " ALBA ")).isEqualTo(TtsEngineType.MOSS);
    }

    @Test
    @DisplayName("即使误把 zh 配进 language-voices，中文也恒走 MOSS")
    void chineseCannotBeConfiguredOntoPocket() {
        assertThat(decide(true, "zh", Map.of("zh", "alba"), POCKET_VOICES, null))
                .isEqualTo(TtsEngineType.MOSS);
    }

    @Test
    @DisplayName("中文 + MOSS 预置音色 / 无音色（克隆）→ MOSS")
    void chineseNormalPathsStayOnMoss() {
        assertThat(decide(true, "zh", LANG_VOICES_EN, POCKET_VOICES, "demo-1")).isEqualTo(TtsEngineType.MOSS);
        assertThat(decide(true, "zh", LANG_VOICES_EN, POCKET_VOICES, null)).isEqualTo(TtsEngineType.MOSS);
        assertThat(decide(true, "zh", LANG_VOICES_EN, POCKET_VOICES, "   ")).isEqualTo(TtsEngineType.MOSS);
    }

    @Test
    @DisplayName("Pocket 可路由语种且已配音色 → POCKET（原有能力不回退）")
    void routableLanguageWithConfiguredVoiceGoesPocket() {
        assertThat(decide(true, "en", LANG_VOICES_EN, POCKET_VOICES, null)).isEqualTo(TtsEngineType.POCKET);
        assertThat(decide(true, "en", LANG_VOICES_EN, POCKET_VOICES, "marius")).isEqualTo(TtsEngineType.POCKET);
    }

    @Test
    @DisplayName("手动指定 Pocket 音色仍可走 POCKET（逃生门保留：语种未配音色时）")
    void manualPocketVoiceStillOverridesForNonChinese() {
        assertThat(decide(true, "fr", Map.of(), POCKET_VOICES, "alba")).isEqualTo(TtsEngineType.POCKET);
        // other（日/韩/俄等 Pocket 不支持、判不准）不出声时也不靠语种判，只有手动选了才分流
        assertThat(decide(true, "other", Map.of(), POCKET_VOICES, "alba")).isEqualTo(TtsEngineType.POCKET);
    }

    @Test
    @DisplayName("语种可路由但没配音色 → 回落 MOSS（不发往取不到音色的节点）")
    void routableLanguageWithoutVoiceFallsBackToMoss() {
        assertThat(decide(true, "fr", Map.of(), POCKET_VOICES, null)).isEqualTo(TtsEngineType.MOSS);
        assertThat(decide(true, "other", Map.of(), POCKET_VOICES, null)).isEqualTo(TtsEngineType.MOSS);
        assertThat(decide(true, "other", Map.of(), POCKET_VOICES, "demo-3")).isEqualTo(TtsEngineType.MOSS);
    }

    @Test
    @DisplayName("空 Pocket 音色清单时，所有 voiceId 都按 MOSS 音色原样透传")
    void emptyPocketSetKeepsVoiceUntouched() {
        assertThat(VoiceCloningTtsService.mossVoiceFor("alba", Set.of(), "demo-1")).isEqualTo("alba");
        assertThat(VoiceCloningTtsService.mossVoiceFor("alba", null, "demo-1")).isEqualTo("alba");
    }

    @Test
    @DisplayName("MOSS 音色 / 空音色不被替换")
    void mossVoicesAndEmptyAreUntouched() {
        assertThat(VoiceCloningTtsService.mossVoiceFor("demo-1", POCKET_VOICES, "demo-2")).isEqualTo("demo-1");
        assertThat(VoiceCloningTtsService.mossVoiceFor("  demo-7 ", POCKET_VOICES, "demo-2")).isEqualTo("demo-7");
        assertThat(VoiceCloningTtsService.mossVoiceFor(null, POCKET_VOICES, "demo-2")).isNull();
        assertThat(VoiceCloningTtsService.mossVoiceFor("", POCKET_VOICES, "demo-2")).isNull();
        assertThat(VoiceCloningTtsService.mossVoiceFor("   ", POCKET_VOICES, "demo-2")).isNull();
    }

    @Test
    @DisplayName("Pocket 音色必须换成兜底音色（否则 MOSS 回 400 Unknown demo_id）")
    void pocketVoiceIsReplacedByFallbackForMoss() {
        assertThat(VoiceCloningTtsService.mossVoiceFor("alba", POCKET_VOICES, "demo-2")).isEqualTo("demo-2");
        assertThat(VoiceCloningTtsService.mossVoiceFor(" ALBA ", POCKET_VOICES, "demo-2")).isEqualTo("demo-2");
        // 兜底音色没配（空/空白）时不能下发空 demo_id —— MOSS 会回
        // "demo_id is required unless prompt speech is uploaded"
        assertThat(VoiceCloningTtsService.mossVoiceFor("alba", POCKET_VOICES, null)).isEqualTo("demo-1");
        assertThat(VoiceCloningTtsService.mossVoiceFor("alba", POCKET_VOICES, "  ")).isEqualTo("demo-1");
    }
}
