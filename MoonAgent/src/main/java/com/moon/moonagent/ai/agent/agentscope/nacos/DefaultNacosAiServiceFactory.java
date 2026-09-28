package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.exception.NacosException;

import java.util.Properties;

public class DefaultNacosAiServiceFactory implements NacosAiServiceFactory {

    @Override
    public AiService create(NacosAssetProperties assets) throws NacosException {
        Properties nacos = new Properties();
        nacos.setProperty(PropertyKeyConst.SERVER_ADDR, assets.getServerAddress());
        nacos.setProperty(PropertyKeyConst.NAMESPACE, assets.getNamespace());
        nacos.setProperty("NACOS.CONNECT.TIMEOUT",
                Long.toString(assets.getConnectTimeout().toMillis()));
        nacos.setProperty(PropertyKeyConst.CONFIG_REQUEST_TIMEOUT,
                Long.toString(assets.getRequestTimeout().toMillis()));
        nacos.setProperty("namingRequestTimeout",
                Long.toString(assets.getRequestTimeout().toMillis()));
        nacos.setProperty(AiConstants.AI_REQUEST_TIMEOUT,
                Long.toString(assets.getRequestTimeout().toMillis()));
        if (!assets.getUsername().isBlank()) {
            nacos.setProperty(PropertyKeyConst.USERNAME, assets.getUsername());
            nacos.setProperty(PropertyKeyConst.PASSWORD, assets.getPassword());
        }
        return AiFactory.createAiService(nacos);
    }
}
