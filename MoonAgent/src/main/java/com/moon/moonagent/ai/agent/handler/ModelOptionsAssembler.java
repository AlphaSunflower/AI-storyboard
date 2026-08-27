package com.moon.moonagent.ai.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moon.moonagent.ai.GatewayModelService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型选项组装（原 {@link AgentOrchestratorSupport} 迁出）：网关模型列表 / LLM 选参用的人类可读选项文本。
 * 仅依赖 {@link GatewayModelService} 与 JSON 解析，无 SSE / checkpooint / 会话状态耦合。
 */
@Component
@RequiredArgsConstructor
public class ModelOptionsAssembler {

    private static final Logger log = LoggerFactory.getLogger(ModelOptionsAssembler.class);

    private final GatewayModelService gatewayModelService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 网关模型列表（卡片下拉用；网关异常返回空） */
    public List<Map<String, Object>> buildModels(String type) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, String> m : gatewayModelService.fetchModels(type)) {
                out.add(new LinkedHashMap<>(m));
            }
            return out;
        } catch (Exception e) {
            log.warn("网关模型列表获取失败: type={}, error={}", type, e.getMessage());
            return List.of();
        }
    }

    /** LLM 选参用的人类可读选项文本：模型名 + 参数枚举（来自网关 params JSON 字符串；无选项返回空串） */
    public String buildModelOptionsText(String type) {
        List<Map<String, Object>> models = buildModels(type);
        if (models.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : models) {
            sb.append("- ").append(m.get("value"));
            String paramsJson = m.get("params") instanceof String s ? s : null;
            if (paramsJson != null && !paramsJson.isBlank()) {
                try {
                    JsonNode p = objectMapper.readTree(paramsJson);
                    List<String> parts = new ArrayList<>();
                    if (p.has("resolutions")) parts.add("分辨率 " + p.get("resolutions"));
                    if (p.has("durations")) parts.add("时长 " + p.get("durations") + " 秒");
                    if (p.has("aspectRatios")) parts.add("画幅 " + p.get("aspectRatios"));
                    if (p.has("sizes")) parts.add("尺寸 " + p.get("sizes"));
                    if (p.has("qualities")) parts.add("质量 " + p.get("qualities"));
                    if (!parts.isEmpty()) sb.append("（").append(String.join("；", parts)).append("）");
                } catch (Exception ignored) {
                    // params 解析失败忽略，仅保留模型名
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
