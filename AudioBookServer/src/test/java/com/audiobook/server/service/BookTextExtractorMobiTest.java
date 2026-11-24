package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MOBI 正文解压的回归测试。
 *
 * <p>MOBI 正文是 PalmDOC LZ77 压缩的 HTML，藏在 PDB 容器里。解压有两个「长度」约束，
 * 缺一个就会把正文读坏：单条文本记录解压后不超过 recordSize（通常 4096，
 * 压缩流末尾带填充字节），正文总长以 record0 的 textLength 为准（最后一条记录
 * 后面可能还挂着目录、封面标记之类的数据）。
 *
 * <p>两条都缺时，本机 9 本中文 MOBI 实测：解压结果比 textLength 多出几百字节，
 * 且每 4096 字节就有一段非法 UTF-8，服务端 new String(bytes, UTF_8)
 * 会把它们变成 U+FFFD 替换符。
 */
class BookTextExtractorMobiTest {

    /** U+FFFD：非法字节被 UTF-8 解码替换后的痕迹 */
    private static final String REPLACEMENT = String.valueOf((char) 0xFFFD);

    private static byte[] pdbHeader(int numRecords) {
        byte[] head = new byte[78];
        byte[] name = "SELFTEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, head, 0, name.length);
        head[76] = (byte) ((numRecords >> 8) & 0xFF);
        head[77] = (byte) (numRecords & 0xFF);
        return head;
    }

    private static byte[] record0(int compression, int textLength, int recordCount, int recordSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((compression >> 8) & 0xFF);
        out.write(compression & 0xFF);
        out.write(0);
        out.write(0);
        out.write((textLength >> 24) & 0xFF);
        out.write((textLength >> 16) & 0xFF);
        out.write((textLength >> 8) & 0xFF);
        out.write(textLength & 0xFF);
        out.write((recordCount >> 8) & 0xFF);
        out.write(recordCount & 0xFF);
        out.write((recordSize >> 8) & 0xFF);
        out.write(recordSize & 0xFF);
        out.write(0);
        out.write(0);   // encryption type = 0（无加密）
        out.write(0);
        out.write(0);   // unused
        return out.toByteArray();
    }

    private static byte[] buildMobi(byte[][] records, int compression, int textLength, int recordSize) {
        int total = 0;
        for (byte[] record : records) {
            total += record.length;
        }
        byte[] head = record0(compression, textLength < 0 ? total : textLength, records.length, recordSize);

        byte[][] all = new byte[records.length + 1][];
        all[0] = head;
        System.arraycopy(records, 0, all, 1, records.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(pdbHeader(all.length));
        int cursor = 78 + all.length * 8;
        for (byte[] record : all) {
            out.write((cursor >> 24) & 0xFF);
            out.write((cursor >> 16) & 0xFF);
            out.write((cursor >> 8) & 0xFF);
            out.write(cursor & 0xFF);
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(0);
            cursor += record.length;
        }
        for (byte[] record : all) {
            out.writeBytes(record);
        }
        return out.toByteArray();
    }

    /** 只含字面量的 PalmDOC 压缩流（0x01 转义 + 0x09~0x7F 直出）。 */
    private static byte[] compressLiterals(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : data) {
            int v = b & 0xFF;
            if (v == 0x00) {
                out.write(0x00);
            } else if (v >= 0x09 && v <= 0x7F) {
                out.write(v);
            } else {
                out.write(0x01);
                out.write(v);
            }
        }
        return out.toByteArray();
    }

    private static File write(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file.toFile();
    }

    @Test
    @DisplayName("MOBI：不压缩的正文能原样取出")
    void uncompressedTextIsExtracted(@TempDir Path dir) throws IOException {
        String html = "<html><body><p>第一章</p><p>正文内容。</p></body></html>";
        byte[] file = buildMobi(new byte[][]{html.getBytes(StandardCharsets.UTF_8)}, 1, -1, 4096);

        String text = new BookTextExtractor().extractMobi(write(dir, "a.mobi", file));

        assertThat(text).contains("第一章").contains("正文内容。");
        assertThat(text).doesNotContain(REPLACEMENT);
    }

    @Test
    @DisplayName("MOBI：PalmDOC 压缩的正文能正确解压")
    void compressedTextIsExtracted(@TempDir Path dir) throws IOException {
        String html = "<html><body><p>压缩章节</p><p>压缩正文。</p></body></html>";
        byte[] payload = html.getBytes(StandardCharsets.UTF_8);
        // textLength 是**解压后**的正文总长（压缩后反而更短）
        byte[] file = buildMobi(new byte[][]{compressLiterals(payload)}, 2, payload.length, 4096);

        String text = new BookTextExtractor().extractMobi(write(dir, "b.mobi", file));

        assertThat(text).contains("压缩章节").contains("压缩正文。");
    }

    @Test
    @DisplayName("MOBI：LZ77 回引按活动缓冲逐字节复制（重叠回引也要对）")
    void backReferenceIsDecoded(@TempDir Path dir) throws IOException {
        // 'A' 'B' + 回引(distance=2, length=5) => "ABABABA"
        byte[] stream = new byte[]{'A', 'B', (byte) 0x80, (byte) 0x12};
        byte[] file = buildMobi(new byte[][]{stream}, 2, 7, 4096);

        String text = new BookTextExtractor().extractMobi(write(dir, "c.mobi", file));

        assertThat(text.replace(" ", "").replace("\n", "")).isEqualTo("ABABABA");
    }

    @Test
    @DisplayName("MOBI：单条记录解压不能超过 recordSize")
    void recordSizeCapsEachRecord(@TempDir Path dir) throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append('A');
        }
        byte[] payload = big.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] file = buildMobi(new byte[][]{payload}, 1, -1, 4096);

        String text = new BookTextExtractor().extractMobi(write(dir, "d.mobi", file));

        assertThat(text.length()).isLessThanOrEqualTo(4096);
    }

    @Test
    @DisplayName("MOBI：正文总长以 record0 的 textLength 为准")
    void textLengthTruncatesTail(@TempDir Path dir) throws IOException {
        String html = "<html><body><p>正文</p></body></html>";
        byte[] payload = html.getBytes(StandardCharsets.UTF_8);
        byte[] withGarbage = new byte[payload.length + 16];
        System.arraycopy(payload, 0, withGarbage, 0, payload.length);
        for (int i = payload.length; i < withGarbage.length; i++) {
            withGarbage[i] = (byte) 0xAA;
        }
        byte[] file = buildMobi(new byte[][]{withGarbage}, 1, payload.length, 4096);

        String text = new BookTextExtractor().extractMobi(write(dir, "e.mobi", file));

        assertThat(text).doesNotContain(REPLACEMENT);
        assertThat(text.trim()).isEqualTo("正文");
    }
}
