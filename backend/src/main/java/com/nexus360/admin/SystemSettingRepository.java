package com.nexus360.admin;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {

    List<SystemSetting> findAllByOrderByKeyAsc();
}
