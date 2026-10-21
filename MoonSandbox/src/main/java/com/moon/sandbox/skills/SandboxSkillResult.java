package com.moon.sandbox.skills;

/**
 * 一次技能物化的结果。
 *
 * @param skill     技能 id（目录名）
 * @param path      会话工作区内的相对路径，如 {@code skills/ppt-master}
 * @param installed 是否真的执行了复制；{@code false} 表示版本命中、复用已有目录
 * @param files     本次复制的文件数
 * @param bytes     本次复制的字节数
 * @param version   本次使用的技能包版本键
 */
public record SandboxSkillResult(
        String skill,
        String path,
        boolean installed,
        int files,
        long bytes,
        String version) {
}
