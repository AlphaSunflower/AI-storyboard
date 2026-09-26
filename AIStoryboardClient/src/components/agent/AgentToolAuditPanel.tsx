import { useEffect, useState } from 'react';
import { Activity, ChevronDown, ChevronRight, RefreshCw } from 'lucide-react';
import {
  agentApi,
  type ToolAuditConfig,
  type ToolInvocationView,
} from '../../api/agent';
import './AgentToolAuditPanel.css';

function statusLabel(status: string): string {
  switch (status) {
    case 'RUNNING': return '执行中';
    case 'COMPLETED': return '已完成';
    case 'WAITING': return '等待补充';
    case 'REJECTED': return '已拒绝';
    case 'FAILED': return '失败';
    case 'UNKNOWN': return '状态未知';
    default: return status || '未知';
  }
}

function sourceLabel(source: string): string {
  switch (source) {
    case 'AGENT_SCOPE': return 'AgentScope';
    case 'SPRING_AI': return 'Spring AI';
    case 'MCP': return 'MCP';
    default: return source || '未知来源';
  }
}

function formatTime(value?: string | null): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}

function formatDuration(value?: number | null): string {
  if (value == null) return '-';
  if (value < 1000) return `${value} ms`;
  return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)} s`;
}

function prettySummary(value?: string | null): string {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

export function AgentToolAuditPanel({
  taskId,
  conversationId,
  refreshToken,
}: {
  taskId: string;
  conversationId?: string | null;
  refreshToken?: number | null;
}) {
  const [config, setConfig] = useState<ToolAuditConfig | null>(null);
  const [records, setRecords] = useState<ToolInvocationView[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    let active = true;
    agentApi.getToolAuditConfig()
      .then((res) => {
        if (active) setConfig(res.data.data);
      })
      .catch(() => {
        if (active) setConfig({ enabled: true, uiEnabled: true, retentionDays: 90 });
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    agentApi.listToolInvocations({
        taskId,
        conversationId: conversationId ?? undefined,
        limit: 50,
      })
      .then((res) => {
        if (!active) return;
        setRecords(res.data.data.records);
        setNextCursor(res.data.data.nextCursor);
      })
      .catch((e: unknown) => {
        if (!active) return;
        setError(e instanceof Error ? e.message : '工具审计加载失败');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [taskId, conversationId, refreshToken, refreshKey]);

  const loadMore = async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    setError('');
    try {
      const res = await agentApi.listToolInvocations({
        taskId,
        conversationId: conversationId ?? undefined,
        cursor: nextCursor,
        limit: 50,
      });
      setRecords((current) => [...current, ...res.data.data.records]);
      setNextCursor(res.data.data.nextCursor);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : '加载更多失败');
    } finally {
      setLoadingMore(false);
    }
  };

  if (config && !config.uiEnabled) {
    return <p className="agent-tool-audit__empty">工具审计暂未开启。</p>;
  }

  return (
    <section className="agent-tool-audit" aria-label="工具调用审计">
      <header className="agent-tool-audit__header">
        <div>
          <Activity size={15} />
          <strong>工具调用</strong>
          <span>{records.length} 条</span>
        </div>
        <button
          type="button"
          onClick={() => setRefreshKey((value) => value + 1)}
          disabled={loading}
          title="刷新工具调用记录"
        >
          <RefreshCw size={14} className={loading ? 'is-spinning' : undefined} />
          刷新
        </button>
      </header>

      {error && <p className="agent-tool-audit__error">{error}</p>}
      {!error && !loading && records.length === 0 && (
        <p className="agent-tool-audit__empty">暂无工具调用记录。</p>
      )}

      <div className="agent-tool-audit__list">
        {records.map((record) => {
          const expanded = expandedId === record.id;
          const referenceMismatch =
            record.expectedReferenceCount != null
            && record.appliedReferenceCount != null
            && record.expectedReferenceCount !== record.appliedReferenceCount;
          return (
            <article
              key={record.id}
              className={`agent-tool-audit__item is-${record.status.toLowerCase()}`}
            >
              <button
                type="button"
                className="agent-tool-audit__summary"
                onClick={() => setExpandedId(expanded ? null : record.id)}
                aria-expanded={expanded}
              >
                {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                <span className="agent-tool-audit__tool">{record.toolName}</span>
                <span className="agent-tool-audit__status">{statusLabel(record.status)}</span>
                <span className="agent-tool-audit__meta">{sourceLabel(record.source)}</span>
                <span className="agent-tool-audit__meta">{formatDuration(record.durationMs)}</span>
                {referenceMismatch && <span className="agent-tool-audit__warning">参考数不一致</span>}
              </button>

              {expanded && (
                <div className="agent-tool-audit__detail">
                  <dl>
                    <div><dt>开始</dt><dd>{formatTime(record.startedAt)}</dd></div>
                    <div><dt>完成</dt><dd>{formatTime(record.completedAt)}</dd></div>
                    <div><dt>调用来源</dt><dd>{sourceLabel(record.source)}</dd></div>
                    <div><dt>工具类型</dt><dd>{record.toolKind || '-'}</dd></div>
                    <div><dt>Skill 版本</dt><dd>{record.skillVersion || '-'}</dd></div>
                    <div><dt>模型</dt><dd>{record.model || '-'}</dd></div>
                    <div><dt>外部动作</dt><dd><code>{record.externalActionKey || '-'}</code></dd></div>
                    <div><dt>供应商任务</dt><dd><code>{record.providerTaskId || '-'}</code></dd></div>
                  </dl>

                  {(record.expectedReferenceCount != null
                    || record.resolvedReferenceCount != null
                    || record.appliedReferenceCount != null) && (
                    <div className="agent-tool-audit__references">
                      <strong>参考图链路</strong>
                      <span>最终记录 {record.expectedReferenceCount ?? '-'}</span>
                      <span>解析 {record.resolvedReferenceCount ?? '-'}</span>
                      <span>实际传入 {record.appliedReferenceCount ?? '-'}</span>
                    </div>
                  )}

                  {record.resultAssetIds.length > 0 && (
                    <div className="agent-tool-audit__assets">
                      <strong>结果资产</strong>
                      <span>{record.resultAssetIds.join(', ')}</span>
                    </div>
                  )}

                  {record.errorMessage && (
                    <div className="agent-tool-audit__error-detail">
                      <strong>{record.errorCode || '失败'}</strong>
                      <span>{record.errorMessage}</span>
                    </div>
                  )}

                  {record.inputSummary && (
                    <div className="agent-tool-audit__payload">
                      <strong>输入摘要</strong>
                      <pre>{prettySummary(record.inputSummary)}</pre>
                    </div>
                  )}

                  {record.outputSummary && (
                    <div className="agent-tool-audit__payload">
                      <strong>输出摘要</strong>
                      <pre>{prettySummary(record.outputSummary)}</pre>
                    </div>
                  )}
                </div>
              )}
            </article>
          );
        })}
      </div>
      {nextCursor && (
        <button
          type="button"
          className="agent-tool-audit__more"
          onClick={() => void loadMore()}
          disabled={loadingMore}
        >
          {loadingMore ? '加载中' : '加载更多'}
        </button>
      )}
    </section>
  );
}
