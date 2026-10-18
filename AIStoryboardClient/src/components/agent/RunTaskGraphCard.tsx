import { useRef } from 'react';
import type { AgentToolDraft } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import './RunTaskGraphCard.css';

type GraphNode = {
  id: string;
  role?: string;
  instruction?: string;
  dependsOn: string[];
};

type ResultRow = {
  status: string;
  output?: string;
  error?: string;
};

const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待',
  RUNNING: '运行中',
  COMPLETED: '完成',
  FAILED: '失败',
  SKIPPED: '跳过',
  CANCELLED: '取消',
};

function parseJson(raw?: string): unknown {
  if (!raw || !raw.trim()) return null;
  try {
    let value: unknown = JSON.parse(raw);
    // 有些事件把 JSON 再包一层字符串
    if (typeof value === 'string') value = JSON.parse(value);
    return value;
  } catch {
    return null;
  }
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function parseGraph(raw?: string): { task: string; nodes: GraphNode[] } | null {
  const root = asRecord(parseJson(raw));
  if (!root || !Array.isArray(root.nodes)) return null;
  const nodes: GraphNode[] = [];
  for (const item of root.nodes) {
    const node = asRecord(item);
    if (!node || typeof node.id !== 'string') continue;
    nodes.push({
      id: node.id,
      role: typeof node.role === 'string' ? node.role : undefined,
      instruction: typeof node.instruction === 'string' ? node.instruction : undefined,
      dependsOn: Array.isArray(node.dependsOn)
        ? node.dependsOn.filter((dep): dep is string => typeof dep === 'string')
        : [],
    });
  }
  return nodes.length > 0
    ? { task: typeof root.task === 'string' ? root.task : '', nodes }
    : null;
}

function collectResults(value: unknown, depth = 0): unknown[] | null {
  if (depth > 4) return null;
  if (typeof value === 'string') {
    return collectResults(parseJson(value), depth + 1);
  }
  const record = asRecord(value);
  if (!record) return null;
  if (Array.isArray(record.results)) return record.results;
  for (const key of ['result', 'data', 'payload', 'output']) {
    const found = collectResults(record[key], depth + 1);
    if (found) return found;
  }
  return null;
}

function parseResults(raw?: string): Map<string, ResultRow> {
  const rows = new Map<string, ResultRow>();
  const list = collectResults(parseJson(raw));
  if (!list) return rows;
  for (const item of list) {
    const row = asRecord(item);
    if (!row || typeof row.id !== 'string') continue;
    rows.set(row.id, {
      status: typeof row.status === 'string' ? row.status.toUpperCase() : 'COMPLETED',
      output: typeof row.output === 'string' ? row.output : undefined,
      error: typeof row.error === 'string' ? row.error : undefined,
    });
  }
  return rows;
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

const ROLE_LABELS: Record<string, string> = {
  researcher: '调研',
  screenwriter: '编剧',
  'visual-director': '视觉',
  reviewer: '评审',
};

function roleLabel(role?: string, id?: string): string {
  if (role && ROLE_LABELS[role]) return ROLE_LABELS[role];
  if (id?.endsWith('-fix')) return '修订';
  if (id?.endsWith('-2')) return '复核';
  return role || id || '子任务';
}

function roleTone(role?: string, id?: string): string {
  if (id?.endsWith('-fix')) return 'repair';
  if (id?.endsWith('-2')) return 'review';
  if (role?.includes('review')) return 'review';
  if (role?.includes('visual')) return 'visual';
  if (role?.includes('screenwriter')) return 'writing';
  if (role?.includes('researcher')) return 'research';
  return 'default';
}

function parseGraphMeta(raw?: string): {
  reviewPassed?: boolean;
  repairRounds?: number;
  violations?: number;
} {
  const root = asRecord(parseJson(raw));
  if (!root) return {};
  return {
    reviewPassed: typeof root.reviewPassed === 'boolean' ? root.reviewPassed : undefined,
    repairRounds: typeof root.repairRounds === 'number' ? root.repairRounds : undefined,
    violations: Array.isArray(root.parameterViolations)
      ? root.parameterViolations.length : undefined,
  };
}

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max)}…`;
}

/**
 * `run_task_graph` 的结构化卡片：把任务图入参和节点结果画成一张任务树。
 * 数据完全来自工具调用事件本身（input + text），不额外依赖新的 SSE 事件。
 */
export function RunTaskGraphCard({ draft, toolCallId }: {
  draft: AgentToolDraft;
  toolCallId?: string;
}) {
  const live = useAgentStore((state) => toolCallId
    ? state.subagentProgress[toolCallId]
    : undefined);
  const results = parseResults(draft.text);
  const parsed = parseGraph(draft.input);
  const lastGraph = useRef<{ task: string; nodes: GraphNode[] } | null>(null);
  if (parsed) lastGraph.current = parsed;
  let graph = parsed ?? lastGraph.current;

  // 新标签恢复 turn 时，事件是分批重放的：input 可能还是半截 JSON，
  // 但 SUBAGENT_* / 结果可能已经到了。用结果 id 先拼一棵最小任务树，避免整卡消失。
  if (!graph) {
    const ids = Array.from(new Set([...results.keys(), ...Object.keys(live ?? {})]));
    if (ids.length > 0) {
      graph = {
        task: '',
        nodes: ids.map((id) => ({
          id,
          role: live?.[id]?.role,
          dependsOn: [],
        })),
      };
    }
  }

  if (!graph) {
    if (draft.status === 'node_started' || draft.status === 'node_running') {
      return (
        <div className="run-task-graph run-task-graph--restoring"
             data-testid="run-task-graph-restoring">
          正在恢复任务图…
        </div>
      );
    }
    return null;
  }
  const knownIds = new Set(graph.nodes.map((node) => node.id));
  const extra = Array.from(results.entries()).filter(([id]) => !knownIds.has(id));
  const meta = parseGraphMeta(draft.text);

  const nodes = graph.nodes.map((node) => {
    const row = results.get(node.id);
    const progress = live?.[node.id];
    return {
      id: node.id,
      role: progress?.role ?? node.role,
      dependsOn: node.dependsOn,
      status: progress?.status ?? row?.status ?? 'PENDING',
      output: row?.output ?? progress?.output,
      error: row?.error ?? progress?.error,
    };
  });
  const extraNodes = extra.map(([id, row]) => {
    const progress = live?.[id];
    return {
      id,
      role: progress?.role,
      dependsOn: [] as string[],
      status: progress?.status ?? row.status,
      output: progress?.output ?? row.output,
      error: progress?.error ?? row.error,
    };
  });
  const allNodes = [...nodes, ...extraNodes];
  const completed = allNodes.filter((node) => node.status === 'COMPLETED').length;
  const failed = allNodes.filter((node) => node.status === 'FAILED').length;
  const running = allNodes.filter((node) => node.status === 'RUNNING').length;

  return (
    <section className="run-task-graph" data-testid="run-task-graph"
             data-tone={failed > 0 ? 'failed' : running > 0 ? 'running' : 'completed'}>
      <header className="run-task-graph__header">
        <div className="run-task-graph__heading">
          <span className="run-task-graph__eyebrow">AIGC 执行器</span>
          <h3 className="run-task-graph__title">{graph.task || '子智能体任务图'}</h3>
        </div>
        <div className="run-task-graph__stats" aria-label="任务概览">
          <span className="run-task-graph__stat">
            节点 <strong>{completed}/{allNodes.length}</strong>
          </span>
          {typeof meta.repairRounds === 'number' && meta.repairRounds > 0 && (
            <span className="run-task-graph__stat run-task-graph__stat--repair">
              修复 {meta.repairRounds} 轮
            </span>
          )}
          {meta.reviewPassed === true && (
            <span className="run-task-graph__stat run-task-graph__stat--ok">评审通过</span>
          )}
          {meta.reviewPassed === false && (
            <span className="run-task-graph__stat run-task-graph__stat--bad">评审未通过</span>
          )}
          {(meta.violations ?? 0) > 0 && (
            <span className="run-task-graph__stat run-task-graph__stat--bad">
              参数冲突 {meta.violations}
            </span>
          )}
        </div>
      </header>

      <ol className="run-task-graph__nodes">
        {allNodes.map((node) => {
          const repair = node.id.endsWith('-fix') || node.id.endsWith('-2');
          return (
            <li key={node.id} className="run-task-graph__node" data-status={node.status}
                data-repair={repair || undefined}>
              <span className="run-task-graph__dot" data-status={node.status} aria-hidden="true" />
              <div className="run-task-graph__body">
                <div className="run-task-graph__head">
                  <span className={`run-task-graph__role run-task-graph__role--${roleTone(node.role, node.id)}`}>
                    {roleLabel(node.role, node.id)}
                  </span>
                  <span className="run-task-graph__id">{node.id}</span>
                  {repair && <span className="run-task-graph__badge">修订</span>}
                  <span className="run-task-graph__status" data-status={node.status}>
                    {statusLabel(node.status)}
                  </span>
                </div>
                {node.dependsOn.length > 0 && (
                  <div className="run-task-graph__deps">
                    依赖
                    {node.dependsOn.map((dependency) => (
                      <span key={dependency} className="run-task-graph__dep">{dependency}</span>
                    ))}
                  </div>
                )}
                {node.output && (
                  <details className="run-task-graph__output">
                    <summary>{truncate(node.output.replace(/\s+/g, ' ').trim(), 96)}</summary>
                    <pre>{node.output}</pre>
                  </details>
                )}
                {node.error && <div className="run-task-graph__error">{node.error}</div>}
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
