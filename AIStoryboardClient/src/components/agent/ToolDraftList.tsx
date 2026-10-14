import { useState, type CSSProperties } from 'react';
import {
  AlertTriangle, ChevronRight, FileText, Film, Image as ImageIcon, Layers,
  ListChecks, Search, Sparkles, Terminal, Users, Wrench,
} from 'lucide-react';
import type { AgentMessageMetadata, AgentTaskCardRef, AgentTaskView, AgentToolDraft } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import {
  collectDraftRenderIntents,
  collectRenderIntents,
  parseRenderIntent,
  renderIntentKey,
} from '../../lib/renderIntent';
import { foldTasksByToolCallId, foldedTaskIds } from '../../lib/taskFolding';
import { AgentTaskCard } from './AgentTaskCard';
import { resolveRenderIntentRenderer } from './renderIntentRegistry';
import {
  toolDisplayName,
  toolIconKey,
  toolOutcomeNote,
  toolPreviewText,
  toolStatusLabel,
  toolStatusTone,
  type ToolIconKey,
} from './toolCallPresentation';
import { toolTimelineStyle } from './toolTimelineTheme';
import './ToolCallTimeline.css';

/**
 * 工具调用时间轴：消费当前 turn 的 `generating.toolDrafts`（直播）与
 * `metadata.toolDrafts`（回放 / 刷新），把每次工具调用渲染成左侧脊线上的一环。
 *
 * - 纯函数 of store state：直播与重放走同一份渲染，不依赖任何只在直播阶段存在的计时器，
 *   因此**不显示耗时**（`AgentToolDraft` 没有结束时间戳，算了就不是纯函数了）；
 * - 每个节点显示中文动作名 + 状态胶囊 + 等宽原名与一行输出摘要，展开后看入参 / 输出全文；
 * - 工具结果带 `render` 意图时按 kind 分派渲染器，否则只渲染文本输出（保持现状）；
 * - 带 `parentToolCallId` 的后台任务（复合旅程的父/子步骤）折到命中的工具卡下，
 *   缺字段时完全不改变既有渲染。
 */

export const TOOL_OUTPUT_PREVIEW_LIMIT = 1200;

/** 图标取自 lucide 家族，与 /chat 其余部分保持同一套图标语言。 */
type ToolIconComponent = typeof Wrench;

const TOOL_ICONS: Record<ToolIconKey, ToolIconComponent> = {
  image: ImageIcon,
  video: Film,
  storyboard: Layers,
  search: Search,
  file: FileText,
  terminal: Terminal,
  skill: Sparkles,
  agent: Users,
  task: ListChecks,
  generic: Wrench,
};

const ICON_SIZE = 13;
const ICON_STROKE = 1.9;

/** 稳定顺序：先按 seq，其次按首次出现顺序，保证刷新前后工具卡不乱序。 */
export function orderedToolDrafts(drafts?: Record<string, AgentToolDraft>): Array<[string, AgentToolDraft]> {
  if (!drafts) return [];
  return Object.entries(drafts)
    .map(([id, draft], index) => ({ id, draft, index }))
    .sort((a, b) => {
      const aSeq = typeof a.draft.seq === 'number' ? a.draft.seq : Number.MAX_SAFE_INTEGER;
      const bSeq = typeof b.draft.seq === 'number' ? b.draft.seq : Number.MAX_SAFE_INTEGER;
      if (aSeq !== bSeq) return aSeq - bSeq;
      return a.index - b.index;
    })
    .map((entry) => [entry.id, entry.draft] as [string, AgentToolDraft]);
}

/** 收集本轮所有工具草稿里的渲染意图（去重 + 稳定排序）。 */
export { collectDraftRenderIntents } from '../../lib/renderIntent';

/** 展开后的 monospace 输出块；超长截断并提供「显示更多」。 */
function ToolOutput({ text }: { text: string }) {
  const [expanded, setExpanded] = useState(false);
  if (!text) return <span className="tool-node__empty">（暂无输出）</span>;
  const truncated = text.length > TOOL_OUTPUT_PREVIEW_LIMIT;
  const shown = truncated && !expanded ? text.slice(0, TOOL_OUTPUT_PREVIEW_LIMIT) : text;
  return (
    <div>
      <pre className="tool-node__pre" style={{ maxHeight: expanded ? 320 : 200 }}>
        {shown}
        {truncated && !expanded ? '…' : ''}
      </pre>
      {truncated && (
        <button
          type="button"
          className="tool-node__more"
          onClick={() => setExpanded((previous) => !previous)}
        >
          {expanded ? '收起' : `显示更多（共 ${text.length} 字符）`}
        </button>
      )}
    </div>
  );
}

