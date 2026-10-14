import { ChevronRight } from 'lucide-react';
import type { CSSProperties } from 'react';
import type { AgentTraceEvent } from '../../api/agent';
import type { ToolTone } from './toolCallPresentation';
import { toolTimelineStyle } from './toolTimelineTheme';
import './ToolCallTimeline.css';

function statusLabel(status: string) {
  switch (status) {
    case 'node_started': return '进行中';
    case 'node_completed':
    case 'completed': return '已完成';
    case 'node_success':
    case 'success': return '成功';
    case 'node_failed':
    case 'failed': return '失败';
    case 'node_error':
    case 'error': return '出错';
    case 'route_changed': return '已切换';
    default: return status || '处理中';
  }
}

function phaseLabel(phase: string) {
  switch (phase) {
    case 'model_reasoning': return '模型推理';
    case 'tool_call': return '工具调用';
    case 'intent_recognize': return '意图识别';
    case 'specialist_execute': return '专家执行';
    case 'handoff': return '任务交接';
    case 'route_change': return '链路切换';
    case 'hitl': return '等待确认';
    default: return phase || '';
  }
}

/** 轨迹状态 → 与工具节点同一套语气色阶，两处视觉不再各自为政。 */
function traceTone(status: string): ToolTone {
  switch (status) {
    case 'node_failed':
    case 'failed':
    case 'node_error':
    case 'error':
      return 'failed';
    case 'node_started':
      return 'running';
    case 'node_completed':
    case 'completed':
    case 'node_success':
    case 'success':
    case 'route_changed':
      return 'done';
    default:
      return 'idle';
  }
}

/** 轨迹链里的执行者名：`agentscope` 与时间线行使用同一可读名。 */
function chainName(event: AgentTraceEvent): string {
  const name = event.specialist || event.agent || '';
  return name === 'agentscope' ? 'AIGC 执行器' : name;
}

/**
 * 交接链：只在**连续不同的执行者**之间画箭头。
 *
 * <p>旧实现是 `events.map((e) => e.specialist || e.agent).join(' → ')`，即把每条事件都串一遍。
 * 但 AgentScope 运行时给每条事件写死同一个 specialist：`AgentTraceBridgeMiddleware` 传
 * `"agentscope"`、`AgentTraceAccumulator` 把 agent 写死 `"moon"`。于是任何一次普通推理都会
 * 渲染成 `agentscope → agentscope → agentscope → …`（截图里那一行），既没有信息也把真正的
 * 交接盖住了。现在：连续重复折叠，且只剩一个执行者时不显示链（此时它不携带任何信息）。
 */
export function traceChain(events: AgentTraceEvent[]): string {
  const names: string[] = [];
  for (const event of events) {
    const name = chainName(event);
    if (!name || names[names.length - 1] === name) continue;
    names.push(name);
  }
  return names.length > 1 ? names.join(' → ') : '';
}

/**
 * 交接轨迹时间线：Moon → 分镜专家 → 审查专家 等阶段。
 *
 * 复用工具节点的脊线骨架（`.tool-node` + 圆点 + 状态胶囊），只是行内没有折叠按钮：
 * 轨迹和工具调用本来就是同一段工作的两个切面，不应该长成两种东西。
 */
export function AgentTraceTimeline({ events }: { events: AgentTraceEvent[] }) {
  if (!events || events.length === 0) return null;
  const chain = traceChain(events);

  return (
    <div className="tool-timeline" style={toolTimelineStyle() as CSSProperties}>
      <div className="tool-timeline__head">
        <span className="tool-timeline__head-icon" aria-hidden="true">
          <ChevronRight size={12} strokeWidth={2} />
        </span>
        <span>Agent 轨迹{chain ? ` · ${chain}` : ''}</span>
      </div>
      {events.map((ev, i) => {
        const tone = traceTone(ev.status);
        return (
          <div
            key={ev.id || i}
            className="tool-node reasoning-trace-item"
            data-tone={tone}
            data-trace-status={ev.status}
            style={toolTimelineStyle(tone, {
              '--item-index': String(i),
            }) as CSSProperties}
          >
            <span className="tool-node__dot" aria-hidden="true" />
            <div className="tool-node__body">
              <div className="tool-node__trace-head">
                <span className="tool-node__trace-agent">{ev.agent || 'Agent'}</span>
                {ev.specialist && (
                  <span className="tool-node__trace-meta">
                    {ev.specialist === 'agentscope' ? 'AIGC 执行器' : ev.specialist}
                  </span>
                )}
                {ev.phase && <span className="tool-node__trace-meta">{phaseLabel(ev.phase)}</span>}
                <span className="tool-node__chip">
                  <span className="tool-node__chip-dot" aria-hidden="true" />
                  {statusLabel(ev.status)}
                </span>
              </div>
              {ev.title && <div className="tool-node__trace-title">{ev.title}</div>}
              {ev.summary && <div className="tool-node__trace-summary">{ev.summary}</div>}
              {(ev.currentIntent || ev.targetIntent) && (
                <div className="tool-node__trace-intent">
                  {ev.currentIntent && <>意图: {ev.currentIntent}</>}
                  {ev.currentIntent && ev.targetIntent && <> → </>}
                  {ev.targetIntent && <>{ev.targetIntent}</>}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
