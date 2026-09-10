package com.storyboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyboard.entity.AgentAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentAssetMapper extends BaseMapper<AgentAsset> {

    /**
     * 查询当前用户指定项目内的一条已完成图片/视频产出记录。
     * 用于校验分镜素材引用不能跨项目、跨用户或指向非产出记录。
     */
    @Select("""
            SELECT aa.*
            FROM public.agent_assets aa
            LEFT JOIN public.conversations c ON c.id = aa.conversation_id
            LEFT JOIN public.projects p ON p.id = c.project_id
            WHERE aa.id = #{assetId}
              AND aa.type IN ('image', 'video')
              AND aa.status = 'completed'
              AND (
                    (aa.project_id = #{projectId} AND aa.user_id = #{userId})
                    OR (p.id = #{projectId} AND p.user_id = #{userId})
                  )
            LIMIT 1
            """)
    AgentAsset findOwnedOutputRecord(@Param("userId") String userId,
                                     @Param("projectId") String projectId,
                                     @Param("assetId") String assetId);
}
