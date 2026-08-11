package com.llmgateway.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.entity.CallLog;

import java.util.List;
import java.util.Map;

/** 调用日志异步落库（不阻塞响应）+ 管理后台日志/统计查询（admin 模块）；videoUrl 供视频下载端点暂存 MiniMax 限时直链 */
public interface CallLogService {

    /** 记录一次调用日志：异步落库，落库失败仅告警不抛出，不阻塞调用方 */
    void log(String model, String channelId, String status, long durationMs, String error, String videoUrl, String taskId);

    // ===== 管理后台查询（admin 模块使用）=====

    /** 分页查询调用日志（按 createdAt 倒序；size 钳制 1~50；model 非空时精确过滤） */
    Page<CallLog> page(long page, long size, String model);

    /** 全量调用状态分布：{total, success, failed, successRate}（'created' 不计入成功/失败） */
    Map<String, Object> callStats();

    /** 今日（当天 00:00 起）调用状态分布：{total, success, failed, successRate} */
    Map<String, Object> todayCallStats();

    /** Top 10 模型：[{model, count, successRate}]（按调用次数倒序） */
    List<Map<String, Object>> topModels();

    /** 近 7 天每日调用量：[{date, count}]（含今天，按日期升序） */
    List<Map<String, Object>> trend7d();
}
