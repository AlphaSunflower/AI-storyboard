import { useState } from 'react';
import type { HumanInputInfo } from '../../stores/agentStore';
import { HumanInputCard } from './HumanInputCard';

/**
 * 待选人工介入卡（human_input 事件）：/chat 对话流内渲染。
 * 选中后由 MessageBubble 基于 metadata.hitl 渲染的「已选」卡接手，本组件仅负责待选态。
 */
export function HitlPromptCard({
  info,
}: {
  info: HumanInputInfo;
}) {
  const [collapsed, setCollapsed] = useState(false);
  const toggle = () => setCollapsed((prev) => !prev);

  return (
    <HumanInputCard
      info={info}
      collapsed={collapsed}
      onToggleCollapse={toggle}
    />
  );
}
