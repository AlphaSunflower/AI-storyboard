package com.storyboard.dto.request;

// 注意：Spring Boot 4 运行时使用 Jackson 3（tools.jackson.* 包）。
// 自定义 JsonDeserializer 必须继承 tools.jackson 版本，否则注解不生效，
// 字符串型 scenes 会走默认 CollectionDeserializer 直接 500。
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;

import java.io.IOException;
import java.util.List;

/**
 * Dify Agent 分镜脚本生成请求
 *
 * scenes 字段容错说明（Dify 工作流偶发问题）：
 * Dify 工作流的 LLM 结构化输出（structured_output）偶发解析为空，POST分镜脚本节点把
 * {{#...structured_output.items#}} 渲染成空字符串 ""（或未解析的 {{#...#}} 变量引用）传给后端。
 * 若按 List<SceneItem> 严格反序列化，Jackson 对 String→List 直接抛异常 → 500 → 整个对话链路报错。
 * 因此 scenes 使用宽松反序列化：空串/未解析变量/空数组一律归一为 null，由控制器 isEmpty 分支
 * 返回 sceneCount=0 的正常响应，保证工作流不因偶发脏数据中断。
 */
public record DifyGenerateScriptRequest(
    String projectId,
    @tools.jackson.databind.annotation.JsonDeserialize(
        using = DifyGenerateScriptRequest.LenientSceneListDeserializer.class)
    List<SceneItem> scenes,
    String aspectRatio
) {
    public record SceneItem(
        int sceneNumber,
        String scriptContent,
        String imagePrompt,
        String videoPrompt,
        String negativePrompt,
        String cameraMovement,
        String shotType,
        String soundDesign
    ) {}

    /**
     * 宽松 List<SceneItem> 反序列化器：
     * - JSON 数组 → 正常解析
     * - null / "" / 空白 / 未解析变量引用（{{#...#}}）→ null（控制器按空处理）
     * - 字符串形式的 JSON 数组（"[...]"）→ 尝试解析，失败归 null
     * 任何异常都归 null，绝不向调用方抛反序列化错误。
     */
    public static class LenientSceneListDeserializer extends ValueDeserializer<List<SceneItem>> {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Override
        public List<SceneItem> deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (p.currentToken() == JsonToken.VALUE_NULL) {
                return null;
            }
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String raw = p.getValueAsString();
                if (raw == null || raw.isBlank() || raw.contains("{{#")) {
                    return null; // 空串 / 未解析的 Dify 变量引用
                }
                try {
                    // 字符串内容按 JSON 解析：兼容数组 "[...]" 与对象 "{\"items\":[...]}"
                    // （后者对应 Dify LLM 节点原始输出 text，structured_output 解析失败时可改用 text 变量）
                    Object parsed = MAPPER.readValue(raw, Object.class);
                    if (parsed instanceof List<?> list) {
                        return MAPPER.convertValue(list, new TypeReference<List<SceneItem>>() {});
                    }
                    if (parsed instanceof java.util.Map<?, ?> map && map.get("items") instanceof List<?> items) {
                        return MAPPER.convertValue(items, new TypeReference<List<SceneItem>>() {});
                    }
                    return null;
                } catch (Exception e) {
                    return null; // 解析失败归空，不中断请求
                }
            }
            if (p.currentToken() == JsonToken.START_ARRAY) {
                // 用 JsonParser.readValueAs 只消费当前数组值；不能在此用
                // ObjectMapper.readValue(parser, ...)（Jackson 3 会检查 trailing tokens
                // 而 parser 仍处于父对象中间 → FAIL_ON_TRAILING_TOKENS 报错）
                return p.readValueAs(new TypeReference<List<SceneItem>>() {});
            }
            return null; // 其他异常 token 一律归空
        }
    }
}
