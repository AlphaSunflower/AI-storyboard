package com.moon.moonagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.moonagent.entity.AssetOutboxEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AssetOutboxMapper extends BaseMapper<AssetOutboxEvent> {

    @Insert("""
            INSERT INTO public.asset_outbox(
                event_id, producer, generation_record_id, result_index, group_id,
                result_asset_id, payload_json
            ) VALUES (
                #{event.eventId}, #{event.producer}, #{event.generationRecordId}, #{event.resultIndex},
                #{event.groupId}, #{event.resultAssetId}, #{event.payloadJson}
            )
            ON CONFLICT (producer, generation_record_id, result_index) DO NOTHING
            """)
    int insertIgnore(@Param("event") AssetOutboxEvent event);

    @Select("""
            WITH claimed AS (
                SELECT event_id
                FROM public.asset_outbox
                WHERE status = 'PENDING' AND next_retry_at <= now()
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE public.asset_outbox e
            SET status = 'PROCESSING',
                attempt_count = attempt_count + 1,
                lease_owner = #{workerId},
                lease_until = now() + interval '5 minutes',
                updated_at = now()
            FROM claimed
            WHERE e.event_id = claimed.event_id
            RETURNING e.event_id, e.producer, e.generation_record_id, e.result_index, e.group_id,
                      e.result_asset_id, e.payload_json, e.status, e.attempt_count,
                      e.error_message, e.next_retry_at, e.created_at, e.updated_at
            """)
    List<AssetOutboxEvent> claim(@Param("workerId") String workerId, @Param("limit") int limit);

    @Update("""
            UPDATE public.asset_outbox
            SET status = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL,
                error_message = NULL, updated_at = now()
            WHERE event_id = #{eventId}
            """)
    int markSucceeded(@Param("eventId") String eventId);

    @Update("""
            UPDATE public.asset_outbox
            SET status = CASE WHEN attempt_count >= 8 THEN 'DEAD' ELSE 'PENDING' END,
                lease_owner = NULL,
                lease_until = NULL,
                next_retry_at = now() + make_interval(secs => LEAST(3600, power(2, attempt_count)::int * 5)),
                error_message = #{error},
                updated_at = now()
            WHERE event_id = #{eventId}
            """)
    int markFailed(@Param("eventId") String eventId, @Param("error") String error);
}
