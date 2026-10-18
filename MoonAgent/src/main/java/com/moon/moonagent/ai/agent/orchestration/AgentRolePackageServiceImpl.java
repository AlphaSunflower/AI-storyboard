package com.moon.moonagent.ai.agent.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.agent.agentscope.ToolCatalog;
import com.moon.moonagent.entity.AgentInstalledPackage;
import com.moon.moonagent.mapper.AgentInstalledPackageMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentRolePackageServiceImpl implements AgentRolePackageService {

    private static final Logger log = LoggerFactory.getLogger(AgentRolePackageServiceImpl.class);
    private static final String KIND_ROLE = "role";
    private static final Set<String> FRAMEWORK_TOOLS = Set.of(
            "read_file", "list_files", "write_file", "run_python_script");

    private final AgentInstalledPackageMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public List<AgentInstalledPackage> list(String kind) {
        String safeKind = kind == null || kind.isBlank() ? KIND_ROLE : kind.trim();
        return mapper.selectList(new LambdaQueryWrapper<AgentInstalledPackage>()
                .eq(AgentInstalledPackage::getKind, safeKind)
                .orderByDesc(AgentInstalledPackage::getUpdatedAt));
    }

    @Override
    public List<SubagentRole> enabledRoles() {
        List<AgentInstalledPackage> rows = mapper.selectList(
                new LambdaQueryWrapper<AgentInstalledPackage>()
                        .eq(AgentInstalledPackage::getKind, KIND_ROLE)
                        .eq(AgentInstalledPackage::getEnabled, true)
                        .orderByDesc(AgentInstalledPackage::getUpdatedAt));
        Map<String, SubagentRole> byName = new LinkedHashMap<>();
        for (AgentInstalledPackage row : rows) {
            SubagentRole role = parse(row);
            if (role == null) continue;
            // 同名多版本：按 updatedAt 倒序，只取最新一条。
            byName.putIfAbsent(role.name(), role);
        }
        return List.copyOf(byName.values());
    }

    private SubagentRole parse(AgentInstalledPackage row) {
        try {
            SubagentRole role = objectMapper.readValue(row.getManifestJson(), SubagentRole.class);
            if (!role.name().equals(row.getName())) {
                throw new IllegalArgumentException("manifest name 与包名不一致");
            }
            if (role.maxTurns() < 1 || role.maxTurns() > 12) {
                throw new IllegalArgumentException("maxTurns 必须在 1-12");
            }
            List<String> invalid = new ArrayList<>();
            for (String tool : role.tools()) {
                if (!ToolCatalog.isRegistered(tool) && !FRAMEWORK_TOOLS.contains(tool)) {
                    invalid.add(tool);
                }
            }
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("未登记工具: " + invalid);
            }
            return role;
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("[AgentPackage] 角色包非法，跳过: id={}, name={}, error={}",
                    row.getId(), row.getName(), e.getMessage());
            return null;
        }
    }
}
