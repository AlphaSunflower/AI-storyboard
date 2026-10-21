package com.moon.moonagent.ai.agent.memory;

/**
 * 项目记忆的硬上限（单一真源）。
 *
 * <p>三个数字同时约束三条写入路径：智能体 {@code remember_memory}、个人信息页手动新增、
 * 以及手动编辑后的内容长度。上限只按「有效期内的记忆」计算——过期行在写入前会被清理，
 * 不占用容量。
 *
 * @param project      单个项目最多保留的记忆条数
 * @param user         单个账号跨项目最多保留的记忆条数
 * @param contentChars 单条记忆的最大字符数
 */
public record AgentMemoryLimits(int project, int user, int contentChars) {

    /** 单项目上限：与项目级列表接口的默认读取上限一致。 */
    public static final int PROJECT_MAX = 200;

    /** 单账号上限：与个人信息页一次拉取的上限一致。 */
    public static final int USER_MAX = 500;

    /** 单条上限：足够容纳一条完整约束，又不至于把整篇对话塞进记忆。 */
    public static final int CONTENT_MAX_CHARS = 4000;

    /** 单条内容的最短长度，防止写入「好」「OK」这类无信息量的行。 */
    public static final int CONTENT_MIN_CHARS = 2;

    public static final AgentMemoryLimits DEFAULTS =
            new AgentMemoryLimits(PROJECT_MAX, USER_MAX, CONTENT_MAX_CHARS);
}
