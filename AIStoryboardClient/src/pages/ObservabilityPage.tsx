import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Activity, ArrowLeft, Clock3, Copy, RefreshCw, Users } from 'lucide-react';import { agentApi, type AgentConversation, type SubagentRunView } from '../api/agent';
import { useAuthStore } from '../stores/authStore';
import { useAgentStore } from '../stores/agentStore';
import { useProjectStore } from '../stores/projectStore';
import { notify } from '../stores/toastStore';

const pageStyle: React.CSSProperties = {
  minHeight: '100svh',
  background: 'var(--color-canvas)',
  color: 'var(--color-ink)',
  padding: '32px 20px 64px',
};
const cardStyle: React.CSSProperties = {
  background: 'var(--color-panel)',
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-lg)',
  padding: 18,
};
const btnStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '7px 12px',
  border: '1px solid var(--color-hairline)',
  borderRadius: 'var(--rounded-md)',
  background: 'var(--color-panel)',
  color: 'var(--color-ink)',
  font: 'var(--text-caption)',
  cursor: 'pointer',
};

const STATUS_LABEL: Record<string, string> = {
  COMPLETED: '完成',
  FAILED: '失败',
  RUNNING: '运行中',
  CANCELLED: '取消',
  SKIPPED: '跳过',
};

function shortId(value?: string): string {
  if (!value) return '—';
  return value.length > 12 ? `${value.slice(0, 10)}…` : value;
}

/** 把节点错误归并成稳定分类，避免同一类失败按不同文本散开。 */
export function classifySubagentError(error?: string): string {
  const text = (error || '').toLowerCase();
  if (!text) return '未记录错误';
  if (text.includes('timeout') || text.includes('timed out') || text.includes('超时')) {
    return '执行超时';
  }
  if (text.includes('hitl') || text.includes('审批') || text.includes('approval')
      || text.includes('rejected') || text.includes('拒绝') || text.includes('没有获批')) {
    return '审批未通过/拒绝';
  }
  if (text.includes('tool not found') || text.includes('未登记工具')
      || text.includes('工具不存在')) {
    return '工具不可用';
  }
  if (text.includes('budget') || text.includes('预算')) return '预算超限';
  if (text.includes('json') || text.includes('parse') || text.includes('格式')) {
    return '输出格式错误';
  }
  if (text.includes('dependency') || text.includes('依赖')) return '依赖失败';
  return '其他错误';
}

