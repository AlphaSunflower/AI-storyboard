package com.moon.moonagent.ai.agent;

/**
 * 对话打断信号：用户在 /chat 点击「停止生成」（或 SSE 流被客户端中断）时，置位会话级
 * interrupt 标志；编排中的流式 LLM 循环 {@code doOnNext} 检测到后抛出本异常，触发
 * 协作式取消——中断流式打字机、跳过被截断回答的落库、提前释放会话锁，让用户能立即续聊。
 *
 * <p>与普通异常区分开：只有本异常会在 AgentScope turn 运行边界与
 * 服务入口（AgentChatServiceImpl.streamMessage 等）被识别为「用户主动打断」——不补发
 * 友好错误文案、不写入被截断的 assistant 消息，静默收尾。
 */
public class AgentInterruptException extends RuntimeException {
    public AgentInterruptException() {
        super("已停止生成");
    }
}
