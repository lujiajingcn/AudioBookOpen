package com.audiobook.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application*.yml} 的配置数据健壮性回归测试（不启动 Spring 上下文，成本极低）。
 *
 * <p><b>起因（2026-09-22 生产事故，未遂）</b>：为取消默认环境，把
 * {@code spring.profiles.active: dev} 注释掉时，只注释了子键、却留下了空的
 * {@code spring.profiles:} <b>父键</b>。当时 {@code mvn test} 57/57 全绿 ——
 * 因为测试都是纯 JUnit，<b>不启动 Spring 上下文</b>。打包上线后 Spring Boot 3.2 在
 * {@code EnvironmentPostProcessor} 阶段把值为 null 的 {@code spring.profiles} 判为非法配置数据
 * 属性，直接抛 {@code InvalidConfigDataPropertyException}，<b>服务完全起不来</b>：
 *
 * <pre>
 * Property 'spring.profiles' imported from location
 * 'class path resource [application.yml]' is invalid
 * and should be replaced with 'spring.config.activate.on-profile'   [60:12]
 * </pre>
 *
 * <p>本测试用 SnakeYAML 直接读这几份文件，把「配置文件的键本身是否合法」变成可在 CI 里断言的
 * 事实，补上「单测不启容器」留下的盲区。
 *
 * <p>注意<b>不要</b>顺带断言 {@code application-prod.yml} 里必须有
 * {@code spring.config.activate.on-profile}：Spring Boot 会自动按 {@code -{profile}} 后缀加载
 * 这些文件，并不要求文件内再写激活条件，加上该断言会是个假约束。
 */
class ApplicationYamlConfigTest {

