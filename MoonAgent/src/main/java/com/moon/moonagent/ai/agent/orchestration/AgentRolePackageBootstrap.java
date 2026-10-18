package com.moon.moonagent.ai.agent.orchestration;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时把 DB 中启用的角色包合并进本地角色清单；DB 不可用时保留内置角色。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent.agentscope", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentRolePackageBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRolePackageBootstrap.class);

    private final AgentRolePackageService packages;
    private final SubagentRoleCatalog catalog;

    @Override
    public void run(ApplicationArguments args) {
        try {
            catalog.refresh(packages.enabledRoles());
        } catch (RuntimeException e) {
            log.warn("[AgentPackage] 加载动态角色失败，保留内置角色: {}", e.getMessage());
        }
    }
}
