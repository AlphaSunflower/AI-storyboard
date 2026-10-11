package com.moon.moonagent.mapper;

import com.moon.moonagent.entity.AgentRecoveryLease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.time.OffsetDateTime;

@Mapper
public interface AgentRecoveryLeaseMapper {

    @Select("""
            INSERT INTO public.agent_recovery_leases (
                resource_type, resource_id, lease_owner, lease_until,
                attempt, next_attempt_at, last_error, updated_at
            ) VALUES (
                #{resourceType}, #{resourceId}, #{owner}, #{leaseUntil},
                1, #{leaseUntil}, NULL, now()
            )
            ON CONFLICT (resource_type, resource_id) DO UPDATE
            SET lease_owner = EXCLUDED.lease_owner,
                lease_until = EXCLUDED.lease_until,
                attempt = public.agent_recovery_leases.attempt + 1,
                next_attempt_at = EXCLUDED.next_attempt_at,
                last_error = NULL,
                updated_at = now()
            WHERE public.agent_recovery_leases.lease_until < now()
              AND public.agent_recovery_leases.next_attempt_at <= now()
            RETURNING attempt
            """)
    Integer tryClaim(@Param("resourceType") String resourceType,
                     @Param("resourceId") String resourceId,
                     @Param("owner") String owner,
                     @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Update("""
            UPDATE public.agent_recovery_leases
            SET lease_until = #{nextAttemptAt},
                next_attempt_at = #{nextAttemptAt},
                last_error = #{error},
                updated_at = now()
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND lease_owner = #{owner}
            """)
    int scheduleRetry(@Param("resourceType") String resourceType,
                      @Param("resourceId") String resourceId,
                      @Param("owner") String owner,
                      @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
                      @Param("error") String error);

    @Delete("""
            DELETE FROM public.agent_recovery_leases
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND lease_owner = #{owner}
            """)
    int release(@Param("resourceType") String resourceType,
                @Param("resourceId") String resourceId,
                @Param("owner") String owner);

    @Delete("""
            DELETE FROM public.agent_recovery_leases
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
            """)
    int releaseAny(@Param("resourceType") String resourceType,
                   @Param("resourceId") String resourceId);

    @Select("""
            SELECT COUNT(*)
            FROM public.agent_recovery_leases
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND lease_owner = #{owner}
              AND lease_until > now()
            """)
    int ownedBy(@Param("resourceType") String resourceType,
                @Param("resourceId") String resourceId,
                @Param("owner") String owner);

    @Select("""
            SELECT COUNT(*)
            FROM public.agent_recovery_leases
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND lease_until > now()
            """)
    int hasActiveLease(@Param("resourceType") String resourceType,
                       @Param("resourceId") String resourceId);

    @Select("""
            SELECT COALESCE(attempt, 0)
            FROM public.agent_recovery_leases
            WHERE resource_type = #{resourceType}
              AND resource_id = #{resourceId}
              AND lease_owner = #{owner}
            """)
    Integer attemptFor(@Param("resourceType") String resourceType,
                       @Param("resourceId") String resourceId,
                       @Param("owner") String owner);
}
