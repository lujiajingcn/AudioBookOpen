package com.audiobook.server.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「业务异常不许被兜底 catch 吞掉」的源码级回归测试。
 *
 * <p><b>为什么需要它</b>：{@code GlobalExceptionHandler.handleIllegalArgument} 能把
 * {@code IllegalArgumentException} 的业务文案原样回显成 400 —— 但那只在异常
 * <em>逃出 Controller</em> 时才生效。{@code TtsTaskController} 里两个建任务端点
 * （{@code /upload-book}、{@code /from-store}）原先都有一个宽泛的
 * {@code catch (Exception e)} 兜底分支，异常根本逃不出去：
 * 「当前合成节点要求参考音频（prompt_audio），无法只用预设音色合成」被压成
 * 500「服务器错误，请稍后重试」，客户端只看到一句毫无操作指引的话。
 *
 * <p>这个 bug 极难靠肉眼发现：{@code handleIllegalArgument} 明明写对了，
 * 单测也全绿，只是 Controller 少了一个 catch 分支。
 * 所以这里用**源码结构断言**把「catch 顺序」钉死：
 * {@code IllegalArgumentException} 的 catch 必须排在 {@code catch (Exception)} 之前。
 *
 * <p>⚠️ 断言的是**源码文本**而非运行时行为——因为 {@code uploadBookForTts} 是个
 * 依赖 8 个构造参数的 Controller，直接实例化做 MockMvc 测试的成本远高于收益；
 * 而这里要防的恰恰是「结构」而非「某个返回值」。
 */
class TtsTaskControllerExceptionOrderTest {

    /** 读取 Controller 源码（相对本测试类的包路径定位到 main 源码树）。 */
    private static String sourceOf(String simpleName) throws IOException {
        Path path = Path.of("src", "main", "java", "com", "audiobook", "server", "controller", simpleName + ".java");
        assertThat(Files.exists(path))
                .as("找不到源码文件 %s（工作目录应为 AudioBookServer 模块根）", path)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 在源码里定位某个方法的源码片段。
     *
     * @param methodSignature 方法签名中用于定位的唯一片段
     * @return 方法体（含大括号配平）文本
     */
    private static String methodBody(String source, String methodSignature) {
        int sigIdx = source.indexOf(methodSignature);
        assertThat(sigIdx)
                .as("源码里找不到方法签名：%s", methodSignature)
                .isGreaterThanOrEqualTo(0);

        // 从签名处的第一个 '{' 开始，按大括号配平截出方法体
        int braceStart = source.indexOf('{', sigIdx);
        assertThat(braceStart).as("方法签名后应有 '{'：%s", methodSignature).isGreaterThanOrEqualTo(0);

        int depth = 0;
        for (int i = braceStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(sigIdx, i + 1);
                }
            }
        }
        throw new AssertionError("方法体大括号未配平：" + methodSignature);
    }

    /** 找到 catch (XxxException) 的位置；没有则返回 -1。 */
    private static int indexOfCatch(String methodBodyText, String exceptionType) {
        Matcher m = Pattern.compile("catch\\s*\\(\\s*" + Pattern.quote(exceptionType) + "\\s+\\w+\\s*\\)")
                .matcher(methodBodyText);
        return m.find() ? m.start() : -1;
    }

    @Test
    @DisplayName("upload-book：IllegalArgumentException 的 catch 必须排在 catch (Exception) 之前")
    void uploadBookCatchesIaeBeforeGenericException() throws IOException {
        String source = sourceOf("TtsTaskController");
        String body = methodBody(source, "public ResponseEntity<Map<String, Object>> uploadBookForTts(");

        int iae = indexOfCatch(body, "IllegalArgumentException");
        int generic = indexOfCatch(body, "Exception");

        assertThat(iae)
                .as("uploadBookForTts 必须单独 catch IllegalArgumentException，"
                        + "否则业务文案会被兜底分支压成 500「服务器错误」")
                .isGreaterThanOrEqualTo(0);
        assertThat(generic)
                .as("uploadBookForTts 应保留 catch (Exception) 兜底")
                .isGreaterThanOrEqualTo(0);
        assertThat(iae)
                .as("catch (IllegalArgumentException) 必须排在 catch (Exception) 之前 —— "
                        + "Java 按声明顺序匹配，先声明的先生效")
                .isLessThan(generic);
    }

    @Test
    @DisplayName("from-store：IllegalArgumentException 的 catch 必须排在 catch (Exception) 之前")
    void fromStoreCatchesIaeBeforeGenericException() throws IOException {
        String source = sourceOf("TtsTaskController");
        String body = methodBody(source, "public ResponseEntity<Map<String, Object>> createTtsFromStore(");

        int iae = indexOfCatch(body, "IllegalArgumentException");
        int generic = indexOfCatch(body, "Exception");

        assertThat(iae)
                .as("createTtsFromStore 必须单独 catch IllegalArgumentException")
                .isGreaterThanOrEqualTo(0);
        assertThat(generic)
                .as("createTtsFromStore 应保留 catch (Exception) 兜底")
                .isGreaterThanOrEqualTo(0);
        assertThat(iae)
                .as("catch (IllegalArgumentException) 必须排在 catch (Exception) 之前")
                .isLessThan(generic);
    }

    @Test
    @DisplayName("两个端点都必须保留 InsufficientCreditsException 的独立分支（积分不足不能变 500）")
    void creditsExceptionKeepsItsOwnBranch() throws IOException {
        String source = sourceOf("TtsTaskController");

        for (String signature : new String[]{
                "public ResponseEntity<Map<String, Object>> uploadBookForTts(",
                "public ResponseEntity<Map<String, Object>> createTtsFromStore("}) {
            String body = methodBody(source, signature);
            assertThat(indexOfCatch(body, "InsufficientCreditsException"))
                    .as("%s 必须单独 catch InsufficientCreditsException", signature)
                    .isGreaterThanOrEqualTo(0);
        }
    }
}
