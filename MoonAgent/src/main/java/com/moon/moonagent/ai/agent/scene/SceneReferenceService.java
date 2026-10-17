package com.moon.moonagent.ai.agent.scene;

import com.moon.moonagent.dto.request.AgentReferenceRequest;

import java.util.List;

/**
 * 分镜引用解析服务：把本轮 {@code @分镜N} 引用解析成可注入提示词的分镜内容。
 *
 * <p>只读、无副作用：绝不写库，也绝不向上抛异常。归属过滤是硬约束——
 * 只返回属于 {@code projectId} 的分镜，越权或已删除一律归入未解析结果。
 */
public interface SceneReferenceService {

    /**
     * 解析本轮的分镜引用（忽略非 {@code scene} 类型的引用，保持输入顺序）。
     *
     * @param projectId  当前会话所属项目 id；空值表示无法校验归属，全部按未解析处理
     * @param references 本轮引用清单；空/null 返回空表
     * @return 与输入中 {@code kind=scene} 项一一对应的解析结果
     */
    List<SceneReferenceView> resolve(String projectId, List<AgentReferenceRequest> references);
}
