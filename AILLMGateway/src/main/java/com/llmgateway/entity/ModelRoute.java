package com.llmgateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.OffsetDateTime;

/** 模型路由表：模型名 → 渠道映射（非唯一：一个模型可指向多个渠道按 priority 轮换） */
@Data
@TableName("model_route")
public class ModelRoute {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String modelName;
    private String channelId;
    /** 模型类型：text（文本）/ image（生图）/ video（视频生成）/ vision（图片视频理解），默认 text */
    private String type;
    /** JSON：size/temperature 等默认参数 */
    private String defaultParams;
    /** 该类型兜底默认模型（每类型至多一个默认路由） */
    private Boolean isDefault;
    /** 路由级启停（停用只下线这条渠道绑定，不影响同模型其它渠道） */
    private Boolean enabled;
    /** 优先级（越小越先；同优先级按 weight 加权随机） */
    private Integer priority;
    /** 同优先级内的负载权重（默认 1；与渠道 weight 相乘） */
    private Integer weight;
    /** 公共模型名 → 上游模型名；空=同名直发 */
    private String upstreamModel;
    /** 单请求超时覆盖（毫秒；空=渠道/全局默认） */
    private Integer timeoutMs;
    /** 路由级重试次数（额外尝试次数；空=渠道配置） */
    private Integer maxRetries;
    /** 路由参数覆盖 JSON：{set:{},setIfAbsent:{},delete:[]} */
    private String paramOverrides;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Boolean deleted;
}
