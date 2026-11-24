package com.audiobook.server.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 上传目录路径安全工具。
 *
 * <p>收敛原先分散在 {@code TtsControllerSupport} / {@code VoiceCloningTtsService} /
 * {@code AdminService} / {@code AppVersionService} / {@code BookStoreService} 中的
 * 多份同构实现（目录基准解析 + 规范路径穿越校验），避免口径漂移导致某处遗漏
 * {@code ../} 或符号链接逃逸校验。
 *
 * <p>各调用方原有的异常契约（IllegalArgumentException / IOException / SecurityException）
 * 由调用方在自己的薄封装中转换，本类统一抛 {@link IllegalArgumentException}。
 */
public final class PathSafetyUtil {

    private PathSafetyUtil() {
    }

    /**
     * 解析配置的存储根目录。兼容 {@code app.upload.dir} 配成相对路径的情况：
     * 相对时以进程工作目录(user.dir)为基准，避免不同入口
     * （{@code new File(dir, x)} 会以 JVM 默认 CWD 解析）之间不一致导致找不到文件。
     */
    public static File resolveBaseDir(String configuredDir) {
        File dir = new File(configuredDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), configuredDir);
        }
        return dir;
    }

    /**
     * 判断 {@code child} 的规范路径是否位于 {@code parent} 之内。
     * 规范路径比较会解析符号链接，可拦截 {@code ../}、绝对路径、符号链接逃逸。
     *
     * @throws IOException 规范路径解析失败
     */
    public static boolean isWithin(File parent, File child) throws IOException {
        Path parentPath = parent.getCanonicalFile().toPath().normalize();
        Path childPath = child.getCanonicalFile().toPath().normalize();
        return childPath.startsWith(parentPath);
    }

    /**
     * 在 {@code baseDir} 下解析相对路径 {@code relativePath}，并校验解析结果
     * 未逃逸出 {@code baseDir}。
     *
     * <p>与其他入口保持同一口径：返回值同样是规范路径（{@code getCanonicalFile()}）。
     *
     * @throws IllegalArgumentException 路径逃逸（疑似目录穿越），或规范路径解析失败
     */
    public static File resolveWithin(File baseDir, String relativePath) {
        try {
            File canonicalDir = baseDir.getCanonicalFile();
            File canonicalFile = new File(canonicalDir, relativePath).getCanonicalFile();
            if (!canonicalFile.toPath().startsWith(canonicalDir.toPath())) {
                throw new IllegalArgumentException("非法文件路径");
            }
            return canonicalFile;
        } catch (IOException e) {
            throw new IllegalArgumentException("文件路径解析失败: " + relativePath, e);
        }
    }

    /**
     * 确认待访问文件确实位于 {@code parent} 目录内（防御目录穿越）。
     * 通过规范路径比较，可拦截 {@code ../}、绝对路径、符号链接逃逸等情形。
     *
     * @throws IOException 当 {@code child} 的规范路径不在 {@code parent} 之内，
     *                     或规范路径解析失败
     */
    public static void assertWithinParent(File parent, File child) throws IOException {
        if (!isWithin(parent, child)) {
            throw new IOException("非法文件路径（疑似目录穿越），已拒绝: " + child.getPath());
        }
    }

    /**
     * 静默删除文件：不存在视为成功；删除失败返回 {@code false}，由调用方决定是否告警。
     *
     * @return 文件已不存在或删除成功返回 {@code true}
     */
    public static boolean deleteQuietly(File file) {
        return file == null || !file.exists() || file.delete();
    }
}
