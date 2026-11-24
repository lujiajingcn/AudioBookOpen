package com.audiobook.server.controller;

import com.audiobook.server.model.AppVersion;
import com.audiobook.server.repository.AppVersionRepository;
import com.audiobook.server.service.AppVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.File;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端版本更新（公开接口，无需登录）。
 *
 * 放行的理由：客户端在【启动页】就检查更新，此时可能尚未登录、或本地 token 已过期。
 * 若要求鉴权，未登录用户永远收不到更新提示，而这类用户恰恰是最该被强制更新覆盖的
 * （老版本客户端可能带着已失效的接口协议）。版本清单本身是公开信息，不含任何用户数据。
 *
 * 安全边界：只暴露「已发布（released=true）」的版本，草稿版本即使知道 ID 也取不到；
 * 下载路径拼接自 DB 记录且经 canonical 校验，无法穿越到上传目录之外。
 */
@RestController
@RequestMapping("/api/app/version")
public class AppVersionController {

    private static final Logger log = LoggerFactory.getLogger(AppVersionController.class);

    /** 平台标识白名单：只允许简单标识符，避免把任意字符串带进查询与日志 */
    private static final String PLATFORM_PATTERN = "^[a-zA-Z0-9_-]{1,32}$";

    private final AppVersionRepository appVersionRepository;
    private final AppVersionService appVersionService;

    public AppVersionController(AppVersionRepository appVersionRepository,
                                AppVersionService appVersionService) {
        this.appVersionRepository = appVersionRepository;
        this.appVersionService = appVersionService;
    }

