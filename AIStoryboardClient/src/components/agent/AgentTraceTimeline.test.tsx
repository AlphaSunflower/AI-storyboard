import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AgentTraceTimeline, traceChain } from './AgentTraceTimeline';
import type { AgentTraceEvent } from '../../api/agent';

function event(overrides: Partial<AgentTraceEvent>): AgentTraceEvent {
  return {
    id: overrides.id ?? 'trace-1',
    phase: 'model_reasoning',
    // 后端写死：agent 恒为 moon（AgentTraceAccumulator），specialist 恒为 agentscope
    // （AgentTraceBridgeMiddleware）。轨迹链的退化就是这两个常量造成的。
    agent: 'moon',
    specialist: 'agentscope',
    status: 'node_completed',
    title: '模型推理',
    summary: '推理完成',
    startedAt: '2026-10-14T08:00:00Z',
    ...overrides,
  };
}

/** 表头文本：只用本次 render 的容器查询，避免跨用例累积的 DOM 干扰。 */
function chainHeader(container: HTMLElement): string {
  const span = Array.from(container.querySelectorAll('span'))
    .find((element) => element.textContent?.startsWith('Agent 轨迹'));
  return span?.textContent ?? '';
}

describe('traceChain', () => {
  it('同一执行者连续出现时不显示链（旧实现会给出 5 个重复 agentscope）', () => {
    const events = [
      event({ id: 'a', phase: 'model_reasoning' }),
      event({ id: 'b', phase: 'tool_call', title: '读取技能' }),
      event({ id: 'c', phase: 'model_reasoning' }),
      event({ id: 'd', phase: 'tool_call', title: '调整可用工具' }),
      event({ id: 'e', phase: 'tool_call', title: '生成图片' }),
    ];

    expect(traceChain(events)).toBe('');
  });

  it('真正发生交接时画出执行者序列，并复用时间线的可读名', () => {
    const events = [
      event({ id: 'a', specialist: 'agentscope', phase: 'model_reasoning' }),
      event({ id: 'b', specialist: 'agentscope', phase: 'tool_call' }),
      event({ id: 'c', specialist: '审查专家', phase: 'specialist_execute' }),
      event({ id: 'd', specialist: '审查专家', phase: 'specialist_execute' }),
      event({ id: 'e', specialist: 'agentscope', phase: 'model_reasoning' }),
    ];

    expect(traceChain(events)).toBe('AIGC 执行器 → 审查专家 → AIGC 执行器');
  });

  it('缺 specialist 时回退到 agent', () => {
    const events = [
      event({ id: 'a', specialist: undefined, agent: 'moon' }),
      event({ id: 'b', specialist: undefined, agent: '分镜专家' }),
    ];

    expect(traceChain(events)).toBe('moon → 分镜专家');
  });
});

describe('AgentTraceTimeline', () => {
  it('全 agentscope 的一轮不渲染重复链，只保留表头', () => {
    const { container } = render(<AgentTraceTimeline events={[
      event({ id: 'a' }),
      event({ id: 'b', phase: 'tool_call', title: '读取技能' }),
      event({ id: 'c' }),
    ]} />);

    const header = chainHeader(container);
    expect(header).toBe('Agent 轨迹');
    expect(header).not.toContain('agentscope');
    expect(header).not.toContain('→');
  });

  it('有交接时表头带上链，且每一行仍逐条渲染', () => {
    const { container } = render(<AgentTraceTimeline events={[
      event({ id: 'a' }),
      event({ id: 'b', specialist: '审查专家', phase: 'specialist_execute', title: '审查分镜' }),
    ]} />);

    expect(chainHeader(container)).toBe('Agent 轨迹 · AIGC 执行器 → 审查专家');
    expect(container.textContent).toContain('审查分镜');
  });

  it('空事件不渲染', () => {
    const { container } = render(<AgentTraceTimeline events={[]} />);
    expect(container.firstChild).toBeNull();
  });
});
