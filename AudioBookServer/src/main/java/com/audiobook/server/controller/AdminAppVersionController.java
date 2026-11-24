package com.audiobook.server.controller;

import com.audiobook.server.model.AppVersion;
import com.audiobook.server.repository.AppVersionRepository;
import com.audiobook.server.service.AppVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 后台管理：客户端版本发布（上传 APK / 发布 / 下线 / 删除）。
 *
 * 权限由 SecurityConfig 的 {@code /api/admin/** hasRole("ADMIN")} 统一保证。
 *
 * 上传大小上限沿用容器配置 {@code spring.servlet.multipart.max-file-size=50MB}
 * （与参考音频共用，不为此单独放大——放大等于同步放宽参考音频入口的磁盘风险）。
 * APK 超过 50MB 时容器会先于本类拒绝，管理端会收到 413；若确实需要分发更大的包，
 * 请显式调大该配置并在前端同步提示。
 *
 * versionCode / versionName 由管理员手填：解析 APK 的二进制 AndroidManifest 需要
 * 引入 aapt 一类的重依赖，收益不抵成本。为防手填出错，上传时强制校验
 * versionCode 必须大于同平台已有记录的最大版本号。
 */
@RestController
@RequestMapping("/api/admin/app-version")
public class AdminAppVersionController {

    private static final Logger log = LoggerFactory.getLogger(AdminAppVersionController.class);

    private static final String PLATFORM_PATTERN = "^[a-zA-Z0-9_-]{1,32}$";

    /**
     * versionName 的白名单。
     *
     * 它不只用于展示：还会被拼进下载接口的 Content-Disposition 响应头
     * （{@code filename="audiobook-<versionName>.apk"}），也是客户端本地 APK 的文件名。
     * 只校验「非空」是不够的 —— 引号、CRLF、非 ASCII 都会让响应头非法，
     * 甚至构成响应头注入。下载侧另有 safeFileName 兜底，这里是入口处就拦住。
     */
    private static final Pattern SAFE_VERSION_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");

    private final AppVersionRepository appVersionRepository;
    private final AppVersionService appVersionService;

    public AdminAppVersionController(AppVersionRepository appVersionRepository,
                                     AppVersionService appVersionService) {
        this.appVersionRepository = appVersionRepository;
        this.appVersionService = appVersionService;
    }

    /** 版本列表（按版本号倒序），含是否发布、文件大小等管理字段 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "platform", required = false) String platform) {
        Map<String, Object> response = new HashMap<>();
        String p = normalizePlatform(platform);
        if (p == null) {
            response.put("success", false);
            response.put("message", "非法的平台标识");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (AppVersion v : appVersionRepository.findByPlatformOrderByVersionCodeDesc(p)) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", v.getId());
                m.put("platform", v.getPlatform());
                m.put("versionCode", v.getVersionCode());
                m.put("versionName", v.getVersionName());
                m.put("fileName", v.getFileName());
                m.put("fileSize", v.getFileSize());
                m.put("md5", v.getMd5());
                m.put("changelog", v.getChangelog());
                m.put("forceUpdate", v.isForceUpdate());
                m.put("released", v.isReleased());
                m.put("createdAt", v.getCreatedAt());
                m.put("releasedAt", v.getReleasedAt());
                // 磁盘文件缺失（人为清理过 uploads/app）时标记出来，避免发布一个下载必然 404 的版本
                boolean exists;
                try {
                    // isFile() 而非 exists()：路径若指向目录，exists() 会误报为可用
                    exists = appVersionService.resolveApkFile(v.getFileName()).isFile();
                } catch (IllegalArgumentException e) {
                    exists = false;
                }
                m.put("fileExists", exists);
                items.add(m);
            }
            response.put("success", true);
            response.put("versions", items);
            response.put("total", items.size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询版本列表失败", e);
            response.put("success", false);
            response.put("message", "查询失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * 上传新版本 APK。
     *
     * 表单字段：apk(文件) / versionCode / versionName / changelog(可选) /
     * forceUpdate(可选，默认 false) / release(可选，默认 false，true 表示上传即发布)。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("apk") MultipartFile apk,
            @RequestParam("versionCode") Integer versionCode,
            @RequestParam(value = "versionName", required = false) String versionName,
            @RequestParam(value = "changelog", required = false) String changelog,
            @RequestParam(value = "forceUpdate", required = false) Boolean forceUpdate,
            @RequestParam(value = "release", required = false) Boolean release,
            @RequestParam(value = "platform", required = false) String platform) {

        Map<String, Object> response = new HashMap<>();
        String p = normalizePlatform(platform);
        if (p == null) {
            response.put("success", false);
            response.put("message", "非法的平台标识");
            return ResponseEntity.badRequest().body(response);
        }

        String storedPath = null;
        try {
            if (apk.isEmpty()) {
                response.put("success", false);
                response.put("message", "APK 文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            if (versionCode == null || versionCode <= 0) {
                response.put("success", false);
                response.put("message", "versionCode 必须为正整数");
                return ResponseEntity.badRequest().body(response);
            }
            String name = versionName == null ? "" : versionName.trim();
            if (name.isEmpty()) {
                response.put("success", false);
                response.put("message", "versionName 不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            if (!SAFE_VERSION_NAME.matcher(name).matches()) {
                response.put("success", false);
                response.put("message", "versionName 只能包含字母、数字、点、下划线、连字符和加号，且以字母或数字开头（最长 64 位）");
                return ResponseEntity.badRequest().body(response);
            }
            // 扩展名兜底：只接受 .apk，防止误传其它文件被分发给客户端安装
            String original = apk.getOriginalFilename() == null ? "" : apk.getOriginalFilename().toLowerCase();
            if (!original.endsWith(".apk")) {
                response.put("success", false);
                response.put("message", "只支持上传 .apk 文件");
                return ResponseEntity.badRequest().body(response);
            }
            if (appVersionRepository.findByPlatformAndVersionCode(p, versionCode).isPresent()) {
                response.put("success", false);
                response.put("message", "该平台已存在 versionCode=" + versionCode + " 的版本");
                return ResponseEntity.badRequest().body(response);
            }
            // 版本号必须递增：手填场景下这是最容易出错、也最致命的一步——
            // 填小了客户端永远检查不到更新，且这个错误在发布后极难被发现
            Integer maxExisting = appVersionRepository.findByPlatformOrderByVersionCodeDesc(p)
                    .stream().findFirst().map(AppVersion::getVersionCode).orElse(0);
            if (versionCode <= maxExisting) {
                response.put("success", false);
                response.put("message", "versionCode 必须大于当前最大版本号 " + maxExisting);
                return ResponseEntity.badRequest().body(response);
            }

            AppVersionService.StoredApk stored = appVersionService.storeApk(apk);
            storedPath = stored.relativePath;

            AppVersion entity = new AppVersion();
            entity.setPlatform(p);
            entity.setVersionCode(versionCode);
            entity.setVersionName(name);
            entity.setFileName(stored.relativePath);
            entity.setFileSize(stored.fileSize);
            entity.setMd5(stored.md5);
            entity.setChangelog(changelog == null ? null : changelog.trim());
            entity.setForceUpdate(Boolean.TRUE.equals(forceUpdate));
            entity.setCreatedAt(LocalDateTime.now());

            boolean doRelease = Boolean.TRUE.equals(release);
            if (doRelease) {
                entity.setReleased(true);
                entity.setReleasedAt(LocalDateTime.now());
            }
            entity = appVersionRepository.save(entity);
            if (doRelease) {
                // 新版本发布即顶掉同平台其它版本（「当前版本」唯一）
                unreleaseOthers(p, entity.getId());
            }

            log.info("APK 上传成功: id={}, platform={}, v{} ({}), size={}",
                    entity.getId(), p, entity.getVersionName(), versionCode, stored.fileSize);

            response.put("success", true);
            response.put("id", entity.getId());
            response.put("versionCode", entity.getVersionCode());
            response.put("versionName", entity.getVersionName());
            response.put("fileSize", entity.getFileSize());
            response.put("md5", entity.getMd5());
            response.put("released", entity.isReleased());
            response.put("message", doRelease ? "已上传并发布" : "已上传（未发布）");
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            // 落盘失败：清理可能残留的半成品文件
            appVersionService.deleteQuietly(storedPath);
            log.error("APK 落盘失败", e);
            response.put("success", false);
            response.put("message", "文件上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (Exception e) {
            appVersionService.deleteQuietly(storedPath);
            log.error("上传 APK 失败", e);
            response.put("success", false);
            response.put("message", "上传失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 发布指定版本：同平台其它版本一并下线，保证「当前对外版本」唯一 */
    @PostMapping("/{id}/release")
    public ResponseEntity<Map<String, Object>> release(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            AppVersion entity = appVersionRepository.findById(id).orElse(null);
            if (entity == null) {
                response.put("success", false);
                response.put("message", "版本不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            File file = appVersionService.resolveApkFile(entity.getFileName());
            if (!file.exists()) {
                // 磁盘文件缺失时拒绝发布：发布了客户端下载也是 404，不如在管理端就拦住
                response.put("success", false);
                response.put("message", "安装包文件已丢失，请重新上传");
                return ResponseEntity.badRequest().body(response);
            }
            entity.setReleased(true);
            entity.setReleasedAt(LocalDateTime.now());
            entity.setFileSize(file.length());
            appVersionRepository.save(entity);
            unreleaseOthers(entity.getPlatform(), entity.getId());

            response.put("success", true);
            response.put("id", entity.getId());
            response.put("message", "已发布 v" + entity.getVersionName());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("发布版本失败: id={}", id, e);
            response.put("success", false);
            response.put("message", "操作失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 下线指定版本（客户端不再收到该版本的更新提示） */
    @PostMapping("/{id}/unrelease")
    public ResponseEntity<Map<String, Object>> unrelease(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            AppVersion entity = appVersionRepository.findById(id).orElse(null);
            if (entity == null) {
                response.put("success", false);
                response.put("message", "版本不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            entity.setReleased(false);
            appVersionRepository.save(entity);
            response.put("success", true);
            response.put("message", "已下线 v" + entity.getVersionName());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("下线版本失败: id={}", id, e);
            response.put("success", false);
            response.put("message", "操作失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 删除版本记录并清理磁盘 APK（已发布版本允许删除——删除即下线） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        try {
            AppVersion entity = appVersionRepository.findById(id).orElse(null);
            if (entity == null) {
                response.put("success", false);
                response.put("message", "版本不存在");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            // 先删 DB 记录再删文件：记录没了即便文件残留也不会被分发，
            // 反过来（先删文件）可能留下指向空文件的「当前版本」
            String fileName = entity.getFileName();
            appVersionRepository.delete(entity);
            appVersionService.deleteQuietly(fileName);

            response.put("success", true);
            response.put("message", "已删除 v" + entity.getVersionName());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("删除版本失败: id={}", id, e);
            response.put("success", false);
            response.put("message", "删除失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /** 把同平台除 keepId 之外的版本全部下线 */
    private void unreleaseOthers(String platform, Long keepId) {
        List<AppVersion> others = appVersionRepository.findByPlatform(platform);
        for (AppVersion v : others) {
            if (!v.getId().equals(keepId) && v.isReleased()) {
                v.setReleased(false);
                appVersionRepository.save(v);
            }
        }
    }

    private String normalizePlatform(String platform) {
        String p = (platform == null || platform.isBlank()) ? "android" : platform.trim();
        return p.matches(PLATFORM_PATTERN) ? p : null;
    }
}
