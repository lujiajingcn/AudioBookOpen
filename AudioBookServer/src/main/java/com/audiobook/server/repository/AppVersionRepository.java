package com.audiobook.server.repository;

import com.audiobook.server.model.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 客户端版本记录。
 *
 * 方法名依 Spring Data 派生查询规范，关键是「当前对外版本」的查询必须只取
 * released=true 中 version_code 最大的一条——客户端每次启动都会打这个接口，
 * 返回多条会让客户端的比较逻辑（只取第一条）变得不可靠。
 */
public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    /** 当前对外版本（客户端检查更新走这里） */
    Optional<AppVersion> findFirstByPlatformAndReleasedTrueOrderByVersionCodeDesc(String platform);

    /** 管理端列表：同平台按版本号倒序，新版在前 */
    List<AppVersion> findByPlatformOrderByVersionCodeDesc(String platform);

    /** 上传时查重：同平台同一 version_code 只能有一条 */
    Optional<AppVersion> findByPlatformAndVersionCode(String platform, Integer versionCode);

    /** 发布/下线时批量取同平台全部记录 */
    List<AppVersion> findByPlatform(String platform);
}
