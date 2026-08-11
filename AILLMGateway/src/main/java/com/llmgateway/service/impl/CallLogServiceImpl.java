package com.llmgateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.entity.CallLog;
import com.llmgateway.mapper.CallLogMapper;
import com.llmgateway.service.CallLogService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用日志异步落库实现（不阻塞响应）+ 管理后台日志/统计查询（admin 模块） */
@Service
@RequiredArgsConstructor
public class CallLogServiceImpl implements CallLogService {

    private static final Logger log = LoggerFactory.getLogger(CallLogServiceImpl.class);

    private final CallLogMapper callLogMapper;

    @Async
    @Override
    public void log(String model, String channelId, String status, long durationMs, String error, String videoUrl, String taskId) {
        try {
            CallLog record = new CallLog();
            record.setModel(model);
            record.setChannelId(channelId);
            record.setStatus(status);
            record.setDurationMs(durationMs);
            record.setError(error);
            record.setVideoUrl(videoUrl);
            record.setTaskId(taskId);
            record.setCreatedAt(OffsetDateTime.now());
            callLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("调用日志落库失败: {}", e.getMessage());
        }
    }

    // ===== 管理后台查询（admin 模块使用）=====

    /** 分页查询（按 createdAt 倒序；size 钳制 1~50 防 size=-1 绕过上限；model 非空时精确过滤） */
    @Override
    public Page<CallLog> page(long page, long size, String model) {
        size = Math.max(1, Math.min(50, size));   // 分页下界校验（防 size=-1 绕过上限）
        LambdaQueryWrapper<CallLog> wrapper = new LambdaQueryWrapper<CallLog>()
                .orderByDesc(CallLog::getCreatedAt);
        if (model != null && !model.isBlank()) {
            wrapper.eq(CallLog::getModel, model);
        }
        return callLogMapper.selectPage(Page.of(page, size), wrapper);
    }

    /** 全量状态分布（status='success' 计成功、'error' 计失败；'created' 视频任务进行中不计入） */
    @Override
    public Map<String, Object> callStats() {
        return aggregateCalls(callLogMapper.countByStatus());
    }

    /** 今日（当天 00:00 起）状态分布 */
    @Override
    public Map<String, Object> todayCallStats() {
        return aggregateCalls(callLogMapper.countTodayByStatus());
    }

    /** Top 10 模型：count + successRate（success_cnt 已在 SQL 里用 FILTER 一次查出） */
    @Override
    public List<Map<String, Object>> topModels() {
        List<Map<String, Object>> topModels = new ArrayList<>();
        for (Map<String, Object> row : callLogMapper.topModels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", row.get("model"));
            long cnt = ((Number) row.getOrDefault("cnt", 0L)).longValue();
            long successCnt = ((Number) row.getOrDefault("success_cnt", 0L)).longValue();
            item.put("count", cnt);
            item.put("successRate", cnt == 0 ? 0.0 : successCnt * 1.0 / cnt);
            topModels.add(item);
        }
        return topModels;
    }

    /** 近 7 天趋势：cnt → count 字段名对齐前端契约 {date, count} */
    @Override
    public List<Map<String, Object>> trend7d() {
        List<Map<String, Object>> trend7d = new ArrayList<>();
        for (Map<String, Object> row : callLogMapper.trend7d()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row.get("date"));
            item.put("count", ((Number) row.getOrDefault("cnt", 0L)).longValue());
            trend7d.add(item);
        }
        return trend7d;
    }

    /** 把 status 分布行（status + cnt）聚合成 {total, success, failed, successRate} */
    private Map<String, Object> aggregateCalls(List<Map<String, Object>> rows) {
        long total = 0, success = 0, failed = 0;
        for (Map<String, Object> row : rows) {
            long cnt = ((Number) row.get("cnt")).longValue();
            total += cnt;
            String status = row.get("status") == null ? "" : row.get("status").toString();
            if ("success".equals(status)) success += cnt;
            else if ("error".equals(status)) failed += cnt;
            // 'created'（视频任务进行中）不计入成功/失败
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("success", success);
        result.put("failed", failed);
        result.put("successRate", total == 0 ? 0.0 : success * 1.0 / total);
        return result;
    }
}
