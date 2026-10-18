package com.moon.moonagent.controller;

import com.moon.moonagent.ai.agent.orchestration.AgentRolePackageService;
import com.moon.moonagent.dto.response.AgentPackageVO;
import com.storyboard.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 动态包只读目录；安装/启停暂不开放公网写接口。 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentPackageController {

    private final AgentRolePackageService packages;

    @GetMapping("/packages")
    public ApiResponse<List<AgentPackageVO>> list(
            Authentication auth,
            @RequestParam(defaultValue = "role") String kind) {
        return ApiResponse.ok(packages.list(kind).stream()
                .map(row -> new AgentPackageVO(
                        row.getId(), row.getKind(), row.getName(), row.getVersion(),
                        Boolean.TRUE.equals(row.getEnabled()), row.getUpdatedAt()))
                .toList());
    }
}
