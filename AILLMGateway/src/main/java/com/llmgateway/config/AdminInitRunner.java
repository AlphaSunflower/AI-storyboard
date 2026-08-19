package com.llmgateway.config;

import com.lambdaworks.crypto.SCryptUtil;
import com.llmgateway.entity.AdminUser;
import com.llmgateway.mapper.AdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 首启自举：admin_user 表空且配置 gateway.admin-init-password 时创建 admin（scrypt N=16384） */
@Component
public class AdminInitRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitRunner.class);

    private final AdminUserMapper adminUserMapper;
    private final GatewayConfig gatewayConfig;

    public AdminInitRunner(AdminUserMapper adminUserMapper, GatewayConfig gatewayConfig) {
        this.adminUserMapper = adminUserMapper;
        this.gatewayConfig = gatewayConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) return;

        String initPassword = gatewayConfig.getAdminInitPassword();
        if (initPassword == null || initPassword.isBlank()) {
            log.warn("【网关初始化】admin_user 表为空且未设置 gateway.admin-init-password，管理后台无法登录");
            return;
        }

        AdminUser admin = new AdminUser();
        admin.setUsername("admin");
        admin.setPasswordHash(SCryptUtil.scrypt(initPassword, 16384, 8, 1));
        admin.setRole("admin");
        admin.setStatus("enabled");
        admin.setCreatedAt(OffsetDateTime.now());
        admin.setUpdatedAt(OffsetDateTime.now());
        adminUserMapper.insert(admin);
        log.info("【网关初始化】已创建默认管理员账号 admin");
    }
}
