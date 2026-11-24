package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「预置音色清单必须与引擎真实契约对齐」的回归测试。
 *
 * <p><b>为什么必须有</b>：线上 MOSS-TTS-Nano 的预置音色叫 <b>demo</b>
 * （{@code assets/demo.jsonl}，共 demo-1…demo-29），引擎的 {@code /api/generate}
 * 只认 {@code demo_id} 字段。服务端原先配的是<b>云端 MOSS API</b> 的音色 id
 * （{@code xiaoyan / xiaoyu / aisjiuxu …}）并用 {@code voice_id} 字段下发 ——
 * 两处都不被引擎承认，于是：
 * <ol>
 *   <li>引擎把 {@code voice_id} 当未知字段忽略，随后报
 *       {@code demo_id is required unless prompt speech is uploaded}（HTTP 400）；</li>
 *   <li>服务端又把这个业务异常吞进了兜底 {@code catch (Exception)}，
 *       变成 500「服务器错误，请稍后重试」，用户完全看不懂。</li>
 * </ol>
 *
 * <p>这两处都是**配置值**，改错了不报编译错、单测也全绿，只在真机上表现为
 * 「在线合成必失败」。所以这里把配置值本身钉住。
 *
 * <p><b>为什么用 YAML 解析器而不是正则</b>：{@code application.yml} 里有 7 处
 * {@code enabled:}，正则按行匹配会抓到第一个（{@code MOSS_TTS_FRAMES_ENABLED}）
 * 而不是目标那个。这里交给 Spring 的 {@link YamlPropertiesFactoryBean} 解析，
 * 按完整路径取值，天然无歧义。
 *
 * <p><b>为什么读源码而不是读 class 常量池</b>：常量池里 {@code "demo-1"} 这类
 * 字面量散落各处，正则匹配到的第一个未必是 {@code @Value} 里的默认值
 * （实测会先匹配到单个 {@code demo-1}）。源码里的 {@code @Value} 注解是唯一的。
 */
class TtsPresetVoiceContractTest {

