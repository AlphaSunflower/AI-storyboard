package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentAssetMapper extends BaseMapper<AgentAsset> {

    @Update("""
            <script>
            UPDATE public.agent_assets
            SET conversation_id = #{conversationId},
                project_id = #{projectId}
            WHERE user_id = #{userId}
              AND conversation_id IS NULL
              AND id IN
              <foreach collection="assetIds" item="assetId"
                       open="(" separator="," close=")">
                #{assetId}
              </foreach>
            </script>
            """)
    int bindConversation(@Param("assetIds") List<String> assetIds,
                         @Param("conversationId") String conversationId,
                         @Param("userId") String userId,
                         @Param("projectId") String projectId);
}
