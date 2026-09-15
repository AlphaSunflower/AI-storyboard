package com.moon.xiaoyuecanvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.moon.xiaoyuecanvas.entity.CanvasJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface CanvasJobMapper extends BaseMapper<CanvasJob> {

    @Select("""
            SELECT *
            FROM public.canvas_jobs
            WHERE status = 'running'
              AND cancel_requested_at IS NULL
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= #{now}
            ORDER BY lease_expires_at
            LIMIT #{limit}
            """)
    List<CanvasJob> selectExpiredRunning(@Param("now") OffsetDateTime now,
                                         @Param("limit") int limit);

    @Update("""
            UPDATE public.canvas_jobs
            SET status = 'interrupted',
                error = COALESCE(error, #{error}),
                lease_owner = NULL,
                lease_expires_at = NULL,
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status = 'running'
              AND cancel_requested_at IS NULL
              AND lease_expires_at IS NOT NULL
              AND lease_expires_at <= #{now}
            """)
    int interruptExpired(@Param("jobId") String jobId,
                         @Param("now") OffsetDateTime now,
                         @Param("error") String error);

    @Update("""
            UPDATE public.canvas_jobs
            SET status = 'running',
                error = NULL,
                started_at = COALESCE(started_at, #{now}),
                completed_at = NULL,
                heartbeat_at = #{now},
                lease_owner = #{owner},
                lease_expires_at = #{expiresAt},
                attempt = attempt + 1,
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND cancel_requested_at IS NULL
              AND (
                    status = 'queued'
                 OR (
                      status = 'interrupted'
                      AND capability_id = 'video.generate'
                      AND provider_task_id IS NOT NULL
                      AND provider_task_id <> ''
                    )
              )
            """)
    int claim(@Param("jobId") String jobId,
              @Param("owner") String owner,
              @Param("now") OffsetDateTime now,
              @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("""
            UPDATE public.canvas_jobs
            SET heartbeat_at = #{now},
                lease_expires_at = #{expiresAt},
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status = 'running'
              AND lease_owner = #{owner}
              AND cancel_requested_at IS NULL
            """)
    int renewLease(@Param("jobId") String jobId,
                   @Param("owner") String owner,
                   @Param("now") OffsetDateTime now,
                   @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("""
            UPDATE public.canvas_jobs
            SET progress = #{progress},
                heartbeat_at = #{now},
                lease_expires_at = #{expiresAt},
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status = 'running'
              AND lease_owner = #{owner}
              AND cancel_requested_at IS NULL
            """)
    int updateProgress(@Param("jobId") String jobId,
                       @Param("owner") String owner,
                       @Param("progress") int progress,
                       @Param("now") OffsetDateTime now,
                       @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("""
            UPDATE public.canvas_jobs
            SET provider_task_id = #{taskId},
                batch_id = COALESCE(batch_id, #{batchId}),
                state_version = state_version + 1,
                updated_at = now()
            WHERE id = #{jobId}
              AND provider_task_id IS NULL
            """)
    int attachProviderTaskId(@Param("jobId") String jobId,
                             @Param("taskId") String taskId,
                             @Param("batchId") String batchId);

    @Update("""
            UPDATE public.canvas_jobs
            SET status = 'completed',
                progress = 100,
                error = NULL,
                result_asset_id = #{resultAssetId},
                result_asset_ids = #{resultAssetIds},
                completed_at = #{now},
                heartbeat_at = #{now},
                lease_owner = NULL,
                lease_expires_at = NULL,
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status = 'running'
              AND lease_owner = #{owner}
              AND cancel_requested_at IS NULL
            """)
    int complete(@Param("jobId") String jobId,
                 @Param("owner") String owner,
                 @Param("resultAssetId") String resultAssetId,
                 @Param("resultAssetIds") String resultAssetIds,
                 @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE public.canvas_jobs
            SET status = 'failed',
                error = #{error},
                completed_at = #{now},
                heartbeat_at = #{now},
                lease_owner = NULL,
                lease_expires_at = NULL,
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status = 'running'
              AND lease_owner = #{owner}
              AND cancel_requested_at IS NULL
            """)
    int fail(@Param("jobId") String jobId,
             @Param("owner") String owner,
             @Param("error") String error,
             @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE public.canvas_jobs
            SET status = 'cancelled',
                cancel_requested_at = #{now},
                completed_at = #{now},
                lease_owner = NULL,
                lease_expires_at = NULL,
                state_version = state_version + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
              AND status IN ('queued', 'running', 'interrupted')
              AND cancel_requested_at IS NULL
            """)
    int cancel(@Param("jobId") String jobId, @Param("now") OffsetDateTime now);

    @Update("""
            UPDATE public.canvas_jobs
            SET last_event_seq = last_event_seq + 1,
                updated_at = #{now}
            WHERE id = #{jobId}
            """)
    int incrementEventSeq(@Param("jobId") String jobId, @Param("now") OffsetDateTime now);

    @Select("SELECT last_event_seq FROM public.canvas_jobs WHERE id = #{jobId}")
    Long selectLastEventSeq(@Param("jobId") String jobId);
}
