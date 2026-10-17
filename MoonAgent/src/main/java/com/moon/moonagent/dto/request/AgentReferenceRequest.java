package com.moon.moonagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 用户消息正文里一个「引用资料」标记（{@code @名称}）的机器可读伴随信息。
 *
 * <p>正文保留 {@code @名称} 原文（系统提示词据此声明「@ = 必须去读」的语义），
 * 本 record 提供同一批引用的结构化版本：{@code token} 给人看，{@code kind + id}
 * 让后端一次命中目标，避免同名资料（同名文档 / 同名会话）产生歧义。
 *
 * <p>{@code kind} 取值固定四类：{@code library_file}（项目库文件）、
 * {@code local_file}（会话内本地文件）、{@code scene}（分镜）、{@code conversation}（会话）。
 * 所有字段都是附加资料，非法项由消费方降级处理，不能因为一条引用拒绝整轮。
 */
public record AgentReferenceRequest(
        @NotBlank(message = "引用类型不能为空")
        @Size(max = 32, message = "引用类型过长")
        @Pattern(regexp = "library_file|local_file|scene|conversation", message = "引用类型不合法")
        String kind,
        @NotBlank(message = "引用 ID 不能为空")
        @Size(max = 128, message = "引用 ID 过长") String id,
        @NotBlank(message = "引用标记不能为空")
        @Size(max = 200, message = "引用标记过长") String token,
        @Size(max = 200, message = "引用名称过长") String name,
        @Size(max = 128, message = "引用资产 ID 过长") String assetId,
        @Size(max = 128, message = "引用媒体 ID 过长") String mediaId,
        @Size(max = 1024, message = "引用 URL 过长") String url,
        @Size(max = 128, message = "引用 MIME 类型过长") String mimeType,
        @PositiveOrZero(message = "引用大小不能为负数") Long sizeBytes,
        Integer sceneNumber
) {
}
