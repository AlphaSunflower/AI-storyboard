package com.moon.moonagent.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.moon.moonagent.mapper.AgentAsyncTaskMapper;
import com.moon.moonagent.mapper.AgentCanvasRunMapper;
import com.moon.moonagent.mapper.AgentCanvasRunOperationMapper;
import com.moon.moonagent.mapper.AgentCanvasSessionMapper;
import com.moon.moonagent.mapper.AgentCheckpointMapper;
import com.moon.moonagent.mapper.AgentConversationAssetGroupMapper;
import com.moon.moonagent.mapper.AgentDocumentIndexMapper;
import com.moon.moonagent.mapper.AgentExternalActionMapper;
import com.moon.moonagent.mapper.AgentMediaCritiqueMapper;
import com.moon.moonagent.mapper.AgentMessageMapper;
import com.moon.moonagent.mapper.AgentTimelineManifestMapper;
import com.moon.moonagent.mapper.AgentTurnEventMapper;
import com.moon.moonagent.mapper.AgentTurnMapper;
import com.moon.moonagent.mapper.AgentWorkflowRunMapper;

/**
 * 单测用的 MyBatis-Plus 实体元数据引导。
 *
 * <p>生产环境里 {@code TableInfo} / lambda 列名缓存由 MyBatis 启动时对每个
 * {@code BaseMapper} 执行 {@code initTableInfo} 建立，所以 {@code LambdaQueryWrapper}
 * 在真实运行中可以直接求值。纯 Mockito 单测没有 MyBatis 启动过程，一旦真的去求值
 * lambda 条件（例如断言删除条件确实按 conversation_id 生成），就会抛
 * {@code MybatisPlus can not find lambda cache for this entity}。
 *
 * <p>这里把一个 {@link MybatisConfiguration} 加上相关 mapper 接口跑一次注册，
 * 让 MyBatis-Plus 自己把列名缓存建好；只给单测用，生产代码不得引用。
 */
public final class MyBatisPlusLambdaCache {

    /** 会话上下文相关的全部 mapper（注册它们即可建立对应实体的 lambda 列名缓存）。 */
    private static final Class<?>[] CONTEXT_MAPPERS = {
            AgentMessageMapper.class,
            AgentCheckpointMapper.class,
            AgentTurnMapper.class,
            AgentTurnEventMapper.class,
            AgentCanvasRunMapper.class,
            AgentCanvasRunOperationMapper.class,
            AgentCanvasSessionMapper.class,
            AgentConversationAssetGroupMapper.class,
            AgentDocumentIndexMapper.class,
            AgentAsyncTaskMapper.class,
            AgentTimelineManifestMapper.class,
            AgentMediaCritiqueMapper.class,
            AgentWorkflowRunMapper.class,
            AgentExternalActionMapper.class,
    };

    private MyBatisPlusLambdaCache() {
    }

    /** 建立会话上下文相关实体的 lambda 列名缓存（幂等，可重复调用）。 */
    public static void installContextMappers() {
        install(CONTEXT_MAPPERS);
    }

    /** 建立指定 mapper 对应实体的 lambda 列名缓存（幂等，可重复调用）。 */
    public static void install(Class<?>... mapperTypes) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapperType : mapperTypes) {
            configuration.addMapper(mapperType);
        }
    }
}
