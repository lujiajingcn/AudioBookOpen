package com.audiobook.server.controller;

import com.audiobook.server.service.DictLookupService;
import com.audiobook.server.service.DictService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 离线英汉词典（ECDICT）的分发接口（公开接口，无需登录）。
 *
 * <p>放行理由与 {@link AppVersionController} 同源：词典是<b>与账号无关的静态资源</b>，
 * 而客户端在阅读页长按取词时才触发下载 —— 那时用户可能正在读一本本地导入的书，
 * 从未登录过。要求鉴权会让「离线读书的人永远装不上词典」。
 *
 * <p>两个端点：
 * <ul>
 *   <li>{@code GET /api/app/dict/info} —— 元信息（大小/MD5/版本/词条数）。词典未部署时
 *       返回 200 + {@code available:false}，客户端据此把下载按钮置灰。</li>
 *   <li>{@code GET /api/app/dict/download} —— 词典本体（gzip 压缩的 SQLite 库），
 *       支持 Range 断点续传。</li>
 * </ul>
 *
 * <p><b>分发的内容是 gzip 包而非裸库</b>：55.3 MB → 23.1 MB。移动网络下这是
 * 「能忍」与「不能忍」的区别，客户端用 {@code GZIPInputStream} 解压即可，
 * Android 自带、无需依赖。
 */
@RestController
@RequestMapping("/api/app/dict")
public class DictController {

    private static final Logger log = LoggerFactory.getLogger(DictController.class);

    private final DictService dictService;
    /** 查询服务（Web 端「翻译」「查字典」走它，见类注释） */
    private final DictLookupService dictLookupService;

    public DictController(DictService dictService,
                          DictLookupService dictLookupService) {
        this.dictService = dictService;
        this.dictLookupService = dictLookupService;
    }

    /**
     * 词典元信息。
     *
     * <p>返回 {@code { success, available, version, downloadSize, downloadMd5,
     * downloadName, rows, license, source, downloadUrl }}。
     *
     * <p>{@code downloadSize}/{@code downloadMd5} 描述的是<b>下载件（gzip 包）</b>，
     * 客户端据此做「下载完校验」，而不是解压后再算 —— 解压后的 md5 不同，
     * 拿 gzip 包的 md5 去比对解压结果必然失败。
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        DictService.DictInfo d = dictService.describe();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("available", d.available);
        if (d.available) {
            response.put("version", d.version);
            response.put("downloadSize", d.downloadSize);
            response.put("downloadMd5", d.downloadMd5);
            response.put("downloadName", d.downloadName);
            response.put("rows", d.rows);
            response.put("license", d.license);
            response.put("source", d.source);
            // 相对路径：客户端拼到自己配置的 baseUrl 上（与 Retrofit 相对路径约定一致）
            response.put("downloadUrl", "api/app/dict/download");
        }
        // 元信息会随词典替换而变，禁止缓存：否则换了词典客户端还按旧的 MD5 校验
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * 查英汉词条（Web 阅读页长按「翻译」）。
     *
     * <p>为什么在服务端查：浏览器没有可用的 SQLite，见 {@link DictLookupService} 类注释。
     * Android 端仍是本地库直查，两端共用同一份 {@code dict.db} 与同一套归一规则。
     *
     * <p>返回 {@code { success, available, query, normalized, entries:[{ word, phonetic,
     * translations[], exchange, tags[], collins, oxford }] }}。
     *
     * <p>「查无此词」是**正常结果**（200 + 空 entries），不是错误：用户选中一个专有名词、
     * 或者词典没收录某个变形，都属可预期情形，客户端据此显示「未收录」。
     * 只有词典未部署时 {@code available=false}，客户端提示「词典未安装」。
     */
    @GetMapping("/lookup")
    public ResponseEntity<Map<String, Object>> lookup(@RequestParam("word") String word) {
        Map<String, Object> response = new HashMap<>();
        boolean available = dictLookupService.englishAvailable();
        response.put("success", true);
        response.put("available", available);
        // 回显原文与归一化结果：排查「为什么查不到」时，一眼能看出是选区没剥干净
        // （`Running.` → running）还是 sw 归一的问题
        String extracted = DictLookupService.extractQueryWord(word);
        response.put("query", word == null ? "" : word);
        response.put("normalized", extracted == null ? "" : DictLookupService.stripWord(extracted));
        response.put("entries", available
                ? dictLookupService.lookupEnglish(word)
                : Collections.emptyList());
        // 词条内容几乎不变，允许浏览器短缓存（同一个人反复查同一个词很常见）
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(response);
    }