    /**
     * 查询最新版本。
     *
     * @param platform    平台，默认 android
     * @param versionCode 客户端当前 BuildConfig.VERSION_CODE；<=0 视为未提供，一律返回最新版本
     *
     * 返回：{ success, hasUpdate, latest:{versionCode, versionName, fileSize, md5,
     * changelog, forceUpdate, releasedAt}, downloadUrl }
     * 没有可用更新时 hasUpdate=false 且不含 latest。
     */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest(
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "versionCode", required = false) Integer versionCode) {

        Map<String, Object> response = new HashMap<>();
        String p = normalizePlatform(platform);
        if (p == null) {
            response.put("success", false);
            response.put("message", "非法的平台标识");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            AppVersion latest = appVersionRepository
                    .findFirstByPlatformAndReleasedTrueOrderByVersionCodeDesc(p)
                    .orElse(null);

            if (latest == null) {
                response.put("success", true);
                response.put("hasUpdate", false);
                response.put("message", "暂无发布版本");
                return noStore(response);
            }

            boolean hasUpdate = versionCode == null || versionCode <= 0
                    || latest.getVersionCode() > versionCode;

            response.put("success", true);
            response.put("hasUpdate", hasUpdate);
            if (hasUpdate) {
                Map<String, Object> item = new HashMap<>();
                item.put("versionCode", latest.getVersionCode());
                item.put("versionName", latest.getVersionName());
                item.put("fileSize", latest.getFileSize());
                item.put("md5", latest.getMd5());
                item.put("changelog", latest.getChangelog());
                item.put("forceUpdate", latest.isForceUpdate());
                item.put("releasedAt", latest.getReleasedAt());
                response.put("latest", item);
                // 相对路径：客户端会拼到自己配置的 baseUrl 上（与 Retrofit 的相对路径约定一致）
                response.put("downloadUrl", "api/app/version/latest/apk?platform=" + p);
            }
            return noStore(response);
        } catch (Exception e) {
            log.error("查询最新版本失败: platform={}", p, e);
            response.put("success", false);
            response.put("message", "查询失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 下载最新版本的 APK（流式输出，不把几十 MB 的安装包读进堆）。
     *
     * 只返回已发布版本；文件缺失（如管理员误删磁盘文件）返回 404，
     * 客户端据此提示「安装包不可用」而不是拿到半截文件去安装。
     */
    @GetMapping("/latest/apk")
    public ResponseEntity<StreamingResponseBody> downloadLatestApk(
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "versionCode", required = false) Integer versionCode,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        String p = normalizePlatform(platform);
        if (p == null) {
            return ResponseEntity.badRequest().build();
        }

        // 客户端带上它手上元信息对应的 versionCode 时，就严格按这个版本给文件。
        // 否则「先查 /latest 拿到 MD5、再下载」这两步之间若管理员发布了新版本，
        // 客户端会下载到新包却拿旧 MD5 校验，必然失败且报错信息完全指错方向。
        AppVersion target;
        if (versionCode != null && versionCode > 0) {
            target = appVersionRepository.findByPlatformAndVersionCode(p, versionCode).orElse(null);
        } else {
            target = appVersionRepository
                    .findFirstByPlatformAndReleasedTrueOrderByVersionCodeDesc(p)
                    .orElse(null);
        }
        // 指定版本不存在/已下线 → 404，客户端会重新查一次 /latest
        if (target == null || !target.isReleased()) {
            return ResponseEntity.notFound().build();
        }

        File file;
        try {
            file = appVersionService.resolveApkFile(target.getFileName());
        } catch (IllegalArgumentException e) {
            log.warn("APK 路径非法: {}", target.getFileName());
            return ResponseEntity.badRequest().build();
        }
        // isFile() 而非 exists()：路径若指向目录，exists() 会放行，随后 copy 抛异常
        if (!file.isFile()) {
            log.error("APK 文件缺失: versionId={}, path={}", target.getId(), target.getFileName());
            return ResponseEntity.notFound().build();
        }

        long length = file.length();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.android.package-archive"));
        // 声明支持字节区间：不写这个头，客户端不会尝试续传（RFC 7233 §2.1）
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        // 文件名来自管理员手填的 versionName，必须先净化：原样拼进响应头时
        // 引号/CRLF/非 ASCII 都会让头值非法，甚至构成响应头注入
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + safeFileName(target.getVersionName()) + "\"");

        // ---- Range 支持（断点续传 / 分片并行）----
        //
        // 【为什么必须在这里做，而不能靠 nginx】实测（2026-10-06）：
        // 对同一台服务器的静态资源 `curl -H "Range: bytes=0-99" /favicon.ico`
        // 返回 **206 Partial Content**，而对本接口同样的请求返回 **200 + 完整长度**。
        // ⇒ **nginx 本身完全支持 Range**，它对「上游自己不做 range 的响应」默认不切分，
        // 所以在 nginx 侧加 `proxy_force_ranges on;` **解决不了这个问题**（此判断已修正）。
        //
        // 【不做 Range 的代价】1.24 的包 29 690 764 B，实测每次下行都在
        // 6~7.7 MB 处被切断（3 路并行分别得 7 702 312 / 6 294 348 / 5 942 304 B，
        // 限速也无效）⇒ 客户端「下载 APK」会反复失败，且**发版时无法拉线上包
        // 比对签名证书**，那条硬验证只能退化成间接证据链。
        //
        // 【语义】只支持单区间（`bytes=start-end` / `bytes=start-` / `bytes=-suffix`），
        // 这已覆盖 curl -C - 续传与多路分片。
        //
        // 【多区间必须回 416，不能「取第一段」】⚠️ 曾经的实现是
        // `spec = spec.substring(0, comma)` 然后继续回 206。那是**静默产出损坏文件**：
        // 多路并行下载器发的 `bytes=0-6294347,6294348-12588695,12588696-...`
        // 每一路都只拿到第 0 段那 100 字节的重复内容，且都认为自己是成功的，
        // 拼出来的 APK 严重损坏 —— 而**MD5 校验会通过**（各路拿到的字节确实一致），
        // 最终在用户手机上「安装包解析失败」，排查方向被完全带偏。
        // 按 RFC 7233，多区间要么回 multipart/byteranges，要么回 200 整份；
        // 本场景下 416（明确不支持）比「假装支持却给错数据」安全得多。
        long[] range = parseRange(rangeHeader, length);

        if (range == null) {
            // Range 存在但无法解析/越界/多区间 → 416，并带上总长度（RFC 7233 要求）。
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            return new ResponseEntity<>(null, headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        }

        final long start = range[0];
        final long end = range[1];
        final long contentLength = end - start + 1;

        // 状态码必须是 206 而非 200：**部分内容**与**整份内容**在协议上是两种响应，
        // 客户端（Android DownloadManager / OkHttp / curl -C -）按状态码决定
        // 「把响应体追加到已有文件之后」还是「从头另存」——
        // 只设 Content-Range 却回 200，客户端会丢弃响应体当作新文件，
        // 断点续传静默失效（且看起来「请求成功」，极难排查）。
        // ⚠️ 部署前我漏了这一处：只验了 Content-Range 头存在就判「已生效」，
        //   是 curl 打印 http_code 时暴露出来的。
        StreamingResponseBody body;
        HttpStatus status;
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(length);
            status = HttpStatus.OK;
            // 200 分支同样走 copyExactly，而不是 Files.copy：
            // 「读满声明的 Content-Length」必须成为**硬约束**。否则文件在
            // file.length() 之后被替换/截断（管理员重新发版、磁盘写满）时，
            // Files.copy「有多少读多少」提前结束，而声明长度没写满 ⇒
            // Tomcat 保持连接不关闭，客户端一路等到超时，表现为「下载卡住 99%」。
            // copyExactly 遇 EOF 抛 IOException，让截断立刻暴露成错误而非静默卡住。
            final long whole = length;
            body = outputStream -> {
                try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    copyExactly(ch, outputStream, whole);
                } catch (java.io.IOException e) {
                    // 响应已提交（状态码与头早已发出），此时抛异常只会变成
                    // AsyncRequestNotUsableException 被容器吞掉，**日志里一行都没有**。
                    // 与另两个下载端点（TtsDownloadController / StoreReadingController）
                    // 保持一致：显式记 warn，运维才看得见发版出了问题。
                    log.warn("APK 流出中断: file={} declared={} (响应已提交，无法再改状态码)",
                            file.getName(), whole, e);
                }
            };
        } else {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
            headers.setContentLength(contentLength);
            status = HttpStatus.PARTIAL_CONTENT;
            // 用 FileChannel 定位而不是 skip(n)：skip 仍会逐字节读过前面的内容，
            // 对 30 MB 包的下半段请求毫无意义（且大 skip 在部分 FS 上很慢）。
            body = outputStream -> {
                try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                    ch.position(start);
                    copyExactly(ch, outputStream, contentLength);
                } catch (java.io.IOException e) {
                    log.warn("APK 分片流出中断: file={} range={}-{} (响应已提交，无法再改状态码)",
                            file.getName(), start, end, e);
                }
            };
        }
        return new ResponseEntity<>(body, headers, status);
    }

    /**
     * 解析单区间 Range 头。
     *
     * @return {@code {start, end}} 闭区间；**null 表示「带了 Range 但不合法」**（应回 416）；
     *         当 rangeHeader 为空时返回 {@code {0, length - 1}}（整份）。
     */
    private static long[] parseRange(String rangeHeader, long length) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return new long[]{0L, length - 1L};
        }
        if (length == 0L) {
            // 空文件带 Range：任何区间都无法满足 ⇒ 交给调用方回 416。
            // ⚠️ 这里原来返回 `new long[]{0L, -1L}`（非 null），于是
            // 调用方的 `range == null` 判断不成立，继续往下拼出
            // `Content-Range: bytes 0--1/0` —— `end < start`，是**语法非法的头**，
            // OkHttp 解析首字节位置时直接抛 IllegalArgumentException。
            // 磁盘写满 / rsync 中断留下的 0 字节包会触发，客户端整条更新流程中断。
            return null;
        }
        String spec = rangeHeader.trim();
        if (!spec.startsWith("bytes=")) {
            return null; // 只认 bytes 单位；其他单位按不合法处理
        }
        spec = spec.substring("bytes=".length()).trim();
        if (spec.indexOf(',') >= 0) {
            // 多区间：本接口不支持 multipart/byteranges，**必须回 416**。
            // 取第一段再回 206 会让并行下载的每一路都拿到同一段重复数据，
            // 拼出损坏文件且 MD5 校验通过（见调用处注释）。
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
                // bytes=-N：最后 N 字节
                if (right.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(right);
                if (suffix <= 0L) {
                    return null;
                }
                long start = Math.max(0L, length - suffix);
                return new long[]{start, length - 1L};
            }
            long start = Long.parseLong(left);
            if (start < 0L || start >= length) {
                return null; // 起点越界（含负数、等于总长）
            }
            long end = right.isEmpty() ? length - 1L : Long.parseLong(right);
            if (end < start) {
                return null;
            }
            // 末尾超出总长要**夹回**而不是报错：curl -C - 传的是「我已要这么多」，
            // 实际剩余不足是常态（文件刚被重发），回 416 会让续传直接失败。
            if (end >= length) {
                end = length - 1L;
            }
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            // 区间数字溢出 long（如 `bytes=0-99999999999999999999`）⇒ 同样 416。
            // 记 debug 而不是静默：线上 416 突增时，要能区分「客户端 bug」与
            // 「扫描器在试探」，两者在响应上完全一样，只看状态码无从判断。
            log.debug("Range 数字非法，按 416 处理: {}", rangeHeader);
            return null;
        }
    }

    /**
     * 从 channel 精确读出 {@code count} 字节到输出流。
     *
     * 用 {@code new byte[8192]} 逐次读而不用 transferTo：后者读到 EOF 就停，
     * 而流式响应里「读满声明的 Content-Length」才是承诺（读少了客户端会等超时）。
     */
    private static void copyExactly(FileChannel ch, OutputStream out, long count) throws java.io.IOException {
        byte[] buf = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            // 必须包成 ByteBuffer 走「单缓冲区」重载：
            // FileChannel.read(ByteBuffer[]) 是**分散读**（GatheringRead），
            // 传 byte[] 会被解析成 dsts 数组而编译失败。
            int read = ch.read(java.nio.ByteBuffer.wrap(buf, 0, want));
            if (read < 0) {
                // 文件在传输期间被截短（管理员重新发版覆盖了同一路径）
                throw new java.io.IOException("APK 在传输期间变短，声明 " + count + " 字节却提前 EOF");
            }
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    /** 版本清单会随发布而变，禁止任何中间层缓存——否则用户迟迟收不到更新提示 */
    private static ResponseEntity<Map<String, Object>> noStore(Map<String, Object> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** 平台标识归一化：空值取 android，非法值返回 null（由调用方转 400） */
    private String normalizePlatform(String platform) {
        String p = (platform == null || platform.isBlank()) ? "android" : platform.trim();
        return p.matches(PLATFORM_PATTERN) ? p : null;
    }

    /** Content-Disposition 文件名净化：只保留安全字符，其余替换为下划线 */
    private static String safeFileName(String versionName) {
        String cleaned = versionName == null
                ? ""
                : versionName.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (cleaned.isEmpty()) cleaned = "latest";
        return "audiobook-" + cleaned + ".apk";
    }
}