export function ObservabilityPage() {
  const navigate = useNavigate();
  const [checking, setChecking] = useState(true);
  const currentProject = useProjectStore((state) => state.currentProject);
  const loadProjects = useProjectStore((state) => state.loadProjects);
  const [conversations, setConversations] = useState<AgentConversation[]>([]);
  const [conversationId, setConversationId] = useState('');
  const [runs, setRuns] = useState<SubagentRunView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [graphFilter, setGraphFilter] = useState('all');
  const [statusFilter, setStatusFilter] = useState<'all' | 'failed' | 'completed'>('all');
  const [autoRefresh, setAutoRefresh] = useState(false);

  useEffect(() => {
    void useAuthStore.getState().checkAuth().then((ok) => {
      if (!ok) {
        navigate('/login', { replace: true });
        return;
      }
      setChecking(false);
    });
  }, [navigate]);

  useEffect(() => {
    if (checking) return;
    void loadProjects();
  }, [checking, loadProjects]);

  useEffect(() => {
    if (checking || !currentProject) return;
    void agentApi.listConversations(currentProject.id).then((response) => {
      const list = response.data.data || [];
      setConversations(list);
      setConversationId((current) => current || list[0]?.id || '');
    }).catch(() => setConversations([]));
  }, [checking, currentProject]);

  const loadRuns = async () => {
    if (!conversationId) return;
    setLoading(true);
    setError('');
    try {
      const response = await agentApi.listSubagentRuns(conversationId, undefined, 100);
      setRuns(response.data.data || []);
    } catch {
      setError('读取运行记录失败');
      setRuns([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (conversationId) void loadRuns();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conversationId]);

  useEffect(() => {
    if (!autoRefresh || !conversationId) return;
    const timer = window.setInterval(() => { void loadRuns(); }, 10_000);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRefresh, conversationId]);

  const graphs = useMemo(() => {
    const grouped = new Map<string, SubagentRunView[]>();
    for (const run of runs) {
      const key = run.graphId || '未分组';
      const list = grouped.get(key) || [];
      list.push(run);
      grouped.set(key, list);
    }
    return Array.from(grouped.entries()).map(([graphId, items]) => ({
      graphId,
      items,
      tokens: items.reduce((sum, item) => sum + (item.tokens || 0), 0),
      durationMs: items.reduce((sum, item) => sum + (item.durationMs || 0), 0),
      failed: items.filter((item) => item.status === 'FAILED').length,
    }));
  }, [runs]);

  const visibleGraphs = useMemo(
    () => {
      const byGraph = graphFilter === 'all' ? graphs : graphs.filter((graph) => graph.graphId === graphFilter);
      if (statusFilter === 'all') return byGraph;
      return byGraph
        .map((graph) => ({
          ...graph,
          items: graph.items.filter((item) => statusFilter === 'failed'
            ? item.status === 'FAILED'
            : item.status === 'COMPLETED'),
        }))
        .filter((graph) => graph.items.length > 0);
    },
    [graphs, graphFilter, statusFilter],
  );

  const failureGroups = useMemo(() => {
    const counts = new Map<string, number>();
    for (const run of runs) {
      if (run.status !== 'FAILED') continue;
      const category = classifySubagentError(run.error);
      counts.set(category, (counts.get(category) || 0) + 1);
    }
    return Array.from(counts.entries()).sort((a, b) => b[1] - a[1]).slice(0, 5);
  }, [runs]);

  const copyRetryPrompt = async (run: SubagentRunView) => {    try {
      const response = await agentApi.retrySubagentRun(run.id, conversationId);
      const prompt = response.data.data.prompt;
      if (!prompt) {
        notify({ title: '没有可重跑的节点快照', variant: 'error' });
        return;
      }
      const store = useAgentStore.getState();
      await store.selectConversation(conversationId);
      await store.sendMessage(prompt);
      if (!useAgentStore.getState().streaming) {
        store.injectComposerText(prompt);
        notify({ title: '重跑未启动，提示词已填入输入框', variant: 'info' });
      } else {
        notify({ title: '已发起节点重跑', description: '写节点仍会先弹审批' });
      }
      navigate('/chat', { state: { conversationId } });
    } catch {
      notify({ title: '发起重跑失败', variant: 'error' });
    }
  };

  if (checking) {
    return <div style={{ ...pageStyle, display: 'grid', placeItems: 'center' }}>正在加载…</div>;
  }

  return (
    <div style={pageStyle}>
      <div style={{ maxWidth: 1080, margin: '0 auto' }}>
        <header style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 22 }}>
          <button type="button" style={btnStyle} onClick={() => navigate('/chat')}>
            <ArrowLeft size={15} /> 返回 Moon
          </button>
          <h1 style={{ margin: 0, font: 'var(--text-title-lg)', flex: 1 }}>运行观测</h1>
          <button type="button" style={btnStyle} onClick={() => void loadRuns()} disabled={!conversationId || loading}>
            <RefreshCw size={14} /> 刷新
          </button>
        </header>

        <section style={{ ...cardStyle, display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', marginBottom: 16 }}>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>项目</span>
          <strong style={{ fontSize: 13 }}>{currentProject?.name || '未选择项目'}</strong>
          <span style={{ width: 1, height: 18, background: 'var(--color-hairline)' }} />
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>会话</span>
          <select
            value={conversationId}
            onChange={(event) => setConversationId(event.target.value)}
            style={{ minWidth: 260, padding: '7px 10px', border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'var(--color-panel)', font: 'var(--text-body-sm)' }}
          >
            {conversations.length === 0 && <option value="">暂无会话</option>}
            {conversations.map((conversation) => (
              <option key={conversation.id} value={conversation.id}>{conversation.title || shortId(conversation.id)}</option>
            ))}
          </select>
          <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--color-muted)' }}>
            {runs.length} 个节点 · {graphs.length} 张任务图
          </span>
        </section>

        <section style={{ ...cardStyle, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', marginBottom: 16 }}>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>任务图</span>
          <select
            value={graphFilter}
            onChange={(event) => setGraphFilter(event.target.value)}
            style={{ minWidth: 240, padding: '7px 10px', border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'var(--color-panel)', font: 'var(--text-body-sm)' }}
          >
            <option value="all">全部任务图（{graphs.length}）</option>
            {graphs.map((graph) => (
              <option key={graph.graphId} value={graph.graphId}>
                {shortId(graph.graphId)} · {graph.items.length} 节点{graph.failed ? ` · ${graph.failed} 失败` : ''}
              </option>
            ))}
          </select>
          <span style={{ fontSize: 12, color: 'var(--color-muted)' }}>
            失败节点 {runs.filter((run) => run.status === 'FAILED').length}
          </span>
          <select
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value as 'all' | 'failed' | 'completed')}
            style={{ padding: '7px 10px', border: '1px solid var(--color-hairline)', borderRadius: 8, background: 'var(--color-panel)', font: 'var(--text-body-sm)' }}
          >
            <option value="all">全部状态</option>
            <option value="failed">只看失败</option>
            <option value="completed">只看完成</option>
          </select>
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-muted)' }}>
            <input type="checkbox" checked={autoRefresh} onChange={(event) => setAutoRefresh(event.target.checked)} />
            10s 自动刷新
          </label>
          {failureGroups.length > 0 && (
            <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 4, marginTop: 4 }}>
              {failureGroups.map(([reason, count]) => (
                <div key={reason} style={{ fontSize: 12, color: 'var(--color-error)' }}>
                  ×{count} {reason}
                </div>
              ))}
            </div>
          )}
        </section>

        {error && (
          <div style={{ ...cardStyle, color: 'var(--color-error)', marginBottom: 16 }}>{error}</div>
        )}

        {!loading && visibleGraphs.length === 0 && (
          <div style={{ ...cardStyle, padding: 40, textAlign: 'center', color: 'var(--color-muted)' }}>
            <Activity size={22} style={{ marginBottom: 10 }} />
            <div>这个会话还没有子智能体运行记录。</div>
            <div style={{ fontSize: 12, marginTop: 6 }}>跑一次「调研 → 写作 → 评审」任务图后，这里会出现节点级轨迹。</div>
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {graphs.map((graph) => (
            <section key={graph.graphId} style={cardStyle}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
                <Users size={16} color="var(--color-primary)" />
                <strong style={{ fontSize: 13 }}>任务图 {shortId(graph.graphId)}</strong>
                <span style={{ fontSize: 11, color: 'var(--color-muted)', marginLeft: 'auto', display: 'inline-flex', gap: 12 }}>
                  <span>{graph.tokens} tokens</span>
                  <span><Clock3 size={12} style={{ verticalAlign: -2 }} /> {graph.durationMs} ms</span>
                </span>
                <button
                  type="button"
                  style={{ ...btnStyle, padding: '3px 9px', fontSize: 11 }}
                  onClick={() => navigate('/chat', { state: { conversationId, turnId: graph.items[0]?.turnId } })}
                >
                  打开会话
                </button>
              </div>
              <div style={{ display: 'grid', gap: 8 }}>
                {graph.items.map((run) => (
                  <article key={run.id} style={{ border: '1px solid var(--color-hairline)', borderRadius: 10, padding: '10px 12px', background: 'var(--color-canvas)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 5 }}>
                      <strong style={{ fontSize: 13 }}>{run.nodeId}</strong>
                      <span style={{ fontSize: 11, color: 'var(--color-muted)' }}>{run.role}</span>
                      <span style={{
                        fontSize: 11,
                        marginLeft: 'auto',
                        color: run.status === 'FAILED' ? 'var(--color-error)' : 'var(--color-primary)',
                        border: '1px solid currentColor',
                        borderRadius: 999,
                        padding: '1px 8px',
                      }}>
                        {STATUS_LABEL[run.status] || run.status}
                      </span>
                      {run.status === 'FAILED' && (
                        <button
                          type="button"
                          style={{ ...btnStyle, padding: '2px 8px', fontSize: 11 }}
                          onClick={() => void copyRetryPrompt(run)}
                        >
                          <Copy size={12} /> 重跑
                        </button>
                      )}
                    </div>
                    {run.instruction && (
                      <div style={{ fontSize: 12, color: 'var(--color-muted)', marginBottom: 4 }}>
                        {run.instruction.length > 180 ? `${run.instruction.slice(0, 180)}…` : run.instruction}
                      </div>
                    )}
                    {(run.output || run.error) && (
                      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
                        <div style={{ flex: 1, fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap', color: run.error ? 'var(--color-error)' : 'var(--color-ink)' }}>
                          {(run.error || run.output || '').slice(0, 500)}
                        </div>
                        <button
                          type="button"
                          title="复制节点输出"
                          aria-label="复制节点输出"
                          onClick={() => {
                            void navigator.clipboard.writeText(run.error || run.output || '');
                            notify({ title: '节点输出已复制' });
                          }}
                          style={{ border: 'none', background: 'transparent', color: 'var(--color-muted)', cursor: 'pointer', padding: 2 }}
                        >
                          <Copy size={13} />
                        </button>
                      </div>
                    )}
                  </article>
                ))}
              </div>
            </section>
          ))}
        </div>
      </div>
    </div>
  );
}
