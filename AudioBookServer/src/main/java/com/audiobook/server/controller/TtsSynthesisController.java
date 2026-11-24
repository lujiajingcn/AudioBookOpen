package com.audiobook.server.controller;

import com.audiobook.server.model.Request;
import com.audiobook.server.service.TtsService;
import com.audiobook.server.util.AudioVideoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;

/**
 * 交互式语音合成：文本 → 音频的同步端点（/clone、/synthesize）。
 * 由原 Controller.java（God Object）拆分而来，路径保持不变。
 */
@RestController
@RequestMapping("/api/tts")
public class TtsSynthesisController {

    private static final Logger log = LoggerFactory.getLogger(TtsSynthesisController.class);

    private final TtsService service;
    private final TtsControllerSupport support;

    public TtsSynthesisController(TtsService service, TtsControllerSupport support) {
        this.service = service;
        this.support = support;
    }

    /**
     * 接收文本 + 参考音频（用于音色克隆）
     *
     * 整条链路不出现「整份字节驻堆」：参考音频先落临时文件（不 getBytes()），
     * 视频走文件→文件转码（不 readAllBytes 回堆），以 FileSystemResource
     * 流式上传给引擎；合成结果也先落盘再用 InputStream 直传响应。
     * 响应体类型为 Resource，HTTP 语义与 byte[] 一致（同样的
     * audio/wav、attachment;speech.wav、Content-Length）。
     * 注意：**只有成功路径**是二进制音频流；参数校验失败走 {@link #badRequest(String)}，
     * 返回 JSON {@code {success:false, message}}，调用方按状态码区分。
     */
    @PostMapping(value = "/clone", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> synthesizeWithVoiceClone(
            @RequestParam("text") String text,
            @RequestParam(value = "prompt_audio", required = false) MultipartFile promptAudio,
            @RequestParam(value = "voiceId", required = false) String voiceId) throws IOException {

        // --- Input validation ---
        if (text == null || text.isBlank()) {
            return badRequest("文本不能为空");
        }
        if (text.length() > 500) {
            return badRequest("文本长度不能超过500个字符");
        }
        if (promptAudio != null && promptAudio.getSize() > support.maxFileSizeBytes()) {
            return badRequest("参考音频/视频不能超过 "
                    + TtsControllerSupport.formatMB(support.maxFileSizeBytes()) + "MB");
        }

        Request request = new Request();
        request.setText(text);
        request.setVoiceId(voiceId);

        File tempUpload = null;   // 上传落盘的参考音频/视频
        File tempWav = null;      // 视频转码产生的 WAV
        File tempOut = null;      // 合成结果（落盘后流式回传）
        try {
            if (promptAudio != null && !promptAudio.isEmpty()) {
                // 服务端兜底校验扩展名（防止绕过前端上传非法文件）
                String ext = support.getFileExtension(promptAudio.getOriginalFilename());
                if (!support.isAllowedReferenceExt(ext)) {
                    return badRequest("不支持的参考音频格式");
                }
                // 直接落盘：multipart 临时文件由容器持有，transferTo 不会把内容读进堆
                tempUpload = File.createTempFile("clone_upload_", ext == null ? ".tmp" : "." + ext);
                promptAudio.transferTo(tempUpload);

                File referenceFile;
                // 视频格式先转为 WAV 音频（文件→文件，转码产物同样留在磁盘），
                // 转码时一并只保留前 N 秒，避免「先整段转码再截断」的二次重编码
                if (ext != null && AudioVideoUtil.isVideoFormat(ext)) {
                    tempWav = File.createTempFile("clone_ref_", ".wav");
                    AudioVideoUtil.convertVideoToWav(tempUpload, tempWav, support.referenceMaxSeconds());
                    referenceFile = tempWav;
                } else {
                    // 音频格式：只在确实超长时才重编码（裁切结果是新的临时文件）；
                    // 未超长直接复用原临时文件，零额外开销。
                    // 裁切的意义见 AudioVideoUtil.capDurationToWav —— 参考音频越长，
                    // 引擎侧 prompt 越长，单请求耗时与内存越是超线性上涨。
                    tempWav = AudioVideoUtil.capDurationToWav(
                            tempUpload, support.referenceMaxSeconds(), "clone_ref_");
                    referenceFile = tempWav;
                }
                request.setReferenceAudioFile(referenceFile);
            }

            // 交互式合成：超时上限更短（默认 30s），避免同步请求长时间占住 Tomcat 工作线程。
            // 合成结果由 service 直接流式解码写入临时文件，
            // 整段音频不驻留堆（base64 边解码边落盘）。
            tempOut = File.createTempFile("clone_out_", ".wav");
            try (OutputStream out = Files.newOutputStream(tempOut.toPath())) {
                service.synthesizeInteractiveTo(request, out);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("audio/wav"));
            headers.setContentDispositionFormData("attachment", "speech.wav");
            headers.setContentLength(tempOut.length());

            // 流关闭时自动删除临时文件，客户端中断也不会留下磁盘孤儿
            ResponseEntity<Object> response = new ResponseEntity<>(
                    new InputStreamResource(new DeleteOnCloseInputStream(tempOut)), headers, HttpStatus.OK);
            tempOut = null; // 清理责任已移交给响应流，finally 不再删除
            return response;
        } finally {
            deleteTempFile(tempUpload);
            deleteTempFile(tempWav);
            deleteTempFile(tempOut);
        }
    }

    /**
     * 使用预置音色合成（只需文本和音色ID）
     */
    @PostMapping("/synthesize")
    public ResponseEntity<Object> synthesizeWithPresetVoice(
            @RequestParam("text") String text,
            @RequestParam("voiceId") String voiceId) throws IOException {

        // 输入校验：缺少校验时任意长度文本/空音色会直接打进 TTS 引擎，
        // 可被用来跑超长合成压垮服务或探测引擎行为。与 /clone 保持一致的口径。
        if (text == null || text.isBlank()) {
            return badRequest("文本不能为空");
        }
        if (text.length() > 500) {
            return badRequest("文本长度不能超过500个字符");
        }
        if (voiceId == null || voiceId.isBlank()) {
            return badRequest("音色ID不能为空");
        }

        Request request = new Request();
        request.setText(text);
        request.setVoiceId(voiceId);

        // 交互式合成：超时上限更短（默认 30s），避免同步请求长时间占住 Tomcat 工作线程。
        // 合成结果由 service 直接流式写入临时文件，整段音频不驻留堆。
        File tempOut = null;
        try {
            tempOut = File.createTempFile("synth_out_", ".wav");
            try (OutputStream out = Files.newOutputStream(tempOut.toPath())) {
                service.synthesizeInteractiveTo(request, out);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("audio/wav"));
            headers.setContentLength(tempOut.length());

            // 流关闭时自动删除临时文件，客户端中断也不会留下磁盘孤儿
            ResponseEntity<Object> response = new ResponseEntity<>(
                    new InputStreamResource(new DeleteOnCloseInputStream(tempOut)), headers, HttpStatus.OK);
            tempOut = null; // 清理责任已移交给响应流，finally 不再删除
            return response;
        } finally {
            deleteTempFile(tempOut);
        }
    }

    /**
     * 参数校验失败时的统一错误响应。
     *
     * <p>此前这里直接回纯文本（{@code body("文本不能为空".getBytes(UTF_8))}），与全工程
     * {@code {success, message}} 的响应契约不一致 —— 客户端按 JSON 解析，拿不到
     * {@code message} 里那句**具体原因**，只能退化成「请求失败」这类泛化提示，
     * 用户看到「文本长度不能超过500个字符」和「音色ID不能为空」是一个样。
     *
     * <p>注意：本端点的**成功**响应仍是 {@code audio/wav} 二进制流（见方法注释），
     * 只有错误路径是 JSON；调用方需按状态码区分。
     */
    private static ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }

    /** 删除临时文件，失败仅记日志——临时目录最终由操作系统/运维清理，不能因此打断响应 */
    private static void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) {
            log.warn("临时文件删除失败: {}", file.getAbsolutePath());
        }
    }

    /**
     * 流关闭（响应写完或客户端中断）后自动删除源临时文件的输入流。
     * /clone 的合成结果以临时文件承载再流式回传，若不在流关闭时清理就会留下永久磁盘孤儿。
     */
    private static final class DeleteOnCloseInputStream extends FilterInputStream {

        private final File file;

        private DeleteOnCloseInputStream(File file) throws IOException {
            super(Files.newInputStream(file.toPath()));
            this.file = file;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                deleteTempFile(file);
            }
        }
    }
}