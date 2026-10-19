package com.llmgateway.service;

import com.llmgateway.dto.admin.ChannelKeyRequest;
import com.llmgateway.entity.Channel;
import com.llmgateway.entity.ChannelKey;

import java.util.List;

/** 渠道多 Key 池：加权选择、失败冷却、成功恢复 */
public interface ChannelKeyService {

    List<ChannelKey> list(String channelId);

    ChannelKey create(String channelId, ChannelKeyRequest request);

    ChannelKey update(String channelId, String keyId, ChannelKeyRequest request);

    void delete(String channelId, String keyId);

    /**
     * 为一次上游调用选 Key：优先 Key 池（跳过冷却、按权重随机），
     * 池为空时回退到 channel.api_key（兼容存量单 Key 渠道）。
     */
    KeySelection select(Channel channel);

    /** 记录失败并设置冷却（429/401/5xx 对应不同时长） */
    void markFailure(String keyId, int status);

    void markSuccess(String keyId);

    record KeySelection(String keyId, String plainKey) {
    }
}
