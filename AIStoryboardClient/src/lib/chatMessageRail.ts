import type { AgentMessage } from '../api/agent';
import { shouldHidePendingCardMessage } from './pendingCardMessage';

/**
 * /chat 消息导航行边栏的纯逻辑：把消息列表折成"每轮一项"的导航数据，
 * 并负责两行文案的截断规则（第一行 ≤15 字、第二行取该轮回复最后一段前 40 字）。
 */

/** 行边栏一项 = 一条用户消息（一轮对话） */
export interface ChatRailItem {
  /** 用户消息 id */
  id: string;
  /** 消息列里对应锚点的 id（当前等于 id，独立出来避免以后换锚点语义） */
  anchorId: string;
  /** 卡片第一行：用户消息折叠空白后的标题（超长已截断） */
  title: string;
  /** 卡片第二行：该轮 AI 回复最后一段的前 40 字，无回复时为空串 */
  preview: string;
}

/** 标题超过该字数即截断 */
export const RAIL_TITLE_MAX = 15;
/** 截断时保留的字数 */
export const RAIL_TITLE_KEEP = 12;
/** 第二行保留的字数 */
export const RAIL_PREVIEW_MAX = 40;
/** 高亮判定线：可视区高度的 35% */
export const RAIL_ACTIVE_THRESHOLD = 0.35;
/** 刻度间隔下限 / 上限（px） */
export const RAIL_MIN_GAP = 10;
export const RAIL_MAX_GAP = 24;

/** 折叠全部空白（含换行）为单空格并去首尾 */
export function foldWhitespace(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

/** 按码点截断：≤max 原样，超出取前 keep 个码点 + 单字符省略号 */
export function truncateCodePoints(text: string, keep: number, max: number): string {
  const points = Array.from(text);
  if (points.length <= max) return text;
  return `${points.slice(0, keep).join('')}…`;
}

/** 第一行：折叠空白后 ≤15 字；超出取前 12 字 + … */
export function truncateRailTitle(text: string): string {
  const folded = foldWhitespace(text);
  if (!folded) return '';
  return truncateCodePoints(folded, RAIL_TITLE_KEEP, RAIL_TITLE_MAX);
}

/** 剥离常见 Markdown 语法，保留可读正文 */
function stripMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, '\n\n')
    .replace(/~~~[\s\S]*?~~~/g, '\n\n')
    .replace(/^\s*\|.*\|\s*$/gm, '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^[ \t]*#{1,6}[ \t]*/gm, '')
    .replace(/^[ \t]*>[ \t]?/gm, '')
    .replace(/^[ \t]*(?:[-*+]|\d+[.)])[ \t]+/gm, '')
    .replace(/^\s*([-*_]\s*){3,}$/gm, '')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/(\*\*|__)(.*?)\1/g, '$2')
    .replace(/(\*|_)([^*_]+)\1/g, '$2');
}

/** 第二行：Markdown 正文里最后一段的前 40 字；没有正文则空串 */
export function railPreviewFromMarkdown(text: string, max: number = RAIL_PREVIEW_MAX): string {
  if (!text) return '';
  const blocks = stripMarkdown(text)
    .split(/\n{2,}/)
    .map((block) => foldWhitespace(block))
    .filter(Boolean);
  const last = blocks.length > 0 ? blocks[blocks.length - 1] : '';
  if (!last) return '';
  return truncateCodePoints(last, max, max);
}

/** 空消息（纯附件 / 完全空白）的兜底标题 */
function titleForMessage(message: AgentMessage): string {
  const title = truncateRailTitle(message.content ?? '');
  if (title) return title;
  const input = message.metadata?.messageInput;
  const attachmentCount = (input?.attachments?.length ?? 0)
    + (input?.assetIds?.length ?? 0)
    + (input?.picUrls?.length ?? 0)
    + (input?.referenceVideos?.length ?? 0)
    + (input?.referenceAudios?.length ?? 0);
  return attachmentCount > 0 ? '（附件消息）' : '（空消息）';
}

/**
 * 把消息列表折成导航项：每条用户消息一项，第二行取其后第一条有正文的助手回复。
 * 只统计当前已加载、且消息列真正渲染的消息（占位卡消息被过滤掉）。
 */
export function buildChatRailItems(messages: readonly AgentMessage[]): ChatRailItem[] {
  const visible = messages.filter((message) => !shouldHidePendingCardMessage(message));
  const items: ChatRailItem[] = [];

  for (let index = 0; index < visible.length; index++) {
    const message = visible[index];
    if (message.role !== 'user') continue;

    let preview = '';
    for (let next = index + 1; next < visible.length; next++) {
      const reply = visible[next];
      if (reply.role === 'user') break;
      preview = railPreviewFromMarkdown(reply.content ?? '');
      if (preview) break;
    }

    items.push({
      id: message.id,
      anchorId: message.id,
      title: titleForMessage(message),
      preview,
    });
  }

  return items;
}

/** 高亮锚点：视口里的消息位置（top 为视口坐标） */
export interface RailAnchor {
  id: string;
  top: number;
}

/**
 * 取"最后一条顶边越过阈值线"的消息作为当前轮次；都没有越过时取第一条。
 * anchors 需按文档顺序（top 升序）传入。
 */
export function pickActiveRailId(
  anchors: readonly RailAnchor[],
  containerTop: number,
  containerHeight: number,
): string | null {
  if (anchors.length === 0) return null;
  const threshold = containerTop + containerHeight * RAIL_ACTIVE_THRESHOLD;
  let active: string | null = null;
  for (const anchor of anchors) {
    if (anchor.top <= threshold) active = anchor.id;
  }
  return active ?? anchors[0].id;
}

export interface RailStackLayout {
  /** 从第几项开始渲染（消息过多时丢弃较早的项） */
  start: number;
  /** 渲染多少项 */
  count: number;
  /** 相邻刻度间隔（px） */
  gap: number;
}

/**
 * 按可用高度算刻度栈：间隔在 8~16px 之间自适应；
 * 即使压到下限仍放不下时，只保留最近能容纳的若干轮。
 */
export function resolveRailStack(availableHeight: number, total: number): RailStackLayout {
  const count = Math.max(0, Math.floor(total));
  if (count === 0) return { start: 0, count: 0, gap: RAIL_MAX_GAP };

  // 尚未测量（首帧 / 测试环境）时按最大间隔铺满，避免闪空
  if (!Number.isFinite(availableHeight) || availableHeight <= 0) {
    return { start: 0, count, gap: RAIL_MAX_GAP };
  }

  const fit = Math.max(1, Math.floor(availableHeight / RAIL_MIN_GAP));
  if (fit >= count) {
    const gap = Math.min(RAIL_MAX_GAP, Math.max(RAIL_MIN_GAP, availableHeight / count));
    return { start: 0, count, gap };
  }

  return { start: count - fit, count: fit, gap: RAIL_MIN_GAP };
}
