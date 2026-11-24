package com.audiobook.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参考音频裁切的「能不能动这个文件」策略测试。
 *
 * <p>裁切会把内容统一转成 WAV，因此文件名理应跟着变成 .wav。
 * 但**已经落库**的素材（任务表的 {@code reference_audio_path}、我的参考音频库、
 * 服务器共享库）是按文件名引用的：一旦改名，按库里记录去找文件就会 404 ——
 * 试听、下载、后续合成会一起坏掉，而且**只在使用时才发现**。
 *
 * <p>所以这条判断必须被测住：入库路径（文件名未落库）可以随便改名；
 * 惰性路径（文件名已落库）只有当扩展名本来就是 .wav、改名是空操作时才允许裁。
 */
class ReferenceAudioCapPolicyTest {

    @Test
    @DisplayName("已落库素材：扩展名不是 .wav 时必须放弃裁切，绝不能改名")
    void storedAudioIsNeverRenamed() {
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.mp3", false)).isFalse();
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.m4a", false)).isFalse();
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.flac", false)).isFalse();
        // 连扩展名都没有的也要判 false：appendExtension 会造出一个新名字
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc", false)).isFalse();
    }

    @Test
    @DisplayName("已落库素材：本来就是 .wav 时可以裁（改名是空操作，引用不受影响）")
    void storedWavCanBeCappedInPlace() {
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.wav", false)).isTrue();
        // 边界：带点的目录/多段名只按最后一个点切扩展名
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file.a.b.wav", false)).isTrue();
    }

    @Test
    @DisplayName("入库路径：文件名尚未落库，任意扩展名都可以裁并改成 .wav")
    void intakePathMayRename() {
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.mp3", true)).isTrue();
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.m4a", true)).isTrue();
        assertThat(VoiceCloningTtsService.canCapWithoutRename("file_abc.wav", true)).isTrue();
    }
}
