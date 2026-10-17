package com.moon.moonagent.ai.agent;

/**
 * 「/清空上下文」的执行结果。
 *
 * <p>被 {@link AgentChatService#clearContext(String, String)} 的方法签名引用，
 * 按分层规范提为同包顶层 record。
 *
 * @param deletedRows 本次真删除的总行数（0 = 本来就没有上下文）
 * @param title       重置后的会话标题（固定「新对话」）
 */
public record ClearContextResult(int deletedRows, String title) {
}
