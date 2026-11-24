package com.audiobook.server.util;

import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.repository.AudioSegmentRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 音频片段 ZIP 打包器。
 *
 * <p>把「按段号排序的已完成片段」流式写成一个 ZIP：优先从磁盘读取音频文件
 * （正常路径：合成时音频均已落盘），磁盘缺失时回退读取该段的 BLOB，
 * 保证任意时刻堆内只有一段音频，内存占用与整书大小无关。
 *
 * <p>收敛了原先 {@code TtsDownloadController.downloadAllAudio} 与
 * {@code BookStoreService.streamStoreAudioZip} 中两份同构实现——两处均带
 * 「与对方保持一致」的注释，属高风险漂移点。
 */
public final class ZipAudioPacker {

    /** ZIP 条目命名：{段号(3位补零)}_segment_{段号}.mp3 */
    private static final String ENTRY_NAME_FORMAT = "%03d_segment_%d.mp3";

    private static final int BUFFER_SIZE = 8192;

    private ZipAudioPacker() {
    }

    /** 定位某段的磁盘音频文件；解析失败或文件不可用应返回 {@code null}，由打包器回退 BLOB。 */
    @FunctionalInterface
    public interface SegmentFileLocator {
        File locate(AudioSegmentRepository.SegmentMeta segment);
    }

    /** 读取某段的 BLOB 音频数据；无数据返回 {@code null} 或空数组。 */
    @FunctionalInterface
    public interface AudioBlobLoader {
        byte[] load(Long segmentId);
    }

    /**
     * 打包结果。
     *
     * @param successCount     成功写入 ZIP 的片段数
     * @param skippedSegmentIds 磁盘与 BLOB 均缺失、被跳过的片段 id
     */
    public record PackResult(int successCount, List<Long> skippedSegmentIds) {
    }

    /**
     * 把已完成片段流式打包写入 {@code out}。
     *
     * <p>调用方负责设置响应头并关闭流（本方法内部的 ZipOutputStream 会随 try-with-resources
     * 关闭，从而收尾 ZIP 中央目录）。
     *
     * @param segments   按段号升序排列的片段元数据
     * @param out        输出流（通常为 HttpServletResponse#getOutputStream）
     * @param locator    磁盘文件定位器
     * @param blobLoader BLOB 回退加载器
     */
    public static PackResult pack(List<AudioSegmentRepository.SegmentMeta> segments,
                                  OutputStream out,
                                  SegmentFileLocator locator,
                                  AudioBlobLoader blobLoader) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int successCount = 0;
        List<Long> skipped = new ArrayList<>();

        try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (AudioSegmentRepository.SegmentMeta segment : segments) {
                if (!SegmentStatus.COMPLETED.value().equals(segment.getStatus())) {
                    continue;
                }

                String entryName = String.format(ENTRY_NAME_FORMAT,
                        segment.getSegmentNumber(), segment.getSegmentNumber());

                // 一级来源：磁盘文件
                File audioFile = locator.locate(segment);
                if (audioFile != null) {
                    zipOut.putNextEntry(new ZipEntry(entryName));
                    try (InputStream in = new FileInputStream(audioFile)) {
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            zipOut.write(buffer, 0, n);
                        }
                    }
                    zipOut.closeEntry();
                    successCount++;
                    continue;
                }

                // 二级来源：BLOB 回退（一次只把一段读进堆）
                byte[] audioData = blobLoader.load(segment.getId());
                if (audioData != null && audioData.length > 0) {
                    zipOut.putNextEntry(new ZipEntry(entryName));
                    zipOut.write(audioData);
                    zipOut.closeEntry();
                    successCount++;
                } else {
                    skipped.add(segment.getId());
                }
            }
        }

        return new PackResult(successCount, skipped);
    }
}
