package com.storyboard.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 分镜资产组快照持久化。 */
@Mapper
public interface SceneMaterialGroupSnapshotMapper {

    @Delete("DELETE FROM public.scene_material_group_snapshots WHERE scene_id = #{sceneId}")
    int deleteBySceneId(@Param("sceneId") String sceneId);

    @Delete("""
            DELETE FROM public.scene_material_group_snapshots
            WHERE scene_id = #{sceneId} AND group_id = #{groupId} AND purpose = #{purpose}
            """)
    int deleteBinding(
            @Param("sceneId") String sceneId,
            @Param("groupId") String groupId,
            @Param("purpose") String purpose
    );

    @Insert("""
            INSERT INTO public.scene_material_group_snapshots(
                scene_id, snapshot_id, group_id, group_version, content_hash, snapshot_json, purpose
            ) VALUES (
                #{sceneId}, #{snapshotId}, #{groupId}, #{groupVersion}, #{contentHash}, #{snapshotJson}, #{purpose}
            )
            """)
    int insert(
            @Param("sceneId") String sceneId,
            @Param("snapshotId") String snapshotId,
            @Param("groupId") String groupId,
            @Param("groupVersion") long groupVersion,
            @Param("contentHash") String contentHash,
            @Param("snapshotJson") String snapshotJson,
            @Param("purpose") String purpose
    );

    @Select("""
            SELECT snapshot_id, group_id, group_version, content_hash, snapshot_json, purpose
            FROM public.scene_material_group_snapshots
            WHERE scene_id = #{sceneId}
            ORDER BY created_at
            """)
    List<BindingRow> listBySceneId(@Param("sceneId") String sceneId);

    record BindingRow(
            String snapshotId,
            String groupId,
            long groupVersion,
            String contentHash,
            String snapshotJson,
            String purpose
    ) {
    }
}