    /** 用 Spring 的 YAML 解析器加载 application.yml。 */
    private static Properties applicationYaml() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties props = factory.getObject();
        assertThat(props).as("application.yml 应能被解析").isNotNull();
        return props;
    }

    /** 按完整路径取值，如 {@code app.tts.voice-only.enabled}。 */
    private static String yamlValue(Properties props, String path) {
        Object value = props.get(path);
        assertThat(value)
                .as("application.yml 里应存在配置项 %s", path)
                .isNotNull();
        return String.valueOf(value);
    }

    /** 读取 Controller 源码。 */
    private static String controllerSource() throws IOException {
        Path path = Path.of("src", "main", "java", "com", "audiobook", "server", "controller",
                "TtsTaskController.java");
        assertThat(Files.exists(path))
                .as("找不到 %s（工作目录应为 AudioBookServer 模块根）", path)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 读取 Service 源码。 */
    private static String serviceSource() throws IOException {
        Path path = Path.of("src", "main", "java", "com", "audiobook", "server", "service",
                "VoiceCloningTtsService.java");
        assertThat(Files.exists(path))
                .as("找不到 %s（工作目录应为 AudioBookServer 模块根）", path)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 取出 {@code ${ENV:default}} 形态配置里的默认值部分。 */
    private static String defaultOf(String raw) {
        Matcher m = Pattern.compile("\\$\\{[^:}]+:([^}]*)}").matcher(raw);
        return m.find() ? m.group(1).trim() : raw;
    }

    @Test
    @DisplayName("预置音色字段名必须是 demo_id（引擎只认这个名）")
    void voiceParamIsDemoId() {
        String param = defaultOf(yamlValue(applicationYaml(), "moss.tts.voice-param"));
        assertThat(param)
                .as("线上 MOSS-TTS-Nano 的预置音色字段是 demo_id；传 voice_id 会被忽略，"
                        + "引擎随后报 demo_id is required（HTTP 400）")
                .isEqualTo("demo_id");
    }

    @Test
    @DisplayName("预置音色清单里的每个 id 都必须是 demo-N 形态")
    void voiceIdsAreDemoShaped() {
        String voices = defaultOf(yamlValue(applicationYaml(), "moss.tts.voices"));
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(voices.split(",")));
        ids.removeIf(String::isBlank);

        assertThat(ids).as("预置音色清单不应为空").isNotEmpty();
        for (String id : ids) {
            assertThat(id)
                    .as("音色 id %s 不是 demo-N 形态 —— 引擎的预置音色来自 assets/demo.jsonl，"
                            + "只认 demo-1…demo-29；云端 API 的 xiaoyan 之类引擎根本不存在", id)
                    .matches("demo-\\d+");
        }
    }

    @Test
    @DisplayName("Controller 的 @Value 默认值与 yml 一致（yml 缺失时行为也不能漂）")
    void controllerDefaultMatchesYaml() throws IOException {
        String yamlDefault = defaultOf(yamlValue(applicationYaml(), "moss.tts.voices"));

        Matcher m = Pattern.compile("@Value\\(\"\\$\\{moss\\.tts\\.voices:([^}]*)}\"\\)")
                .matcher(controllerSource());
        assertThat(m.find())
                .as("TtsTaskController 源码里应存在 @Value(\"${moss.tts.voices:...}\")")
                .isTrue();

        assertThat(m.group(1).trim())
                .as("Controller 的 @Value 默认值与 application.yml 不一致 —— "
                        + "yml 缺失/未加载时行为会漂到另一份清单")
                .isEqualTo(yamlDefault);
    }

    @Test
    @DisplayName("仅预设音色通道默认开启（引擎已实测支持：text + demo_id 返回 200）")
    void voiceOnlyEnabledByDefault() {
        String value = defaultOf(yamlValue(applicationYaml(), "app.tts.voice-only.enabled"));
        assertThat(value)
                .as("线上引擎已实测支持纯音色通道（text + demo_id → 200 + WAV），"
                        + "voice-only 开关应默认开启，否则「仅预设音色」在建任务阶段就被拒")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("Service 的 voice-param 默认值也是 demo_id")
    void serviceDefaultParamIsDemoId() throws IOException {
        Matcher m = Pattern.compile("@Value\\(\"\\$\\{moss\\.tts\\.voice-param:([^}]*)}\"\\)")
                .matcher(serviceSource());
        assertThat(m.find()).as("VoiceCloningTtsService 源码里应存在 voice-param 的 @Value").isTrue();
        assertThat(m.group(1).trim())
                .as("Service 的 voice-param 默认值必须是 demo_id —— 它是真正下发到引擎的那个字段名")
                .isEqualTo("demo_id");
    }

    @Test
    @DisplayName("Service 的 voice-only 默认值也是 true")
    void serviceDefaultVoiceOnlyEnabled() throws IOException {
        Matcher m = Pattern.compile("@Value\\(\"\\$\\{app\\.tts\\.voice-only\\.enabled:([^}]*)}\"\\)")
                .matcher(serviceSource());
        assertThat(m.find()).as("VoiceCloningTtsService 源码里应存在 voice-only.enabled 的 @Value").isTrue();
        assertThat(m.group(1).trim())
                .as("Service 的 voice-only 默认值应为 true（与 yml 保持一致）")
                .isEqualTo("true");
    }

    @Test
    @DisplayName("yml 与源码里的 voices 清单完全一致")
    void bothSourcesParseToIds() throws IOException {
        String yamlDefault = defaultOf(yamlValue(applicationYaml(), "moss.tts.voices"));

        Matcher m = Pattern.compile("@Value\\(\"\\$\\{moss\\.tts\\.voices:([^}]*)}\"\\)")
                .matcher(controllerSource());
        assertThat(m.find()).isTrue();

        List<String> yamlIds = new ArrayList<>(Arrays.asList(yamlDefault.split(",")));
        List<String> codeIds = new ArrayList<>(Arrays.asList(m.group(1).trim().split(",")));
        yamlIds.removeIf(String::isBlank);
        codeIds.removeIf(String::isBlank);

        assertThat(yamlIds).isNotEmpty();
        assertThat(codeIds).isNotEmpty();
        assertThat(codeIds).as("yml 与源码的音色清单应完全一致").isEqualTo(yamlIds);
    }
}
