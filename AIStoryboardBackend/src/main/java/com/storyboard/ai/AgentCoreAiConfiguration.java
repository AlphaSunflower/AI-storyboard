package com.storyboard.ai;

import com.moon.moonagent.ai.AiConfigProperties;
import com.moon.moonagent.ai.GatewayModelService;
import com.moon.moonagent.ai.ImageGenerationService;
import com.moon.moonagent.ai.SceneGenerationBridge;
import com.moon.moonagent.ai.VideoGenerationService;
import com.moon.moonagent.ai.impl.GatewayModelServiceImpl;
import com.moon.moonagent.ai.impl.ImageGenerationServiceImpl;
import com.moon.moonagent.ai.impl.VideoGenerationServiceImpl;
import com.moon.moonagent.ai.impl.VideoPollExecutor;
import com.moon.moonagent.service.FileStorageService;
import com.storyboard.common.generation.GenerationRecordWriter;
import org.springframework.ai.image.ImageModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 显式装配 agent-core 的网关客户端与媒体生成服务。
 *
 * <p>不扫描整个 {@code com.moon.moonagent}，避免把 MoonAgent 专属的分镜生产链 Bean 带进主后端。
 */
@Configuration
public class AgentCoreAiConfiguration {

    @Bean("agentCoreGatewayModelService")
    public GatewayModelService agentCoreGatewayModelService(
            AiConfigProperties config,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        return new GatewayModelServiceImpl(config, redisTemplateProvider);
    }

    @Bean("agentCoreImageGenerationService")
    public ImageGenerationService agentCoreImageGenerationService(
            AiConfigProperties config,
            GatewayModelService gatewayModelService,
            FileStorageService fileStorageService,
            SceneGenerationBridge sceneGenerationBridge,
            ImageModel imageModel) {
        return new ImageGenerationServiceImpl(
                config, gatewayModelService, fileStorageService, sceneGenerationBridge, imageModel);
    }

    @Bean("agentCoreVideoPollExecutor")
    public VideoPollExecutor agentCoreVideoPollExecutor(
            AiConfigProperties config,
            SceneGenerationBridge sceneGenerationBridge,
            FileStorageService fileStorageService,
            GenerationRecordWriter generationRecordWriter) {
        return new VideoPollExecutor(
                config, sceneGenerationBridge, fileStorageService, generationRecordWriter);
    }

    @Bean("agentCoreVideoGenerationService")
    public VideoGenerationService agentCoreVideoGenerationService(
            AiConfigProperties config,
            GatewayModelService gatewayModelService,
            VideoPollExecutor videoPollExecutor,
            FileStorageService fileStorageService,
            SceneGenerationBridge sceneGenerationBridge) {
        return new VideoGenerationServiceImpl(
                config, gatewayModelService, videoPollExecutor, fileStorageService, sceneGenerationBridge);
    }
}
