package com.moon.moonagent.ai.storyboard.production;

@FunctionalInterface
public interface StoryboardProgressListener {

    StoryboardProgressListener NOOP = (stage, progress) -> {
    };

    void update(String stage, int progress);

    default void plotReady(String script) {
    }
}