/**
 * 单个工具节点：脊线上的一个圆点 + 一行标题 + 一行等宽摘要。
 *
 * 展开区用**条件挂载**而非 presence 过渡：折叠态必须真的不在 DOM 里
 * （无障碍与 `queryByText` 都依赖这一点），用 rAF 延迟挂载的过渡会让这一步变成异步。
 * 所以入场用纯 CSS 关键帧，收起即时。
 */
function ToolDraftRow({ toolCallId, draft, nestedTasks = [] }: {
  toolCallId: string;
  draft: AgentToolDraft;
  /** 归属该工具卡的后台任务（复合旅程的父任务与逐步骤子任务）。 */
  nestedTasks?: AgentTaskView[];
}) {
  const [expanded, setExpanded] = useState(false);
  const intent = parseRenderIntent(draft.render);
  const Renderer = intent ? resolveRenderIntentRenderer(intent.kind) : null;
  const hasOutput = !!draft.text || !!intent;
  const tone = toolStatusTone(draft.status);
  const Icon = TOOL_ICONS[toolIconKey(draft.name)];
  const outcomeNote = toolOutcomeNote(draft.text);
  // 展开后完整输出就在下面，摘要行再重复一遍是冗余；折叠态才需要它做一行速览。
  const preview = expanded ? '' : toolPreviewText(draft);
  const rawName = draft.name || 'tool';

  return (
    <article
      className="tool-node"
      data-tone={tone}
      data-tool-call-id={toolCallId}
      data-tool-status={draft.status}
      style={toolTimelineStyle(tone) as CSSProperties}
    >
      <span className="tool-node__dot" aria-hidden="true" />
      <div className="tool-node__body">
        <button
          type="button"
          className="tool-node__toggle"
          aria-expanded={expanded}
          onClick={() => setExpanded((previous) => !previous)}
        >
          <span className="tool-node__icon" aria-hidden="true">
            <Icon size={ICON_SIZE} strokeWidth={ICON_STROKE} />
          </span>
          <span className="tool-node__title">{toolDisplayName(draft.name)}</span>
          <span className="tool-node__chip">
            <span className="tool-node__chip-dot" aria-hidden="true" />
            {toolStatusLabel(draft.status)}
          </span>
          {outcomeNote && (
            <span className="tool-node__note">
              <AlertTriangle size={11} strokeWidth={2.1} aria-hidden="true" />
              {outcomeNote}
            </span>
          )}
          <span className="tool-node__chevron" aria-hidden="true">
            <ChevronRight size={14} strokeWidth={1.9} />
          </span>
        </button>

        <div className="tool-node__meta">
          <code className="tool-node__rawname">{rawName}</code>
          {preview && (
            <>
              <span className="tool-node__separator" aria-hidden="true">·</span>
              <span className="tool-node__preview" title={preview}>{preview}</span>
            </>
          )}
        </div>

        {nestedTasks.length > 0 && (
          <div className="tool-node__nested" data-nested-tool-tasks={toolCallId}>
            <div className="tool-node__nested-label">步骤进度（{nestedTasks.length}）</div>
            {nestedTasks.map((task) => (
              <AgentTaskCard key={task.taskId} taskId={task.taskId} />
            ))}
          </div>
        )}

        {expanded && (
          <div className="tool-node__detail">
            {draft.input && (
              <div>
                <div className="tool-node__section-label">入参</div>
                <ToolOutput text={draft.input} />
              </div>
            )}
            {hasOutput && (
              <div>
                <div className="tool-node__section-label">输出</div>
                <ToolOutput text={draft.text} />
              </div>
            )}
            {intent && Renderer && <Renderer intent={intent} />}
          </div>
        )}
      </div>
    </article>
  );
}

/**
 * @param drafts 显式传入的草稿（如从消息 metadata 恢复）；缺省时读当前会话的直播草稿。
 * @param liveAssistantId 当前流式 assistant 消息 id；与直播草稿的属主不一致时不渲染直播草稿。
 */
