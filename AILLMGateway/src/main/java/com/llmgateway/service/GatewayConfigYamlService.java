package com.llmgateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.llmgateway.dto.admin.ChannelKeyRequest;
import com.llmgateway.dto.admin.ChannelRequest;
import com.llmgateway.dto.admin.ModelParamsRequest;
import com.llmgateway.dto.admin.RouteRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ChannelKey;
import com.llmgateway.entity.ModelParams;
import com.llmgateway.entity.ModelRoute;
import com.llmgateway.exception.BusinessException;
import com.llmgateway.mapper.ChannelKeyMapper;
import com.llmgateway.mapper.ChannelMapper;
import com.llmgateway.mapper.ModelParamsMapper;
import com.llmgateway.mapper.ModelRouteMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 配置即代码：把渠道 / Key 元数据 / 模型参数与 Schema / 路由 / 回退链导出成一份 YAML，
 * 并支持校验后导入（按名字 upsert，不删除 YAML 里没有的配置）。
 *
 * <p>安全约定：导出永远不包含 Key 明文或密文；导入时 Key 只能通过 {@code api_key} 新提供，
 * 已有 Key 不写 api_key 则保持不变。这样配置文件可以进 Git，而密钥仍在数据库里。
 *
 * <p>Schema 在 YAML 里是对象（{@code schema.request/response}），导入时转成 JSON 存
 * {@code model_params.params_schema}，保存前会跑一遍 {@link ParamsSchemaValidator}。
 */
