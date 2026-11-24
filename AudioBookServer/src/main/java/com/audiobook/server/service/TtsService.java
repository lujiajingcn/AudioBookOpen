package com.audiobook.server.service;

import com.audiobook.server.model.Request;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;

public interface TtsService {
    byte[] synthesize(Request request);

    /**
     * 交互式合成：供同步 HTTP 接口（/clone、/synthesize）调用。
     * 与批量合成的区别只在超时上限——同步请求会占住一个 Tomcat 工作线程，
     * 必须尽早打破等待，不能沿用后台任务的分钟级超时。
     * 默认退化为 {@link #synthesize(Request)}，实现类可覆盖。
     */
    default byte[] synthesizeInteractive(Request request) {
        return synthesize(request);
    }

    /**
     * 流式交互式合成：把结果音频直接写入 {@code out}，避免整段音频驻留堆。
     * 默认实现退化为「先合成 byte[] 再写出」，实现类可覆盖以真正流式输出。
     */
    default void synthesizeInteractiveTo(Request request, OutputStream out) throws IOException {
        out.write(synthesizeInteractive(request));
    }

    
    /**
     * 单独上传参考音频，返回保存的文件名作为 referenceId
     */
    String uploadReferenceAudio(MultipartFile referenceAudio) throws IOException;
    
    /**
     * 创建TTS任务
     * @param bookFile 电子书文件（必填）
     * @param referenceAudio 参考音频文件（与 referenceFileName 二选一）
     * @param referenceFileName 已上传的参考音频文件名（与 referenceAudio 二选一）
     * @param bookExtension 电子书文件扩展名，如 "txt"/"pdf"/"epub"/"mobi"
     * @param userId 用户ID
     * @param defaultVoiceId 任务级默认预设音色（可选，按段绑定时的兜底）
     * @param segmentVoicesJson 段落级音色映射 JSON（可选，{段落号: voiceId}）
     * @param storeBookId 书城书籍 id（可选）：来自书城的任务会据此落「正文快照」，
     *                    保证阅读页正文与音频同源；用户自行上传的书传 null
     */
    Long createTtsTask(MultipartFile bookFile, MultipartFile referenceAudio, String referenceFileName,
                       String bookExtension, String userId, String defaultVoiceId, String segmentVoicesJson,
                       Long storeBookId) throws IOException;

    /**
     * 异步执行TTS任务（提交到 ttsTaskExecutor 线程池，立即返回）。
     * 段落从数据库按 taskId 读取，调用方只需保证任务及其段落已提交入库。
     */
    void processTtsTaskAsync(Long taskId);

    /**
     * 修改任务音色分配并触发受影响片段的重新合成。
     *
     * <p>客户端在初始合成完成后，对每段分配/修改绑定音色（按段、按角色），调用本方法：
     * <ul>
     *   <li>更新任务级默认音色 defaultVoiceId 与段落级映射 segmentVoices；</li>
     *   <li>把发生变更的段重置为待处理（保留其余已合成段不动）；</li>
     *   <li>重新派发异步合成，仅重跑变更段，其余已完成段自动跳过；</li>
     *   <li>沿用原任务的已扣积分（按任务计费，重合成不二次收费）。</li>
     * </ul>
     *
     * @param taskId          任务 ID
     * @param defaultVoiceId  任务级默认预设音色（可为 null）
     * @param segmentVoices   段落号 → voiceId 映射；value 为 null/空串表示清除预设、回退克隆参考音频
     */
    /**
     * 修改任务的音色绑定并重合成受影响的段落。
     *
     * @param defaultVoiceId      任务级默认音色；<b>仅在 defaultVoiceProvided=true 时生效</b>，
     *                            为 null 表示清空默认音色。传 false 表示「本次不修改默认音色」。
     * @param segmentVoices       段落级音色映射（段号 → voiceId），value 为 null/空串表示清除该段预设。
     *                            采用 <b>merge</b> 语义：只覆盖本次上报的段，历史绑定保留。
     * @param defaultVoiceProvided 请求体是否显式携带了 defaultVoiceId（区分「不改」与「置空」）
     */
    void applySegmentVoices(Long taskId, String defaultVoiceId,
                            java.util.Map<Integer, String> segmentVoices, boolean defaultVoiceProvided);
}