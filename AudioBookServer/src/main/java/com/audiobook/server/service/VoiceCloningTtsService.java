package com.audiobook.server.service;

import com.audiobook.server.exception.TtsTimeoutException;
import com.audiobook.server.model.AudioSegment;
import com.audiobook.server.model.Request;
import com.audiobook.server.model.SegmentStatus;
import com.audiobook.server.model.Task;
import com.audiobook.server.model.TaskStatus;
import com.audiobook.server.model.TtsEngineType;
import com.audiobook.server.model.TtsNode;
import com.audiobook.server.model.User;
import com.audiobook.server.repository.AudioSegmentRepository;
import com.audiobook.server.repository.TaskRepository;
import com.audiobook.server.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.audiobook.server.util.AudioVideoUtil;
import com.audiobook.server.util.LanguageDetector;
import com.audiobook.server.util.PathSafetyUtil;
import com.audiobook.server.util.PocketVoiceConfig;
import com.audiobook.server.util.TextSegmenter;
import com.audiobook.server.util.WavClauseJoiner;
import com.audiobook.server.util.WavConcat;
import com.audiobook.server.util.WavEnvelope;
import jakarta.annotation.PostConstruct;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class VoiceCloningTtsService implements TtsService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCloningTtsService.class);

    /**
     * 逐段 WAV→MP3 的转码超时上限（秒）。
     * 单段音频只有几秒到几十秒，60s 已足够宽松；不给超时时异常码流会让 ffmpeg
     * 子进程与 TTS 工作线程一起永久挂死——工作线程默认并发仅 2，挂死一个就废掉一半产能，
     * 且没有看门狗，队列会永久停滞。
     */
    private static final long MP3_CONVERT_TIMEOUT_SECONDS = 60L;

    /**
     * 引擎帧率：375 帧 ≈ 30 秒音频，即 12.5 帧/秒。
     * 只用于帧数与音频时长的互相换算（估算与日志），不参与任何业务判断。
     */
    static final double ENGINE_FRAMES_PER_SECOND = 12.5;

    /**
     * 「至少一侧紧邻 CJK 字符或中文标点」的空白。用来在送引擎前清理中文里冗余的空格/换行，
     * 同时保留英文单词之间的空格。
     *
     * <p><b>为什么不用 {@code \\s+}</b>：改造前 {@code buildMultipartBody} 直接
     * {@code text.replaceAll("\\s+", "")}，中文没问题，但英文/中英混排会被毁掉 ——
     * {@code "Hello world." → "Helloworld."}，引擎拿到就一个词，读法完全不对。
     *
     * <p>覆盖的 Unicode 段：
     * <ul>
     *   <li>{@code \u4E00-\u9FFF} CJK 统一汉字（基本区）</li>
     *   <li>{@code \u3400-\u4DBF} CJK 扩展 A</li>
     *   <li>{@code \u3000-\u303F} CJK 符号与标点（含全角空格 {@code \u3000}、
     *       顿号 {@code \u3001}、句号 {@code \u3002} 等）</li>
     *   <li>{@code \uFF00-\uFFEF} 半角/全角形式（含全角标点、全角英文字母）</li>
     * </ul>
     * 只匹配「一侧是 CJK、另一侧任意」的空白，因此纯英文串里的空格不会被吃掉。
     *
     * <p>预编译：每段合成都会走一次这条清理，正则不预编译就每次重新解析，属于纯浪费。
     */
    private static final Pattern CJK_ADJACENT_WHITESPACE = Pattern.compile(
            "(?<=[" + "\u3000-\u303F" + "\u3400-\u4DBF" + "\u4E00-\u9FFF" + "\uFF00-\uFFEF" + "])\\s+"
                    + "|\\s+(?=[" + "\u3000-\u303F" + "\u3400-\u4DBF" + "\u4E00-\u9FFF" + "\uFF00-\uFFEF" + "])");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final TaskRepository taskRepository;
    private final AudioSegmentRepository audioSegmentRepository;
    private final UserRepository userRepository;
    private final RedisTaskService redisTaskService;
    private final BookTextExtractor bookTextExtractor;
    /** 正文快照（W1）：书城书籍合成时把「这次提取到的正文」存下来，供阅读页与音频同源 */
    private final BookContentSnapshotService bookContentSnapshotService;
    private final CreditService creditService;

    /**
     * 多节点调度器。取代原先单一的 {@code moss.tts.url} ——
     * 每次合成由它选出一台节点，并给出失败后可顺延重试的候选序列。
     * 节点地址本身由 {@code TtsNodeRegistry} 从数据库维护，运行期可增删。
     */
    private final TtsLoadBalancer loadBalancer;

    /**
     * 服务关闭信号：关闭期间被打断的段不写 failed、任务不落终态，
     * 一律保持可续跑状态。详见 {@link ShutdownSignal}。
     */
    private final ShutdownSignal shutdownSignal;

    /**
     * 正在执行中的任务ID，防止同一个任务被重复派发（例如启动恢复与正常上传撞车）
     */
    private final Set<Long> runningTasks = ConcurrentHashMap.newKeySet();

    /**
     * 已被删除的任务ID → 登记时刻（毫秒）。
     * 异步工作线程在关键节点检查：一旦发现自己处理的任务已被删除，立即中止，
     * 不再合成后续段落、不再写任何库——避免浪费 TTS 资源，也堵住「检查通过后
     * 行才被删」的残余竞态窗口（配合 TaskRepository/AudioSegmentRepository 的
     * JPQL UPDATE，写操作本身也不可能复活已删行）。
     *
     * 条目带 TTL：只需存活到「删除动作 → 工作线程下一次检查点」，TTL 30 分钟已远超
     * 单段合成的最坏耗时；若只增不减，集合会无界增长，且数据库重置 / 自增 ID
     * 回绕复用后，新任务会被误判为「已删除」而静默放弃（不报错、不入库、
     * 任务永远停在 queued）。读取时顺带清理过期项，无需额外定时任务。
     */
    private static final long DELETED_TASK_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final Map<Long, Long> deletedTasks = new ConcurrentHashMap<>();

    /** 删除任务/删除用户在真正删行之前调用，通知可能正在运行的工作线程中止。 */
    public static void markTaskDeleted(Long taskId) {
        deletedTasks.put(taskId, System.currentTimeMillis());
    }

    /**
     * 任务是否已被删除（登记且在 TTL 内）。
     * 命中过期条目时顺手移除：该任务早已删完，工作线程不可能还在处理它；
     * 若该 ID 已被新任务复用，绝不能把新任务误判为已删除。
     */
    public static boolean isTaskDeleted(Long taskId) {
        Long deletedAt = deletedTasks.get(taskId);
        if (deletedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - deletedAt > DELETED_TASK_TTL_MILLIS) {
            deletedTasks.remove(taskId, deletedAt);
            return false;
        }
        return true;
    }

    /**
     * 已被管理员挂起的任务ID → 登记时刻（毫秒）。
     *
     * <p>与 {@link #deletedTasks} 同构：内存标记让工作线程在**每个段落检查点**零成本地问一句
     * 「要不要收手」，不必每段查一次库（一本 2500 段的书就是 2500 次额外查询）。
     *
     * <p>两者的关键区别是**落法**：删除是真删除（连行带段落一起没），所以工作线程命中后直接
     * return、不写任何状态；暂停是「任务还在、进度要留、随时能续」，因此 DB 状态由
     * {@link TaskRepository#pauseIfInStatus} 写成 paused 落盘 —— 进程重启后任务仍是暂停的，
     * 不会被启动恢复器偷偷捞起来跑。
     *
     * <p>同样带 TTL 30 分钟：只需存活到「点暂停 → 工作线程下一次检查点」，而正常「继续合成」
     * 会显式 {@link #clearTaskPaused} 清掉标记。与 deletedTasks 一致，这是**单进程内**标记，
     * 多实例部署时暂停只对接到该请求的实例生效（本服务以单实例 systemd 运行）。
     */
    private static final long PAUSED_TASK_TTL_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final Map<Long, Long> pausedTasks = new ConcurrentHashMap<>();

    /** 管理员点「暂停合成」时登记，通知（可能正在跑的）工作线程在下一个检查点收手。 */
    public static void markTaskPaused(Long taskId) {
        pausedTasks.put(taskId, System.currentTimeMillis());
    }

    /** 管理员点「继续合成」或「删除语音」时清掉标记，避免标记滞留把后续新任务误判为暂停。 */
    public static void clearTaskPaused(Long taskId) {
        pausedTasks.remove(taskId);
    }

    /**
     * 任务是否已被管理员挂起（登记且在 TTL 内）。
     * 过期条目顺手移除：TTL 之后若无人「继续」，工作线程早已收手，标记没有意义。
     */
    public static boolean isTaskPaused(Long taskId) {
        Long pausedAt = pausedTasks.get(taskId);
        if (pausedAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - pausedAt > PAUSED_TASK_TTL_MILLIS) {
            pausedTasks.remove(taskId, pausedAt);
            return false;
        }
        return true;
    }

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private VoiceCloningTtsService self;

    /**
     * 预置音色 ID 在 TTS 引擎 multipart 请求中的字段名。
     * 不同引擎版本可能叫 demo_id / voice_id / spk / speaker，做成配置项便于对齐。
     *
     * <p>默认 <b>demo_id</b>：线上 MOSS-TTS-Nano 的预置音色叫 demo
     * （其 assets/demo.jsonl，共 demo-1…demo-29），引擎只认 demo_id。
     * 传 voice_id 会被当未知字段忽略，引擎随即报
     * {@code demo_id is required unless prompt speech is uploaded}（HTTP 400）。
     */
    @Value("${moss.tts.voice-param:demo_id}")
    private String ttsVoiceParam;

    /**
     * 回落 MOSS 时使用的兜底音色（MOSS 自带的 {@code demo-N}）。
     *
     * <p>存在的唯一理由：中文书如果被用户配了 Pocket 音色（如 {@code alba}），
     * {@link #decideEngineType} 会强制改走 MOSS，但那个音色 id 不是 MOSS 的 {@code demo_id}。
     * 实测线上 MOSS 对未知 id 直接 400（{@code {"error":"Unknown demo_id: alba"}}），
     * 对缺省 {@code demo_id} 同样 400（{@code demo_id is required unless prompt speech is uploaded}），
     * 所以既不能原样透传、也不能干脆不下发 —— 必须换成一个 MOSS 真有的音色。
     */
    @Value("${moss.tts.fallback-voice:demo-1}")
    private String mossFallbackVoice;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /**
     * Pocket-TTS 引擎总开关。默认 {@code false}：整套分支在显式开启前不改变任何既有行为，
     * 每个请求仍按 MOSS 处理，避免「合并了代码但没部署 Pocket 节点」时误把请求发给不存在的引擎。
     */
    @Value("${pocket.tts.enabled:false}")
    private boolean pocketEnabled;

    /**
     * Pocket 预置音色清单，逗号分隔，每项 {@code id} 或 {@code id:显示名}。
     *
     * <p>这里的 {@code id} 即请求携带的 {@code voiceId}：命中集合的段落判为 POCKET 引擎，
     * 未命中（含克隆参考音频、MOSS 的 demo-N）一律走 MOSS。清单同时喂给
     * {@code /api/tts/voices} 下拉与 {@link #resolveEngineType}，两处口径必须一致。
     */
    @Value("${pocket.tts.voices:}")
    private String pocketVoicesConfig;

    /** {@link #pocketVoicesConfig} 解析出的音色 id 集合，首次使用时构建后缓存（配置运行期不变）。 */
    private volatile Set<String> pocketVoiceIds;

    /**
     * 语言 → Pocket 音色 id 的映射（{@code lang:voiceId} 逗号分隔），
     * 由 {@code extractSegmentsForTask}/{@code processTtsTaskAsync} 识别出的 {@code Task.language}
     * 反查。没配的语种（如运营还没接入意大利语 Pocket 音色）会走「不匹配→回落 MOSS」的兜底路径。
     *
     * <p>解析口径复用 {@link PocketVoiceConfig#parse(String)}（与 {@code pocket.tts.voices} 同格式），
     * 保证「运营在下拉里列出的 Pocket 音色」与「按语种自动挑到的 Pocket 音色」是同一批 id。
     */
    @Value("${pocket.tts.language-voices:}")
    private String pocketLanguageVoicesConfig;

    /** {@link #pocketLanguageVoicesConfig} 的惰性解析缓存（配置运行期不变）。 */
    private volatile Map<String, String> pocketLanguageVoices;

    /** 书级语种识别样本的字数上限（够词频投票即可，避免整本书几十万字进内存）。 */
    private static final int LANGUAGE_SAMPLE_CHARS = 24_000;

    public VoiceCloningTtsService(WebClient.Builder webClientBuilder, 
                                  ObjectMapper objectMapper,
                                  TaskRepository taskRepository,
                                  AudioSegmentRepository audioSegmentRepository,
                                  UserRepository userRepository,
                                  RedisTaskService redisTaskService,
                                  BookTextExtractor bookTextExtractor,
                         BookContentSnapshotService bookContentSnapshotService,
                                  CreditService creditService,
                                  TtsLoadBalancer loadBalancer,
                                  ShutdownSignal shutdownSignal) {
        this.webClient = webClientBuilder
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
        
        // 注入的 ObjectMapper 是全进程共享单例，直接改其 factory 的
        // StreamReadConstraints 会把「50MB 字符串上限」强加给所有模块的 JSON 解析
        //（副作用泄漏出本服务边界）。copy() 出私有实例再调约束，只影响本服务。
        ObjectMapper privateMapper = objectMapper.copy();
        privateMapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxStringLength(50 * 1024 * 1024)
                        .build());

        this.objectMapper = privateMapper;
        this.taskRepository = taskRepository;
        this.audioSegmentRepository = audioSegmentRepository;
        this.userRepository = userRepository;
        this.redisTaskService = redisTaskService;
        this.bookTextExtractor = bookTextExtractor;
        this.bookContentSnapshotService = bookContentSnapshotService;
        this.creditService = creditService;
        this.loadBalancer = loadBalancer;
        this.shutdownSignal = shutdownSignal;
    }

    /** 后台批量任务的单段合成超时上限 */
    @Value("${moss.tts.timeout-seconds:180}")
    private long ttsTimeoutSeconds;

    /**
     * 交互式接口（/clone、/synthesize）的合成超时上限，默认 60 秒。
     * 这两个端点是同步阻塞的：统一按 3 分钟等待时，几十个并发请求即可把
     * Tomcat 工作线程全部占死，整个服务（含登录、查询）一起不可用。
     * 下限也不能太低：引擎节点是串行执行的，交互式请求很可能排在某个批量段落后面，
     * 排队 + 自身耗时叠加后 30 秒会偶发超时（表现为「重试一次又好了」）。
     */
    @Value("${moss.tts.sync-timeout-seconds:60}")
    private long ttsSyncTimeoutSeconds;

    /**
     * 参考音频入库前的最大时长（秒）。{@code <=0} 关闭裁切，完全保持旧行为。
     *
     * <p>为什么要裁：MOSS-TTS-Nano 把参考音频编码成「每帧一行」的 prompt codes
     * （约 12.5 帧/秒），prompt 越长生成代价越是超线性增长。实测 2 vCPU / 3.6 GB：
     * 30 s 参考音频单请求 297 s（峰值内存撞 3.2 GB cgroup 上限被杀），
     * 裁到 12 s 后只要 15 s。音色克隆并不需要长素材 —— 8 s 已足够稳定复刻音色，
     * 而 prompt 与生成帧数是**相乘**关系，压低它能同时缩小每段的耗时与内存峰值。
     *
     * <p>节点侧另有 {@code MOSS_TTS_MAX_PROMPT_SECONDS} 截断护栏（那里是 12 s），
     * 那是**止损**：本值必须 ≤ 节点侧护栏，否则护栏会先动手，而入库的长素材
     * 仍要逐段上传（30 s 立体声 5.29 MB vs 8 s 单声道约 0.7 MB）。
     *
     * <p>本值同时被 {@code TtsControllerSupport} 用于 {@code /clone}、{@code /synthesize}
     * 的临时参考音频，两处必须同源，否则「入库的裁了、直传的没裁」。
     */
    @Value("${moss.tts.reference-max-seconds:8}")
    private float referenceMaxSeconds;

    /**
     * 是否按文本长度显式下发 {@code max_new_frames}。
     * 关闭后不下发该字段，引擎沿用自身默认值 375 帧（≈30 s 音频），即旧行为。
     */
    @Value("${moss.tts.frames.enabled:true}")
    private boolean framesEnabled;

    /** 每字符预留的生成帧数。引擎 12.5 帧/秒，此处 4.0 帧/字符 ≈ 3.1 字/秒（比正常朗读慢 30%+，留足余量不截断） */
    @Value("${moss.tts.frames.per-char:4.0}")
    private double framesPerChar;

    /** 固定帧数余量：应付句首静音、句末停顿与标点带来的额外时长 */
    @Value("${moss.tts.frames.base:30}")
    private int framesBase;

    /**
     * 帧数下限：短句也要给足起音与收音的余量。
     *
     * <p><b>默认值 30 必须与 {@code application.yml} 的 {@code moss.tts.frames.min} 一致</b>
     * （v5 / S4：这里原先是 60，而 yml 是 30）。yml 总提供值，所以线上一直生效 30；
     * 但代码默认一旦与 yml 漂移，将来谁把 yml 那个键删掉，行为会静默从 30 变成 60 ——
     * 表现为短句合成变慢、且白白多生成一段拖长音。默认值取两者中「线上实际值」才对。
     */
    /**
     * 是否允许「仅预设音色」合成（即不下发 prompt_audio 的 demo_id 通道）。
     *
     * <p><b>默认 true</b>：线上 MOSS-TTS-Nano 节点支持这条通道 ——
     * 实测只发 {@code text + demo_id}（不带 prompt_audio）返回 200 与完整 WAV。
     * 同时带 prompt_audio + demo_id 也正常（引擎以参考音频为准），所以开启本开关
     * 不会影响克隆路径。
     *
     * <p>本开关存在的意义是兼容「把 prompt_audio 当必填」的**老引擎**：
     * 那种节点上只发 text + demo_id 会被 400 拒掉，表现为
     * 「任务建成功、每一段都失败」，错误信息只有一句 {@code 400 Bad Request}，
     * 用户既看不懂也修不了。此时置 false，建任务阶段就明确拒绝并给出可操作提示。
     */
    @Value("${app.tts.voice-only.enabled:true}")
    private boolean voiceOnlyEnabled;

    @Value("${moss.tts.frames.min:30}")
    private int framesMin;

    /** 帧数上限：等于引擎默认值 375（≈30 s）。取值不超过它，保证不会比改造前更差 */
    @Value("${moss.tts.frames.max:375}")
    private int framesMax;

    /**
     * 多片拼接时、每个接缝单侧的淡出/淡入时长（毫秒）；0 或负数关闭。
     *
     * <p>纯粹的服务端音频后处理，引擎不参与（它一次只回一小片，从不做拼接）。
     * 用途与取值依据见 {@link com.audiobook.server.util.WavConcat#DEFAULT_JOINT_FADE_MILLIS}。
     * 关闭它即可与改造前的音频做 A/B 对比（其余链路逐字节相同）。
     */
    @Value("${app.tts.joint-fade-millis:15}")
    private int jointFadeMillis;

    /**
     * 段级包络整形总开关，<b>默认关闭</b>。关闭时输出与改造前逐字节一致，可用于 A/B 对比。
     *
     * <p>{@link WavEnvelope} 是「一句话开始和结束很突兀」的服务端解药：MOSS-TTS 每句都是
     * 独立冷启动合成，引擎给的段头尾静音长度不一致，末帧非零采样编成 MP3 就是「啪」，
     * 段间停顿忽长忽短。开启后每段都走「裁头尾静音 → 端点淡化 → 头尾统一补齐」三步。
     *
     * <p><b>为什么默认关</b>：2026-10-13 线上验收确认，关掉本项后音质达标；叠加子句接缝整形
     * （{@code subclause.join}）时，本项给段尾补的 150 ms 会把「按标点算好的停顿」再拉长一次，
     * 节奏反而被拉平（每片 +0.19 s）。代码保留 —— 通用后处理工具，换引擎时可能重新有用。
     */
    @Value("${app.tts.segment-envelope.enabled:false}")
    private boolean segmentEnvelopeEnabled;

    /**
     * 静音判定的采样绝对值阈值。16 bit PCM 满幅 32767，取 300 ≈ -40 dBFS，
     * 足以避开本底噪声又不误伤轻辅音。{@code <= 0} 关闭裁剪。
     */
    @Value("${app.tts.segment-envelope.trim-threshold:300}")
    private int segmentTrimThreshold;

    /**
     * 裁剪时头部保留的自然呼吸（毫秒）。裁到 0 会让首字听起来被切掉；30 ms 是听感下限。
     */
    @Value("${app.tts.segment-envelope.head-keep-millis:30}")
    private int segmentHeadKeepMillis;

    /**
     * 裁剪时尾部保留的自然呼吸（毫秒）。尾字收音需要更长余量，80 ms ≈ 一次自然停顿。
     */
    @Value("${app.tts.segment-envelope.tail-keep-millis:80}")
    private int segmentTailKeepMillis;

    /**
     * 头部统一补齐的静音长度（毫秒）。让所有段以等长静音起头 —— 播放器的 MP3 priming
     * 落在这里就不会被听见。40 ms 是舒适下限。
     */
    @Value("${app.tts.segment-envelope.lead-millis:40}")
    private int segmentLeadMillis;

    /**
     * 尾部统一补齐的静音长度（毫秒）。决定段间停顿的<b>基准</b> —— 150 ms ≈ 一次自然句读，
     * 比大部分引擎给的尾静音更整齐，也是「忽长忽短」的直接对治。
     */
    @Value("${app.tts.segment-envelope.tail-millis:150}")
    private int segmentTailMillis;

    /**
     * 首端淡入时长（毫秒）。5 ms 足以把首帧非零采样抹平；再长会让首字音量明显偏低。
     */
    @Value("${app.tts.segment-envelope.fade-in-millis:5}")
    private int segmentFadeInMillis;

    /**
     * 末端淡出时长（毫秒）。15 ms 与 {@link WavConcat#DEFAULT_JOINT_FADE_MILLIS} 同量级 ——
     * 短于 10 ms 压不住半周期的阶跃，长于 20 ms 会被听成一次可辨的音量凹陷。
     */
    @Value("${app.tts.segment-envelope.fade-out-millis:15}")
    private int segmentFadeOutMillis;

    /**
     * 子句接缝整形总开关（见 {@link WavClauseJoiner}）。
     *
     * <p>关闭后走改造前的路径（小片原样 {@link WavConcat#concat}），输出逐字节一致 —— A/B 对比用。
     */
    @Value("${app.tts.subclause.join.enabled:true}")
    private boolean subclauseJoinEnabled;

    /** 子句级静音判定阈值（采样绝对值）；300 ≈ -40 dBFS，与段级 envelope 同口径。 */
    @Value("${app.tts.subclause.join.trim-threshold:300}")
    private int subclauseTrimThreshold;

    /** 子句归一化后头部固定的静音长度（毫秒）；不足则补、超出则裁。 */
    @Value("${app.tts.subclause.join.head-keep-millis:25}")
    private int subclauseHeadKeepMillis;

    /** 子句归一化后尾部固定的静音长度（毫秒）；真正的句读长度由下面几个 pause 决定。 */
    @Value("${app.tts.subclause.join.tail-keep-millis:60}")
    private int subclauseTailKeepMillis;

    /** 「，」后的接缝停顿（毫秒）。 */
    @Value("${app.tts.subclause.join.comma-pause-millis:260}")
    private int subclauseCommaPauseMillis;

    /** 「、」后的接缝停顿（毫秒）。 */
    @Value("${app.tts.subclause.join.enumeration-pause-millis:70}")
    private int subclauseEnumerationPauseMillis;

    /** 「；」「：」后的接缝停顿（毫秒）。 */
    @Value("${app.tts.subclause.join.strong-pause-millis:300}")
    private int subclauseStrongPauseMillis;

    /** 硬切（片尾不是停顿标点）时的接缝停顿（毫秒）。切点在词中间，加停顿只会更明显，故取小值。 */
    @Value("${app.tts.subclause.join.hard-cut-pause-millis:40}")
    private int subclauseHardCutPauseMillis;

    /** 子句间是否做响度对齐（把每片有效发声 RMS 拉到各片中位数）。 */
    @Value("${app.tts.subclause.join.loudness-match:true}")
    private boolean subclauseLoudnessMatch;

    /** 单片响度增益上限（±dB）。超过 6 dB 的差异通常是引擎异常，硬拉会引入失真。 */
    @Value("${app.tts.subclause.join.loudness-max-gain-db:6}")
    private double subclauseLoudnessMaxGainDb;

    /**
     * 片内停顿封顶：低于本值（毫秒）的静音算发音缝隙（塞音闭塞/换气），一律不动。
     *
     * <p>动它会直接把字咬碎 —— 这是本项唯一不能商量的约束。
     */
    @Value("${app.tts.subclause.join.internal-pause-min-millis:220}")
    private int subclauseInternalPauseMinMillis;

    /**
     * 片内停顿的长度上限（毫秒）。<b>默认值由真机数据决定</b>（见 {@code agent_output/prosody_ab}）：
     * MOSS-TTS-Nano 在逗号处会插 0.7~2.0 s 静音，而每片最多 45 字，
     * 这些巨停顿全落在片<b>内部</b>，接缝整形管不到 —— 不封顶的话句内节奏依旧跳变。
     *
     * <p>{@code <= internal-pause-min-millis} 即关闭本项。
     */
    @Value("${app.tts.subclause.join.internal-pause-cap-millis:380}")
    private int subclauseInternalPauseCapMillis;

    /**
     * 单个段落的失败重试退避序列（秒，逗号分隔），长度即「最多重试几次」。
     * 默认 {@code 5,15,30} = 最多重试 3 次，分别等待 5/15/30 秒。
     * 配成空串即关闭段级重试，回到旧行为（失败即定死）。
     *
     * <p>为什么要段级重试：引擎被 cgroup OOM 杀掉后 systemd 会在 5 秒内把它拉起来，
     * 但这 5 秒里正在处理的那一段会以「连接提前关闭」失败、紧随其后的若干段以
     * 「连接被拒」失败。没有段级重试时这些段落直接被标成 failed 永久定格 ——
     * 一次 5 秒的引擎重启，就这样变成一本书 1450 段永久缺失（实测 taskId=337）。
     * 退避 5/15/30 秒正好跨过引擎重启窗口。
     */
    @Value("${app.tts.segment-retry.backoff-seconds:5,15,30}")
    private String segmentRetryBackoffSeconds;

    /** 退避序列的解析缓存（懒加载；@Value 字段在构造完成后才注入，不能提前解析） */
    private volatile long[] segmentRetryBackoffCache;

    /**
     * 节点全部不可用时，暂停合成、等待节点恢复的最长秒数。
     *
     * <p>上限刻意压得不大：{@code ttsTaskExecutor} 的线程数就是
     * {@code app.tts.concurrency}（默认 2），一个任务长期占着线程不放，
     * 新任务会被 AbortPolicy 直接拒绝、连队列都进不去。180 秒足够覆盖
     * 「引擎被 OOM 杀掉后自动重启」这类秒级恢复，又不会把线程池长期占死。
     */
    @Value("${moss.tts.nodes.wait-for-healthy-max-seconds:180}")
    private long nodeWaitMaxSeconds;

    /** 等待节点恢复期间的轮询间隔（毫秒） */
    @Value("${moss.tts.nodes.wait-for-healthy-poll-millis:2000}")
    private long nodeWaitPollMillis;

    /**
     * 启动时把「段级重试 / 节点等待」的实际生效参数打进日志。
     *
     * <p>这不是一条为了测试而加的日志：{@code app.tts.segment-retry.backoff-seconds}
     * 配成空串就会静默退回旧行为（一段失败即永久定格），而旧行为的症状是
     * 「引擎抖了一下，几百段音频再也没补回来」—— 排查时如果不能从启动日志一眼确认
     * 这个开关到底开没开，就得去翻配置文件和源码。
     */
    @PostConstruct
    void logRetryConfig() {
        long[] backoff = segmentRetryBackoffMillis();
        if (backoff.length == 0) {
            log.warn("========== 段级重试已关闭(app.tts.segment-retry.backoff-seconds 为空)："
                    + "单段失败将直接标记为 failed，引擎短暂重启会造成段落永久缺失 ==========");
        } else {
            StringBuilder sb = new StringBuilder();
            for (long b : backoff) {
                if (sb.length() > 0) {
                    sb.append('/');
                }
                sb.append(b / 1000).append('s');
            }
            log.info("========== 段级重试已启用：最多 {} 次，退避 {}；"
                            + "节点全部不可用时最多等待 {}s（轮询 {}ms）==========",
                    backoff.length, sb, nodeWaitMaxSeconds, nodeWaitPollMillis);
        }
    }

    @Override
    public byte[] synthesize(Request request) {
        return doSynthesize(request, Duration.ofSeconds(ttsTimeoutSeconds));
    }

    @Override
    public byte[] synthesizeInteractive(Request request) {
        return doSynthesize(request, Duration.ofSeconds(ttsSyncTimeoutSeconds));
    }

    /**
     * 流式版交互式合成：结果直接写入 {@code out}，不把整段音频驻留堆。
     * 供同步 HTTP 接口（/clone、/synthesize）把音频直接落临时文件后流式回传。
     */
    @Override
    public void synthesizeInteractiveTo(Request request, OutputStream out) throws IOException {
        doSynthesizeTo(request, Duration.ofSeconds(ttsSyncTimeoutSeconds), out);
    }

    /**
     * 交互式/批量合成的统一入口（返回 byte[] 形式）。
     * 实现上复用 {@link #callTtsEngine(Request, Duration)} 取引擎 JSON，
     * 再由 {@link #extractAudioFromJson(String)} 解出音频字节——保持与原行为完全一致，
     * 供批量合成与默认 {@code synthesizeInteractive} 使用。
     */
    private byte[] doSynthesize(Request request, Duration timeout) {
        if (resolveEngineType(request) == TtsEngineType.POCKET) {
            // Pocket /tts 直接返回 audio/wav 裸字节，不套 JSON/base64，故不经过 extractAudioFromJson
            byte[] wav = callPocketEngine(request, timeout);
            if (wav == null || wav.length == 0) {
                log.warn("从 Pocket TTS 服务接收到空音频数据");
                throw new RuntimeException("TTS 服务返回空音频数据");
            }
            log.info("Pocket TTS 合成完成，WAV音频大小: {} bytes", wav.length);
            return wav;
        }
        String jsonResponse = callTtsEngine(request, timeout);
        byte[] audioBytes = extractAudioFromJson(jsonResponse);
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("从 TTS 服务接收到空音频数据");
            throw new RuntimeException("TTS 服务返回空音频数据");
        }
        log.info("TTS 合成完成，WAV音频大小: {} bytes", audioBytes.length);
        return audioBytes;
    }

    /**
     * 与 {@link #doSynthesize(Request, Duration)} 语义相同，但把合成结果以 base64 流式解码
     * 后直接写入给定 OutputStream，**全程不把整段音频 byte[] 驻留堆**：
     * /clone、/synthesize 这类同步接口单段音频可达数 MB，并发请求叠加时堆占用显著。
     * base64 文本本身仍在堆，但解码为「边读边写」的流式过程，不再整体解码成 byte[]。
     */
    private void doSynthesizeTo(Request request, Duration timeout, OutputStream out) throws IOException {
        if (resolveEngineType(request) == TtsEngineType.POCKET) {
            // Pocket 直接给 audio/wav 裸字节，无需 base64 流式解码，整段写入 out 即可
            byte[] wav = callPocketEngine(request, timeout);
            if (wav == null || wav.length == 0) {
                throw new RuntimeException("TTS 服务返回空音频数据");
            }
            out.write(wav);
            out.flush();
            return;
        }
        String jsonResponse = callTtsEngine(request, timeout);
        String base64Audio = extractBase64Field(jsonResponse);
        if (base64Audio == null) {
            // extractBase64Field 内部已记录告警，这里对应原「空音频」分支的语义
            throw new RuntimeException("TTS 服务返回空音频数据");
        }
        writeBase64ToStream(base64Audio, out);
    }

    /**
     * 调用 TTS 引擎并返回原始 JSON 响应字符串。
     * 抽离后 {@code doSynthesize}（返回 byte[]）与 {@code doSynthesizeTo}（写流）共用，避免重复。
     *
     * <p>自多节点改造起，本方法同时承担「选点 + 故障切换」：每次调用向
     * {@link TtsLoadBalancer} 索取一个按优先级排序的候选节点序列，从队首开始尝试，
     * 遇到可重试的失败就顺延到下一台机器。这是唯一一处接触引擎地址的代码，
     * 因此四个合成入口（{@code synthesize} / {@code synthesizeInteractive} /
     * {@code synthesizeInteractiveTo} / 批量任务的逐段合成）全部自动获得多节点能力。
     */
    private String callTtsEngine(Request request, Duration timeout) {
        // multipart body 只构建一次，换节点重试时复用同一份。
        // 绝不能放进下面的循环里：MultipartBodyBuilder.part() 是「追加」语义，
        // 每轮重新构建会把 text / prompt_audio 重复塞进同一个 builder，
        // 引擎收到重复字段的行为未定义。
        // 复用是安全的：FileSystemResource 与 ByteArrayResource 都支持重复读取。
        MultiValueMap<String, HttpEntity<?>> body = buildMultipartBody(request);

        List<TtsNode> candidates = loadBalancer.candidates(TtsEngineType.MOSS);
        if (candidates.isEmpty()) {
            // 一台可用节点都没有：这是配置问题（节点全被停用/删除），
            // 不是引擎问题，给出可操作的提示而不是让前端看到超时
            log.error("❌ 当前没有可用的 MOSS TTS 节点，无法合成。请检查 tts_nodes 表或 moss.tts.url 配置");
            throw new RuntimeException("当前没有可用的语音合成节点，请联系管理员");
        }

        int textLength = request.getText().length();
        for (int i = 0; i < candidates.size(); i++) {
            TtsNode node = candidates.get(i);
            loadBalancer.onAcquire(node);
            try {
                String jsonResponse = invokeEngine(node, body, timeout, textLength);
                loadBalancer.onSuccess(node);
                return jsonResponse;
            } catch (RuntimeException e) {
                // 硬失败（连接被拒/域名解析失败）说明机器不可达，立即冷却；
                // 软失败（5xx/超时）累计到阈值才冷却，避免一次抖动摘掉好机器
                loadBalancer.onFailure(node, e, isConnectionLevelFailure(e));

                boolean isLast = (i == candidates.size() - 1);
                if (isLast || !isFailoverEligible(e)) {
                    // 最后一个候选，或该错误换机器也没用：原样抛出，
                    // 保留 TtsTimeoutException 等既有异常类型，
                    // 让段落级失败标记与任务终态的退款逻辑口径不变
                    throw e;
                }
                log.warn("⚠️ 节点 {} 调用失败，切换到下一节点重试: {}", node.getUrl(), e.getMessage());
            } finally {
                loadBalancer.onRelease(node);
            }
        }
        // 理论不可达：循环内要么 return 要么 throw。
        // 保留兜底是为了让编译器与后来者都能确定「本方法永不返回 null」
        throw new RuntimeException("TTS 合成失败：候选节点已全部尝试");
    }

    /**
     * 判定本次请求该走哪个引擎。
     *
     * <p><b>主判据：自动识别的书级语种。</b>{@code request.language} 由正文解析时对整本书识别一次
     * 得到（{@code other}/{@code zh}→MOSS，命中的拉丁语且运营已配该语种 Pocket 音色→POCKET）。
     * 交互式预览没有这个字段，则就地按当前文本兜底识别（单句判不准会回 {@code other}→MOSS，安全）。
     *
     * <p><b>硬保护：中文恒走 MOSS。</b>Pocket 只有拉丁语系模型，中文送进去产出的音频没有
     * 可听内容，而积分照扣。这条必须挡在次判据之前 —— 见 {@link #decideEngineType}。
     *
     * <p><b>次判据（兼容手动）：voiceId 是否属于 Pocket 音色清单。</b>保留这条是为了运营在不依赖
     * 语种识别时仍能手动指定某音色走 Pocket。两条任一命中即 POCKET；都不命中→MOSS。
     *
     * <p>总开关 {@code pocket.tts.enabled=false} 时恒为 MOSS，保证未部署 Pocket 前行为不变。
     * 之所以「语种可路由」还要求「该语种确有配置的 Pocket 音色」才分流：没接好音色的语种（如暂未
     * 提供意大利语）自动回落 MOSS（其自带多语音色），而不是把请求发给取不到音色的 Pocket 节点。
     */
    private TtsEngineType resolveEngineType(Request request) {
        return decideEngineType(pocketEnabled, effectiveLanguage(request),
                pocketLanguageVoicesMap(), pocketVoiceIdSet(), request.getVoiceId());
    }

    /**
     * 路由判定的纯函数本体（无副作用、不依赖 Spring，供单测直接断言）。
     *
     * <p>{@link #resolveEngineType} 只负责把运行期配置喂进来；判定顺序本身是本方法的契约：
     * <ol>
     *   <li>Pocket 总开关关 → MOSS；</li>
     *   <li><b>中文 → MOSS（短路）</b>；</li>
     *   <li>语种是 Pocket 可路由的拉丁语系、且该语种确已配好音色 → POCKET；</li>
     *   <li>voiceId 命中 Pocket 音色清单（手动指定的逃生门）→ POCKET；</li>
     *   <li>其余 → MOSS。</li>
     * </ol>
     */
    static TtsEngineType decideEngineType(boolean pocketEnabled,
                                          String language,
                                          Map<String, String> languageVoices,
                                          Set<String> pocketVoiceIds,
                                          String voiceId) {
        if (!pocketEnabled) {
            return TtsEngineType.MOSS;
        }
        // 中文恒走 MOSS，且必须写在下面的 voiceId 判据【之前】：那条判据是为「手动指定音色」
        // 留的逃生门，它会绕过语种判定。用户在音色下拉里给一本中文书选了 Alba（Pocket 音色）时，
        // 整本书（实测 3512 段）都会发往英文模型 —— 音频无意义、积分照扣，且任务状态还是
        // completed，事后从数据上完全看不出异常。故在入口处短路，不再往下判。
        if (LanguageDetector.ZH.equals(language)) {
            return TtsEngineType.MOSS;
        }
        if (LanguageDetector.isPocketRoutable(language)
                && !languageVoices.getOrDefault(language, "").isBlank()) {
            return TtsEngineType.POCKET;
        }
        if (isPocketVoice(pocketVoiceIds, voiceId)) {
            return TtsEngineType.POCKET;
        }
        return TtsEngineType.MOSS;
    }

    /**
     * {@code voiceId} 是否是 Pocket 音色清单里的一个（大小写不敏感、忽略首尾空白）。
     *
     * <p>大小写不敏感是必要的：客户端本应回传配置里的 id（{@code alba}），但它也可能误传显示名
     * （{@code Alba}）。若这里严格区分大小写，那个值会被当成「MOSS 音色」原样下发给 MOSS，
     * 得到 400 {@code Unknown demo_id: Alba}，整本任务逐段失败。
     */
    static boolean isPocketVoice(Set<String> pocketVoiceIds, String voiceId) {
        if (pocketVoiceIds == null || pocketVoiceIds.isEmpty() || voiceId == null || voiceId.isBlank()) {
            return false;
        }
        String trimmed = voiceId.trim();
        if (pocketVoiceIds.contains(trimmed)) {
            return true;
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        return !lower.equals(trimmed) && pocketVoiceIds.contains(lower);
    }

    /**
     * 本次判定用的语种：优先取请求携带的书级识别结果；为空（交互式预览等）时就地对文本识别一次。
     * 交互式文本通常只有一句，识别易失准 → {@link LanguageDetector} 会回 {@code other}，
     * 从而安全地留在 MOSS；批量任务始终带着书级语种，不走这条兜底。
     */
    private String effectiveLanguage(Request request) {
        String lang = request.getLanguage();
        if (lang != null && !lang.isBlank()) {
            return lang.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return LanguageDetector.detect(request.getText());
    }

    /** 惰性解析 {@code pocket.tts.language-voices} 为 {@code 语种 -> Pocket 音色 id} 映射。 */
    private Map<String, String> pocketLanguageVoicesMap() {
        Map<String, String> cached = pocketLanguageVoices;
        if (cached == null) {
            cached = Collections.unmodifiableMap(PocketVoiceConfig.parse(pocketLanguageVoicesConfig));
            pocketLanguageVoices = cached;
        }
        return cached;
    }

    /**
     * 选定走 Pocket 时实际下发的音色（{@code voice_url}）。
     *
     * <p>优先级：①书级语种在 {@code language-voices} 里映射到的音色；②请求本身带的、且确属
     * Pocket 清单的 voiceId（手动指定）；③清单里的第一个（最后一道兜底，保证 {@code voice_url}
     * 非空、不因缺音色而 400）。MOSS 的 demo_id 不会出现在这里——那条路径根本不调本方法。
     */
    private String resolvePocketVoice(Request request) {
        String lang = effectiveLanguage(request);
        String mapped = pocketLanguageVoicesMap().get(lang);
        if (mapped != null && !mapped.isBlank()) {
            return mapped.trim();
        }
        String voiceId = request.getVoiceId();
        if (voiceId != null && !voiceId.isBlank() && pocketVoiceIdSet().contains(voiceId.trim())) {
            return voiceId.trim();
        }
        Set<String> ids = pocketVoiceIdSet();
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    /** 从 {@code pocket.tts.voices} 惰性解析出的音色 id 集合；配置运行期不变，构建一次即缓存。 */
    private Set<String> pocketVoiceIdSet() {
        Set<String> cached = pocketVoiceIds;
        if (cached == null) {
            cached = Collections.unmodifiableSet(
                    new LinkedHashSet<>(PocketVoiceConfig.parse(pocketVoicesConfig).keySet()));
            pocketVoiceIds = cached;
        }
        return cached;
    }

    /**
     * Pocket-TTS 的调度入口，与 {@link #callTtsEngine} 同构（选点 + 故障切换 + 运行时上报），
     * 区别只有两点：候选点限定为 {@code POCKET} 节点，且引擎直接返回裸 WAV 而非 JSON。
     *
     * <p>单独成一条通道而不是把 {@code callTtsEngine} 的返回类型改成 {@code byte[]}，
     * 是为了保住 MOSS 那条已经调好的「base64 边读边写、整段不落堆」流式优化——
     * 两种引擎的响应形态差异太大，硬合并会让任一侧为另一侧买单。
     */
    private byte[] callPocketEngine(Request request, Duration timeout) {
        MultiValueMap<String, HttpEntity<?>> body = buildPocketMultipartBody(request);

        List<TtsNode> candidates = loadBalancer.candidates(TtsEngineType.POCKET);
        if (candidates.isEmpty()) {
            log.error("❌ 当前没有可用的 Pocket TTS 节点，无法合成。请在 tts_nodes 表添加 engine_type=POCKET 的节点并启用");
            throw new RuntimeException("当前没有可用的语音合成节点，请联系管理员");
        }

        int textLength = request.getText() == null ? 0 : request.getText().length();
        for (int i = 0; i < candidates.size(); i++) {
            TtsNode node = candidates.get(i);
            loadBalancer.onAcquire(node);
            try {
                byte[] wav = invokePocketEngine(node, body, timeout, textLength);
                loadBalancer.onSuccess(node);
                return wav;
            } catch (RuntimeException e) {
                loadBalancer.onFailure(node, e, isConnectionLevelFailure(e));
                boolean isLast = (i == candidates.size() - 1);
                if (isLast || !isFailoverEligible(e)) {
                    throw e;
                }
                log.warn("⚠️ Pocket 节点 {} 调用失败，切换到下一节点重试: {}", node.getUrl(), e.getMessage());
            } finally {
                loadBalancer.onRelease(node);
            }
        }
        throw new RuntimeException("Pocket TTS 合成失败：候选节点已全部尝试");
    }

    /**
     * 构建 Pocket {@code /tts} 的 multipart body。
     *
     * <p>与 MOSS 的差异：Pocket 用 {@code voice_url}（内置音色名 / http / https / hf:// 参考音频 URL）
     * 做音色选择，实时克隆则改用 {@code voice_wav}（文件）——二者互斥，同时下发会被 Pocket 判为非法。
     * Pocket 也没有 {@code max_new_frames} 概念（按其内部时长生成），故不下发。
     *
     * <p>文本清理沿用 {@link #CJK_ADJACENT_WHITESPACE}：它只吃「一侧紧邻 CJK」的空白，
     * 对英文/法/德等 Pocket 语言是恒等变换（词间空格两侧都不是 CJK，不会被删），因此可安全复用。
     */
    private MultiValueMap<String, HttpEntity<?>> buildPocketMultipartBody(Request request) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        String cleanedText = CJK_ADJACENT_WHITESPACE
                .matcher(request.getText() == null ? "" : request.getText())
                .replaceAll("")
                .trim();
        builder.part("text", cleanedText);

        File referenceFile = request.getReferenceAudioFile();
        byte[] referenceBytes = request.getReferenceAudio();
        boolean hasReference = (referenceFile != null && referenceFile.isFile())
                || (referenceBytes != null && referenceBytes.length > 0);

        if (hasReference) {
            // 实时克隆：以 voice_wav 文件上传，忽略 voice_url（互斥）
            if (referenceFile != null && referenceFile.isFile()) {
                builder.part("voice_wav",
                                new FileSystemResource(referenceFile) {
                                    @Override
                                    public String getFilename() {
                                        return "reference.wav";
                                    }
                                })
                        .contentType(MediaType.APPLICATION_OCTET_STREAM);
            } else {
                builder.part("voice_wav",
                                new ByteArrayResource(referenceBytes) {
                                    @Override
                                    public String getFilename() {
                                        return "reference.wav";
                                    }
                                })
                        .contentType(MediaType.APPLICATION_OCTET_STREAM);
            }
        } else {
            // 预置音色：按书级语种映射到 Pocket 音色（自动分流下 request.voiceId 可能是 MOSS 的
            // demo_id，对 Pocket 无意义，故用 resolvePocketVoice 重新取该语种应下发的音色）。
            String pocketVoice = resolvePocketVoice(request);
            if (pocketVoice != null && !pocketVoice.isBlank()) {
                builder.part("voice_url", pocketVoice);
            }
        }

        return builder.build();
    }

    /**
     * 向指定 Pocket 节点发起一次 {@code /tts} 调用，返回裸 WAV 字节。
     * 异常翻译与 {@link #invokeEngine} 完全一致，好让上层「能否换节点重试」「是否超时」的判定口径统一。
     */
    private byte[] invokePocketEngine(TtsNode node, MultiValueMap<String, HttpEntity<?>> body,
                                      Duration timeout, int textLength) {
        String url = node.getUrl();
        try {
            log.info("开始调用 Pocket TTS API: node={}({}), 文本长度: {} 字符", node.getName(), url, textLength);

            byte[] wav = webClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(timeout)
                    .block(timeout.plusSeconds(5));

            if (wav == null || wav.length == 0) {
                log.error("Pocket TTS 服务返回空音频: node={}", url);
                throw new RuntimeException("TTS 服务返回空音频数据");
            }

            log.info("Pocket TTS API 调用成功: node={}, 音频大小: {} bytes", url, wav.length);
            return wav;
        } catch (WebClientResponseException e) {
            log.error("Pocket TTS 引擎返回错误状态: node={}, status={}, body={}",
                    url, e.getStatusCode(), abbreviate(e.getResponseBodyAsString(), 300));
            throw e;
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.error("Pocket TTS 合成超时（{}）: node={}, 文本长度: {} 字符", timeout, url, textLength, e);
                throw new TtsTimeoutException("TTS 合成超时，请稍后重试或缩短文本长度", e);
            }
            log.error("Pocket TTS 合成失败: node={}, {}", url, e.getMessage(), e);
            throw new RuntimeException("TTS 合成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Pocket TTS 合成异常: node={}, {}", url, e.getMessage(), e);
            throw new RuntimeException("TTS 合成异常: " + e.getMessage(), e);
        }
    }

    /**
     * 构建合成请求的 multipart body。
     *
     * @return 可直接复用多次的 body（资源均为可重复读取类型）
     */
    private MultiValueMap<String, HttpEntity<?>> buildMultipartBody(Request request) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();

        // 只吃「一侧紧邻 CJK 字符或中文标点」的空白：中文里冗余的空格/换行被清理掉，
        // 英文单词之间的空格保留。改造前用 text.replaceAll("\\s+", "") 会把
        // "Hello world." 变成 "Helloworld."，中英混排/纯英文书读音直接错乱。
        String textWithoutSpaces = CJK_ADJACENT_WHITESPACE.matcher(request.getText()).replaceAll("").trim();
        builder.part("text", textWithoutSpaces);

        // 显式下发 max_new_frames。不下发时引擎用默认 375 帧（≈30 s 音频）：
        // 而一次请求的文本只有 ≤45 字（超长句已在小片那一层拆开），按 30 秒生成既慢又占内存。
        // 调用方给了具体值就用它（可做单次覆盖），否则按文本长度推算。
        Integer maxNewFrames = request.getMaxNewFrames() != null
                ? request.getMaxNewFrames()
                : resolveMaxNewFrames(textWithoutSpaces);
        if (maxNewFrames != null && maxNewFrames > 0) {
            builder.part("max_new_frames", String.valueOf(maxNewFrames));
        }

        // 预置音色必须透传给引擎，否则「预置音色合成」永远输出默认音色、功能形同虚设；
        // 与参考音频同时存在时引擎以参考音频（克隆）优先，不影响 /clone 行为。
        // 但 Pocket 音色（alba 等）不是 MOSS 的 demo_id，见 mossVoiceFor 的说明。
        String mossVoice = mossVoiceFor(request.getVoiceId(), pocketVoiceIdSet(), mossFallbackVoice);
        if (mossVoice != null) {
            builder.part(ttsVoiceParam, mossVoice);
        }

        // 优先走文件形式：File 形式可让 WebClient 以流的方式读取磁盘文件，
        // ≤50MB 的参考音频全程不进堆；字节数组形式保留给仍持有 byte[] 的调用方。
        File referenceFile = request.getReferenceAudioFile();
        if (referenceFile != null && referenceFile.isFile()) {
            builder.part("prompt_audio",
                            new FileSystemResource(referenceFile) {
                                @Override
                                public String getFilename() {
                                    return "reference.wav";
                                }
                            })
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
        } else if (request.getReferenceAudio() != null && request.getReferenceAudio().length > 0) {
            builder.part("prompt_audio",
                            new ByteArrayResource(request.getReferenceAudio()) {
                                @Override
                                public String getFilename() {
                                    return "reference.wav";
                                }
                            })
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return builder.build();
    }

    /**
     * MOSS 请求实际下发的音色 id（本方法只服务 MOSS，Pocket 走 {@link #buildPocketMultipartBody}）。
     *
     * <p>{@code voiceId} 为空 → 返回 {@code null}（不下发，交给克隆通道或引擎默认值）；
     * Pocket 音色 → 换成 {@code fallbackVoice}；其余（{@code demo-N}）原样透传。
     *
     * <p>为什么要换而不是原样发：{@link #decideEngineType} 已把中文钉死在 MOSS，但音色 id 仍在
     * 请求里。线上 MOSS 对未知 {@code demo_id} 回 400 {@code Unknown demo_id: alba}，
     * 于是「选了英文音色的中文书」会从「产出无意义音频」变成「整本每段都失败」。
     * 换成兜底音色后，用户听到的是正常中文朗读，只是音色不是他选的 —— 比失败或念不出字都好。
     *
     * <p>纯函数（配置由调用方传入），便于单测直接覆盖「Pocket 音色必须被替换」这条。
     */
    static String mossVoiceFor(String voiceId, Set<String> pocketVoiceIds, String fallbackVoice) {
        if (voiceId == null || voiceId.isBlank()) {
            return null;
        }
        if (!isPocketVoice(pocketVoiceIds, voiceId)) {
            return voiceId.trim();
        }
        return fallbackVoice == null || fallbackVoice.isBlank() ? "demo-1" : fallbackVoice.trim();
    }

    /**
     * 按文本长度推算本次合成需要的 {@code max_new_frames}。
     *
     * <p>公式：{@code 帧数 = framesBase + 字符数 × framesPerChar}，再夹到
     * {@code [framesMin, framesMax]} 区间内。
     *
     * <p>换算依据：MOSS-TTS-Nano 的 375 帧 ≈ 30 秒音频，即 **12.5 帧/秒**；
     * 中文 TTS 正常朗读约 4~6 字/秒，取 4.0 帧/字符（≈3.1 字/秒）是刻意保守的估计 ——
     * 宁可多给几帧（多花一点生成时间），也不能少给导致句子被从中间截断。
     *
     * <p>上限取引擎默认值 375：这样对任何文本，生成的音频长度都不会比改造前更短，
     * 「短文本省时间」的收益不会以「长文本被截断」为代价。
     * 若某段文本按公式需要的帧数超过上限，说明它可能需要更长的音频，
     * 此时打 WARN 记录（日志是唯一能事后发现截断线索的地方）。
     *
     * @param text 已去除空白字符的待合成文本
     * @return 要下发的帧数；返回 {@code null} 表示不下发该字段（引擎用自身默认值）
     */
    Integer resolveMaxNewFrames(String text) {
        if (!framesEnabled || text == null || text.isEmpty()) {
            return null;
        }
        long desired = estimateFrames(text.length(), framesPerChar, framesBase);
        if (desired > framesMax) {
            log.warn("文本 {} 字按估算需 {} 帧（约 {} 秒），已按上限 {} 帧下发，本段音频可能被截断；"
                            + "如需完整合成请调大 moss.tts.frames.max（注意单段耗时与内存会随之上升）",
                    text.length(), desired, String.format("%.1f", desired / ENGINE_FRAMES_PER_SECOND), framesMax);
        }
        return clampFrames(desired, framesMin, framesMax);
    }

    /**
     * 按文本长度估算所需帧数（夹上下限之前的原始值）。
     *
     * <p>{@code 帧数 = baseFrames + 字符数 × framesPerChar}。
     * 换算依据：中文 TTS 正常朗读约 4~6 字/秒，{@code framesPerChar} 取 4.0 帧/字符
     * （折算 ≈3.1 字/秒）是刻意保守的估计 ——
     * 宁可多给几帧（多花一点生成时间），也不能少给导致句子被从中间截断。
     */
    static long estimateFrames(int textLength, double framesPerChar, int baseFrames) {
        return Math.round(Math.ceil(baseFrames + textLength * framesPerChar));
    }

    /**
     * 把估算帧数夹到 {@code [minFrames, maxFrames]}。
     *
     * <p>上限默认取引擎默认值 375（≈30 秒音频），这样无论文本多长，
     * 生成的音频都不会比改造前（一律 375 帧）更短 ——
     * 「短文本省时间」的收益不会以「长文本被截断」为代价。
     */
    static int clampFrames(long frames, int minFrames, int maxFrames) {
        return (int) Math.max(minFrames, Math.min(frames, maxFrames));
    }

    /**
     * 向指定节点发起一次合成调用，并把底层异常翻译成上层可判断的异常类型。
     *
     * <p><b>异常不在这里统一包成 RuntimeException</b>，因为上层要靠具体类型决定
     * 能否换节点重试：{@link WebClientResponseException} 原样抛出以保留 HTTP 状态码，
     * 超时则转成 {@link TtsTimeoutException}（不可重试）。
     */
    private String invokeEngine(TtsNode node, MultiValueMap<String, HttpEntity<?>> body,
                                Duration timeout, int textLength) {
        String url = node.getUrl();
        try {
            log.info("开始调用 TTS API: node={}({}), 文本长度: {} 字符", node.getName(), url, textLength);

            String jsonResponse = webClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block(timeout.plusSeconds(5));

            if (jsonResponse == null || jsonResponse.isEmpty()) {
                log.error("TTS 服务返回空响应: node={}", url);
                throw new RuntimeException("TTS 服务返回空响应");
            }

            log.info("TTS API 调用成功: node={}, 响应长度: {} 字符", url, jsonResponse.length());
            return jsonResponse;
        } catch (WebClientResponseException e) {
            // 引擎返回了 HTTP 错误状态。不包裹，让上层按状态码判断是否换节点
            log.error("TTS 引擎返回错误状态: node={}, status={}, body={}",
                    url, e.getStatusCode(), abbreviate(e.getResponseBodyAsString(), 300));
            throw e;
        } catch (IllegalStateException e) {
            // Mono.block(Duration) 超时会抛 IllegalStateException，真正的超时异常在 cause 里
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                log.error("TTS 合成超时（{}）: node={}, 文本长度: {} 字符。可能原因：网络延迟、服务负载高或文本过长",
                         timeout, url, textLength, e);
                throw new TtsTimeoutException("TTS 合成超时，请稍后重试或缩短文本长度", e);
            }
            log.error("TTS 合成失败: node={}, {}", url, e.getMessage(), e);
            throw new RuntimeException("TTS 合成失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("TTS 合成异常: node={}, {}", url, e.getMessage(), e);
            throw new RuntimeException("TTS 合成异常: " + e.getMessage(), e);
        }
    }

    /**
     * 该错误是否值得换一台机器重试。
     *
     * <p><b>超时一律不重试</b>：超时说明引擎正在算，换节点等于让同一段文本
     * 被两台机器同时计算。慢节点的超时会把负载转移给它「旁边」的节点，
     * 一旦整体变慢就是级联雪崩 —— 宁可让这一段快速失败，由用户在任务列表里重试。
     *
     * <p>4xx 也不重试：通常是请求本身有问题（字段名不匹配、参数非法），
     * 换哪台机器都一样失败，重试只是白白多占一次算力。
     */
    private boolean isFailoverEligible(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TtsTimeoutException) {
                return false;
            }
            if (current instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                // 5xx = 引擎自身故障；429 = 被引擎限流。换台机器是合理的规避
                return status >= 500 || status == 429;
            }
            if (current instanceof WebClientRequestException) {
                // 连接被拒、域名解析失败、连接中断：机器不可达，换节点必然有意义
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /**
     * 是否为「硬失败」：机器明确不可达。这类失败无需累计，一次即可让节点进入冷却。
     * 与 {@link #isFailoverEligible} 的区别是它不考虑重试次数配额，
     * 只看失败性质 —— 一个不可重试的硬失败（已是最后一个候选）同样说明机器挂了。
     */
    private boolean isConnectionLevelFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WebClientRequestException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /** 日志用截断，避免引擎返回的整页 HTML 错误页把日志刷爆 */
    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    @Override
    public String uploadReferenceAudio(MultipartFile referenceAudio) throws IOException {
        log.info("单独上传参考音频到目录: {}", uploadDir);
        String referenceFileName = saveReferenceAudio(referenceAudio);
        log.info("参考音频保存成功，文件名: {}", referenceFileName);
        return referenceFileName;
    }

    /**
     * 保存参考音频，并在入库前把超长素材裁短。
     *
     * <p>与 {@link #saveUploadedFile} 的区别只在最后一步：本方法保证**落盘的参考音频不超过
     * {@code moss.tts.reference-max-seconds} 秒**（超长时裁成单声道 44.1kHz WAV，
     * 扩展名同步改为 .wav，因为内容确实变了）。
     * 所有参考音频入口（/upload-reference、/references、/references/{id}/update、
     * 建任务时随书一起上传）都必须走本方法，否则会漏掉裁切。
     *
     * <p>裁切是可选的优化路径，**永不因它让上传失败**：探测不到时长、裁切失败、
     * 替换失败都只记日志并放行原文件（节点侧另有 prompt 截断护栏兜底）。
     */
    private String saveReferenceAudio(MultipartFile referenceAudio) throws IOException {
        String fileName = saveUploadedFile(referenceAudio);
        return capReferenceAudioInPlace(fileName);
    }

    /**
     * 把已落盘的参考音频裁到 {@link #referenceMaxSeconds} 秒以内（原地替换）。
     *
     * <p>用于**入库时**：此时文件名还没落库，可以自由改成 .wav（内容确实变成 WAV 了）。
     *
     * @param fileName uploads 目录下的文件名
     * @return 裁切后实际生效的文件名；无需裁切或裁切失败时原样返回
     */
    private String capReferenceAudioInPlace(String fileName) {
        File stored = resolveUploadFile(fileName);
        if (stored == null || !stored.isFile()) {
            return fileName;
        }
        File capped = capReferenceAudioInPlace(stored, true);
        // 没发生替换时原样返回入参：fileName 理论上可能带子目录，
        // 而 File.getName() 只取末段，直接返回它会把目录信息丢掉
        return capped.equals(stored) ? fileName : capped.getName();
    }

    /**
     * 同上，但直接操作 File。返回**最终生效的文件**（可能还是原文件）。
     *
     * <p>三条短路规则见 {@link AudioVideoUtil#capDurationToWav}：探测失败、本来就够短、
     * 裁切失败，一律返回原文件 —— 裁切是可选的优化，绝不能让它把上传/合成搞失败。
     *
     * @param allowRename 是否允许把文件名改成 .wav。
     *                    <b>只有入库路径可以传 true</b>：那时文件名尚未落库。
     *                    对**已经落库**的素材（任务表 / 我的参考音频库 / 共享库）必须传 false ——
     *                    改了名之后按落库文件名就找不到文件了（表现为试听与下载 404）。
     */
    private File capReferenceAudioInPlace(File stored, boolean allowRename) {
        if (referenceMaxSeconds <= 0 || stored == null || !stored.isFile()) {
            return stored;
        }
        String desiredName = replaceExtensionWithWav(stored.getName());
        if (!canCapWithoutRename(stored.getName(), allowRename)) {
            // 非 WAV 的长素材在惰性路径下跳过：它仍会被节点侧的 prompt 截断护栏兜住，
            // 功能不受影响，只是每段仍要上传整份长音频（带宽换正确性，这里选正确性）
            log.info("参考音频 {} 超长但格式不是 WAV，跳过惰性裁切（改名会让库里的记录失效）",
                    stored.getName());
            return stored;
        }
        File capped = AudioVideoUtil.capDurationToWav(stored, referenceMaxSeconds, "ref_capped_");
        if (capped == null || capped.equals(stored)) {
            return stored;
        }
        String finalName = AudioVideoUtil.replaceWithCappedWav(stored, capped, desiredName);
        return new File(stored.getParentFile(), finalName);
    }

    /**
     * 能否在不改文件名（扩展名）的前提下完成裁切。
     *
     * <p>裁切会把内容统一转成 WAV，所以文件名理应变成 .wav。但**已经落库**的素材
     * （任务表的 reference_audio_path、我的参考音频库、服务器共享库）是按文件名引用的，
     * 一旦改名，按库里记录去找文件就会 404 —— 试听、下载、后续合成都跟着坏。
     * 因此这类素材只有在「扩展名本来就是 .wav、改名是空操作」时才允许裁。
     *
     * @param currentName 当前文件名
     * @param allowRename 调用方是否允许改名（只有「文件名尚未落库」的入库路径可以）
     * @return true 表示可以裁切
     */
    static boolean canCapWithoutRename(String currentName, boolean allowRename) {
        if (allowRename) {
            return true;
        }
        return replaceExtensionWithWav(currentName).equals(currentName);
    }

    /** {@code file_xxx.mp3} → {@code file_xxx.wav}；无扩展名时直接追加 .wav */
    private static String replaceExtensionWithWav(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName) + ".wav";
    }

    @Override
    public Long createTtsTask(MultipartFile bookFile, MultipartFile referenceAudio, String referenceFileName,
                             String bookExtension, String userId, String defaultVoiceId, String segmentVoicesJson,
                             Long storeBookId) throws IOException {
        // 重 IO 全部在事务外完成，只有「建任务 + 扣费 + 注册派发」
        // 走短事务（经 self 代理调用，保证 @Transactional 生效）；
        // 文件落盘（几十 MB 电子书）与视频→WAV ffmpeg 转码（可达分钟级重 IO）
        // 放进事务会长时间占用数据库连接，高并发上传时耗尽连接池。
        // chargeForTask 的 Propagation.MANDATORY 原子扣费约束不受影响。
        //
        // 先查用户 + 余额预检：这是本方法最常见的失败源。提前校验保证失败时文件尚未落盘，
        // 不必先写几十 MB 再清理（两个调用各自走隐式短事务，不会拉长任何连接占用）。
        User user = userRepository.findByUsername(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在: " + userId));

        // 积分余额预检（不加锁，只为快速失败）：余额本来就不够的请求在这里就抛 402，
        // 不必先把几十 MB 的电子书写入磁盘再靠回滚清理。真正的原子扣减在下方短事务内
        // 由 creditService.chargeForTask 完成。
        creditService.preCheckBalance(creditService.getRule(), user);

        // 记录本次新建的文件；事务开启前无回滚清理可用，失败路径需手动清理。
        List<String> createdFiles = new java.util.ArrayList<>();

        log.info("保存上传文件到目录: {}", uploadDir);
        String bookFileName = saveUploadedFile(bookFile);
        createdFiles.add(bookFileName);

        // 逐段绑定预设音色时，那些段同样走 voice_id 通道、不下发 prompt_audio，
        // 在本节点上必然 400。与「仅预设音色」共用一个开关，一起在读之前挡住。
        if (!voiceOnlyEnabled && segmentVoicesJson != null && !segmentVoicesJson.isBlank()) {
            deleteUploadedFile(bookFileName);
            throw new IllegalArgumentException(
                    "当前合成节点要求参考音频（prompt_audio），不支持按段落绑定预设音色。"
                            + "请改用参考音频（整本或逐段都用参考音频）。");
        }

        // 优先使用已上传的参考音频文件名，否则保存新的
        String actualReferenceFileName;
        if (referenceFileName != null && !referenceFileName.isEmpty()) {
            actualReferenceFileName = referenceFileName;
            log.info("使用已上传的参考音频: {}", actualReferenceFileName);
        } else if (referenceAudio != null && !referenceAudio.isEmpty()) {
            try {
                // 走 saveReferenceAudio 而非 saveUploadedFile：随书一起上传的参考音频同样要裁到
                // referenceMaxSeconds 秒内，否则每一段合成都会带着一份 30 s 的长 prompt 去请求引擎
                actualReferenceFileName = saveReferenceAudio(referenceAudio);
                createdFiles.add(actualReferenceFileName);
                log.info("保存新上传的参考音频: {}", actualReferenceFileName);
            } catch (IOException e) {
                // 事务外没有回滚清理，必须手动删除已落盘的 book 文件，否则成为孤儿
                deleteUploadedFile(bookFileName);
                throw e;
            }
        } else if (defaultVoiceId != null && !defaultVoiceId.isBlank()) {
            // 仅预设音色：当前节点不支持（它把 prompt_audio 当必填）。
            // 与其让任务建成后每段都 400、留下一个 failed 任务和一堆无信息量的错误，
            // 不如在这里就说清楚该怎么办。
            if (!voiceOnlyEnabled) {
                deleteUploadedFile(bookFileName);
                throw new IllegalArgumentException(
                        "当前合成节点要求参考音频（prompt_audio），无法只用预设音色合成。"
                                + "请上传一段参考音频，或从「参考音频库」选择一条后再试。");
            }
            log.info("使用预设音色合成（无参考音频）: {}", defaultVoiceId);
            actualReferenceFileName = null;
        } else {
            // 没有任何参考音频也没有预设音色：book 文件已落盘但任务不会建立，手动清理后抛业务异常
            deleteUploadedFile(bookFileName);
            throw new IllegalArgumentException("必须提供参考音频文件、reference_id 或预设音色 voice_id");
        }

        log.info("文件保存完成 - 电子书: {}, 参考音频: {}", bookFileName, actualReferenceFileName);
        log.info("找到用户: ID={}, 用户名={}", user.getId(), user.getUsername());

        String originalBookName = bookFile.getOriginalFilename();
        if (originalBookName != null && originalBookName.contains(".")) {
            int dotIndex = originalBookName.lastIndexOf('.');
            originalBookName = originalBookName.substring(0, dotIndex);
        }

        try {
            return self.createTaskRecord(user, bookFileName, actualReferenceFileName, originalBookName,
                    defaultVoiceId, segmentVoicesJson, createdFiles, storeBookId);
        } catch (RuntimeException e) {
            // 兜底：短事务若在「注册回滚清理之前」就失败（如数据库不可用导致事务无法开启），
            // 已落盘文件无人清理。这里主动补删（幂等，事务回滚路径已删过也不会有副作用）。
            for (String fileName : createdFiles) {
                deleteUploadedFile(fileName);
            }
            throw e;
        }
    }

    /**
     * 建任务短事务：任务落库 + 原子扣费 + 注册「提交后派发」。
     *
     * 文件落盘与转码（重 IO）已在事务外完成，本方法只保留必须同生共死的三步：
     * 任务记录、积分扣减（{@link CreditService#chargeForTask} 要求 MANDATORY 事务）、
     * 提交后派发注册。任一失败整体回滚，createdFiles 中的文件由注册的回滚同步器清理。
     *
     * 注意：必须经 self 代理调用（本类方法直接 this 调用不走事务代理）。
     */
    @Transactional
    public Long createTaskRecord(User user, String bookFileName, String referenceFileName,
                                 String originalBookName, String defaultVoiceId, String segmentVoicesJson,
                                 List<String> createdFiles, Long storeBookId) {
        // 第一时间注册回滚清理：此后到提交前的任何异常触发回滚，已落盘文件都会被统一删除
        registerFileCleanupOnRollback(createdFiles);

        Task task = new Task(user.getId(), bookFileName, referenceFileName);
        task.setOriginalBookName(originalBookName);
        // 书城书籍：把书 id 记在任务上，异步提取正文时据此落「正文快照」（W1）
        task.setStoreBookId(storeBookId);
        // 记录任务级默认音色与段落级音色映射，供异步切段时逐段落库
        task.setDefaultVoiceId(defaultVoiceId);
        task.setSegmentVoices(segmentVoicesJson);

        Task savedTask = taskRepository.save(task);

        // 扣积分：必须在本事务内、任务已拿到 ID 之后执行（流水要记 taskId）。
        // 余额不足会抛出 InsufficientCreditsException 触发回滚 —— 任务记录不落库，
        // 已落盘的文件由上方注册的回滚清理删除，不留任何痕迹。
        // 文件落盘（慢 IO）已移到事务外，行锁只在扣费这一步短暂持有。
        int creditsCost = creditService.chargeForTask(savedTask, user);
        if (creditsCost > 0) {
            savedTask = taskRepository.save(savedTask);
        }

        log.info("任务记录已保存到数据库，任务ID: {}, 原始书名: {}, 消耗积分: {}",
                savedTask.getId(), originalBookName, creditsCost);

        // 全书文本提取+切段（大 PDF 可达分钟级）不放在 HTTP 线程和数据库事务里做，
        // 否则前端必然超时重试、产生重复的昂贵合成任务。这里只落盘+建任务+注册派发，
        // 提取切段在异步工作线程的 processTtsTaskAsync 中执行（extractSegmentsForTask）。
        // 好处：即使进程在建任务后、提取前崩溃，任务停在 pending，启动恢复 Runner 也会重新派发补做提取。
        dispatchAfterCommit(savedTask.getId(), user.getUsername(), originalBookName);

        return savedTask.getId();
    }

    /**
     * 异步阶段执行的全书提取+切段（从 createTtsTask 挪来）。
     * 扩展名从落盘文件名推导（saveUploadedFile 保留了原始扩展名）。
     *
     * @return 段落总数
     * @throws IOException 提取失败（文件损坏、格式无法解析等），由调用方标记任务 failed
     */
    private int extractSegmentsForTask(Task task) throws IOException {
        String bookFileName = task.getBookFilePath();
        String extension = "txt";
        int dotIndex = bookFileName.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < bookFileName.length() - 1) {
            extension = bookFileName.substring(dotIndex + 1);
        }

        // 走 resolveUploadFile 而不是 new File(uploadDir, bookFileName)（v5 / S5）：
        // bookFileName 来自数据库，但它的来源是**用户上传时的文件名**，属于不可信输入。
        // resolveUploadFile 会做 canonical 校验，把目录穿越（"../../etc/passwd"）挡在外面；
        // 其余 6 处读取上传文件的调用点已是这个口径，这里补齐，避免「同一个目录两种防御标准」。
        File bookFile = resolveUploadFile(bookFileName);
        // 片段 = 完整句子（不限长度）。必须与 BookTextExtractor.extractBookContent 用同一个方法：
        // 两处口径一旦不同，阅读页段落与音频段号就会错位（历史上这两处各有一份逐行同构的实现）。
        // 引擎的单次 30 字上限不在这里处理，而在 doSynthesizeSegment 里拆小片 + 拼接。
        List<String> segments;
        BookTextExtractor.BookContent snapshot = null;
        if (task.getStoreBookId() != null) {
            // 书城书籍（W1）：**一次提取**同时拿到段落与「原文行 / 目录 / 行↔段映射」。
            // 段文本取自 paragraphs()，而 paragraphs() 与 sentences() 是同一个方法产出的，
            // 所以音频段序与阅读页段号仍然严格一致；差别只是顺手把整份结果存成快照，
            // 让阅读页以后不用再按「当前磁盘文件」重新提取（文件一换就会静默错位）。
            snapshot = bookTextExtractor.extractBookContent(bookFile, extension);
            segments = new ArrayList<>(snapshot.paragraphs().size());
            for (BookTextExtractor.Paragraph paragraph : snapshot.paragraphs()) {
                segments.add(paragraph.text());
            }
        } else {
            String bookContent = bookTextExtractor.extractText(bookFile, extension);
            segments = TextSegmenter.sentences(bookContent);
        }
        log.info("文本提取分割完成: taskId={}, 共 {} 个句子片段{}", task.getId(), segments.size(),
                snapshot != null ? "（书城书：同时生成正文快照）" : "");

        // 书级语种识别（自动，非人工选语言）：整本书的语言是稳定属性，在此对「刚解析出的全书文本」
        // 识别一次并落库，逐段合成时据此选引擎——中文/other→MOSS，Pocket 支持且已配音色的拉丁语→Pocket。
        // 放在解析这一层而非逐段，是因为样本量：单句太短难以在法/西/意/葡之间区分，上万词的书级样本才稳。
        String detectedLanguage = LanguageDetector.detect(buildLanguageSample(segments));
        task.setLanguage(detectedLanguage);
        taskRepository.updateLanguage(task.getId(), detectedLanguage);
        log.info("🌐 书级语种识别: taskId={}, language={}（中文/other 走 MOSS，外语命中 Pocket 音色才分流）",
                task.getId(), detectedLanguage);

        // 一次性 saveAll：长书一次可达数千段，逐条 save() 会产生数千次数据库往返（每段的
        // TEXT 正文各发一条 INSERT）。saveAll 配合 application.yml 中的
        // hibernate.jdbc.batch_size / order_inserts 让 Hibernate 合并成批次提交。
        // 内存量级不变（段落实体本就已在 segments 里），只是把往返次数降一个数量级。
        // 解析段落级音色映射（{段落号: voiceId}），与任务级默认音色共同决定每段音色
        Map<Integer, String> voiceMap = parseSegmentVoices(task.getSegmentVoices());
        String taskDefaultVoice = task.getDefaultVoiceId();

        List<AudioSegment> batch = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            AudioSegment seg = new AudioSegment(task.getId(), i + 1, segments.get(i));
            String v = voiceMap.get(i + 1);
            if (v == null) v = taskDefaultVoice;
            if (v != null && !v.isBlank()) {
                seg.setVoice(v.trim());
            }
            batch.add(seg);
        }
        audioSegmentRepository.saveAll(batch);
        audioSegmentRepository.flush();

        // 段落落库之后再存快照：快照里的 paragraphIndex 与段号一一对应，
        // 必须建立在段行已经写入的前提上（万一段行写入失败，留着快照反而是错的口径）。
        if (snapshot != null) {
            try {
                bookContentSnapshotService.save(task.getStoreBookId(), task.getId(),
                        bookFile.length(), snapshot);
            } catch (Exception e) {
                // 快照是「让阅读页更准」的增强，失败不能把整本书的合成带崩；
                // 没有快照时 /content 会回退到实时提取（即修复前的行为）。
                log.warn("正文快照保存失败（不影响合成）: taskId={}, bookId={}, err={}",
                        task.getId(), task.getStoreBookId(), e.getMessage());
            }
        }

        // 注意：不在此处 save(task) 写 totalSegments——工作线程持有脱管实体，
        // 若任务刚被并发删除，save 会经 merge 把任务行重新 INSERT 复活。
        // totalSegments 由调用方通过 JPQL updateRuntime 写入（UPDATE 不插入）。
        return segments.size();
    }

    /**
     * 为语种识别拼一段有界样本：顺序累加句子，够 {@value #LANGUAGE_SAMPLE_CHARS} 字即停。
     * 长书几十万字没必要全量拼进内存再交给正则——识别只需足够的词频样本，前几万字早已封顶。
     */
    private static String buildLanguageSample(List<String> sentences) {
        StringBuilder sb = new StringBuilder(Math.min(LANGUAGE_SAMPLE_CHARS + 1024, 32 * 1024));
        for (String s : sentences) {
            if (sb.length() >= LANGUAGE_SAMPLE_CHARS) {
                break;
            }
            if (s != null && !s.isEmpty()) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 把异步TTS任务的派发推迟到「当前事务真正提交之后」。
     *
     * 为什么必须这么做：createTtsTask 带 @Transactional，方法返回时事务才提交。
     * 如果在方法体内直接 self.processTtsTaskAsync(...)，异步线程会立刻在另一条数据库
     * 连接上执行 taskRepository.findById(taskId)。MySQL InnoDB 默认 REPEATABLE READ，
     * 尚未提交的 task 行对该连接不可见，findById 返回空，异步方法就会走
     * 「找不到任务」分支直接 return —— 任务被静默丢弃，永远停在 pending。
     *
     * 这是一个竞态：线程池冷启动时（第一本书）要新建线程 + 新建数据库物理连接，
     * 耗时足够长，主线程往往已经提交完成，所以第一个任务能跑；
     * 之后线程和连接都已预热，异步线程几乎瞬间发出查询，必然抢在提交之前，
     * 于是「后面的任务一个都不执行」。
     *
     * 顺便把 Redis 的任务登记也挪到这里：原来它在 Controller 里、异步派发之后才执行，
     * 会把已经跑起来的任务状态又覆盖回 queued。
     */
    private void dispatchAfterCommit(Long taskId, String userId, String bookName) {
        Runnable dispatch = () -> {
            redisTaskService.recordNewTask(taskId, userId, bookName);
            redisTaskService.updateTaskStatus(taskId, TaskStatus.QUEUED, 0, 0);
            // DB 状态同步为 queued，让客户端区分「排队中」与「等待派发」。
            // 注意这里用 JPQL UPDATE 而不是 save()：本方法不在事务里（调用方在
            // afterCommit 里注册了同步器），所以 save() 会抛 NoTransactionException。
            try {
                taskRepository.updateRuntime(taskId, TaskStatus.QUEUED.value(), 0, 0);
            } catch (Exception e) {
                log.error("更新任务DB状态为queued失败(已降级忽略，不影响派发): taskId={}, 原因: {}",
                        taskId, e.getMessage());
            }
            log.info("派发异步TTS任务: taskId={}", taskId);
            try {
                self.processTtsTaskAsync(taskId);
            } catch (org.springframework.core.task.TaskRejectedException e) {
                // AbortPolicy 兜底：队列已满被拒绝入队。任务已入库无法撤回，
                // 标记 failed 让用户在任务列表里看到明确终态并重新提交，
                // 绝不能让它停在 pending 假装还在排队。
                log.error("任务队列已满，任务被拒绝执行，标记为 failed: taskId={}", taskId, e);
                try {
                    // JPQL UPDATE（REQUIRES_NEW 独立事务）：本方法不在事务里，不能用 save()；
                    // 且任务若已被并发删除只更新 0 行，绝不 INSERT 复活。
                    int updated = taskRepository.updateFinal(taskId, TaskStatus.FAILED.value(), 0, 0,
                            "任务队列已满，未能进入合成队列");
                    if (updated == 0) {
                        log.info("任务已被删除，跳过标记 failed: taskId={}", taskId);
                    }
                } catch (Exception e1) {
                    log.error("更新任务状态为 failed 失败: taskId={}, 原因: {}", taskId, e1.getMessage());
                }
                redisTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, 0, 0);
                // 任务根本没跑起来，扣掉的积分必须退回（幂等，重复调用无副作用）
                try {
                    creditService.refundForTask(taskId, "任务队列已满未执行，退还积分");
                } catch (Exception re) {
                    log.error("⚠️ 队列拒绝任务的退款失败，需人工核对: taskId={}", taskId, re);
                }
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("事务已提交: taskId={}", taskId);
                    dispatch.run();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        log.warn("事务未提交(status={})，不派发TTS任务: taskId={}", status, taskId);
                    }
                }
            });
        } else {
            log.info("当前无活跃事务，直接派发: taskId={}", taskId);
            dispatch.run();
        }
    }

    private String saveUploadedFile(MultipartFile file) throws IOException {
        log.info("准备保存文件: {}, 大小: {} bytes", file.getOriginalFilename(), file.getSize());

        File dir = new File(uploadDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), uploadDir);
        }

        if (!dir.exists()) {
            dir.mkdirs();
            log.info("创建上传目录: {}", dir.getAbsolutePath());
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        // 防御目录穿越：扩展名来自用户文件名，若含路径分隔符（如 "x./../evil"），
        // 拼出的目标路径可能逃逸上传目录。与 AdminService 的 assertWithinParent 对齐
        if (extension.contains("/") || extension.contains("\\") || extension.contains("..")) {
            throw new IOException("非法文件扩展名: " + extension);
        }

        // 如果是视频格式，先提取音频并转为 WAV
        if (AudioVideoUtil.isVideoFormat(extension)) {
            log.info("检测到视频格式({})，开始转换为 WAV 音频...", extension);
            // 文件名用 UUID：原「时间戳+Math.random()」在同毫秒并发上传时会碰撞，
            // 后写入者会静默覆盖前一位用户的参考音频。
            String filename = "file_" + UUID.randomUUID() + ".wav";
            File destFile = new File(dir, filename);

            // 流式落盘 + 文件到文件转码：视频与 WAV 都不再整份读进堆内存
            File tempVideoFile = File.createTempFile("upload_video_", extension);
            try {
                file.transferTo(tempVideoFile);
                log.info("保存转换后的 WAV 文件到: {}", destFile.getAbsolutePath());
                AudioVideoUtil.convertVideoToWav(tempVideoFile, destFile);
            } finally {
                if (tempVideoFile.exists() && !tempVideoFile.delete()) {
                    log.warn("临时视频文件删除失败: {}", tempVideoFile.getAbsolutePath());
                }
            }
            log.info("视频转 WAV 完成，文件大小: {} bytes", destFile.length());
            return filename;
        }

        String filename = "file_" + UUID.randomUUID() + extension;
        File destFile = new File(dir, filename);

        log.info("保存文件到: {}", destFile.getAbsolutePath());
        file.transferTo(destFile);

        if (destFile.exists()) {
            log.info("文件保存成功，文件大小: {} bytes", destFile.length());
        } else {
            log.error("文件保存失败！");
        }

        return filename;
    }

    /**
     * 在本事务回滚时清理给定文件（幂等，文件不存在时忽略）。
     * 仅当事务实际回滚(STATUS_ROLLED_BACK / 未提交)时才执行；若提交则保留文件。
     * 幂等性保证调用多次也不会重复删除或抛错。
     */
    private void registerFileCleanupOnRollback(List<String> fileNames) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        for (String fileName : fileNames) {
                            deleteUploadedFile(fileName);
                        }
                    }
                }
            });
        }
    }

    /** 删除 uploadDir 下的文件（不存在则忽略），返回是否实际删除了文件 */
    private boolean deleteUploadedFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        File file = resolveUploadFile(fileName);
        // 语义不同于 deleteQuietly：此处要区分「本就存在但删除失败」与「原本就不存在」，
        // 返回值用于判断是否真的清理掉了孤立文件
        boolean deleted = file.exists() && file.delete();
        if (deleted) {
            log.info("已清理孤立的临时上传文件: {}", file.getAbsolutePath());
        }
        return deleted;
    }

    /**
     * 把客户端下发的段落级音色映射 JSON（{"1":"xiaoyan","5":"xiaoyu"}）解析成
     * 段落号 → voiceId。解析失败时降级返回空 Map（全程用任务默认音色 / 克隆参考音频），
     * 单条非法段落号（非数字 key）跳过，不影响其余段落。
     */
    private Map<Integer, String> parseSegmentVoices(String json) {
        Map<Integer, String> map = new HashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root.isObject()) {
                root.fields().forEachRemaining(e -> {
                    try {
                        int num = Integer.parseInt(e.getKey());
                        JsonNode node = e.getValue();
                        // 必须先判 isNull()：Jackson 的 NullNode.asText() 返回字符串 "null"
                        // 而非 null，直接 asText() 会把「清除预设」还原成一个非法的 voiceId
                        // 并下发给 TTS 引擎。
                        if (node.isNull()) {
                            return;
                        }
                        String val = node.asText();
                        if (!val.isBlank()) {
                            map.put(num, val);
                        }
                    } catch (NumberFormatException ignore) {
                        // 跳过非法段落号
                    }
                });
            }
        } catch (Exception e) {
            log.warn("#20 解析段落音色映射失败，降级为默认音色: {}", e.getMessage());
        }
        return map;
    }

    /**
     * 修改任务音色分配并触发受影响片段的重新合成。
     *
     * <p>流程：更新任务级默认音色 + 段落级映射 → 把变更的段重置为待处理（其余已合成段不动）
     * → 重新派发异步合成（{@code processTtsTaskAsync} 会跳过 COMPLETED 段、只重跑 PENDING 的变更段）。
     * 沿用原任务已扣积分，不二次收费。
     *
     * <p>注意事项：本方法不在 @Transactional 内（与 Controller 的 performRetry 同口径，
     * 让每个仓库调用各自提交），从而异步线程能读到已提交的段状态变更。
     */
    @Override
    public void applySegmentVoices(Long taskId, String defaultVoiceId,
                                   Map<Integer, String> segmentVoices, boolean defaultVoiceProvided) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        // 段落级映射采用 merge 语义：客户端只上报「本次变更的段」，
        // 若整体覆盖，第二次编辑会把第一次绑定的段落映射从任务上抹掉。
        Map<Integer, String> merged = new HashMap<>(parseSegmentVoices(task.getSegmentVoices()));
        if (segmentVoices != null) {
            for (Map.Entry<Integer, String> e : segmentVoices.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isBlank()) {
                    // 空值 → 清除该段预设，回退到任务默认 / 克隆参考音频
                    merged.remove(e.getKey());
                } else {
                    merged.put(e.getKey(), v.trim());
                }
            }
        }

        // 任务级默认音色：只有请求体显式携带 defaultVoiceId 时才改动。
        // 两端调用方都传 null 表示「不改默认音色」；若无条件 setDefaultVoiceId(defaultVoiceId)，
        // 每次改任意一段音色都会把整本书的默认音色抹掉。对「仅预设音色创建的任务」（无参考音频）
        // 这还会导致清除段预设后无处回退、该段合成必失败。
        String newDefaultVoiceId = defaultVoiceProvided ? defaultVoiceId : task.getDefaultVoiceId();

        String mergedJson;
        try {
            mergedJson = objectMapper.writeValueAsString(merged);
        } catch (Exception e) {
            log.warn("#20 序列化段落音色映射失败，忽略持久化映射: taskId={}, {}", taskId, e.getMessage());
            mergedJson = null;
        }
        if (taskRepository.updateVoiceConfig(taskId, newDefaultVoiceId, mergedJson) == 0) {
            log.info("#20 任务已被删除，跳过音色更新: taskId={}", taskId);
            return;
        }

        if (segmentVoices == null || segmentVoices.isEmpty()) {
            log.info("#20 无段落音色变更，仅更新任务级默认音色: taskId={}", taskId);
            return;
        }

        // 补扣积分：任务整体失败时已全额退款，若改音色重合成不补扣，
        // 「失败 → 退款 → 改音色重合成」就是一条无限免费合成的通路（比 /retry 更划算）。
        // chargeForRetry 只在「已扣过费且已退款」时真正收费；对 COMPLETED / PARTIAL 任务
        // （钱还在）返回 0 不重复收，与 /retry 完全同口径，避免跨入口资损不一致。
        //
        // 时机必须在上面「无段落变更」早退判断【之后】：早退分支不重置任何片段、不派发任何合成，
        // 若在此之前扣费就是「收了钱什么都没做」的静默资损（旧实现即把扣费放在早退之前）。
        int recharged = creditService.chargeForRetry(taskId);
        if (recharged > 0) {
            log.info("#20 改音色重合成补扣积分: taskId={}, cost={}", taskId, recharged);
        }

        int total = task.getTotalSegments() != null ? task.getTotalSegments() : 0;
        int changed = 0;
        for (Map.Entry<Integer, String> e : segmentVoices.entrySet()) {
            int number = e.getKey();
            String v = e.getValue();
            // 空值 → 清除预设音色，回退到任务默认 / 克隆参考音频
            String voiceArg = (v == null || v.isBlank()) ? null : v.trim();
            int updated = audioSegmentRepository.updateVoiceAndReset(
                    taskId, number, voiceArg, SegmentStatus.PENDING.value());
            if (updated > 0) {
                changed++;
            }
        }
        log.info("#20 已重置 {} 个片段为待合成: taskId={}", changed, taskId);

        // 任务整体回到 queued。已完成数取真实计数而非「total - changed」：
        // 后者隐含「未变更的段全部已完成」，对 PARTIAL/FAILED 任务（存在 failed 段）会虚高
        // （例如 100 段全失败、只改 1 段 → 算出 99，进度条与真实情况严重不符）。
        int remainCompleted = (int) audioSegmentRepository.countByTaskIdAndStatus(
                taskId, SegmentStatus.COMPLETED.value());
        taskRepository.clearErrorMessage(taskId);
        taskRepository.updateRuntime(taskId, TaskStatus.QUEUED.value(), remainCompleted, total);
        redisTaskService.updateTaskStatus(taskId, TaskStatus.QUEUED, remainCompleted, total);

        // 重新派发异步合成：只重跑 PENDING 的变更段，COMPLETED 段被自动跳过
        try {
            self.processTtsTaskAsync(taskId);
        } catch (org.springframework.core.task.TaskRejectedException ex) {
            // 队列已满：本方法已把段重置为 PENDING，但任务进不了队列会永远停在 queued。
            // 与 createTtsTask 同口径，标记 failed + 退款（避免白占用），让用户稍后重试。
            log.error("#20 重新合成被队列拒绝，标记 failed 并退款: taskId={}", taskId, ex);
            try {
                int updated = taskRepository.updateFinal(taskId, TaskStatus.FAILED.value(), remainCompleted, total,
                        "任务队列已满，重新合成未能进入队列");
                if (updated == 0) {
                    log.info("任务已被删除，跳过标记 failed: taskId={}", taskId);
                }
            } catch (Exception e1) {
                log.error("更新任务状态为 failed 失败: taskId={}, 原因: {}", taskId, e1.getMessage());
            }
            redisTaskService.updateTaskStatus(taskId, TaskStatus.FAILED, remainCompleted, total);
            try {
                creditService.refundForTask(taskId, "任务队列已满未执行，退还积分");
            } catch (Exception re) {
                log.error("⚠️ 队列拒绝任务的退款失败，需人工核对: taskId={}", taskId, re);
            }
        }
    }

    @Override
    @Async("ttsTaskExecutor")
    public void processTtsTaskAsync(Long taskId) {
        if (!runningTasks.add(taskId)) {
            log.warn("⏭️ 任务已在执行中，跳过重复派发: taskId={}", taskId);
            return;
        }

        int total = 0;
        int completedCount = 0;
        Task task = null;
        // 是否因「所有 TTS 节点都不可用」而中止：决定收尾文案，以及剩余段落
        // 保持 pending（可被重试接口继续调度）而不是被逐段标成 failed
        boolean nodeUnavailable = false;

        try {
            // 派发与执行之间任务可能已被删除（用户删任务/管理员删用户）：
            // 此时数据库行已不存在，直接放弃，避免后续 save 复活已删行。
            // 必须放在 try 内：在 finally 之前 return 会跳过 runningTasks.remove，
            // taskId 永久残留（且 DB 重置后 ID 复用会被「已在执行中」静默跳过）
            if (isTaskDeleted(taskId)) {
                log.info("🗑️ 任务已被删除，放弃执行: taskId={}", taskId);
                return;
            }
            // 派发瞬间就已被管理员挂起（例如「继续合成」的重新派发撞上第二次暂停）：
            // 直接收手，别白跑一次全书解析与首个段落的 TTS 调用。
            if (isTaskPaused(taskId)) {
                log.info("⏸️ 任务已被挂起，放弃执行: taskId={}", taskId);
                return;
            }

            log.info("========== 异步任务开始执行 ==========");
            log.info("当前线程: {}, 任务ID: {}", Thread.currentThread().getName(), taskId);

            task = loadTaskWithRetry(taskId);
            if (task == null) {
                log.error("❌ 找不到任务，放弃执行: taskId={}", taskId);
                return;
            }
            log.info("✅ 找到任务: ID={}, 状态={}", task.getId(), task.getStatus());

            // 用元数据投影替代实体查询：只取工作线程真正用到的列，避免把无关大字段带入堆。
            // 注：audio_data 列当前恒为 NULL（音频只落磁盘 audio_file_path），已不存在大 BLOB 进堆风险。
            List<AudioSegmentRepository.SegmentMeta> segments =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);
            total = segments.size();
            if (total == 0) {
                // 新任务首次被工作线程接手时在这里做全书解析。
                // 提取失败则直接落终态 failed，用户可在任务列表看到并重新提交。
                try {
                    total = extractSegmentsForTask(task);
                } catch (Exception e) {
                    log.error("❌ 全书文本提取失败，标记任务为 failed: taskId={}, 原因: {}",
                            taskId, e.getMessage(), e);
                    finishTask(task, TaskStatus.FAILED, 0, 0, "电子书内容提取失败: " + e.getMessage());
                    return;
                }
                // 提取期间任务可能被并发删除：刚插入的段落会成为孤儿，必须清理后中止。
                if (isTaskDeleted(taskId)) {
                    log.info("🗑️ 提取完成但任务已被删除，清理新插入段落并中止: taskId={}", taskId);
                    try {
                        audioSegmentRepository.deleteByTaskId(taskId);
                    } catch (Exception ce) {
                        log.warn("清理已删任务的孤儿段落失败: taskId={}, error={}", taskId, ce.getMessage());
                    }
                    return;
                }
                if (total == 0) {
                    log.error("❌ 任务文本分割后没有任何段落: taskId={}", taskId);
                    finishTask(task, TaskStatus.FAILED, 0, 0, "电子书内容为空，无可合成文本");
                    return;
                }
                segments = audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);
            }

            task.setStatus(TaskStatus.PROCESSING);
            task.setTotalSegments(total);
            // JPQL UPDATE：任务若已被并发删除，更新 0 行即可，绝不 INSERT 复活
            taskRepository.updateRuntime(taskId, TaskStatus.PROCESSING.value(), 0, total);
            redisTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, 0, total);

            // 一次性定位参考音频文件，避免每个段落重复从磁盘读取
            File cachedReferenceAudioFile = loadReferenceAudio(task);

            // 为当前任务创建专用音频目录（按任务ID组织）
            File taskAudioDir = getTaskAudioDirectory(taskId);
            if (!taskAudioDir.exists()) {
                boolean created = taskAudioDir.mkdirs();
                log.info("📁 创建任务音频目录: {}, 结果: {}", taskAudioDir.getAbsolutePath(), created);
            }

            // 本书语种：新解析的任务已在 extractSegmentsForTask 里就设置进 task 对象；续跑/历史任务
            // 从库里的 language 列读出。仍为 null 说明是「本改造上线前就解析过正文」的老任务——
            // 这里补一次书级识别并落库，保证老任务重跑也能自动分流，而不是永远默认 MOSS。
            String taskLanguage = task.getLanguage();
            if (taskLanguage == null) {
                StringBuilder sb = new StringBuilder(LANGUAGE_SAMPLE_CHARS + 1024);
                for (AudioSegmentRepository.SegmentMeta sm : segments) {
                    if (sb.length() >= LANGUAGE_SAMPLE_CHARS) {
                        break;
                    }
                    String t = sm.getTextContent();
                    if (t != null && !t.isEmpty()) {
                        sb.append(t).append('\n');
                    }
                }
                taskLanguage = LanguageDetector.detect(sb.toString());
                task.setLanguage(taskLanguage);
                taskRepository.updateLanguage(taskId, taskLanguage);
                log.info("🌐 补识别书级语种（历史/续跑任务）: taskId={}, language={}", taskId, taskLanguage);
            }

            for (AudioSegmentRepository.SegmentMeta segment : segments) {
                // 每段开始前检查任务是否已被删除：是则立即中止，不再消耗 TTS 资源，
                // 也不再写库（后续写操作即使执行也是幂等无害的 0 行 UPDATE）。
                if (isTaskDeleted(taskId)) {
                    log.info("🗑️ 任务已被删除，中止剩余 {} 段的合成: taskId={}", total - completedCount, taskId);
                    return;
                }
                // 管理员挂起：与 isTaskDeleted 同为「收手但保留一切」的出口。
                // 刻意**不**调 finishTask —— paused 已由 AdminService 落库，
                // 这里再写一次只会把进度写成终态、把 paused 冲掉。
                // 已完成段与 mp3 原样保留，管理员点「继续合成」即可从 completedSegments 续跑。
                if (isTaskPaused(taskId)) {
                    log.info("⏸️ 任务已被管理员暂停，停在 {}/{} 段待继续: taskId={}",
                            completedCount, total, taskId);
                    return;
                }
                int number = segment.getSegmentNumber();

                // 服务正在停止（部署/重启）：立刻收手，任务保持 processing、剩余段保持 pending，
                // 由下次启动的恢复器断点续跑。放在最前面还有一层考虑 ——
                // 关闭时不该再进「等节点恢复」，那会白等满 node-wait-max-seconds 才退出。
                if (shutdownSignal.isShuttingDown()) {
                    log.warn("🛑 服务正在关闭，任务 {} 停在 {}/{} 段，保持 processing 待下次启动续跑",
                            taskId, completedCount, total);
                    return;
                }

                // 节点全部不可用时先停下等它恢复，而不是逐段「连上就报错、立刻放弃」。
                // 等待超时则中止剩余段落：未处理段保持 pending（不标 failed），
                // 任务落到可重试的终态，用户点一次重试即可从断点续跑 —— 见 awaitHealthyNode。
                if (!awaitHealthyNode(taskId)) {
                    if (isTaskDeleted(taskId)) {
                        return;
                    }
                    // 等待被关闭打断：同样不能落终态 —— nodeUnavailable 会把任务写成
                    // PARTIAL/FAILED，而关闭时被标 failed 的段永远补不回来（见 ShutdownSignal）。
                    if (shutdownSignal.isShuttingDown()) {
                        log.warn("🛑 服务正在关闭，任务 {} 的节点等待被中止，保持 processing 待下次启动续跑: {}/{}",
                                taskId, completedCount, total);
                        return;
                    }
                    nodeUnavailable = true;
                    break;
                }

                try {
                    // 断点续跑：已完成且音频文件仍在，直接跳过（服务重启恢复时有用）
                    if (SegmentStatus.COMPLETED.value().equals(segment.getStatus()) && audioFileExists(segment.getAudioFilePath())) {
                        completedCount++;
                        log.debug("⏭️ 段落 {} 已完成，跳过", number);
                        continue;
                    }

                    log.info("开始处理第 {} 段 (共 {} 段), 文本长度: {} 字符",
                             number, total, segment.getTextContent().length());

                    synthesizeSegmentWithRetry(taskId, cachedReferenceAudioFile, taskAudioDir, segment, number, taskLanguage);

                    completedCount++;
                    log.info("进度更新: {}/{} 已完成", completedCount, total);

                } catch (Exception e) {
                    // 关闭打断的段不是「合成失败」：不写 failed，直接收手让恢复器续跑。
                    // failed 是终态、恢复器只捞 pending —— 写进去就等于这一段永久缺失
                    // （2026-09-27 实测：每次热补丁重启都留一个）。
                    if (shutdownSignal.isShutdownInterruption(e)) {
                        log.warn("🛑 服务正在关闭，第 {} 段保持原状态待下次启动续跑（不写 failed）: {}",
                                number, e.getMessage());
                        return;
                    }
                    log.error("❌ 处理段落 {} 失败: {}", number, e.getMessage(), e);
                    try {
                        String msg = truncateErrorMessage(e.getMessage());
                        audioSegmentRepository.updateState(segment.getId(), SegmentStatus.FAILED.value(),
                                segment.getAudioFilePath(), msg);
                    } catch (Exception ex) {
                        log.warn("标记段落 {} 失败状态时出错: {}", number, ex.getMessage());
                    }
                }

                task.setCompletedSegments(completedCount);
                // 守卫式心跳：管理员可能刚在本段与下一段之间点了「暂停」，
                // 用 updateRuntimeUnlessPaused 避免把 paused 踩回 processing（否则暂停瞬间失效、
                // 而工作线程紧接着就在检查点收手，任务永远停在「看起来在跑、实际没人跑」的 processing）。
                // 返回 0 = 行已暂停或已被删除，此时不再广播 processing，避免前端进度条继续跳。
                int heartbeat = taskRepository.updateRuntimeUnlessPaused(
                        taskId, TaskStatus.PROCESSING.value(), completedCount, total,
                        TaskStatus.PAUSED.value());
                if (heartbeat == 0 && (isTaskPaused(taskId) || isTaskDeleted(taskId))) {
                    log.info("⏸️ 任务已被暂停/删除，停在 {}/{} 段待处理: taskId={}",
                            completedCount, total, taskId);
                    return;
                }
                redisTaskService.updateTaskStatus(taskId, TaskStatus.PROCESSING, completedCount, total);
                com.audiobook.server.controller.TaskStreamController.broadcastProgress(
                        taskId, TaskStatus.PROCESSING.value(), completedCount, total);
            }

            // 因节点不可用中止：已完成段保留，未处理段维持 pending 而不标 failed ——
            // 用户点「重试」时它们会被一并重新调度，无需人工挑选失败段。
            if (nodeUnavailable) {
                TaskStatus suspended = (completedCount == 0) ? TaskStatus.FAILED : TaskStatus.PARTIAL;
                String reason = (completedCount == 0)
                        ? "语音合成节点暂时不可用，任务已中断，请稍后重新提交"
                        : "语音合成节点暂时不可用，剩余 " + (total - completedCount) + " 段已暂停，稍后可在任务列表继续合成";
                log.error("⏸️ 因节点不可用中止任务: taskId={}, 完成度={}/{}, 原因={}",
                        taskId, completedCount, total, reason);
                finishTask(task, suspended, completedCount, total, reason);
                return;
            }

            // 一段都没成功 = 合成失败（空音频/超时等），落 FAILED 终态（触发全额退款 + 前端友好文案）；
            // 只有部分成功才叫 PARTIAL，前端按成功引导下载已有音频。
            TaskStatus finalStatus = (completedCount == 0)
                    ? TaskStatus.FAILED
                    : (completedCount == total ? TaskStatus.COMPLETED : TaskStatus.PARTIAL);
            finishTask(task, finalStatus, completedCount, total);

        } catch (Throwable t) {
            // 关闭打断：不落终态。任务保持 processing 会被下次启动的恢复器捞起来续跑；
            // 写成 PARTIAL/FAILED 则相反 —— 只有「还剩 pending 段」的 partial 才被恢复，
            // 而关闭瞬间被标 failed 的那一段永远补不回来。
            if (shutdownSignal.isShutdownInterruption(t)) {
                log.warn("🛑 服务正在关闭，任务 {} 中断于 {}/{} 段，保持 processing 待下次启动续跑: {}",
                        taskId, completedCount, total, t.getMessage());
                return;
            }
            // 顶层兜底：任何未预期异常都必须把任务落到终态，否则会永远卡在 processing
            log.error("❌ 任务执行异常终止: taskId={}, 原因: {}", taskId, t.getMessage(), t);
            if (task != null) {
                try {
                    finishTask(task, completedCount > 0 ? TaskStatus.PARTIAL : TaskStatus.FAILED, completedCount, total,
                            "合成异常终止: " + t.getMessage());
                } catch (Exception e) {
                    log.error("写入任务终态失败: taskId={}", taskId, e);
                }
            }
        } finally {
            runningTasks.remove(taskId);
            log.info("========== 异步任务执行完毕 ========== taskId={}, 完成度: {}/{}",
                     taskId, completedCount, total);
        }
    }

    /**
     * 合成单个段落，失败时按 {@code app.tts.segment-retry.backoff-seconds} 描述的退避序列重试。
     *
     * <p><b>为什么需要段级重试。</b>引擎被 cgroup OOM 杀掉后 systemd 会在 5 秒内把它拉起来，
     * 但这 5 秒里正在处理的那一段会以「连接提前关闭」失败、紧随其后的若干段以
     * 「连接被拒」失败。没有段级重试时这些段落直接被标成 failed 永久定格 ——
     * 一次 5 秒的引擎重启，就这样变成一本书 1450 段永久缺失（实测 taskId=337）。
     * 退避 5/15/30 秒，正好跨过引擎重启窗口。
     *
     * <p><b>与节点级故障切换的关系。</b>{@code synthesize} 内部已经会在一台节点失败后
     * 换下一台（见 {@link #callTtsEngine}）。段级重试是它之上的一层，负责的是
     * 「所有候选节点都试过仍然失败」以及「单节点部署时根本无处可换」这两种情况。
     *
     * @throws Exception 重试预算用尽、或该错误被判定为「重发也不会变好」时原样抛出，
     *                   由调用方把该段标记为 failed
     */
    private void synthesizeSegmentWithRetry(Long taskId, File cachedReferenceAudioFile, File taskAudioDir,
                                            AudioSegmentRepository.SegmentMeta segment, int number,
                                            String language) throws Exception {
        long[] backoff = segmentRetryBackoffMillis();
        // 已成功小片的缓存：一个句子可能被拆成多片分别合成（见 doSynthesizeSegment），
        // 重试时只重发失败的那一片，不重算已经拿到的音频（每片约 1 MB，一句最多几片）。
        //
        // 刻意**不**在片级再套一层退避：退避次数与等待序列必须与改造前完全一致
        // （见 isSegmentRetryEligible 的权衡）。两层重试会把最坏等待时间乘起来，
        // 让「引擎重启 5 秒」这种可自愈的故障变成十几分钟的卡死。
        Map<Integer, byte[]> donePieces = new HashMap<>();
        for (int attempt = 0; ; attempt++) {
            try {
                doSynthesizeSegment(taskId, cachedReferenceAudioFile, taskAudioDir, segment, number, donePieces, language);
                return;
            } catch (Exception e) {
                // 服务正在停止：立刻放弃重试。关闭期间 App 侧的 Netty/Redis 已经先被拆掉，
                // 每次重试都是「立刻失败」，重试满 3 次只是把关闭拖长、把日志刷满，
                // 还会把节点误送进 30 秒冷却（实测段 907）。原异常原样抛给调用方，
                // 由它识别为关闭打断、保持段可续跑（见 ShutdownSignal）。
                if (shutdownSignal.isShutdownInterruption(e)) {
                    throw e;
                }
                boolean canRetry = attempt < backoff.length
                        && !isTaskDeleted(taskId)
                        && isSegmentRetryEligible(e);
                if (!canRetry) {
                    if (attempt > 0) {
                        log.error("❌ 段落 {} 已重试 {} 次仍然失败: {}", number, attempt, e.getMessage());
                    }
                    throw e;
                }
                long waitMillis = backoff[attempt];
                log.warn("🔁 段落 {} 将于 {}s 后重试（第 {}/{} 次），原因: {}",
                        number, waitMillis / 1000, attempt + 1, backoff.length, e.getMessage());
                if (!sleepQuietly(waitMillis)) {
                    // 等待被中断（服务停止等）：保留原异常，交给调用方收尾
                    throw e;
                }
            }
        }
    }

    /**
     * 实际执行一段的合成：调引擎 → WAV 转 MP3 → 落盘 → 把段落置为 completed。
     * 任何一步失败都抛异常，由 {@link #synthesizeSegmentWithRetry} 决定是否重试。
     *
     * <p><b>本段可能拆成多次请求</b>：片段 = 完整句子，而引擎单次只吃
     * {@link TextSegmenter#DEFAULT_MAX_LENGTH} 个字。超过时就按句子内部的标点拆小片
     * （{@link TextSegmenter#subPieces(String)}），逐片合成后由 {@link WavConcat}
     * 在 WAV 层拼成一条音频，最后仍只转一次 MP3、只落一个文件 ——
     * 对上层（DB 一行、客户端一段、播放器一次）而言与单次合成本质无差别。
     *
     * @param donePieces 本次「句子级重试」中已成功的小片（片下标 → WAV），跨重试复用
     */
    private void doSynthesizeSegment(Long taskId, File cachedReferenceAudioFile, File taskAudioDir,
                                     AudioSegmentRepository.SegmentMeta segment, int number,
                                     Map<Integer, byte[]> donePieces, String language) throws IOException {
        // 投影是只读视图，状态一律走 updateState 回写（脱管实体 setXxx 无意义）
        audioSegmentRepository.updateState(segment.getId(), SegmentStatus.PROCESSING.value(),
                segment.getAudioFilePath(), null);

        String segVoice = segment.getVoice();

        List<String> pieces = TextSegmenter.subPieces(segment.getTextContent());

        // 丢弃「无可朗读内容」的小片：引擎的鲁棒性归一化会把纯下划线/空白清成空文本，
        // 再送过去只会拿到 0 帧音频 + 500（段级重试对它无效，最终把整段固化成 failed）。
        // 详见 TextSegmenter.hasSpeakableContent。
        int pieceCount = pieces.size();
        pieces.removeIf(piece -> !TextSegmenter.hasSpeakableContent(piece));
        if (pieces.size() < pieceCount) {
            log.warn("⚠️ 第 {} 段丢弃了 {} 个无可朗读内容的小片（如整行下划线/点线/空白）",
                    number, pieceCount - pieces.size());
        }

        byte[] wavAudioData;
        if (pieces.isEmpty()) {
            // 整段都没有可朗读内容（如整段就是一条分隔线）⇒ 本就不该送引擎：
            // 空文本必然 500。给一段静音占位，保住「每段都有 mp3」的契约。
            log.warn("⚠️ 第 {} 段（{} 字）没有任何可朗读内容 ⇒ 生成静音占位，不调用引擎",
                    number, segment.getTextContent().length());
            wavAudioData = WavConcat.silentWav();
        } else if (pieces.size() == 1) {
            // 单片句子：没有接缝，但**仍然要走静音归一化** —— 片内停顿封顶管的正是「片内部」
            // 那 0.7~2.0 s 的巨停顿（真机实测），而抬到 45 字上限之后多数句子都是单片，
            // 少了这一步最大的那项修复就对大多数段不生效。
            log.info("🔊 调用 TTS API 合成第 {} 段（{} 字）...", number, pieces.get(0).length());
            byte[] raw = synthesize(pieceRequest(pieces.get(0), segVoice, cachedReferenceAudioFile, language));
            wavAudioData = subclauseJoinEnabled ? WavClauseJoiner.normalize(raw, clauseJoinerOptions()) : raw;
        } else {
            log.info("🔊 第 {} 段共 {} 字，超过单次上限 ⇒ 拆成 {} 个小片分别合成后拼接",
                    number, segment.getTextContent().length(), pieces.size());
            wavAudioData = synthesizePiecesAndConcat(
                    pieces, segVoice, cachedReferenceAudioFile, number, donePieces, language);
        }
        log.info("✅ TTS 合成成功, WAV音频大小: {} bytes", wavAudioData.length);

        // 段级包络整形：裁头尾静音 + 端点淡化 + 头尾统一补齐。
        // 不做这一步的话，每段的头尾静音长度由引擎随机给（抽样 10 段有 2 段尾静音为 0），
        // 末帧非零采样编成 MP3 就是「啪」；段间停顿也会忽长忽短。
        // 关闭 segment-envelope.enabled 即回退到改造前逐字节一致的行为（A/B 对比用）。
        wavAudioData = applySegmentEnvelope(wavAudioData);

        byte[] mp3AudioData = convertWavToMp3(wavAudioData);
        log.info("✅ WAV转MP3完成, MP3音频大小: {} bytes", mp3AudioData.length);

        String audioFilename = "tts_" + taskId + "_" + number + ".mp3";
        File audioFile = new File(taskAudioDir, audioFilename);
        try (FileOutputStream fos = new FileOutputStream(audioFile)) {
            fos.write(mp3AudioData);
        }

        // 存储相对路径：taskId/文件名（便于后续定位和下载）
        String relativePath = taskId + "/" + audioFilename;
        log.info("💾 MP3音频文件已保存: {}", relativePath);

        audioSegmentRepository.updateState(segment.getId(), SegmentStatus.COMPLETED.value(), relativePath, null);
    }

    /**
     * 构造一次合成请求。单次请求的文本必须 ≤ {@link TextSegmenter#DEFAULT_MAX_LENGTH}，
     * 超长片段请先经 {@link TextSegmenter#subPieces(String)} 拆片。
     *
     * <p><b>prompt_audio 的两条通道，优先级从高到低</b>：
     * <ol>
     *   <li>{@code segVoice} 非空 → 走 {@code voice_id}，<b>不下发 prompt_audio</b>
     *       （引擎以 prompt_audio 优先，同时下发会让预置音色形同虚设）；</li>
     *   <li>{@code cachedReferenceAudioFile} 非 null → 用<b>原始</b> 8 s 参考音频
     *       （走 {@link Request#setReferenceAudioFile(File)} 流式通道，避免 50 MB 素材进堆）。</li>
     * </ol>
     * <b>两条通道互斥</b>：本方法只会 set 其中一个。
     *
     * <p>曾经的第三条「滚动韵律尾巴（byte[]）」已随 {@code prosody-continuation} 一并删除：
     * 12 组配对实测证明引擎不经 {@code prompt_audio} 传递韵律（音高相关系数 r=0.00），
     * 而 2 s 尾巴比 8 s 参考音频少了 3/4 的音色信息 ⇒ 只会让音色漂移并拉长每片时长。
     */
    private Request pieceRequest(String text, String segVoice, File cachedReferenceAudioFile,
                                 String language) {
        Request request = new Request();
        request.setText(text);
        request.setLanguage(language);
        if (segVoice != null && !segVoice.isBlank()) {
            request.setVoiceId(segVoice);
        } else if (cachedReferenceAudioFile != null) {
            request.setReferenceAudioFile(cachedReferenceAudioFile);
        }
        return request;
    }

    /**
     * 逐片合成，再把它们拼成「一句话」。
     *
     * <p><b>串行发送</b>：片与片是同一句话的相邻部分，并行不会更快 ——
     * 节点侧引擎是串行执行的（{@code app_onnx.py} 的 {@code _execution_lock}），
     * 同时发只会排更长的队，还会一次吃满 {@code app.tts.concurrency} 的在途额度，
     * 把别的任务挤在门外。
     *
     * <p><b>接缝由 {@link WavClauseJoiner} 治</b>：它把每片的头尾静音归一化到固定长度、
     * 按片尾标点插入等长停顿、并把各片有效发声响度拉到同一水平 ——
     * 改造前这些小片自带静音直接叠加，接缝停顿忽长忽短（实测拆片段停顿数中位 23 / 未拆片段 7）。
     * 关闭 {@code app.tts.subclause.join.enabled} 时本方法行为与改造前逐字节一致。
     *
     * <p>原先还有一层「句内滚动 prompt」（第 2 片起用上一片的尾巴起调，
     * {@code SubclauseProsody}）已在 2026-10-30 删除：12 组配对实测音高相关系数 r=0.00，
     * 引擎不经 {@code prompt_audio} 传韵律，留着只会缩短 prompt 里的音色信息。
     *
     * <p><b>重试安全</b>：{@code donePieces} 缓存的是<b>引擎原始输出</b>，而静音归一化/响度对齐只在
     * 本方法内对副本做 ⇒ 段级重试只重发失败的那一片，之前各片的结果与第一次完全一致。
     */
    private byte[] synthesizePiecesAndConcat(List<String> pieces, String segVoice,
                                             File cachedReferenceAudioFile,
                                             int number, Map<Integer, byte[]> donePieces,
                                             String language) {
        boolean normalizeEachPiece = subclauseJoinEnabled;

        List<byte[]> wavs = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            byte[] piece = donePieces.get(i);
            if (piece == null) {
                log.info("🔊 调用 TTS API 合成第 {} 段的第 {}/{} 个小片（{} 字）...",
                        number, i + 1, pieces.size(), pieces.get(i).length());
                piece = synthesize(pieceRequest(pieces.get(i), segVoice, cachedReferenceAudioFile, language));
                donePieces.put(i, piece);
            } else {
                log.info("♻️ 第 {} 段的第 {}/{} 个小片已有缓存，跳过重发", number, i + 1, pieces.size());
            }
            // 静音归一化在拼接之前做：引擎原始输出的尾静音长度随机（0~几百 ms），
            // 直接拼进接缝则让停顿忽长忽短。归一化是「超出则裁、不足则补」，
            // 于是接缝停顿只由片尾标点决定，完全可复现。
            wavs.add(normalizeEachPiece ? WavClauseJoiner.normalize(piece, clauseJoinerOptions()) : piece);
        }

        if (!normalizeEachPiece) {
            // A/B 基线：与改造前完全一致（裸拼接 + 接缝淡化）
            return WavConcat.concat(wavs, jointFadeMillis);
        }
        return WavClauseJoiner.join(wavs, pieces, clauseJoinerOptions());
    }

    /**
     * 段级包络整形（{@link WavEnvelope}）的门面：把 @Value 注入的 8 个参数打包成
     * {@link WavEnvelope.Options}，再调用一次 {@code apply}。
     *
     * <p><b>为什么要有这层薄封装</b>：8 个参数直接摊在 {@code doSynthesizeSegment} 里
     * 会让那段代码多出一大坨构造调用，看不清主干（合成 → 整形 → 转码 → 落盘）。
     * 收敛到一个方法后，主干读起来仍然只有 4 步；参数含义与取值依据都留在字段 javadoc 里。
     *
     * <p>{@code segmentEnvelopeEnabled == false} 时直接返回原字节 —— 这条短路是 A/B 对比的
     * 唯一开关（{@link WavEnvelope.Options#disabled()} 是等效的第二道保险，两者任一关闭都透传）。
     */
    private byte[] applySegmentEnvelope(byte[] wavAudioData) {
        if (!segmentEnvelopeEnabled) {
            return wavAudioData;
        }
        return WavEnvelope.apply(wavAudioData, new WavEnvelope.Options(
                segmentTrimThreshold,
                segmentHeadKeepMillis,
                segmentTailKeepMillis,
                segmentLeadMillis,
                segmentTailMillis,
                segmentFadeInMillis,
                segmentFadeOutMillis));
    }

    /**
     * 把 @Value 注入的子句接缝参数打包成 {@link WavClauseJoiner.Options}。
     * 与 {@link #applySegmentEnvelope} 同思路：主干里只留一次构造调用。
     */
    private WavClauseJoiner.Options clauseJoinerOptions() {
        return new WavClauseJoiner.Options(
                subclauseJoinEnabled,
                subclauseTrimThreshold,
                subclauseHeadKeepMillis,
                subclauseTailKeepMillis,
                subclauseCommaPauseMillis,
                subclauseEnumerationPauseMillis,
                subclauseStrongPauseMillis,
                subclauseHardCutPauseMillis,
                subclauseLoudnessMatch,
                subclauseLoudnessMaxGainDb,
                jointFadeMillis,
                subclauseInternalPauseMinMillis,
                subclauseInternalPauseCapMillis);
    }

    /**
     * 该段落级失败是否值得「等一会儿再发一次」。
     *
     * <p><b>与节点级 {@link #isFailoverEligible} 的取舍不同，这里允许超时重试。</b>
     * 节点级换机是「立刻把同一段发给另一台机器」，两台机器会同时计算同一段文本，
     * 引擎整体变慢时会级联雪崩；段级重试则是「上一次调用已经返回（成功或失败）之后，
     * 串行等待若干秒再发一次」，任意时刻只有一次计算在飞，不存在并发放大。
     * 而超时恰恰是引擎被 OOM 杀死前后最典型的失败形态 —— 等它自动重启后重发即可成功。
     *
     * <p>不重试的是「重发也不会变好」的那类：4xx（请求本身有问题，如字段名不匹配），
     * 以及其它未识别异常（含「返回空音频」「JSON 解析失败」）。
     */
    private boolean isSegmentRetryEligible(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TtsTimeoutException) {
                return true;
            }
            if (current instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                // 5xx = 引擎自身故障；429 = 被引擎限流。稍后重发都有意义
                return status >= 500 || status == 429;
            }
            if (current instanceof WebClientRequestException) {
                // 连接被拒 / 连接提前关闭 / 域名解析失败：机器暂时不可用，等它起来就能成功
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /**
     * 等到至少一台 TTS 节点恢复可用；等待超时、任务被删除或线程被中断时返回 false。
     *
     * <p><b>为什么必须等，而不是继续往下跑。</b>引擎的 systemd 单元是
     * {@code RestartSec=5}，被杀之后 5 秒就回来了。但在它回来的这 5 秒里，
     * 如果应用侧继续逐段尝试，每一段都会「连上就报错、立刻放弃」——
     * 实测 3 分钟就把 1450 段永久烧成 failed（平均每秒 8 段）。
     * 停下来等 5～30 秒，剩下的段落就全能正常合成。
     *
     * <p>判定用 {@link TtsLoadBalancer#hasHealthyNode()} 而不是 {@code candidates()}
     * 是否为空 —— 后者在全部节点冷却时会降级为半开探测、永远返回候选，
     * 回答不了「现在到底有没有机器能干活」这个问题。
     */
    private boolean awaitHealthyNode(Long taskId) {
        if (loadBalancer.hasHealthyNode()) {
            return true;
        }
        // tts_nodes 表里一台机器都没配：这是配置问题，等再久也不会好，立刻失败，
        // 不要白等满 180 秒（candidates() 仅在「节点列表本身为空」时返回空列表）。
        if (loadBalancer.candidates().isEmpty()) {
            log.error("❌ 未配置任何可用的 TTS 节点，无法合成: taskId={}", taskId);
            return false;
        }
        long deadline = System.currentTimeMillis() + nodeWaitMaxSeconds * 1000L;
        log.warn("⏸️ 所有 TTS 节点当前不可用，暂停合成等待恢复（最长 {}s）: taskId={}",
                nodeWaitMaxSeconds, taskId);
        while (System.currentTimeMillis() < deadline) {
            if (isTaskDeleted(taskId)) {
                return false;
            }
            // 关闭时就别再等下去了：本方法的调用方会据此保持任务 processing（可续跑），
            // 而不是白等满 node-wait-max-seconds —— 热补丁部署后那几十秒的延迟只有这一个来源。
            if (shutdownSignal.isShuttingDown()) {
                log.warn("🛑 服务正在关闭，停止等待 TTS 节点: taskId={}", taskId);
                return false;
            }
            if (!sleepQuietly(nodeWaitPollMillis)) {
                return false;
            }
            if (loadBalancer.hasHealthyNode()) {
                log.info("▶️ TTS 节点已恢复，继续合成: taskId={}", taskId);
                return true;
            }
        }
        log.error("❌ 等待 {}s 后仍无可用 TTS 节点，中止本次合成: taskId={}", nodeWaitMaxSeconds, taskId);
        return false;
    }

    /**
     * 解析段级重试的退避序列（毫秒）。配置形如 {@code 5,15,30} 表示「最多重试 3 次，
     * 分别等待 5/15/30 秒」；配成空串即关闭段级重试，回到「失败即定死」的旧行为。
     *
     * <p>无法解析的单项按 0 处理（立即重试）而不是整体放弃 —— 一个笔误不应该让
     * 整条重试能力静默失效。结果缓存在 volatile 字段里，避免每段都重新解析。
     */
    private long[] segmentRetryBackoffMillis() {
        long[] cached = segmentRetryBackoffCache;
        if (cached != null) {
            return cached;
        }
        String raw = segmentRetryBackoffSeconds;
        if (raw == null || raw.isBlank()) {
            cached = new long[0];
        } else {
            String[] parts = raw.split(",");
            cached = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    cached[i] = Math.max(0L, (long) (Double.parseDouble(parts[i].trim()) * 1000L));
                } catch (NumberFormatException e) {
                    log.warn("段级重试退避配置项无法解析，按「立即重试」处理: '{}'", parts[i]);
                    cached[i] = 0L;
                }
            }
        }
        segmentRetryBackoffCache = cached;
        return cached;
    }

    /** 可中断的等待：被中断时恢复中断标记并返回 false，让调用方尽快收尾 */
    private static boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 读取任务，带短重试。
     * 正常情况下 dispatchAfterCommit 已保证事务提交后才派发，这里只是极端场景（
     * 主从延迟、手动调用等）的兜底，避免再次出现「查不到任务就静默丢弃」。
     * 每次 findById 都是独立事务，所以重试能看到新提交的数据。
     */
    private Task loadTaskWithRetry(Long taskId) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            Task task = taskRepository.findById(taskId).orElse(null);
            if (task != null) {
                return task;
            }
            log.warn("第 {} 次未读到任务(可能事务尚未提交): taskId={}", attempt, taskId);
            try {
                Thread.sleep(200L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 统一写入任务终态：落库 + 更新Redis + 推送SSE + 清理Redis任务记录 + 失败退款
     */
    private void finishTask(Task task, TaskStatus status, int completedCount, int total) {
        finishTask(task, status, completedCount, total, null);
    }

    /**
     * 统一写入任务终态（带失败原因）。
     *
     * 退款语义：只有 failed（一个片段都没成功）才全额退还积分；partial 不退，
     * 因为用户已经拿到了可用音频，退款会变成「花一半钱拿一半货还全额退」的漏洞。
     *
     * 退款必须放在最后一步，并且在 taskRepository.save(task) 之后：
     * refundForTask 会在独立事务里重新读取任务并写 creditsRefunded，
     * 如果这之后本方法再保存手里这份旧对象，就会把退款标记覆盖成 0，
     * 下次收敛又退一次 —— 那是直接的资损。
     */
    private void finishTask(Task task, TaskStatus status, int completedCount, int total, String failureReason) {
        Long taskId = task.getId();
        // 暂停优先于终态：管理员点「暂停」是显式意图，不该被工作线程单方面撤销。
        //
        // 触发窗口很窄但真实存在 —— 最后一段正在合成时点暂停，工作线程收不到检查点
        // （检查点在每段**开始**前），于是一路走到这里：updateFinal 的 UPDATE 只按 id 匹配，
        // 会把刚落库的 paused 直接改成 completed / partial / failed，界面上的「已暂停」
        // 一闪而过。进度与 mp3 都还在，只是管理员以为停住了，实际任务已经结束。
        //
        // 这里直接返回、不写任何终态：已完成段与心跳进度早已逐段落库，
        // DB 里停在 paused 是正确的；管理员下次「继续合成」会从 completedSegments 接着跑。
        if (isTaskPaused(taskId)) {
            log.info("⏸️ 任务已被暂停，跳过终态写入（保留 paused）: taskId={}, 原拟写入={}",
                    taskId, status);
            return;
        }
        task.setStatus(status);
        task.setCompletedSegments(completedCount);
        if (total > 0) {
            task.setTotalSegments(total);
        }
        // FAILED 且调用方没给原因时，从段落级错误（空音频/超时）聚合友好原因
        String reason = failureReason;
        if (status == TaskStatus.FAILED && (reason == null || reason.isBlank())) {
            reason = aggregateSegmentErrors(taskId);
        }
        String errorMessage = (reason != null && !reason.isBlank())
                ? truncateErrorMessage(reason)
                : (status == TaskStatus.FAILED ? "合成失败，请重试或换音色" : null);
        if (errorMessage != null) {
            task.setErrorMessage(errorMessage);
        }
        // JPQL UPDATE 替代 save()：任务若已被并发删除，这里只会更新 0 行，
        // 绝不会把已删行重新 INSERT 复活（悬空 user_id、幽灵任务）。
        int updated = taskRepository.updateFinal(taskId, status.value(), completedCount,
                total > 0 ? total : (task.getTotalSegments() == null ? 0 : task.getTotalSegments()),
                errorMessage);
        if (updated == 0) {
            log.info("任务已被删除，跳过终态写入与退款: taskId={}, intendedStatus={}", taskId, status);
            return;
        }

        redisTaskService.updateTaskStatus(taskId, status, completedCount, total);
        com.audiobook.server.controller.TaskStreamController.broadcastProgress(
                taskId, status.value(), completedCount, total);

        User user = userRepository.findById(task.getUserId()).orElse(null);
        if (user != null) {
            redisTaskService.cleanupCompletedTask(taskId, user.getUsername());
        }

        log.info("任务终态: taskId={}, status={}, 完成度={}/{}", taskId, status, completedCount, total);

        if (status == TaskStatus.FAILED) {
            try {
                int refunded = creditService.refundForTask(taskId,
                        failureReason == null || failureReason.isBlank()
                                ? "合成失败退款" : "合成失败退款：" + failureReason);
                if (refunded > 0) {
                    log.info("已退还积分: taskId={}, credits={}", taskId, refunded);
                }
            } catch (Exception e) {
                // 退款失败不能影响任务终态（终态已落库），记录告警由管理员在后台手动补偿
                log.error("⚠️ 任务失败退款异常，需人工核对: taskId={}, 原因: {}", taskId, e.getMessage(), e);
            }
        }
    }

    /** 从段落级失败原因（空音频/超时）聚合出任务级友好文案，最多取前 2 条 */
    private String aggregateSegmentErrors(Long taskId) {
        try {
            List<AudioSegmentRepository.SegmentMeta> segs =
                    audioSegmentRepository.findMetaByTaskIdOrderBySegmentNumberAsc(taskId);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (AudioSegmentRepository.SegmentMeta s : segs) {
                if (SegmentStatus.FAILED.value().equals(s.getStatus())) {
                    String em = s.getErrorMessage();
                    if (em != null && !em.isBlank()) {
                        if (n > 0) sb.append("；");
                        sb.append(em);
                        if (++n >= 2) break;
                    }
                }
            }
            return n > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("聚合段落失败原因失败: taskId={}, {}", taskId, e.getMessage());
            return null;
        }
    }

    private boolean audioFileExists(String audioFilePath) {
        if (audioFilePath == null || audioFilePath.isEmpty()) {
            return false;
        }

        // resolveUploadFile 统一兼容「含子目录（taskId/filename.mp3）」与
        // 「直接在 uploads 目录下」两种路径格式，无需分支判断
        return resolveUploadFile(audioFilePath).exists();
    }

    /**
     * 获取任务专用的音频目录路径
     * 格式：{uploadDir}/{taskId}/
     */
    private File getTaskAudioDirectory(Long taskId) {
        File dir = resolveUploadFile(String.valueOf(taskId));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private File loadReferenceAudio(Task task) {
        String path = task.getReferenceAudioPath();
        if (path == null || path.isEmpty()) {
            log.warn("⚠️ 任务未配置参考音频: taskId={}", task.getId());
            return null;
        }

        File referenceAudioFile = resolveUploadFile(path);
        if (!referenceAudioFile.exists()) {
            log.warn("⚠️ 参考音频文件不存在: {}", referenceAudioFile.getAbsolutePath());
            return null;
        }

        // 懒裁切：把「本次改造之前就已入库、仍是长素材」的参考音频就地裁短。
        // 放在这里是因为一个任务只解析一次参考音频（逐段复用同一个 File），
        // 所以整个任务最多付一次 ffmpeg 的开销；裁完文件即达标，下次自动跳过（幂等）。
        //
        // 裁掉的部分对克隆结果**没有影响**：节点侧另有一道 prompt 截断护栏
        // （MOSS_TTS_MAX_PROMPT_SECONDS=12s），超长素材本来就不可能全量参与合成；
        // 应用侧裁得更紧（reference-max-seconds=8s）⇒ 真正会被用到的只有前 8 秒，
        // 8~30 s 那一段此前也只是被白传上去、再被节点侧丢掉。
        // 唯一的变化是每段上传给节点的体积（30 s 立体声 5.29 MB → 8 s 单声道约 0.7 MB），
        // 一本书数千段时这是 GB 级的流量差异。
        //
        // allowRename=false：文件名已经落库（任务表引用的就是它），改名会让引用失效。
        referenceAudioFile = capReferenceAudioInPlace(referenceAudioFile, false);

        // 直接返回 File，由调用方塞进 Request.referenceAudioFile，
        // 最终经 callTtsEngine 的 FileSystemResource 分支流式上传给引擎，
        // 避免用 Files.readAllBytes 把整份参考音频（≤50MB）读进堆。
        log.info("📦 参考音频将以文件形式流式上传: {}", referenceAudioFile.getAbsolutePath());
        return referenceAudioFile;
    }

    /**
     * 统一解析上传目录下的文件，兼容 app.upload.dir 配成相对路径的情况。
     * 纵深防御：校验解析后的规范路径仍在上传目录内，拦截 ../ / 绝对路径 /
     * 符号链接逃逸。当前入参均来自 DB 白名单记录，风险低，但防止未来
     * 有用户可控路径落库时形成穿越读写入口（与 AdminService.assertWithinParent 对齐）。
     */
    private File resolveUploadFile(String filename) {
        File dir = PathSafetyUtil.resolveBaseDir(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        try {
            return PathSafetyUtil.resolveWithin(dir, filename);
        } catch (IllegalArgumentException e) {
            log.error("拒绝上传目录外的文件路径（疑似目录穿越）: {}", filename);
            throw new SecurityException("非法文件路径: " + filename, e);
        }
    }

    /** 截断错误信息到 500 字符（与 audio_segments.error_message 列宽一致），空值兜底为未知错误 */
    private String truncateErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "合成失败（无详细原因）";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private byte[] convertWavToMp3(byte[] wavData) {
        File tempWavFile = null;
        File tempMp3File = null;
        
        try {
            log.debug("开始WAV转MP3转换，原始数据大小: {} bytes", wavData.length);
            
            tempWavFile = File.createTempFile("tts_temp_", ".wav");
            tempMp3File = File.createTempFile("tts_temp_", ".mp3");
            
            try (FileOutputStream fos = new FileOutputStream(tempWavFile)) {
                fos.write(wavData);
            }
            
            log.debug("临时WAV文件已创建: {}", tempWavFile.getAbsolutePath());
            
            MultimediaObject multimediaObject = new MultimediaObject(tempWavFile);
            
            AudioAttributes audioAttributes = new AudioAttributes();
            audioAttributes.setCodec("libmp3lame");
            audioAttributes.setBitRate(128000);
            audioAttributes.setChannels(1);
            audioAttributes.setSamplingRate(22050);
            
            EncodingAttributes encodingAttributes = new EncodingAttributes();
            encodingAttributes.setOutputFormat("mp3");
            encodingAttributes.setAudioAttributes(audioAttributes);
            
            Encoder encoder = new Encoder();
            // 与视频→WAV 共用超时 / 并发保护：超时会 abortEncoding() 杀掉 ffmpeg 子进程，
            // 避免异常码流把 TTS 工作线程（默认并发 2）永久挂死
            AudioVideoUtil.encodeWithTimeout(encoder, multimediaObject, tempMp3File,
                    encodingAttributes, MP3_CONVERT_TIMEOUT_SECONDS, "WAV转MP3");

            byte[] mp3Data = java.nio.file.Files.readAllBytes(tempMp3File.toPath());
            
            double compressionRatio = (mp3Data.length * 100.0 / wavData.length);
            log.debug("WAV转MP3完成，压缩率: {}%", String.format("%.2f", compressionRatio));
            
            return mp3Data;
            
        } catch (Exception e) {
            log.error("WAV转MP3失败: {}", e.getMessage(), e);
            throw new RuntimeException("音频格式转换失败: " + e.getMessage(), e);
        } finally {
            if (tempWavFile != null && tempWavFile.exists()) {
                boolean deleted = tempWavFile.delete();
                if (!deleted) {
                    log.warn("临时WAV文件删除失败: {}", tempWavFile.getAbsolutePath());
                }
            }
            if (tempMp3File != null && tempMp3File.exists()) {
                boolean deleted = tempMp3File.delete();
                if (!deleted) {
                    log.warn("临时MP3文件删除失败: {}", tempMp3File.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 从引擎 JSON 响应中解析出音频字段的 base64 文本。
     * 解析失败或字段为空时返回 {@code null}（由调用方决定是抛异常还是回退）。
     */
    private String extractBase64Field(String jsonResponse) {
        try {
            JsonNode jsonNode = objectMapper.readTree(jsonResponse);

            String base64Audio = null;
            if (jsonNode.has("audio_base64")) {
                base64Audio = jsonNode.get("audio_base64").asText();
            } else if (jsonNode.has("audio")) {
                base64Audio = jsonNode.get("audio").asText();
            } else if (jsonNode.has("data")) {
                base64Audio = jsonNode.get("data").asText();
            } else {
                StringBuilder sb = new StringBuilder("Unknown JSON structure. Available fields: ");
                jsonNode.fieldNames().forEachRemaining(field -> sb.append(" ").append(field));
                log.warn(sb.toString());
                return null;
            }

            if (base64Audio == null || base64Audio.isEmpty()) {
                log.warn("Base64 audio field is empty");
                return null;
            }
            return base64Audio;
        } catch (Exception e) {
            log.error("Failed to parse JSON response: {}", e.getMessage(), e);
            return null;
        }
    }

    private byte[] extractAudioFromJson(String jsonResponse) {
        String base64Audio = extractBase64Field(jsonResponse);
        if (base64Audio == null) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(base64Audio);
    }

    /**
     * 把 base64 音频文本以「边解码边写」的方式流式写入 {@code out}，
     * 避免把整段解码后的音频整体驻留堆（base64 文本本身仍在堆，但解码过程不积累 byte[]）。
     */
    private void writeBase64ToStream(String base64Audio, OutputStream out) throws IOException {
        try (InputStream b64In = new ByteArrayInputStream(base64Audio.getBytes(StandardCharsets.US_ASCII));
             InputStream decoded = Base64.getDecoder().wrap(b64In)) {
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = decoded.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            // 与 doSynthesize 对齐，额外校验「解码后音频长度 > 0」。
            // 仅 null 检查无法拦截全部空白/空 base64：这里用计数流解码，
            // 不把整段音频驻留堆，仍能在长度为零时抛出明确异常。
            if (total == 0) {
                log.warn("从 TTS 服务接收到空音频数据");
                throw new RuntimeException("TTS 服务返回空音频数据");
            }
        }
    }
}