export function ToolDraftList({ drafts, liveAssistantId }: {
  drafts?: Record<string, AgentToolDraft>;
  liveAssistantId?: string;
}) {
  const liveDrafts = useAgentStore((state) => {
    const conversationId = state.activeConversationId;
    if (!conversationId) return undefined;
    const generation = state.generating[conversationId];
    if (!generation || generation.finished) return undefined;
    if (liveAssistantId && generation.assistantId !== liveAssistantId) return undefined;
    return generation.toolDrafts;
  });
  // 直播草稿优先（含正在流式的输出）；无直播草稿时回落到回放 metadata 快照。
  const effective = Object.keys(liveDrafts ?? {}).length > 0 ? liveDrafts : drafts;
  const entries = orderedToolDrafts(effective);
  const tasks = useAgentStore((state) => state.tasks);
  // 只有「确实渲染了工具卡」的 toolCallId 才会折叠步骤，未命中时任务保持既有独立卡片。
  const nested = foldTasksByToolCallId(Object.values(tasks), entries.map(([toolCallId]) => toolCallId));
  if (entries.length === 0) return null;

  return (
    <div
      className="tool-timeline"
      data-tool-draft-list=""
      style={toolTimelineStyle() as CSSProperties}
    >
      <div className="tool-timeline__head">
        <span className="tool-timeline__head-icon" aria-hidden="true">
          <Wrench size={12} strokeWidth={2} />
        </span>
        <span>工具调用（{entries.length}）</span>
      </div>
      {entries.map(([toolCallId, draft]) => (
        <ToolDraftRow
          key={toolCallId}
          toolCallId={toolCallId}
          draft={draft}
          nestedTasks={nested.get(toolCallId) ?? []}
        />
      ))}
    </div>
  );
}

/** 直播工具草稿（当前会话、未结束）；与 `ToolResultBlocks` / `ToolDraftList` 同一口径。
 *
 * @param messageId 属主气泡 id；给了就只认「本轮直播草稿属于这条消息」的那一份。
 */
function useLiveToolDrafts(messageId?: string): Record<string, AgentToolDraft> | undefined {
  return useAgentStore((state) => {
    const conversationId = state.activeConversationId;
    if (!conversationId) return undefined;
    const generation = state.generating[conversationId];
    if (!generation || generation.finished) return undefined;
    // 直播草稿只挂在发起本轮的那个气泡上（否则每条历史消息都会显示同一批工具调用）
    if (messageId && generation.assistantId !== messageId) return undefined;
    return generation.toolDrafts;
  });
}

/**
 * 消息正文下方的独立任务卡：`parentToolCallId` 命中本消息工具卡的任务已经折到工具卡下，
 * 这里不再重复渲染；缺字段 / 空白 / 未命中任何工具卡时，渲染与今天逐字一致。
 */
export function StandaloneTaskCards({ cards, metadata, messageId }: {
  cards?: AgentTaskCardRef[];
  metadata?: AgentMessageMetadata;
  messageId?: string;
}) {
  const liveDrafts = useLiveToolDrafts(messageId);
  const tasks = useAgentStore((state) => state.tasks);
  if (!cards || cards.length === 0) return null;
  const drafts = Object.keys(liveDrafts ?? {}).length > 0 ? liveDrafts : metadata?.toolDrafts;
  const folded = foldedTaskIds(foldTasksByToolCallId(Object.values(tasks), Object.keys(drafts ?? {})));
  return (
    <>
      {cards
        .filter((card) => !folded.has(card.taskId))
        .map((card) => <AgentTaskCard key={card.taskId} taskId={card.taskId} />)}
    </>
  );
}

/**
 * 工具结果区：工具调用列表 + 结果渲染意图（`render` 块）。
 *
 * 直播走 `generating.toolDrafts`，重放/刷新走 `metadata.toolDrafts` + `metadata.renders`，
 * 两者都是 store state 的纯函数，因此直播与重放结果一致。
 * `metadata.renders` / `metadata.toolDrafts` 都缺失时整块不渲染（零回归）。
 *
 * @param messageId 属主气泡 id。直播草稿只属于发起本轮的那条消息：
 * 不做归属判断时，同一会话里每条历史消息都会挂上当前这轮的工具调用，
 * 而本轮一收尾（暂停 / 完成）它们又会从所有消息上一起消失——正是「一开始有的，后面被吞」。
 */
export function ToolResultBlocks({ metadata, messageId }: {
  metadata?: AgentMessageMetadata;
  messageId?: string;
}) {
  const liveDrafts = useLiveToolDrafts(messageId);
  const replayDrafts = metadata?.toolDrafts;
  const drafts = Object.keys(liveDrafts ?? {}).length > 0 ? liveDrafts : replayDrafts;
  const entries = orderedToolDrafts(drafts);
  // 工具草稿可能整轮都在，但渲染意图只在 metadata 上（服务端结果 metadata 下发）。
  const intents = collectRenderIntents(
    metadata?.renders,
    collectDraftRenderIntents(drafts),
  );
  if (entries.length === 0 && intents.length === 0) return null;

  return (
    <div
      data-tool-result-blocks=""
      style={{ width: '100%', maxWidth: '100%', display: 'flex', flexDirection: 'column', gap: 6 }}
    >
      {entries.length > 0 && <ToolDraftList drafts={drafts} liveAssistantId={messageId} />}
      {intents.map((intent, index) => {
        const Renderer = resolveRenderIntentRenderer(intent.kind);
        return <Renderer key={renderIntentKey(intent, index)} intent={intent} />;
      })}
    </div>
  );
}
