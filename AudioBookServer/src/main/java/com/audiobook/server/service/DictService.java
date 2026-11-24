package com.audiobook.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 离线英汉词典（ECDICT）的分发元信息。
 *
 * <p>词典本体是一个预构建的 SQLite 库（{@code dict.db.gz}），由
 * {@code _dict_build/build_dict.py} 从 ECDICT 的 ecdict.csv 转换而来：
 * 76 万词条 → 精简为 10 列（丢掉 definition/audio/detail/pos/frq/bnc）、
 * 把 strip-word 字段 {@code sw} 提进复合主键（免建一份索引），
 * 55.3 MB 的库 gzip 后 23.1 MB，客户端解压即可用 SQLite 直接查。
 *
 * <p><b>本类只负责元信息</b>：文件大小、MD5、版本号。实际字节流由
 * {@link com.audiobook.server.controller.DictController} 输出，
 * 与 APK 下载走同一套 Range 续传实现。
 *
 * <p><b>为什么元信息要落成同目录的 sidecar 文件</b>：MD5 只能靠扫全文件算出来，
 * 而这个文件有 23 MB。若每次请求 {@code /info} 都重算一遍，一次 200ms；
 * 若改成启动时算一次并常驻内存，则管理员替换词典后不重启就取不到新值。
 * 因此约定「词典文件旁放一个 {@code dict.meta}（key=value 文本）」，
 * 由构建脚本一并产出；缺失时退化为「按文件大小 + 启动时算一次 MD5」的兜底。
 */
@Service
public class DictService {

    private static final Logger log = LoggerFactory.getLogger(DictService.class);

    /** 词典分发件的文件名（gzip 后的 SQLite 库） */
    private static final String DICT_FILE_NAME = "dict.db.gz";

    /** 元信息 sidecar 文件名 */
    private static final String META_FILE_NAME = "dict.meta";

    /** ECDICT 许可：MIT，允许商用/闭源分发，只需保留版权声明 */
    private static final String DEFAULT_LICENSE = "MIT (skywind3000/ECDICT)";

    private static final String DEFAULT_SOURCE = "https://github.com/skywind3000/ECDICT";

    private final String dictDir;

    /**
     * 启动时算出的 MD5 兜底值（sidecar 缺失时用），null 表示还没算。
     *
     * ⚠️ 这里**必须缓存String 而不是 long**。曾把 16 字节摘要压进一个 long
     *   （逐字节 {@code v = (v << 8) | b}），而 long 只有 64 位 ⇒
     *   前 8 字节在第 9 次移位后被移出，**只剩后 8 字节**，
     *   再用 {@code String.format("%032x", long)} 格式化会补成
     *   {@code "0000000000000000" + 后8字节} —— 一个永远不可能匹配任何真实 MD5 的值。
     *   客户端会据此判定「词典校验失败」并让用户重下 23MB，而服务端毫无报错。
     *   另：首字节 ≥ 0x80 时该 long 为负，用 {@code > 0} 判缓存命中也会永远落空。
     */
    private volatile String cachedMd5Disk;
    private volatile long cachedMd5DiskSize = -1L;

    public DictService(@Value("${app.dict.dir:dicts}") String dictDir) {
        this.dictDir = dictDir;
    }

    /**
     * 词典元信息。
     *
     * <p>{@code available=false} 表示服务端尚未部署词典文件 —— 这是一个**可预期状态**
     * （新部署、或还没跑构建脚本），因此 info 接口回 200 + available=false，
     * 客户端据此把「下载词典」按钮置灰，而不是报错弹窗。
     */
    public static class DictInfo {
        public final boolean available;
        public final String version;
        public final long downloadSize;
        public final String downloadMd5;
        public final String downloadName;
        public final long rows;
        public final String license;
        public final String source;

        DictInfo(boolean available, String version, long downloadSize, String downloadMd5,
                 String downloadName, long rows, String license, String source) {
            this.available = available;
            this.version = version;
            this.downloadSize = downloadSize;
            this.downloadMd5 = downloadMd5;
            this.downloadName = downloadName;
            this.rows = rows;
            this.license = license;
            this.source = source;
        }
    }

    /**
     * 词典目录（绝对路径）；`app.dict.dir` 为空时返回 null。
     *
     * <p>抽出来是因为「查询接口」也要用同一个目录找裸库（dict.db）与中文单字库
     * （chinese_dict.sqlite）—— 目录解析规则只能有一份，两处各算一次必然漂移
     * （相对路径里 `user.dir` 的取法最容易分叉）。
     */
    public File resolveDictDir() {
        if (dictDir == null || dictDir.isBlank()) {
            return null;
        }
        File dir = new File(dictDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), dictDir);
        }
        return dir;
    }

    /** 词典文件路径；不存在时返回 null */
    public File resolveDictFile() {
        File dir = resolveDictDir();
        if (dir == null) {
            return null;
        }
        File f = new File(dir, DICT_FILE_NAME);
        // isFile() 而非 exists()：路径若指向目录，exists() 会放行，随后流式读取抛异常
        return f.isFile() ? f : null;
    }

    /** 取词典元信息；词典文件缺失时返回 available=false 的结果，绝不抛异常 */
    public DictInfo describe() {
        File f = resolveDictFile();
        if (f == null) {
            return new DictInfo(false, null, 0L, null, null, 0L, DEFAULT_LICENSE, DEFAULT_SOURCE);
        }
        long size = f.length();
        Map<String, String> meta = readMeta(f.getParentFile());
        String md5 = meta.getOrDefault("downloadMd5", null);
        if (md5 == null || md5.isBlank()) {
            md5 = cachedMd5(size);
        }
        long rows = parseLong(meta.get("rows"), 0L);
        return new DictInfo(true,
                meta.getOrDefault("version", "unknown"),
                size,
                md5,
                meta.getOrDefault("downloadName", "audiobook-dict.db.gz"),
                rows,
                meta.getOrDefault("license", DEFAULT_LICENSE),
                meta.getOrDefault("source", DEFAULT_SOURCE));
    }

    /** 读同目录的 dict.meta；不存在或读失败返回空 map（不抛，避免词典功能拖垮启动） */
    private Map<String, String> readMeta(File dir) {
        Map<String, String> out = new LinkedHashMap<>();
        File meta = new File(dir, META_FILE_NAME);
        if (!meta.isFile()) {
            log.info("词典元信息文件缺失，将退化为「按实际文件大小 + 现算 MD5」: {}", meta.getAbsolutePath());
            return out;
        }
        try {
            for (String line : Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            log.warn("读取词典元信息失败: {}", meta.getAbsolutePath(), e);
        }
        return out;
    }

    /**
     * 现算文件 MD5，并按「文件大小」缓存。
     *
     * <p>缓存键用size 而不是路径：管理员替换词典时，新文件几乎必然大小不同，
     * 于是缓存自动失效重算；同大小替换（极不可能）才会拿到过期值，
     * 那种情况下 MD5 校验也仍在客户端侧兜着。
     */
    private String cachedMd5(long size) {
        if (cachedMd5DiskSize == size && cachedMd5Disk != null && !cachedMd5Disk.isBlank()) {
            return cachedMd5Disk;
        }
        File f = resolveDictFile();
        if (f == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                }
            }
            // 16 字节摘要 → 32 位小写 hex，直接存String（见字段注释：不能压成 long）
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            cachedMd5Disk = sb.toString();
            cachedMd5DiskSize = size;
            log.info("词典 MD5 已计算: size={} md5={}", size, cachedMd5Disk);
            return cachedMd5Disk;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            log.error("计算词典 MD5 失败: {}", f.getAbsolutePath(), e);
            return null;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}