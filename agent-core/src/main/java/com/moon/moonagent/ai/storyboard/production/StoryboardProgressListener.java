package com.moon.moonagent.ai.storyboard.production;

@FunctionalInterface
public interface StoryboardProgressListener {

    StoryboardProgressListener NOOP = (stage, progress) -> {
    };

    void update(String stage, int progress);

    default void plotReady(String script) {
    }

    /** 面向用户的可读文本流式增量（结构化阶段会自动抽取正文字段）。 */
    default void stream(String stage, String delta) {
    }

    /** 模型思考链增量（上游返回 reasoning 时才有；仅用于展示，可折叠）。 */
    default void thinking(String stage, String delta) {
    }
}
