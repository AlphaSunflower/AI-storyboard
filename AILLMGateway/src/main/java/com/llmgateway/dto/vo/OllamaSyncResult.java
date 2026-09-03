package com.llmgateway.dto.vo;

import java.util.List;

/** Ollama 渠道模型列表同步结果（仅刷新测试候选，不建路由） */
public record OllamaSyncResult(
        int total,
        List<String> models
) {}
