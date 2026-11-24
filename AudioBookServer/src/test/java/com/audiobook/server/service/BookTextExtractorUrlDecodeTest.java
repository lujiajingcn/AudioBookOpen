package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link BookTextExtractor#safeUrlDecode(String)} 的回归测试（v5 / S6）。
 *
 * <p><b>为什么必须有</b>：EPUB 的 {@code href} 里出现非法 {@code %} 转义是常态
 * （手工拼的 manifest 很常见）。裸调 {@code URLDecoder.decode} 会抛
 * {@code IllegalArgumentException}，一路逃到全局处理器，客户端最终看到的是
 * `URLDecoder: Illegal hex characters in escape (%) pattern` 这种 JDK 内部文案 ——
 * 用户既看不懂、也无法据此操作。
 *
 * <p>本测试把这三种情况钉死：**合法编码要解码、非法编码要原样保留、绝不能抛**。
 */
class BookTextExtractorUrlDecodeTest {

    @Test
    @DisplayName("合法 % 编码正常解码（回归：别为了容错把正常路径也一起废掉）")
    void decodesValidPercentEncoding() {
        assertThat(BookTextExtractor.safeUrlDecode("%E4%B8%AD%E6%96%87.xhtml"))
                .isEqualTo("中文.xhtml");
        // 未编码的普通路径原样返回
        assertThat(BookTextExtractor.safeUrlDecode("OEBPS/ch1.xhtml"))
                .isEqualTo("OEBPS/ch1.xhtml");
    }

    @Test
    @DisplayName("非法 % 转义不抛异常、按原样返回（这正是 S6 的病灶）")
    void illegalEscapeFallsBackToRawText() {
        for (String bad : new String[]{
                "ch1%zz.xhtml",          // 非十六进制
                "ch1%2.xhtml",           // 只有一位
                "ch1%",                  // 结尾孤立 %
                "100%纯天然/ch.xhtml",    // 中文书名里带裸百分号
        }) {
            assertThatCode(() -> BookTextExtractor.safeUrlDecode(bad))
                    .as("非法转义 %s 绝不能抛异常（否则整本书解析失败）", bad)
                    .doesNotThrowAnyException();
            assertThat(BookTextExtractor.safeUrlDecode(bad))
                    .as("非法转义应按原样保留 —— 文件名的真实字节往往就是未编码的那个")
                    .isEqualTo(bad);
        }
    }

    @Test
    @DisplayName("null / 空串按原样返回，不 NPE")
    void handlesNullAndEmpty() {
        assertThat(BookTextExtractor.safeUrlDecode(null)).isNull();
        assertThat(BookTextExtractor.safeUrlDecode("")).isEmpty();
    }
}
