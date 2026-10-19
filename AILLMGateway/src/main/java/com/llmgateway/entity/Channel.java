package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 上游渠道表 */
@Data
@TableName("channel")
public class Channel {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String name;
    /** openai_compatible | gemini | minimax | ollama */
    private String type;
    private String baseUrl;
    /** AES 加密密文 */
    private String apiKey;
    /** 该渠道支持的模型名（逗号分隔，可空；测试弹窗候选与已配路由模型合并去重） */
    private String models;
    private Boolean enabled;
    /** 同模型多渠道时升序取第一个 */
    private Integer priority;
    /** 同优先级内的负载权重（默认 1；与路由 weight 相乘） */
    private Integer weight;
    /** 单请求超时覆盖（毫秒；空=全局默认） */
    private Integer timeoutMs;
    /** 渠道级重试次数（额外尝试次数；空/0=不重试） */
    private Integer maxRetries;
    /** 最近一次失败原因（管理端排障用，异步更新） */
    private String lastError;
    private OffsetDateTime lastCheckedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Boolean deleted;
}
