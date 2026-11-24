package com.audiobook.server.repository;

import com.audiobook.server.model.UserSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 用户级跨端单值偏好（LWW）。 */
public interface UserSettingRepository extends JpaRepository<UserSetting, Long> {

    Optional<UserSetting> findByUserIdAndSettingKey(Long userId, String settingKey);

    List<UserSetting> findByUserId(Long userId);
}
