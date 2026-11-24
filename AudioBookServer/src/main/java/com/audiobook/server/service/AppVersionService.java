package com.audiobook.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.audiobook.server.util.PathSafetyUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * APK 落盘与校验。
 *
 * 存放位置：{@code ${app.upload.dir}/app/<uuid>.apk}，与参考音频共用上传根目录但独立子目录，
 * 避免清理任务目录时误删安装包。
 *
 * 文件名一律由服务端生成 UUID，绝不使用客户端上传的原始文件名——原始文件名可被
 * 构造成 {@code ../../etc/xx} 之类的路径穿越序列。解析落盘文件时同样做 canonical
 * 路径校验（与 TtsControllerSupport.resolveUploadFile 的纵深防御口径一致）。
 */
@Service
public class AppVersionService {

    private static final Logger log = LoggerFactory.getLogger(AppVersionService.class);

    /** 上传根目录下的 APK 子目录名 */
    private static final String APK_SUB_DIR = "app";

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /** 落盘结果（文件名相对上传根目录，如 app/xxxx.apk） */
    public static class StoredApk {
        public final String relativePath;
        public final long fileSize;
        public final String md5;

        public StoredApk(String relativePath, long fileSize, String md5) {
            this.relativePath = relativePath;
            this.fileSize = fileSize;
            this.md5 = md5;
        }
    }

    /**
     * 保存上传的 APK 并返回落盘信息（相对路径、字节数、MD5）。
     *
     * 写入前先算 MD5：流式读一遍算摘要再 transferTo，避免"先落盘再读一遍算摘要"
     * 对几十 MB 的包做双倍 IO。异常时清理已写入的半成品文件，不留垃圾。
     */
    public StoredApk storeApk(MultipartFile file) throws IOException {
        File dir = apkDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建 APK 存储目录: " + dir.getAbsolutePath());
        }
        String relativePath = APK_SUB_DIR + "/" + UUID.randomUUID() + ".apk";
        File target = resolveApkFile(relativePath);

        String md5;
        try (InputStream in = file.getInputStream()) {
            md5 = md5OfStream(in);
        }
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(relativePath);
            throw e;
        }

        long size = target.length();
        log.info("APK 已落盘: path={}, size={}, md5={}", relativePath, size, md5);
        return new StoredApk(relativePath, size, md5);
    }

    /**
     * 将相对路径解析为绝对文件，并校验结果仍在上传根目录之内（目录穿越纵深防御）。
     *
     * @throws IllegalArgumentException 路径逃逸出上传根目录（由 GlobalExceptionHandler 转 400）
     */
    public File resolveApkFile(String relativePath) {
        return PathSafetyUtil.resolveWithin(PathSafetyUtil.resolveBaseDir(uploadDir), relativePath);
    }

    /** 删除 APK 文件（不存在则忽略），删除失败仅记日志 */
    public void deleteQuietly(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return;
        }
        File file;
        try {
            file = resolveApkFile(relativePath);
        } catch (IllegalArgumentException e) {
            log.warn("跳过非法路径的 APK 清理: {}", relativePath);
            return;
        }
        if (!PathSafetyUtil.deleteQuietly(file)) {
            log.warn("删除 APK 文件失败: {}", file.getAbsolutePath());
        }
    }

    private File apkDir() {
        File dir = new File(uploadDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), uploadDir);
        }
        return new File(dir, APK_SUB_DIR);
    }

    private static String md5OfStream(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
