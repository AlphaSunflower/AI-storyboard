package com.moon.moonagent.ai.agent.canvas;

import com.moon.moonagent.ai.agent.CanvasMediaRef;
import com.moon.moonagent.ai.agent.CanvasSessionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把画布范围与媒体引用写入技能系统提示，确保工具调用始终使用当前项目和画布。
 */
public final class CanvasContextPromptBuilder {

    private CanvasContextPromptBuilder() {
    }

    public static String build(CanvasSessionContext context, List<String> uploadedPictureUrls) {
        boolean canvasEdit = context != null && context.isCanvasEditMode();
        List<CanvasMediaRef> uploadedRefs = uploadedPictureUrls == null ? List.of() : uploadedPictureUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .filter(url -> context == null || !context.isMediaExcluded(url))
                .map(url -> new CanvasMediaRef("image", null, url, null, null, "upload"))
                .toList();
        if (!canvasEdit && uploadedRefs.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder();
	    if (canvasEdit) {
	        prompt.append("## 当前画布编辑上下文\n")
	                .append("projectId=").append(context.projectId()).append('\n')
	                .append("canvasId=").append(context.canvasId()).append('\n')
	                .append("当前处于画布编辑模式：只能执行画布相关操作，不得切换到分镜、图片、视频或通用回答链路。\n")
	                .append("调用 Canvas MCP 工具时必须使用上述 projectId 和 canvasId。\n")
	                .append("每次执行前以 canvas_get_context 的真实返回为准；若目标画布未打开或存在多个活动画布，请让用户在当前界面切换到目标画布后再继续，不得要求关闭其他窗口。\n")
	                .append("禁止向用户展示或要求输入 projectId、canvasId、nodeId、edgeId；需要选择时调用 request_human_input，以项目名、画布名或节点名作为标题。\n");
	    }
        Map<String, CanvasMediaRef> refsByKey = new LinkedHashMap<>();
        if (context != null) {
            for (CanvasMediaRef ref : context.normalizedMediaRefs()) {
                if (ref.url() != null && context.isMediaExcluded(ref.url())) continue;
                refsByKey.putIfAbsent(refKey(ref), ref);
            }
        }
        for (CanvasMediaRef ref : uploadedRefs) {
            refsByKey.putIfAbsent(refKey(ref), ref);
        }
        List<CanvasMediaRef> refs = new ArrayList<>(refsByKey.values());
        if (!refs.isEmpty()) {
            prompt.append("本轮上传媒体/可用素材：\n")
                    .append("这些图片已经上传成功，用户说“这三张图/这些图/刚上传的图”时指的就是下面的引用。\n")
                    .append("不得要求用户再次提供 URL；必须对每条 source=upload 的图片调用 canvas_import_media，导入后再连接或布局。\n");
            for (CanvasMediaRef ref : refs) {
                prompt.append("- kind=").append(ref.kind());
                if (ref.assetId() != null && !ref.assetId().isBlank()) {
                    prompt.append(", assetId=").append(ref.assetId());
                }
                if (ref.url() != null && !ref.url().isBlank()) {
                    prompt.append(", url=").append(ref.url());
                }
                if (ref.name() != null && !ref.name().isBlank()) {
                    prompt.append(", name=").append(ref.name());
                }
                if (ref.mimeType() != null && !ref.mimeType().isBlank()) {
                    prompt.append(", mimeType=").append(ref.mimeType());
                }
                if (ref.source() != null && !ref.source().isBlank()) {
                    prompt.append(", source=").append(ref.source());
                }
                prompt.append('\n');
            }
        }
        return prompt.toString().trim();
    }

    private static String refKey(CanvasMediaRef ref) {
        if (ref.assetId() != null && !ref.assetId().isBlank()) return "asset:" + ref.assetId();
        return "url:" + (ref.url() == null ? "" : ref.url());
    }
}
