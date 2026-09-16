package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasSessionContext;

/**
 * 画布编辑模式内只允许画布技能。明确命中执行/删除时保留细分类，其余统一留在画布设计链。
 */
public final class CanvasModeIntentPolicy {

    public static final String DESIGN = "intent-canvas-design";
    public static final String RUN = "intent-canvas-run";
    public static final String DELETE = "intent-canvas-delete";
    public static final String BIND_ACTION = "canvas-bind";
    public static final String BOUND_ACTION = "canvas-bound";

    private CanvasModeIntentPolicy() {
    }

    public static String resolve(String query, String ruleMatchedIntent) {
        if (RUN.equals(ruleMatchedIntent) || DELETE.equals(ruleMatchedIntent)) {
            return ruleMatchedIntent;
        }
        return DESIGN;
    }

    public static boolean isCanvasIntent(String intent) {
        return DESIGN.equals(intent) || RUN.equals(intent) || DELETE.equals(intent);
    }

    public static boolean requiresBinding(String intent, CanvasSessionContext context) {
        return isCanvasIntent(intent) && (context == null || !context.isCanvasEditMode());
    }
}