    /**
     * 查中文单字（Web 阅读页长按「查字典」，只在选区恰好一个汉字时出现）。
     *
     * <p>返回 {@code { success, available, char, entries:[{ pinyin, definitions[] }] }}。
     * 多音字会返回多条（每个读音一条），客户端用「/」把拼音连起来、释义按条列出 ——
     * 只显示第一条会让「行」只查到 xíng，用户以为字典错了。
     *
     * <p>传入非单字（0 个或 2 个及以上汉字）一律 200 + 空 entries：显隐判定在客户端，
     * 这里是兜底，何况手柄拖动与点击之间选区可能已经变了。
     */
    @GetMapping("/char")
    public ResponseEntity<Map<String, Object>> lookupChar(@RequestParam("c") String c) {
        Map<String, Object> response = new HashMap<>();
        boolean available = dictLookupService.chineseAvailable();
        String single = DictLookupService.singleCjkChar(c);
        response.put("success", true);
        response.put("available", available);
        response.put("char", single == null ? "" : single);
        response.put("entries", available && single != null
                ? dictLookupService.lookupChinese(single)
                : Collections.emptyList());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(response);
    }

    /**
     * 下载词典（流式输出，不把 23MB 的库读进堆）。
     *
     * <p>Range 语义与 {@link AppVersionController#downloadLatestApk} 完全一致
     * （单区间支持、多区间回 416、状态码必须是 206），此处不复述其推导，
     * 只强调一条<b>不能改的约定</b>：客户端的断点续传依赖 206 + {@code Content-Range}，
     * 只设头不回 206 会让续传静默失效。
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        File file = dictService.resolveDictFile();
        if (file == null) {
            // 与 APK 下载一致：文件缺失回 404，让客户端提示「词典不可用」而不是拿到半截文件
            log.warn("词典文件不存在，检查 app.dict.dir 配置与部署");
            return ResponseEntity.notFound().build();
        }

        // ⚠️ 只取一次 describe()：它在 dict.meta 缺失时会**扫全文件算 MD5**（23MB），
        // 而这里只需要 downloadName 一个字段。重复调用等于每次下载都白扫一遍。
        DictService.DictInfo info = dictService.describe();

        long length = file.length();
        if (length == 0L) {
            log.error("词典文件为 0 字节（上传中断？），拒绝提供: {}", file.getAbsolutePath());
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        // 落盘的是 gzip 包，按 gzip 的媒体类型下发；
        // ⚠️ 刻意**不**用 Content-Encoding: gzip —— 那会让 OkHttp 自动透明解压，
        // 于是客户端拿到的字节与 downloadMd5 校验的字节不一致（校验必失败），
        // 而且断点续传时无法按原始字节对齐。透明解压在这里是负资产。
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/gzip"));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        // 文件名来自 dict.meta 的 downloadName（构建脚本产出，非用户输入）；
        // 仍做一次净化，任何来源的 header 值都不该直接信任
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + safeFileName(info.downloadName) + "\"");

        long[] range = parseRange(rangeHeader, length);
        if (range == null) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return new ResponseEntity<>(null, headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }

        final long start = range[0];
        final long end = range[1];
        final long contentLength = end - start + 1;
        final File target = file;

        StreamingResponseBody body;
        HttpStatus status;
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(length);
            status = HttpStatus.OK;
            final long whole = length;
            body = out -> {
                try (FileChannel ch = FileChannel.open(target.toPath(), StandardOpenOption.READ)) {
                    copyExactly(ch, out, whole);
                } catch (IOException e) {
                    // 响应已提交，改不了状态码，异常会被容器吞掉 ⇒ 必须显式记 warn，
                    // 否则运维在日志里看不到任何线索
                    log.warn("词典流出中断: file={} declared={}", target.getName(), whole, e);
                }
            };
        } else {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
            headers.setContentLength(contentLength);
            status = HttpStatus.PARTIAL_CONTENT;
            body = out -> {
                try (FileChannel ch = FileChannel.open(target.toPath(), StandardOpenOption.READ)) {
                    ch.position(start);
                    copyExactly(ch, out, contentLength);
                } catch (IOException e) {
                    log.warn("词典分片流出中断: file={} range={}-{}", target.getName(), start, end, e);
                }
            };
        }
        return new ResponseEntity<>(body, headers, status);
    }

    /**
     * 解析单区间 Range 头。
     *
     * @return {@code {start, end}} 闭区间；<b>null 表示「带了 Range 但不合法」</b>（应回 416）
     *
     * <p>口径与 {@code AppVersionController#parseRange} 一致（多区间必须 416 ——
     * 取第一段会让并行下载的每一路都拿到同一段数据，拼出损坏文件且 MD5 校验通过）。
     * 此处保持独立实现而非抽公共方法：APK 那个类已上线并被
     * {@code AppVersionRangeTest} 用反射锁住私有方法签名，抽公共方法会牵动
     * 已验证的线上路径，收益（省 40 行）远小于风险。
     */
    private static long[] parseRange(String rangeHeader, long length) {
        // ⚠️ 必须先挡 length == 0：否则 suffix 分支 `bytes=-100` 会算出
        //   {Math.max(0, 0-100), 0-1} = {0, -1}，end 为负 ⇒拼出非法的
        //   `Content-Range: bytes 0--1/0`。AppVersionController 里同一函数
        //   早就补上了这道守卫（注释记着这个踩过的坑），这里必须对齐，
        //   否则日后一旦调整上面那处「空文件 404」的位置，非法头立刻复活。
        if (length <= 0L) {
            return null;
        }
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new long[]{0L, length - 1L};
        }
        String spec = rangeHeader.trim();
        if (!spec.startsWith("bytes=")) {
            return null;
        }
        spec = spec.substring("bytes=".length()).trim();
        if (spec.indexOf(',') >= 0) {
            return null;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            if (left.isEmpty()) {
                if (right.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(right);
                if (suffix <= 0L) {
                    return null;
                }
                return new long[]{Math.max(0L, length - suffix), length - 1L};
            }
            long start = Long.parseLong(left);
            if (start < 0L || start >= length) {
                return null;
            }
            long end = right.isEmpty() ? length - 1L : Long.parseLong(right);
            if (end < start) {
                return null;
            }
            // 末尾超出总长要夹回而不是报错：curl -C - 传的正是「我已要这么多」，
            // 实际剩余不足是常态，回 416 会让续传直接失败
            if (end >= length) {
                end = length - 1L;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            log.debug("Range 数字非法，按 416 处理: {}", rangeHeader);
            return null;
        }
    }

    /** 精确读出 {@code count} 字节；提前 EOF 抛 IOException，让截断暴露成错误而非静默卡住 */
    private static void copyExactly(FileChannel ch, OutputStream out, long count) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int read = ch.read(ByteBuffer.wrap(buf, 0, want));
            if (read < 0) {
                throw new IOException("词典在传输期间变短，声明 " + count + " 字节却提前 EOF");
            }
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    /** Content-Disposition 文件名净化：只保留安全字符 */
    private static String safeFileName(String name) {
        String cleaned = (name == null || name.isBlank()) ? "dict.db.gz"
                : name.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (cleaned.isBlank()) {
            cleaned = "dict.db.gz";
        }
        return cleaned;
    }
}