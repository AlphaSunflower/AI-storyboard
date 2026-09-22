import { useEffect, useState } from 'react';
import { Ban, Check, RefreshCw, RotateCcw, X } from 'lucide-react';
import {
  agentApi,
  type AgentTaskPlanAction,
  type AgentTaskPlanNode,
  type AgentTaskPlanView,
} from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';

const PLAN_POLL_INTERVAL_MS = 3000;

const ACTION_LABELS: Record<AgentTaskPlanAction, string> = {
  approve: '批准',
  reject: '驳回',
  retry: '重试',
  replan: '重新规划',
  cancel: '取消任务',
  resume: '继续',
};

const ACTION_ICONS: Record<AgentTaskPlanAction, typeof Check> = {
  approve: Check,
  reject: X,
  retry: RotateCcw,
  replan: RefreshCw,
  cancel: Ban,
  resume: RefreshCw,
};

const VISIBLE_ACTIONS: AgentTaskPlanAction[] = ['approve', 'reject', 'retry', 'replan', 'cancel'];

function stateTone(state: string) {
  if (state === 'COMPLETED') return { color: '#16794b', background: '#edf8f1' };
  if (state === 'FAILED' || state === 'CANCELLED') return { color: '#b42318', background: '#fff1f0' };
  if (state === 'WAITING_HUMAN' || state === 'WAITING_ASYNC') return { color: '#9a5b13', background: '#fff7e6' };
  if (state === 'RUNNING' || state === 'REPLANNING') return { color: '#175cd3', background: '#eff6ff' };
  return { color: '#52606d', background: '#f3f4f6' };
}

function NodeRow({ node }: { node: AgentTaskPlanNode }) {
  const tone = stateTone(node.state);
  return (
    <div style={{
      display: 'grid',
      gridTemplateColumns: 'minmax(0, 1fr) auto',
      gap: '4px 10px',
      padding: '8px 0',
      borderTop: '1px solid var(--color-hairline)',
    }}>
      <div style={{ minWidth: 0, fontSize: 13, fontWeight: 600, color: 'var(--color-ink)' }}>
        {node.title}
      </div>
      <span style={{
        ...tone,
        justifySelf: 'end',
        padding: '1px 6px',
        borderRadius: 5,
        fontSize: 10,
        fontWeight: 700,
        whiteSpace: 'nowrap',
      }}>
        {node.state}
      </span>
      <div style={{ minWidth: 0, color: 'var(--color-muted)', fontSize: 11 }}>
        {`${node.capabilityKey} · 尝试 ${node.attemptCount} 次`}
      </div>
      <div style={{ color: 'var(--color-muted)', fontSize: 11, justifySelf: 'end' }}>
        {node.hitlPolicy}
      </div>
      {node.error && (
        <div style={{ gridColumn: '1 / -1', color: 'var(--color-error)', fontSize: 11, lineHeight: 1.5 }}>
          {node.error}
        </div>
      )}
    </div>
  );
}

export function DynamicTaskPlanPanel() {
  const conversationId = useAgentStore((state) => state.activeConversationId);
  const [plan, setPlan] = useState<AgentTaskPlanView | null>(null);
  const [pendingAction, setPendingAction] = useState<AgentTaskPlanAction | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    setPlan(null);
    setPendingAction(null);
    setError('');
    if (!conversationId) return;

    let disposed = false;
    const refresh = async () => {
      try {
        const response = await agentApi.getActiveTaskPlan(conversationId);
        if (!disposed) setPlan(response.data.data);
      } catch (refreshError) {
        if (!disposed) {
          setError(refreshError instanceof Error ? refreshError.message : '任务计划加载失败');
        }
      }
    };

    void refresh();
    const timer = window.setInterval(refresh, PLAN_POLL_INTERVAL_MS);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [conversationId]);

  const handleAction = async (action: AgentTaskPlanAction) => {
    if (!conversationId || !plan || pendingAction) return;
    setPendingAction(action);
    setError('');
    try {
      const response = await agentApi.actOnTaskPlan(conversationId, plan.planId, action);
      if (useAgentStore.getState().activeConversationId === conversationId) {
        setPlan(response.data.data);
      }
    } catch (actionError) {
      setError(actionError instanceof Error ? actionError.message : '任务操作失败');
    } finally {
      setPendingAction(null);
    }
  };

  if (!plan) return null;

  const planTone = stateTone(plan.state);
  return (
    <section
      aria-label="动态任务计划"
      style={{
        margin: '8px 0 14px',
        padding: '12px 14px',
        border: '1px solid var(--color-hairline)',
        borderRadius: 12,
        background: 'white',
        boxShadow: '0 1px 3px rgba(16, 24, 40, 0.05)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--color-muted)', letterSpacing: 0.4 }}>
              动态任务
            </span>
            <span style={{
              ...planTone,
              padding: '1px 7px',
              borderRadius: 5,
              fontSize: 10,
              fontWeight: 700,
            }}>
              {plan.state}
            </span>
            {plan.replanCount > 0 && (
              <span style={{ color: 'var(--color-muted)', fontSize: 11 }}>
                已重规划 {plan.replanCount} 次
              </span>
            )}
          </div>
          <div style={{ marginTop: 5, fontSize: 14, fontWeight: 600, color: 'var(--color-ink)' }}>
            {plan.goal}
          </div>
        </div>
        <span style={{ flexShrink: 0, color: 'var(--color-muted-soft)', fontSize: 11 }}>
          {`v${plan.version}`}
        </span>
      </div>

      <div style={{ marginTop: 8 }}>
        {plan.nodes.map((node) => <NodeRow key={node.nodeId} node={node} />)}
      </div>

      {plan.error && (
        <div style={{ marginTop: 8, color: 'var(--color-error)', fontSize: 12, lineHeight: 1.5 }}>
          {plan.error}
        </div>
      )}
      {error && (
        <div style={{ marginTop: 8, color: 'var(--color-error)', fontSize: 12, lineHeight: 1.5 }}>
          {error}
        </div>
      )}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 }}>
        {VISIBLE_ACTIONS.map((action) => {
          const Icon = ACTION_ICONS[action];
          const pending = pendingAction === action;
          return (
            <button
              key={action}
              type="button"
              disabled={!!pendingAction}
              onClick={() => void handleAction(action)}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '5px 9px',
                border: '1px solid var(--color-hairline)',
                borderRadius: 7,
                background: action === 'cancel' ? '#fff7f6' : 'var(--color-canvas)',
                color: action === 'cancel' ? 'var(--color-error)' : 'var(--color-ink)',
                cursor: pendingAction ? 'not-allowed' : 'pointer',
                opacity: pendingAction && !pending ? 0.55 : 1,
                fontSize: 12,
              }}
            >
              <Icon size={12} strokeWidth={2} />
              {pending ? '处理中…' : ACTION_LABELS[action]}
            </button>
          );
        })}
      </div>
    </section>
  );
}
