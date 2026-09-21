package com.moon.moonagent.service.asset;

import com.moon.moonagent.entity.AgentAsset;

/** 将生成结果可靠写入项目库 outbox。 */
public interface AssetOutputOutboxService {

    void enqueue(AgentAsset asset);
}
