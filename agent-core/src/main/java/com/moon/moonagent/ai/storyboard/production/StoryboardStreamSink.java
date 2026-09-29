package com.moon.moonagent.ai.storyboard.production;

/**
 * 分镜管线把「面向用户的长文本」的流式增量推给调用方（例如后台任务面板实时显示脚本）。
 * 结构化阶段只传清洗后的可读字段，不把原始 JSON 暴露给用户。
 */
@FunctionalInterface
public interface StoryboardStreamSink {

    StoryboardStreamSink NOOP = (stage, delta) -> {
    };

    void onDelta(String stage, String delta);

    /** 模型思考链增量（上游返回 reasoning 时才有）。 */
    default void onThinking(String stage, String delta) {
    }
}
