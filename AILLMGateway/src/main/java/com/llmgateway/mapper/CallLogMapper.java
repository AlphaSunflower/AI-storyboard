package com.llmgateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmgateway.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
