package com.moon.moonagent.ai.agent.sandbox;

/**
 * MoonSandbox「按需物化技能」接口的返回体。
 *
 * @param skill     技能 id
 * @param path      会话工作区内的相对路径（如 {@code skills/ppt-master}）
 * @param installed 是否真的执行了复制
 * @param files     复制的文件数
 * @param bytes     复制的字节数
 * @param version   技能包版本键
 */
public record SandboxSkillResult(
        String skill,
        String path,
        boolean installed,
        int files,
        long bytes,
        String version) {
}
