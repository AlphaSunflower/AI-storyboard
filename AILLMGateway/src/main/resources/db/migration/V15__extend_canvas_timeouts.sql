-- 画布链路允许生图/视频等上游任务持续运行，避免网关在 120 秒时提前断开。
UPDATE sys_config
SET config_value = '1800000',
    remark = '上游请求超时（毫秒；SSE 流式取 max(该值,300s)）',
    updated_at = now()
WHERE config_key = 'gateway.upstream.request-timeout-ms';