@Service
@RequiredArgsConstructor
public class GatewayConfigYamlService {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigYamlService.class);

    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final ModelRouteMapper routeMapper;
    private final ModelParamsMapper modelParamsMapper;

    private final ChannelService channelService;
    private final ChannelKeyService channelKeyService;
    private final ModelParamsService modelParamsService;
    private final ModelRouteService routeService;
    private final ModelFallbackService fallbackService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    /** YAML 用 snake_case，DTO 用 camelCase */
    private final ObjectMapper snakeMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {}

    public record ImportResult(int channels, int keys, int models, int routes, int fallbacks,
                               List<String> warnings) {}

    // ==================================================================
    // 导出
    // ==================================================================

    public String exportYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);

        List<Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .orderByAsc(Channel::getPriority));
        List<Object> channelNodes = new ArrayList<>();
        for (Channel channel : channels) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", channel.getName());
            node.put("type", channel.getType());
            node.put("base_url", channel.getBaseUrl());
            node.put("enabled", channel.getEnabled());
            node.put("priority", channel.getPriority());
            node.put("weight", channel.getWeight());
            if (channel.getMaxRetries() != null && channel.getMaxRetries() > 0) {
                node.put("max_retries", channel.getMaxRetries());
            }
            if (channel.getTimeoutMs() != null) node.put("timeout_ms", channel.getTimeoutMs());
            node.put("models", splitCsv(channel.getModels()));
            List<Map<String, Object>> keys = new ArrayList<>();
            for (ChannelKey key : channelKeyService.list(channel.getId())) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("label", key.getLabel());
                k.put("weight", key.getWeight());
                k.put("enabled", key.getEnabled());
                keys.add(k);
            }
            if (!keys.isEmpty()) node.put("keys", keys);
            channelNodes.add(node);
        }
        root.put("channels", channelNodes);

        // 模型：按 (model_name, type) 聚合参数、Schema、路由、回退
        List<ModelRoute> routes = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                .orderByAsc(ModelRoute::getModelName).orderByAsc(ModelRoute::getPriority));
        Map<String, Map<String, Object>> modelNodes = new LinkedHashMap<>();
        for (ModelRoute route : routes) {
            String key = route.getModelName() + "|" + route.getType();
            Map<String, Object> model = modelNodes.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", route.getModelName());
                m.put("type", route.getType());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Object> routeNodes = (List<Object>) model.computeIfAbsent("routes", k -> new ArrayList<>());
            Map<String, Object> rn = new LinkedHashMap<>();
            Channel channel = channelMapper.selectById(route.getChannelId());
            rn.put("channel", channel == null ? route.getChannelId() : channel.getName());
            if (route.getUpstreamModel() != null) rn.put("upstream_model", route.getUpstreamModel());
            rn.put("enabled", route.getEnabled());
            rn.put("priority", route.getPriority());
            rn.put("weight", route.getWeight());
            if (route.getMaxRetries() != null && route.getMaxRetries() > 0) rn.put("max_retries", route.getMaxRetries());
            if (route.getTimeoutMs() != null) rn.put("timeout_ms", route.getTimeoutMs());
            if (Boolean.TRUE.equals(route.getIsDefault())) rn.put("is_default", true);
            if (route.getParamOverrides() != null && !route.getParamOverrides().isBlank()) {
                try {
                    rn.put("param_overrides", objectMapper.readValue(route.getParamOverrides(), Object.class));
                } catch (Exception e) {
                    rn.put("param_overrides_raw", route.getParamOverrides());
                }
            }
            routeNodes.add(rn);
        }

        List<Object> modelList = new ArrayList<>();
        // 只有参数、还没有路由的模型也要导出，避免导入丢配置
        for (ModelParams params : modelParamsMapper.selectList(null)) {
            String key = params.getModelName() + "|" + params.getType();
            modelNodes.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", params.getModelName());
                m.put("type", params.getType());
                return m;
            });
        }
        for (Map.Entry<String, Map<String, Object>> entry : modelNodes.entrySet()) {
            Map<String, Object> model = entry.getValue();
            String[] parts = entry.getKey().split("\\|", 2);
            ModelParams params = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                    .eq(ModelParams::getModelName, parts[0])
                    .eq(ModelParams::getType, parts[1])
                    .last("LIMIT 1"));
            if (params != null) {
                Map<String, Object> p = snakeMapper.convertValue(params, new TypeReference<LinkedHashMap<String, Object>>() {});
                p.keySet().removeAll(List.of("id", "model_name", "type", "is_default", "created_at", "updated_at"));
                p.values().removeIf(Objects::isNull);
                // Jackson 把 getNDefault 命名为 ndefault，而不是 n_default；统一成导入期望的键
                moveKey(p, "ndefault", "n_default");
                moveKey(p, "nmin", "n_min");
                moveKey(p, "nmax", "n_max");
                String schema = (String) p.remove("params_schema");
                String ollama = (String) p.remove("ollama_params");
                if (schema != null && !schema.isBlank()) {
                    try {
                        model.put("schema", objectMapper.readValue(schema, Object.class));
                    } catch (Exception e) {
                        model.put("schema_raw", schema);
                    }
                }
                if (ollama != null && !ollama.isBlank()) {
                    try {
                        p.put("ollama", objectMapper.readValue(ollama, Object.class));
                    } catch (Exception ignored) {
                    }
                }
                Map<String, Object> cleaned = new LinkedHashMap<>();
                p.forEach((k, v) -> {
                    if (v instanceof String s && s.isBlank()) return;
                    cleaned.put(k, v);
                });
                if (!cleaned.isEmpty()) model.put("params", cleaned);
            }
            List<String> fallbacks = fallbackService.list(parts[0], parts[1]);
            if (!fallbacks.isEmpty()) model.put("fallbacks", fallbacks);
            modelList.add(model);
        }
        root.put("models", modelList);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setAllowUnicode(true);
        return new Yaml(options).dump(root);
    }

    // ==================================================================
    // 校验
    // ==================================================================

    @SuppressWarnings("unchecked")
    public ValidationResult validate(String yaml) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> root;
        try {
            Object loaded = new Yaml().load(yaml == null ? "" : yaml);
            if (!(loaded instanceof Map<?, ?> map)) {
                return new ValidationResult(false, List.of("YAML 顶层必须是对象"), List.of());
            }
            root = (Map<String, Object>) map;
        } catch (Exception e) {
            return new ValidationResult(false, List.of("YAML 解析失败：" + e.getMessage()), List.of());
        }

        Set<String> channelNames = new LinkedHashSet<>();
        channelMapper.selectList(null).forEach(c -> channelNames.add(c.getName()));

        List<Map<String, Object>> channels = mapList(root.get("channels"));
        for (int i = 0; i < channels.size(); i++) {
            Map<String, Object> c = channels.get(i);
            String path = "channels[" + i + "]";
            String name = text(c.get("name"));
            if (name == null) {
                errors.add(path + ".name 不能为空");
                continue;
            }
            channelNames.add(name);
            if (text(c.get("base_url")) == null) errors.add(path + ".base_url 不能为空");
            if (text(c.get("type")) == null) warnings.add(path + ".type 为空，默认 openai_compatible");
            for (int j = 0; j < mapList(c.get("keys")).size(); j++) {
                Map<String, Object> k = mapList(c.get("keys")).get(j);
                if (text(k.get("api_key")) == null) {
                    warnings.add(path + ".keys[" + j + "] 未提供 api_key，已有 Key 保持不变，新 Key 会被跳过");
                }
            }
        }

        List<Map<String, Object>> models = mapList(root.get("models"));
        Set<String> modelKeys = new LinkedHashSet<>();
        for (int i = 0; i < models.size(); i++) {
            Map<String, Object> m = models.get(i);
            String path = "models[" + i + "]";
            String name = text(m.get("name"));
            if (name == null) {
                errors.add(path + ".name 不能为空");
                continue;
            }
            String type = text(m.get("type"));
            type = type == null ? "text" : type;
            if (!modelKeys.add(name + "|" + type)) errors.add(path + " 重复的模型/类型：" + name + "/" + type);

            Object schema = m.get("schema");
            if (schema != null) {
                try {
                    String json = schema instanceof String s ? s : objectMapper.writeValueAsString(schema);
                    ParamsSchemaValidator.Result result = ParamsSchemaValidator.validate(json);
                    if (!result.valid()) errors.add(path + ".schema " + result.summary());
                } catch (Exception e) {
                    errors.add(path + ".schema 无法序列化：" + e.getMessage());
                }
            }

            List<Map<String, Object>> routes = mapList(m.get("routes"));
            boolean hasDefault = false;
            for (int j = 0; j < routes.size(); j++) {
                Map<String, Object> r = routes.get(j);
                String rPath = path + ".routes[" + j + "]";
                String channelName = text(r.get("channel"));
                if (channelName == null) {
                    errors.add(rPath + ".channel 不能为空");
                } else if (!channelNames.contains(channelName)) {
                    errors.add(rPath + ".channel 引用了不存在的渠道：" + channelName);
                }
                Object overrides = r.get("param_overrides");
                if (overrides != null) {
                    try {
                        ParamOverrideSupport.parse(overrides instanceof String s
                                ? s : objectMapper.writeValueAsString(overrides));
                    } catch (Exception e) {
                        errors.add(rPath + ".param_overrides " + e.getMessage());
                    }
                }
                if (Boolean.TRUE.equals(r.get("is_default"))) {
                    if (hasDefault) errors.add(path + " 只能有一个 is_default 路由");
                    hasDefault = true;
                }
            }
            if (m.get("fallbacks") != null && !(m.get("fallbacks") instanceof List<?>)) {
                errors.add(path + ".fallbacks 必须是数组");
            }
        }
        return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    // ==================================================================
    // 导入
    // ==================================================================

    @SuppressWarnings("unchecked")
    @Transactional
    public ImportResult importYaml(String yaml) {
        ValidationResult validation = validate(yaml);
        if (!validation.valid()) {
            throw new BusinessException(40001, "配置校验失败：" + String.join("；", validation.errors()));
        }
        Map<String, Object> root = (Map<String, Object>) new Yaml().load(yaml);
        List<String> warnings = new ArrayList<>(validation.warnings());
        int channelCount = 0, keyCount = 0, modelCount = 0, routeCount = 0, fallbackCount = 0;

        Map<String, Channel> channelByName = new LinkedHashMap<>();
        channelMapper.selectList(null).forEach(c -> channelByName.put(c.getName(), c));

        for (Map<String, Object> c : mapList(root.get("channels"))) {
            String name = text(c.get("name"));
            if (name == null) continue;
            ChannelRequest request = convert(c, ChannelRequest.class, "models", "keys");
            request.setModels(joinCsv(c.get("models")));
            Channel existing = channelByName.get(name);
            Channel saved;
            if (existing == null) {
                saved = channelService.create(request);
                channelCount++;
            } else {
                saved = channelService.update(existing.getId(), request);
                channelCount++;
            }
            channelByName.put(name, saved);

            for (Map<String, Object> k : mapList(c.get("keys"))) {
                String label = text(k.get("label"));
                if (label == null) {
                    warnings.add("渠道 " + name + " 的 Key 缺少 label，已跳过");
                    continue;
                }
                ChannelKey existingKey = channelKeyMapper.selectList(new LambdaQueryWrapper<ChannelKey>()
                                .eq(ChannelKey::getChannelId, saved.getId())
                                .eq(ChannelKey::getLabel, label))
                        .stream().findFirst().orElse(null);
                ChannelKeyRequest keyRequest = snakeMapper.convertValue(k, ChannelKeyRequest.class);                if (existingKey != null) {
                    channelKeyService.update(saved.getId(), existingKey.getId(), keyRequest);
                    keyCount++;
                } else if (keyRequest.getApiKey() != null && !keyRequest.getApiKey().isBlank()) {
                    channelKeyService.create(saved.getId(), keyRequest);
                    keyCount++;
                } else {
                    warnings.add("渠道 " + name + " 的新 Key「" + label + "」缺少 api_key，已跳过");
                }
            }
        }

        for (Map<String, Object> m : mapList(root.get("models"))) {
            String name = text(m.get("name"));
            if (name == null) continue;
            String type = text(m.get("type"));
            type = type == null ? "text" : type;

            Object schema = m.get("schema");
            Object paramsNode = m.get("params");
            boolean hasParams = paramsNode instanceof Map<?, ?> pm && !pm.isEmpty();
            boolean hasSchema = schema != null;
            ModelParams existingParams = modelParamsMapper.selectOne(new LambdaQueryWrapper<ModelParams>()
                    .eq(ModelParams::getModelName, name).eq(ModelParams::getType, type).last("LIMIT 1"));
            if (hasParams || hasSchema || existingParams != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                if (paramsNode instanceof Map<?, ?> pm) {
                    p.putAll((Map<String, Object>) pm);
                    Object ollama = p.remove("ollama");
                    if (ollama != null && p.get("ollama_params") == null) {
                        p.put("ollama_params", json(ollama));
                    }
                }
                // snakeMapper 期望 snake_case：record 组件 modelName/paramsSchema/ollamaParams
                // 对应 model_name/params_schema/ollama_params
                p.put("model_name", name);
                p.put("type", type);
                // 兼容历史导出里的 ndefault/nmin/nmax
                moveKey(p, "ndefault", "n_default");
                moveKey(p, "nmin", "n_min");
                moveKey(p, "nmax", "n_max");
                if (hasSchema) {
                    try {
                        p.put("params_schema", schema instanceof String s
                                ? s : objectMapper.writeValueAsString(schema));
                    } catch (Exception e) {
                        throw new BusinessException(40001, "模型 " + name + " 的 schema 无法序列化");
                    }
                }
                ModelParamsRequest request = snakeMapper.convertValue(p, ModelParamsRequest.class);
                modelParamsService.upsert(request);
                modelCount++;
            }

            List<Map<String, Object>> routes = mapList(m.get("routes"));
            for (Map<String, Object> r : routes) {
                String channelName = text(r.get("channel"));
                Channel channel = channelByName.get(channelName);
                if (channel == null) {
                    throw new BusinessException(40001, "模型 " + name + " 的路由引用了不存在的渠道：" + channelName);
                }
                RouteRequest request = convert(r, RouteRequest.class, "channel", "param_overrides");
                request.setModelName(name);
                request.setType(type);
                request.setChannelId(channel.getId());
                request.setFallbacks(null);
                Object overrides = r.get("param_overrides");
                if (overrides != null) {
                    request.setParamOverrides(overrides instanceof String s ? s : json(overrides));
                }
                ModelRoute existingRoute = routeMapper.selectList(new LambdaQueryWrapper<ModelRoute>()
                                .eq(ModelRoute::getModelName, name)
                                .eq(ModelRoute::getChannelId, channel.getId()))
                        .stream().findFirst().orElse(null);
                if (existingRoute == null) {
                    routeService.create(request);
                } else {
                    routeService.update(existingRoute.getId(), request);
                }
                routeCount++;
                if (Boolean.TRUE.equals(r.get("is_default"))) {
                    routeService.setDefaultRoute(type, name, true);
                }
            }

            Object fallbacks = m.get("fallbacks");
            if (fallbacks instanceof List<?> list) {
                List<String> names = list.stream().map(String::valueOf).toList();
                fallbackService.replace(name, type, names);
                fallbackCount += names.size();
            }
        }
        log.info("配置导入完成: channels={}, keys={}, models={}, routes={}, fallbacks={}",
                channelCount, keyCount, modelCount, routeCount, fallbackCount);
        return new ImportResult(channelCount, keyCount, modelCount, routeCount, fallbackCount, warnings);
    }

    // ==================================================================
    // helpers
    // ==================================================================

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** 把 map 里的旧键移动到新键（新键已存在时不覆盖）。 */
    private static void moveKey(Map<String, Object> map, String from, String to) {
        if (map.containsKey(from) && !map.containsKey(to)) {
            map.put(to, map.remove(from));
        } else if (map.containsKey(from)) {
            map.remove(from);
        }
    }

    /** 复制一份 map、去掉 YAML 专用键后再转 DTO，避免 list→String / object→String 转换失败。 */
    private <T> T convert(Map<String, Object> source, Class<T> type, String... dropKeys) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        for (String key : dropKeys) copy.remove(key);
        return snakeMapper.convertValue(copy, type);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(40001, "JSON 序列化失败：" + e.getMessage());
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return new ArrayList<>();
        return new ArrayList<>(java.util.Arrays.stream(csv.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    private static String joinCsv(Object value) {
        if (!(value instanceof List<?> list)) return null;
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining(","));
    }
}
