package com.storyboard.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词管理器——YAML 文件版。
 * 启动时扫描 classpath:prompts/ 下所有 YAML 文件，以 name 字段作为唯一索引。
 * 占位符由 StringSubstitutor 处理。
 */
@Component
public class PromptConfig {

    private static final Logger log = LoggerFactory.getLogger(PromptConfig.class);

    /** name → PromptEntry */
    private final Map<String, PromptEntry> prompts = new ConcurrentHashMap<>();

    @PostConstruct
    void loadAll() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:prompts/**/*.yaml");
            Yaml yaml = new Yaml();
            for (Resource res : resources) {
                try {
                    String content = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
                    Map<String, Object> data = yaml.load(content);
                    if (data == null || data.get("name") == null) {
                        log.warn("[PromptConfig] 跳过无 name 字段的 YAML: {}", res.getFilename());
                        continue;
                    }
                    String name = data.get("name").toString();
                    String template = data.getOrDefault("template", "").toString();
                    @SuppressWarnings("unchecked")
                    List<String> variables = (List<String>) data.getOrDefault("variables", List.of());
                    prompts.put(name, new PromptEntry(name, template, variables));
                } catch (Exception e) {
                    log.warn("[PromptConfig] 解析 YAML 失败: {} — {}", res.getFilename(), e.getMessage());
                }
            }
            log.info("[PromptConfig] 已加载 {} 个提示词: {}", prompts.size(), prompts.keySet());
        } catch (IOException e) {
            log.error("[PromptConfig] 扫描 prompts/ 目录失败", e);
        }
    }

    /**
     * 获取原始提示词模板（不替换占位符）。
     *
     * @param name 提示词名称，如 "script/storyboard-system"
     * @return 模板文本；找不到时返回空字符串并打 warn
     */
    public String get(String name) {
        PromptEntry entry = prompts.get(name);
        if (entry == null) {
            log.warn("[PromptConfig] 提示词未找到: {}", name);
            return "";
        }
        return entry.template();
    }

    /**
     * 获取提示词并替换占位符。
     *
     * @param name 提示词名称
     * @param vars 变量键值对，替换模板中的 {{key}} 占位符
     * @return 替换后的文本
     */
    public String get(String name, Map<String, String> vars) {
        String raw = get(name);
        if (raw.isEmpty() || vars == null || vars.isEmpty()) {
            return raw;
        }
        return StringSubstitutor.replace(raw, vars);
    }

    /** 列出所有已加载的提示词名称（调试用） */
    public List<String> listNames() {
        return new ArrayList<>(prompts.keySet());
    }

    /** 提示词条目 */
    private record PromptEntry(String name, String template, List<String> variables) {}
}
