package com.audiobook.server.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 音视频格式处理工具类。
 * <p>
 * 供 Controller 与 VoiceCloningTtsService 共用，避免视频→WAV 转换逻辑在多处重复。
 */
public final class AudioVideoUtil {

    private static final Logger log = LoggerFactory.getLogger(AudioVideoUtil.class);

    /** 视频格式扩展名集合（不区分大小写匹配） */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "avi", "mkv", "mov", "flv", "wmv", "webm", "m4v", "3gp"
    );

    /**
     * ffmpeg 转码超时上限（秒）。
     * 转码必须带超时：同步 encode 时，损坏文件/异常码流会让 ffmpeg 子进程与调用线程
     * 一起无限挂住，上传线程与进程句柄永不释放，最终打满线程池。超时后强制 kill 子进程并抛异常。
     */
    private static final long CONVERT_TIMEOUT_SECONDS = 120L;

    /**
     * 时长探测容差（秒）。媒体容器声明的时长与真实解码长度常有零点几秒的出入，
     * 没有容差时会为「差 0.1 秒」做一次不必要的重编码（既慢又是有损的）。
     */
    private static final double DURATION_PROBE_TOLERANCE_SECONDS = 0.5;

    /**
     * 全局并发转码上限：每次转码都会起一个 ffmpeg 子进程（CPU 密集）。
     * 视频→WAV 与 TTS 逐段 WAV→MP3 共用这组槽位；TTS 工作线程默认并发仅 2，
     * 无上限时逐段转码会与上传转码互相挤占并无限堆积子进程。
     * 按 IP 的限流管不住多 IP 并发上传，这里用信号量做进程级兜底，超出排队最多 10 秒。
     */
    private static final int MAX_CONCURRENT_CONVERSIONS = 4;
    private static final java.util.concurrent.Semaphore CONVERSION_SLOTS =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_CONVERSIONS);

    private AudioVideoUtil() {
        // 工具类不允许实例化
    }

    /** 判断扩展名（含点，如 ".mp4"，也可能无点如 "mp4"）是否属于视频格式 */
    public static boolean isVideoFormat(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        String normalized = extension;
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return VIDEO_EXTENSIONS.contains(normalized.toLowerCase(Locale.ROOT));
    }

    /**
     * 使用 ffmpeg（通过 JAVE）从视频中提取音频并转为单声道 16bit 44100Hz WAV。
     * 这是语音克隆参考音频最兼容的格式。
     * <p>
     * 文件到文件的流式转换：视频与 WAV 全程只在磁盘上流转，堆内不驻留任何一份完整字节，
     * 且转码带超时上限（{@value #CONVERT_TIMEOUT_SECONDS} 秒），超时强制中断底层 ffmpeg 进程。
     *
     * @param videoFile     输入视频文件
     * @param outputWavFile 输出 WAV 文件（已存在则被覆盖）
     */
    public static void convertVideoToWav(File videoFile, File outputWavFile) throws IOException {
        convertVideoToWav(videoFile, outputWavFile, null);
    }

    /**
     * 同 {@link #convertVideoToWav(File, File)}，但可只保留输入的前 N 秒。
     *
     * <p>为什么要能截断：MOSS-TTS-Nano 把参考音频编码成「每帧一行」的 prompt codes
     * （约 12.5 帧/秒），生成代价随 prompt 长度超线性增长。实测 2 vCPU / 3.6 GB 上
     * 30 s 参考音频单请求需 297 s（且峰值内存撞 3.2 GB cgroup 上限被杀），
     * 裁到 12 s 后只要 15 s —— 上限取多少由 {@code moss.tts.reference-max-seconds}
     * 配置决定，本方法只负责按传入值执行。裁切放在**入库前**做一次，远优于让每一段合成
     * 都重新付出一次长 prompt 的代价。
     *
     * @param maxDurationSeconds 只保留前多少秒；null 表示完整转码（保持原有行为）
     */
    public static void convertVideoToWav(File videoFile, File outputWavFile,
                                         Float maxDurationSeconds) throws IOException {
        AudioAttributes audioAttributes = new AudioAttributes();
        audioAttributes.setCodec("pcm_s16le");
        audioAttributes.setChannels(1);            // 单声道
        audioAttributes.setSamplingRate(44100);    // 44100Hz

        EncodingAttributes encodingAttributes = new EncodingAttributes();
        encodingAttributes.setOutputFormat("wav");
        encodingAttributes.setAudioAttributes(audioAttributes);
        // jave 会把 duration 渲染成 ffmpeg 的 `-t <秒>`，解码器读到第 N 秒即停止
        if (maxDurationSeconds != null && maxDurationSeconds > 0) {
            encodingAttributes.setDuration(maxDurationSeconds);
        }

        Encoder encoder = new Encoder();
        try {
            // 与 TTS 逐段 WAV→MP3 共用同一套超时 / 并发保护（见 encodeWithTimeout）
            encodeWithTimeout(encoder, new MultimediaObject(videoFile), outputWavFile,
                    encodingAttributes, CONVERT_TIMEOUT_SECONDS, "视频转码");
        } catch (IOException e) {
            // 转码失败/超时时输出文件可能是半成品（已写入部分损坏字节），
            // 调用方 finally 只清理临时视频，这里负责清理输出，避免磁盘孤儿永久残留
            cleanupTempFile(outputWavFile);
            throw e;
        }
        log.info("视频→WAV 转换成功，WAV 大小: {} bytes{}", outputWavFile.length(),
                maxDurationSeconds != null ? "（已限制前 " + maxDurationSeconds + " 秒）" : "");
    }

    /**
     * 探测媒体时长（秒）。**失败返回 -1 而不是抛异常**：
     * 本方法的唯一用途是「判断要不要裁切」这类可选的优化路径，
     * 探测失败时让调用方原样放行（节点侧另有 prompt 截断护栏兜底），
     * 绝不能因为一个探测失败就把用户的上传整体拒掉。
     *
     * @return 时长（秒）；无法解析时返回 -1
     */
    public static double probeDurationSeconds(File mediaFile) {
        if (mediaFile == null || !mediaFile.isFile()) {
            return -1d;
        }
        try {
            return new MultimediaObject(mediaFile).getInfo().getDuration() / 1000.0;
        } catch (Exception e) {
            log.warn("读取媒体时长失败，跳过长度的相关处理: {} ({})", mediaFile.getName(), e.getMessage());
            return -1d;
        }
    }

    /**
     * 把媒体裁到不超过 {@code maxSeconds} 秒（输出单声道 44100Hz WAV）。
     *
     * <p>三条「不做无谓工作 / 不制造新故障」的短路规则：
     * <ul>
     *   <li>时长探测失败（-1）→ 返回原文件，交给节点侧护栏；</li>
     *   <li>本来就够短（含 {@value #DURATION_PROBE_TOLERANCE_SECONDS} 秒容差）→ 返回原文件，
     *       避免为了「省 0 秒」而做一次有损重编码；</li>
     *   <li>裁切失败 → 记日志并返回原文件，上传流程照常成功。</li>
     * </ul>
     *
     * @param source         输入媒体（音频或视频）
     * @param maxSeconds     保留前多少秒
     * @param tempFilePrefix 需要裁切时生成的临时文件前缀（由调用方负责删除）
     * @return 裁切后的临时 WAV；无需裁切或裁切失败时返回 {@code source} 本身
     */
    public static File capDurationToWav(File source, float maxSeconds, String tempFilePrefix) {
        if (source == null || maxSeconds <= 0) {
            return source;
        }
        double durationSeconds = probeDurationSeconds(source);
        if (durationSeconds <= 0) {
            return source;
        }
        if (durationSeconds <= maxSeconds + DURATION_PROBE_TOLERANCE_SECONDS) {
            return source;
        }

        File cappedFile = null;
        try {
            // 临时文件刻意放在源文件同目录：同一个目录 = 同一个文件系统，
            // 后续 replaceWithCappedWav 的 Files.move 才是一次原子改名，
            // 而不是跨盘「先复制再删除」（复制中途失败会把目标文件写坏）。
            cappedFile = createTempFileIn(source, tempFilePrefix);
            convertVideoToWav(source, cappedFile, maxSeconds);
            // 上面只做了「前 maxSeconds 秒」的限制，实际长度还取决于源文件与解码器；
            // 这里回读一次，把「裁切是否真的生效」写进日志，便于事后核对（日志是唯一的观测手段）。
            // 注意用 String.format 先算好再传参：SLF4J 只认 `{}`，写 `{:.2f}` 不会被格式化
            log.info("参考音频超长已裁切: {} ({}s) -> {}s",
                    source.getName(),
                    String.format("%.2f", durationSeconds),
                    String.format("%.2f", probeDurationSeconds(cappedFile)));
            return cappedFile;
        } catch (Exception e) {
            log.warn("参考音频裁切失败，保留原文件交由节点侧护栏处理: {} ({})",
                    source.getName(), e.getMessage());
            cleanupTempFile(cappedFile);
            return source;
        }
    }

    /** 在 {@code source} 所在目录建临时文件（拿不到父目录时退回系统临时目录） */
    private static File createTempFileIn(File source, String prefix) throws IOException {
        File parent = source.getAbsoluteFile().getParentFile();
        if (parent != null && parent.isDirectory()) {
            return File.createTempFile(prefix, ".wav", parent);
        }
        return File.createTempFile(prefix, ".wav");
    }

    /**
     * 用裁切结果替换原文件，保留「原文件扩展名换成 .wav」的语义。
     *
     * <p>先 move 再删的原因：{@code Files.move(REPLACE_EXISTING)} 在同一个目录内
     * 是原子替换，不会出现「原文件已删、新文件没到位」的中间态；
     * 而「先 delete 再 rename」一旦 rename 失败就把用户的数据弄丢了。
     *
     * @param target       要被替换的原文件
     * @param cappedFile   裁切结果（即 {@link #capDurationToWav} 的返回值）
     * @param desiredName  期望的新文件名（扩展名应为 .wav）
     * @return 实际生效的文件名；未发生替换时返回 {@code target.getName()}
     */
    public static String replaceWithCappedWav(File target, File cappedFile, String desiredName) {
        if (target == null || cappedFile == null || cappedFile.equals(target)) {
            return target == null ? null : target.getName();
        }
        try {
            File dest = new File(target.getParentFile(), desiredName);
            // move 成功后数据已在 dest 上，此时再删原文件是安全的
            Files.move(cappedFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!dest.equals(target)) {
                // 改了扩展名（如 .mp3 → .wav）时 move 不会覆盖原文件，它会变成磁盘孤儿
                cleanupTempFile(target);
            }
            log.info("参考音频已替换为裁切版: {} -> {}", target.getName(), desiredName);
            return desiredName;
        } catch (Exception e) {
            // 替换失败不是致命错误：原文件仍在，节点侧护栏仍能限制 prompt 长度
            log.warn("参考音频替换失败，保留原文件: {} ({})", target.getName(), e.getMessage());
            cleanupTempFile(cappedFile);
            return target.getName();
        }
    }

    /**
     * 带超时与并发上限地执行一次 ffmpeg 编码。
     * <p>
     * JAVE 的 encode() 是阻塞调用且不支持超时：损坏文件 / 异常码流会让 ffmpeg 子进程
     * 与调用线程一起无限挂住，线程与进程句柄永不释放。这里把编码放到独立线程用
     * Future 限时，超时后 abortEncoding() 会 destroy 底层 ffmpeg 进程，避免进程泄漏。
     * <p>
     * 视频→WAV 与 TTS 逐段 WAV→MP3 共用同一实现、同一组并发槽位，避免两处各写一份
     * 超时 / 排队逻辑而各自漏配。
     *
     * @param encoder        编码器实例（超时时对它调用 abortEncoding）
     * @param input          输入多媒体对象
     * @param output         输出文件
     * @param attrs          编码参数
     * @param timeoutSeconds 超时秒数
     * @param description    操作描述，用于日志与异常文案（如「视频转码」「WAV转MP3」）
     */
    public static void encodeWithTimeout(Encoder encoder, MultimediaObject input, File output,
                                         EncodingAttributes attrs, long timeoutSeconds,
                                         String description) throws IOException {
        // 进程级并发兜底：拿不到槽位说明转码已饱和，快速失败而非无限堆积子进程
        boolean slotAcquired = false;
        try {
            slotAcquired = CONVERSION_SLOTS.tryAcquire(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(description + "排队被中断", e);
        }
        if (!slotAcquired) {
            throw new IOException(description + "排队超时：当前转码任务过多，请稍后重试");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ffmpeg-" + description);
            t.setDaemon(true);
            return t;
        });
        try {
            Future<?> future = executor.submit(() -> {
                encoder.encode(input, output, attrs);
                return null;
            });
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                encoder.abortEncoding();
                future.cancel(true);
                log.error("{}超时({}s)，已强制中断 ffmpeg: {}", description, timeoutSeconds,
                        input.getFile().getName());
                throw new IOException(description + "超时（超过 " + timeoutSeconds + " 秒）");
            } catch (InterruptedException e) {
                encoder.abortEncoding();
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException(description + "被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                log.error("{}失败: {}", description, cause.getMessage(), cause);
                throw new IOException(description + "失败: " + cause.getMessage(), cause);
            }
        } finally {
            executor.shutdownNow();
            CONVERSION_SLOTS.release();
        }
    }

    private static void cleanupTempFile(File file) {
        if (file != null && file.exists()) {
            boolean deleted = file.delete();
            if (!deleted) {
                log.warn("临时文件删除失败: {}", file.getAbsolutePath());
            }
        }
    }
}
