package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.util.List;
import java.util.Map;

/** 调用日志表 Mapper */
@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {

    /** 状态分布（全量） */
    @Select("SELECT status, COUNT(*) AS cnt FROM call_log GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /** 今日（当天 00:00 起）状态分布 */
    @Select("SELECT status, COUNT(*) AS cnt FROM call_log WHERE created_at >= date_trunc('day', now()) GROUP BY status")
    List<Map<String, Object>> countTodayByStatus();

    /** Top 10 模型（按调用次数倒序；success_cnt 用 PG FILTER 一次查出，避免拆多条查询） */
    @Select("SELECT model, COUNT(*) AS cnt, COUNT(*) FILTER (WHERE status = 'success') AS success_cnt "
            + "FROM call_log WHERE model IS NOT NULL AND model <> '' GROUP BY model ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> topModels();

    /** 近 7 天每日调用量（含今天，按日期升序） */
    @Select("SELECT to_char(date_trunc('day', created_at), 'MM-DD') AS date, COUNT(*) AS cnt "
            + "FROM call_log WHERE created_at >= now() - interval '6 days' GROUP BY 1 ORDER BY 1")
    List<Map<String, Object>> trend7d();

    /**
     * 冷热归档：把 call_log 中早于保留期的行原子迁移到 call_log_archive（单条 CTE：先 DELETE RETURNING 再 INSERT）。
     * 分批只搬 {@code batchSize} 行，避免大事务锁表；返回值 = 实际迁移行数（0 = 无可搬）。
     */
    @Insert("WITH moved AS ("
            + "DELETE FROM call_log WHERE id IN ("
            + "  SELECT id FROM call_log WHERE created_at < now() - (#{days} * interval '1 day')"
            + "  ORDER BY created_at LIMIT #{batchSize})"
            + " RETURNING *) "
            + "INSERT INTO call_log_archive "
            + "(id, model, channel_id, status, duration_ms, error, video_url, task_id, created_at) "
            + "SELECT id, model, channel_id, status, duration_ms, error, video_url, task_id, created_at FROM moved")
    int archiveOlderThan(@Param("days") int days, @Param("batchSize") int batchSize);
}
