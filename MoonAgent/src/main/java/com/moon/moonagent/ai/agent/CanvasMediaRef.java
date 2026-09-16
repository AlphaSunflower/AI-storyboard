package com.moon.moonagent.ai.agent;

/**
 * 画布编辑模式随消息携带的媒体引用。文件本体只上传一次，后续轮次和 HITL 续流只传引用。
 */
public record CanvasMediaRef(
        String kind,
        String assetId,
        String url,
        String name,
        String mimeType,
        String source
) {
}
