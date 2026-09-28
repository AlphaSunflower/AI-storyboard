package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;

@FunctionalInterface
public interface NacosAiServiceFactory {

    AiService create(NacosAssetProperties properties) throws NacosException;
}
