package com.moon.moonagent.ai.agent.agentscope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从服务端受控目录加载任务图共享常量，避免让模型把长 JSON 逐字复制进工具参数。
 *
 * <p>只允许 {@code context-root} 下的普通文件；路径先 normalize 再 realPath，
 * 防止 {@code ../} 与符号链接逃逸。
 */
final class SubagentContextLoader {

    private SubagentContextLoader() {
    }

    static String load(String ref, String root, int maxChars) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("contextRef 不能为空");
        }
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("context-root 未配置");
        }
        try {
            Path rootPath = Path.of(root).toAbsolutePath().normalize();
            Path candidate = Path.of(ref.trim());
            if (!candidate.isAbsolute()) {
                candidate = rootPath.resolve(candidate);
            }
            candidate = candidate.normalize();
            if (!candidate.startsWith(rootPath)) {
                throw new IllegalArgumentException("contextRef 必须位于 " + rootPath + " 下");
            }
            if (!Files.isRegularFile(candidate)) {
                throw new IllegalArgumentException("contextRef 文件不存在: " + candidate);
            }
            Path realRoot = rootPath.toRealPath();
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new IllegalArgumentException("contextRef 不能通过符号链接逃逸: " + realFile);
            }
            long size = Files.size(realFile);
            if (maxChars > 0 && size > maxChars) {
                throw new IllegalArgumentException("contextRef 文件过大: " + size + " bytes");
            }
            String text = Files.readString(realFile, StandardCharsets.UTF_8);
            if (maxChars > 0 && text.length() > maxChars) {
                throw new IllegalArgumentException("contextRef 内容过大: " + text.length() + " chars");
            }
            return text.trim();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 contextRef 失败: " + e.getMessage(), e);
        }
    }
}
