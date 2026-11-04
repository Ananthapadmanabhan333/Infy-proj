package com.nexus360.user;

import com.nexus360.config.AppProperties;
import com.nexus360.security.PasswordPolicy;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from BOOTSTRAP_ADMIN_EMAIL / BOOTSTRAP_ADMIN_PASSWORD when no active admin
 * exists. The password is never logged and must be changed at first sign-in.
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final AppProperties properties;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.BootstrapAdmin config = properties.bootstrapAdmin();
        if (config == null || !config.configured() || users.existsByRoleAndActiveTrue(Role.SYSTEM_ADMIN)) {
            return;
        }
        String email = config.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            log.warn("Bootstrap admin {} already exists but is not an active administrator; not modifying it", email);
            return;
        }
        PasswordPolicy.validate(config.password(), email);
        User admin = new User();
        admin.setEmail(email);
        admin.setFullName(config.fullName() == null || config.fullName().isBlank() ? "Platform Administrator" : config.fullName());
        admin.setRole(Role.SYSTEM_ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(config.password()));
        admin.setMustChangePassword(true);
        users.save(admin);
        log.info("Created bootstrap administrator {} (password change required at first sign-in)", email);
    }
}
