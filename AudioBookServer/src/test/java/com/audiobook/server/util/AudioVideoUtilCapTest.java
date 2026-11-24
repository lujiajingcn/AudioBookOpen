package com.audiobook.server.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参考音频裁切的**真实转码**测试（会真的调起 JAVE 自带的 ffmpeg）。
 *
 * <p>为什么不做成纯逻辑单测：本方法的正确性完全压在一个外部假设上 ——
 * “{@code EncodingAttributes.setDuration(Float)} 会被渲染成 ffmpeg 的 {@code -t}”。
 * 这个假设只能靠一次真实转码来证伪：如果它不成立，{@code capDurationToWav} 会
 * 静默地返回一个「和输入一样长的文件」，而日志依旧打印「已裁切」——
 * 上层看不出来，线上表现为「改了但一点没省」。
 *
 * <p>测试用的 WAV 由代码现场合成（纯 JDK 的 javax.sound，不依赖任何外部素材），
 * 因此可离线、可重复运行。
 */
class AudioVideoUtilCapTest {

    /** 采纳的参考音频上限，与 application.yml 的 moss.tts.reference-max-seconds 一致 */
    private static final float MAX_SECONDS = 12f;

    /** 时长断言容差：转码会补齐到帧边界，且 WAV 头信息与解码长度有零头差异 */
    private static final double TOLERANCE_SECONDS = 0.6;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("超长素材被真的裁到上限（验证 setDuration → ffmpeg -t 这条链路成立）")
    void capsLongAudioForReal() {
        File source = writeSineWav("long.wav", 30.0);
        assertThat(AudioVideoUtil.probeDurationSeconds(source)).isCloseTo(30.0, within(TOLERANCE_SECONDS));

        File capped = AudioVideoUtil.capDurationToWav(source, MAX_SECONDS, "cap_test_");
        try {
            assertThat(capped)
                    .as("30 秒素材必须被判定为超长并产出新的裁切文件")
                    .isNotSameAs(source)
                    .exists();

            double cappedSeconds = AudioVideoUtil.probeDurationSeconds(capped);
            assertThat(cappedSeconds)
                    .as("裁切后的时长（实际拿到 %.2f 秒）", cappedSeconds)
                    .isBetween((double) MAX_SECONDS - TOLERANCE_SECONDS, (double) MAX_SECONDS + TOLERANCE_SECONDS);
        } finally {
            deleteQuietly(capped);
        }
    }

    @Test
    @DisplayName("本来就够短的素材原样返回：不做无谓的重编码")
    void shortAudioIsReturnedUntouched() {
        File source = writeSineWav("short.wav", 8.0);

        assertThat(AudioVideoUtil.capDurationToWav(source, MAX_SECONDS, "cap_test_"))
                .as("8 秒 < 12 秒上限，不应重编码")
                .isSameAs(source);
    }

    @Test
    @DisplayName("关闭裁切（上限 <= 0）时完全不碰文件")
    void disabledCapIsNoOp() {
        File source = writeSineWav("long2.wav", 30.0);

        assertThat(AudioVideoUtil.capDurationToWav(source, 0f, "cap_test_")).isSameAs(source);
        assertThat(AudioVideoUtil.capDurationToWav(source, -1f, "cap_test_")).isSameAs(source);
    }

    @Test
    @DisplayName("探测失败（文件不存在/非媒体）返回 -1，绝不抛异常")
    void probeFailureIsSwallowed() {
        assertThat(AudioVideoUtil.probeDurationSeconds(new File(tempDir.toFile(), "nope.wav"))).isEqualTo(-1d);

        File garbage = tempDir.resolve("garbage.wav").toFile();
        try {
            java.nio.file.Files.write(garbage.toPath(), "这不是音频".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(AudioVideoUtil.probeDurationSeconds(garbage)).isEqualTo(-1d);
        // 探测不出来时必须原样放行，否则用户会上传失败
        assertThat(AudioVideoUtil.capDurationToWav(garbage, MAX_SECONDS, "cap_test_")).isSameAs(garbage);
    }

    @Test
    @DisplayName("替换：新文件落到原目录，改扩展名后原文件不残留")
    void replaceMovesCappedFileAndRemovesOriginal() {
        File source = writeSineWav("swap.mp3", 30.0);   // 故意用 .mp3 名，模拟「音频入库保留原扩展名」
        File capped = AudioVideoUtil.capDurationToWav(source, MAX_SECONDS, "cap_test_");
        assertThat(capped).isNotSameAs(source);

        String finalName = AudioVideoUtil.replaceWithCappedWav(source, capped, "swap.wav");

        assertThat(finalName).isEqualTo("swap.wav");
        File result = new File(source.getParentFile(), finalName);
        assertThat(result).exists();
        assertThat(result.getParentFile()).isEqualTo(source.getParentFile());
        assertThat(AudioVideoUtil.probeDurationSeconds(result)).isCloseTo(MAX_SECONDS, within(TOLERANCE_SECONDS));
        assertThat(source)
                .as("改了扩展名后原文件必须被清掉，否则每裁一次就留一个同体积的磁盘孤儿")
                .doesNotExist();
    }

    @Test
    @DisplayName("替换：无需裁切时原文件保持不变")
    void replaceWithSameFileIsNoOp() {
        File source = writeSineWav("keep.wav", 8.0);

        assertThat(AudioVideoUtil.replaceWithCappedWav(source, source, "whatever.wav"))
                .isEqualTo("keep.wav");
        assertThat(source).exists();
    }

    // ---------- helpers ----------

    private File writeSineWav(String name, double seconds) {
        float sampleRate = 44100f;
        int samples = (int) Math.round(seconds * sampleRate);
        byte[] data = new byte[samples * 2];             // 16bit 单声道
        for (int i = 0; i < samples; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 0.3 * Short.MAX_VALUE);
            data[i * 2] = (byte) (value & 0xFF);
            data[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        File file = tempDir.resolve(name).toFile();
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(data), format, samples)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file);
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 WAV 失败: " + name, e);
        }
        return file;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
