package com.moon.moonagent.ai.agent.orchestration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 本地角色清单：{@code classpath*:agents/roles/*.json}。
 *
 * <p>先不引注册中心；角色文件跟着代码走，改角色 = 改文件 + 重启。
 * 后续接注册中心时只换这一层的加载来源，任务图 schema 不变。
 */
@Component
public class SubagentRoleCatalog {

    private static final Logger log = LoggerFactory.getLogger(SubagentRoleCatalog.class);
    private static final String PATTERN = "classpath*:agents/roles/*.json";

    private final Map<String, SubagentRole> builtIn;
    private volatile Map<String, SubagentRole> roles;

    public SubagentRoleCatalog() {
        this(new ObjectMapper());
    }

    SubagentRoleCatalog(ObjectMapper objectMapper) {
        ObjectMapper mapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Map<String, SubagentRole> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(PATTERN);
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    SubagentRole role = mapper.readValue(in, SubagentRole.class);
                    if (loaded.putIfAbsent(role.name(), role) != null) {
                        throw new IllegalStateException("角色重名: " + role.name());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载本地角色清单失败: " + PATTERN, e);
        }
        this.builtIn = Map.copyOf(loaded);
        this.roles = this.builtIn;
        log.info("[Subagent] 本地角色 {} 个: {}", roles.size(), roles.keySet());
    }

    /**
     * 用 DB 动态角色覆盖/追加内置角色；同名以动态包为准。
     * 空集合等价于回落到内置角色。
     */
    public synchronized void refresh(java.util.Collection<SubagentRole> dynamicRoles) {
        Map<String, SubagentRole> merged = new LinkedHashMap<>(builtIn);
        if (dynamicRoles != null) {
            for (SubagentRole role : dynamicRoles) {
                if (role == null || role.name() == null || role.name().isBlank()) continue;
                merged.put(role.name().trim(), role);
            }
        }
        this.roles = Map.copyOf(merged);
        log.info("[Subagent] 角色刷新完成，共 {} 个: {}", roles.size(), roles.keySet());
    }

    public SubagentRole require(String name) {
        SubagentRole role = name == null ? null : roles.get(name.trim());
        if (role == null) {
            throw new IllegalArgumentException("未登记的子智能体角色: " + name
                    + "（可用: " + roles.keySet() + "）");
        }
        return role;
    }

    public Set<String> names() {
        return roles.keySet();
    }
}
