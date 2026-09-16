package com.moon.moonagent.ai.agent.canvas;

/**
 * The canvas was created but the browser did not finish switching and reconnecting in time.
 */
public final class CanvasActivationRequiredException extends RuntimeException {

    private final String projectId;
    private final String canvasId;
    private final String canvasTitle;

    public CanvasActivationRequiredException(String projectId, String canvasId, String canvasTitle) {
        super("新画布已创建，但浏览器尚未完成切换");
        this.projectId = projectId;
        this.canvasId = canvasId;
        this.canvasTitle = canvasTitle;
    }

    public String projectId() {
        return projectId;
    }

    public String canvasId() {
        return canvasId;
    }

    public String canvasTitle() {
        return canvasTitle;
    }
}
