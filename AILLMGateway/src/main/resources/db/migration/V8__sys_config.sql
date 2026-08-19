-- 系统可调配置表（key-value）：替代写死在 application.yml 的 tunable 参数
-- 修改经 admin API（/admin/config）落库；应用启动时加载进 GatewayConfig，
-- 修改后需重启生效（不做热更新，避免 HttpClient 等运行时副作用）
CREATE TABLE IF NOT EXISTS sys_config (
    id VARCHAR(64) PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    remark VARCHAR(255),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ
);

-- 种子数据：当前值即原 application.yml 的值（ON CONFLICT 幂等，重复执行不重复插入）
INSERT INTO sys_config (id, config_key, config_value, remark, created_at, updated_at) VALUES
    ('gateway.upstream.connect-timeout-ms', 'gateway.upstream.connect-timeout-ms', '30000', '上游连接超时（毫秒）', now(), now()),
    ('gateway.upstream.request-timeout-ms', 'gateway.upstream.request-timeout-ms', '120000', '上游请求超时（毫秒；SSE 流式取 max(该值,300s)）', now(), now()),
    ('gateway.upstream.retry-count', 'gateway.upstream.retry-count', '2', '上游 429/5xx 重试次数（指数退避）', now(), now())
ON CONFLICT (config_key) DO NOTHING;
