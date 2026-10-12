package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AgentAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    /**
     * 历史产出回补：已完成、且从未入过项目库 outbox、库中也不存在同一对象的产出素材。
     *
     * <p>沙箱产出在引入自动回写之前不会入队，需要靠这个扫描把历史数据补进项目库。
     */
    @Select("""
            SELECT a.*
            FROM public.agent_assets a
            WHERE a.status IN ('completed', 'succeeded')
              AND a.type IN ('image', 'video', 'audio', 'document')
              AND a.url IS NOT NULL AND a.url <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM public.asset_outbox o
                  WHERE o.producer = 'moon-agent' AND o.generation_record_id = a.id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM asset.library_blobs b
                  WHERE b.object_key = regexp_replace(regexp_replace(a.url, '\\?.*$', ''), '^/api/files/', '')
              )
            ORDER BY a.created_at
            LIMIT #{limit}
            """)
    List<AgentAsset> findUnsyncedOutputAssets(@Param("limit") int limit);
}