    /** 会被打进 jar 的运行期配置文件 */
    private static final List<String> CONFIG_FILES =
            List.of("application.yml", "application-prod.yml", "application-dev.yml");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String name) throws Exception {
        try (InputStream in = ApplicationYamlConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("classpath 上应能找到 %s", name).isNotNull();
            Object parsed = new Yaml().load(in);
            return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        }
    }

    @Test
    @DisplayName("不得出现 spring.profiles 键：空父键会让 Spring Boot 3.2 拒绝启动")
    void springProfilesKeyMustNotAppear() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String name : CONFIG_FILES) {
            Object spring = load(name).get("spring");
            if (spring instanceof Map<?, ?> m && m.containsKey("profiles")) {
                offenders.add(name + " -> spring.profiles=" + m.get("profiles"));
            }
        }
        assertThat(offenders)
                .as("spring.profiles 已被 Spring Boot 3.2 废弃，值为 null 时启动即抛 "
                        + "InvalidConfigDataPropertyException；要取消默认环境请整段删除该父键，"
                        + "而不是只注释掉 active 子键。冒犯文件：%s", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("application.yml 不得默认激活任何 profile（安全失败：缺 profile 就起不来）")
    void noDefaultActiveProfile() throws Exception {
        Object spring = load("application.yml").get("spring");
        Object active = spring instanceof Map<?, ?> m ? m.get("profiles") : null;
        assertThat(active)
                .as("application.yml 不应再写默认 profile。原先写死 active: dev 会让"
                        + "「生产漏设 SPRING_PROFILES_ACTIVE」时静默按 dev 启动："
                        + "公开的 dev jwt 密钥 + allow-mock 白拿积分 + ddl-auto=update 改生产表结构")
                .isNull();
    }

    /**
     * 句内子句一致性（{@code app.tts.subclause.*}）的配置键必须真实存在于 yml 里。
     *
     * <p><b>为什么值得单测</b>：这些键在 {@code VoiceCloningTtsService} 里全部写成
     * {@code @Value("${...:默认值}")} —— 键名打错或整段被删，<b>Spring 不会报错</b>，
     * 只会静默使用默认值。于是一个「配置项被我改了却没生效」的问题会表现成
     * 「听感没变化」，而排查方向会被引向 DSP 代码而不是配置。
     * 这条用例把「键存在」变成可断言的事实。
     */
    @Test
    @DisplayName("app.tts.subclause.* 的配置键必须真实存在（@Value 有默认值，缺键不会报错）")
    @SuppressWarnings("unchecked")
    void subclauseKeysMustExist() throws Exception {
        Map<String, Object> app = (Map<String, Object>) load("application.yml").get("app");
        Map<String, Object> tts = (Map<String, Object>) app.get("tts");
        Map<String, Object> subclause = (Map<String, Object>) tts.get("subclause");
        assertThat(subclause)
                .as("app.tts.subclause 整段缺失时，@Value 会静默回落到代码里的默认值 —— "
                        + "线上调的配置就白调了")
                .isNotNull();

        // subclause.prosody（句内滚动 prompt）已于 2026-10-30 随滚动 prompt 机制一并删除：
        // 真机 12 组配对实测引擎不经 prompt_audio 传韵律（r=0.00），留着开关只是给未来
        // 留一个「看起来能调、其实无效」的旋钮。再出现说明有人把机制加回来了。
        assertThat(subclause)
                .as("app.tts.subclause.prosody 已删除，不应再出现")
                .doesNotContainKey("prosody");

        Map<String, Object> join = (Map<String, Object>) subclause.get("join");
        assertThat(join)
                .as("app.tts.subclause.join（子句接缝整形）")
                .containsKeys("enabled", "trim-threshold", "head-keep-millis", "tail-keep-millis",
                        "comma-pause-millis", "enumeration-pause-millis", "strong-pause-millis",
                        "hard-cut-pause-millis", "loudness-match", "loudness-max-gain-db",
                        "internal-pause-min-millis", "internal-pause-cap-millis");

        // 停顿必须「顿号 < 逗号 < 分号」，否则插入的停顿会把句读层级颠倒过来
        assertThat(placeholderDefault(join.get("enumeration-pause-millis")))
                .as("顿号停顿应短于逗号停顿")
                .isLessThan(placeholderDefault(join.get("comma-pause-millis")));
        assertThat(placeholderDefault(join.get("comma-pause-millis")))
                .as("逗号停顿应短于分号/冒号停顿")
                .isLessThan(placeholderDefault(join.get("strong-pause-millis")));
        assertThat(placeholderDefault(join.get("hard-cut-pause-millis")))
                .as("硬切（切点在词中间）的停顿必须是四种里最短的，否则等于把词劈开")
                .isLessThanOrEqualTo(placeholderDefault(join.get("enumeration-pause-millis")));
    }

    /**
     * 段级包络整形的默认值必须是 {@code false}。
     *
     * <p><b>为什么值得单测</b>：这条默认值就是「音质会不会被悄悄弄坏」的总闸。2026-10-13
     * 线上验收确认关掉它（以及已删除的滚动 prompt）后音质达标；而它原先默认 {@code true}，
     * 意味着任何没带 {@code APP_TTS_SEGMENT_ENVELOPE_ENABLED} 的环境（本地起服、换机器、
     * 容器重建）都会自动回到已判定有害的路径，且失败模式只是「音质变差」，不报错。
     */
    @Test
    @DisplayName("段级包络整形默认关闭（音质验收结论，不能回退成 true）")
    @SuppressWarnings("unchecked")
    void segmentEnvelopeDefaultsOff() throws Exception {
        Map<String, Object> app = (Map<String, Object>) load("application.yml").get("app");
        Map<String, Object> tts = (Map<String, Object>) app.get("tts");
        Map<String, Object> envelope = (Map<String, Object>) tts.get("segment-envelope");
        assertThat(envelope)
                .as("app.tts.segment-envelope 整段缺失")
                .isNotNull();
        assertThat(String.valueOf(envelope.get("enabled")))
                .as("段级包络整形默认必须为 false —— 与子句接缝整形叠加会把停顿再拉长一次")
                .endsWith(":false}");
    }

    /**
     * 取配置项的<b>默认值</b>并转成整数。
     *
     * <p>yml 里写的是 {@code ${ENV_NAME:130}}，SnakeYAML 解析出来是<b>字符串</b>而不是数字，
     * 所以要自己把冒号后的默认值摘出来。用真正生效的默认值做断言，才能保证
     * 「代码里 @Value 的兜底值」与「yml 里的值」不会各说各话。
     */
    private static int placeholderDefault(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(raw).trim();
        int colon = s.lastIndexOf(':');
        assertThat(colon)
                .as("配置项 %s 应写成 ${ENV:默认值} 形式，便于运维按环境覆盖", s)
                .isGreaterThanOrEqualTo(0);
        String def = s.substring(colon + 1).replace("}", "").trim();
        return Integer.parseInt(def);
    }
}
