package com.storyboard.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 提示词管理器——YAML 文件版。
 * 启动时扫描 classpath:prompts/ 下所有 YAML 文件，以 name 字段作为唯一索引。
 * 本地开发时额外从文件系统 prompts.dir 加载并做 mtime 热刷新（60s 扫描，改文件即生效）。
 * 占位符由 StringSubstitutor 处理。
 */
@Component
public class PromptConfig {

    private static final Logger log = LoggerFactory.getLogger(PromptConfig.class);

    /** 文件系统提示词目录（本地开发，vibecoding 直接改文件即热生效）；打包进 jar 后该路径通常不存在，走 classpath 兜底 */
    @Value("${prompts.dir:src/main/resources/prompts}")
    private String promptsDir;

    /** name → PromptEntry */
    private final Map<String, PromptEntry> prompts = new ConcurrentHashMap<>();

    /** 文件系统 YAML 路径 → 上次 mtime，用于热刷新判变更 */
    private final Map<Path, Long> fileModTimes = new ConcurrentHashMap<>();

    @PostConstruct
    void loadAll() {
        loadFromClasspath();
        loadFromFileSystem();
    }

    /** 启动兜底：从 classpath 扫描（打包进 jar 时提示词位于 jar 内，文件系统路径不存在） */
    private void loadFromClasspath() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:prompts/**/*.yaml");
            for (Resource res : resources) {
                try {
                    String content = new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
                    putFromString(content, res.getFilename());
                } catch (Exception e) {
                    log.warn("[PromptConfig] 解析 YAML 失败: {} — {}", res.getFilename(), e.getMessage());
                }
            }
            log.info("[PromptConfig] 已加载 {} 个提示词: {}", prompts.size(), prompts.keySet());
        } catch (IOException e) {
            log.error("[PromptConfig] 扫描 prompts/ 目录失败", e);
        }
    }

    /** 从文件系统 prompts.dir 扫描（覆盖 classpath 同名项；本地开发热刷新入口） */
    private void loadFromFileSystem() {
        Path root = Paths.get(promptsDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walker = Files.walk(root)) {
            for (Path p : walker.filter(f -> f.getFileName().toString().endsWith(".yaml")).toList()) {
                try {
                    String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    putFromString(content, p.getFileName().toString());
                    fileModTimes.put(p, Files.getLastModifiedTime(p).toMillis());
                } catch (Exception e) {
                    log.warn("[PromptConfig] 解析 YAML 失败: {} — {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[PromptConfig] 扫描提示词目录失败: {} — {}", promptsDir, e.getMessage());
        }
        log.info("[PromptConfig] 已加载 {} 个提示词: {}", prompts.size(), prompts.keySet());
    }

    /** 60s 扫描文件系统提示词目录，mtime 变更 → 重载对应提示词（不重启生效） */
    @Scheduled(fixedDelay = 60_000)
    public void reloadOnChange() {
        Path root = Paths.get(promptsDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        int changed = 0;
        try (Stream<Path> walker = Files.walk(root)) {
            for (Path p : walker.filter(f -> f.getFileName().toString().endsWith(".yaml")).toList()) {
                try {
                    long mtime = Files.getLastModifiedTime(p).toMillis();
                    Long prev = fileModTimes.get(p);
                    if (prev != null && prev.longValue() == mtime) {
                        continue;
                    }
                    putFromString(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), p.getFileName().toString());
                    fileModTimes.put(p, mtime);
                    changed++;
                } catch (Exception e) {
                    log.warn("[PromptConfig] 重载提示词失败: {} — {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[PromptConfig] 扫描提示词目录失败: {} — {}", promptsDir, e.getMessage());
        }
        if (changed > 0) {
            log.info("[PromptConfig] 热刷新 {} 个提示词，共 {}", changed, prompts.size());
        }
    }

    /** 解析单段 YAML 内容并写入 prompts（name 字段作为唯一索引） */
    private void putFromString(String content, String filename) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(content);
        if (data == null || data.get("name") == null) {
            log.warn("[PromptConfig] 跳过无 name 字段的 YAML: {}", filename);
            return;
        }
        String name = data.get("name").toString();
        String template = data.getOrDefault("template", "").toString();
        @SuppressWarnings("unchecked")
        List<String> variables = (List<String>) data.getOrDefault("variables", List.of());
        prompts.put(name, new PromptEntry(name, template, variables));
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
        StringSubstitutor sub = new StringSubstitutor(vars);
        sub.setVariablePrefix("{{");
        sub.setVariableSuffix("}}");
        return sub.replace(raw);
    }

    /** 列出所有已加载的提示词名称（调试用） */
    public List<String> listNames() {
        return new ArrayList<>(prompts.keySet());
    }

    /** 提示词条目 */
    private record PromptEntry(String name, String template, List<String> variables) {}
}
