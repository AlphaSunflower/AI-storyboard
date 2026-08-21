package com.moon.moonagent.ai.agent;

import java.util.List;
import java.util.Map;

/**
 * HITL 表单快照：用户点"确认"时后端需要的一切。
 * formContent = 确认卡片文案（含完整方案文本）；plan = 最近 LLM 节点结构化输出
 * （分镜 items / 图片 message+style+size / 视频方案），缺失时降级用 formContent。
 *
 * <p>原为 {@link AgentChatService} 内部嵌套 record，按「单文件单类」规范提取为同包顶层类，
 * 由 {@link com.moon.moonagent.ai.agent.impl.AgentChatServiceImpl} 使用。
 */
public record FormSnapshot(String formContent, List<Map<String, String>> actions,
                           Map<String, Object> plan, String conversationId,
                           String projectId, long createdAt) {

    /** 表单快照 TTL（与 Dify form_token 过期时间对齐，30 分钟） */
    private static final long FORM_SNAPSHOT_TTL_MS = 30 * 60 * 1000L;

    /** 是否已过期（实现类在 .impl 子包，需 public 跨包调用） */
    public boolean expired() { return System.currentTimeMillis() - createdAt > FORM_SNAPSHOT_TTL_MS; }
}
