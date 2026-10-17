import type { AgentConversation, AgentReference } from '../api/agent';

/**
 * `@` 引用资料的纯逻辑（与 React 无关，便于单测）。
 *
 * <p>设计要点：**正文里的 `@名称` 是唯一真源**。store 里的 `pendingReferences` 只是一份目录
 * （记住每个 token 指向谁），发送时再由 `activeReferences()` 按 token 在正文里出现与否过滤，
 * 因此用户整段删掉标记、粘贴覆盖、全选重写都不会留下"幽灵引用"。
 */

export const MENTION_LIMITS = {
  total: 10,
  scene: 5,
  conversation: 3,
} as const;

export interface MentionQuery {
  /** `@` 在正文里的下标 */
  start: number;
  /** `@` 之后到光标之间的查询词 */
  query: string;
}

export interface MentionRange {
  reference: AgentReference;
  start: number;
  end: number;
  /** 含 token 后紧跟的一个空格（渲染不需要，删除时需要） */
  endWithSpace: number;
}

/**
 * 光标前是否处于 `@` 提及模式。
 *
 * <p>`@` 与光标之间不允许空白/换行；`@` 前一个字符若是字母数字则不算（避免把 `a@b.com` 当提及）。
 */
export function resolveMentionQuery(value: string, caret: number): MentionQuery | null {
  if (caret < 0 || caret > value.length) return null;
  const before = value.slice(0, caret);
  const at = before.lastIndexOf('@');
  if (at < 0) return null;
  const query = before.slice(at + 1);
  if (/[\s\n]/.test(query)) return null;
  const previous = at > 0 ? before[at - 1] : '';
  if (previous && /[A-Za-z0-9_]/.test(previous)) return null;
  return { start: at, query };
}

/**
 * 把选中项插入正文：用 `@名称 ` 替换用户刚输入的 `@查询词`，光标落在名称后的空格之后。
 */
export function insertMention(
  value: string,
  caret: number,
  queryStart: number,
  token: string,
): { value: string; caret: number } {
  const before = value.slice(0, queryStart);
  const after = value.slice(caret);
  const inserted = `${token} `;
  return { value: `${before}${inserted}${after}`, caret: before.length + inserted.length };
}

/** 单个 token 在正文中的所有出现位置（同一 token 只应出现一次，重复出现按全部高亮处理）。 */
function tokenRanges(value: string, reference: AgentReference): MentionRange[] {
  const ranges: MentionRange[] = [];
  const token = reference.token;
  if (!token) return ranges;
  let from = 0;
  for (;;) {
    const start = value.indexOf(token, from);
    if (start < 0) break;
    const end = start + token.length;
    ranges.push({
      reference,
      start,
      end,
      endWithSpace: value[end] === ' ' ? end + 1 : end,
    });
    from = end;
  }
  return ranges;
}

/** 正文里实际存在的引用（发送时与高亮渲染共用同一口径）。 */
export function activeReferences(value: string, references: AgentReference[]): AgentReference[] {
  return references.filter((reference) => value.includes(reference.token));
}

/** 正文里实际存在的引用区间，按位置升序（镜像高亮层用）。 */
export function mentionRanges(value: string, references: AgentReference[]): MentionRange[] {
  return references
    .flatMap((reference) => tokenRanges(value, reference))
    .sort((left, right) => left.start - right.start);
}

export interface MentionSegment {
  text: string;
  /** true = 这段是引用标记，渲染成品牌色 */
  mention: boolean;
  reference?: AgentReference;
}

/**
 * 把正文切成「普通文本 / 引用标记」交替的片段，供镜像高亮层逐段渲染。
 *
 * <p>只着色**真正入选的引用**：用户手打的 `@随便写写` 不在此列，保持普通文本——否则
 * 输入框会出现"看起来是引用、其实没人认领"的假标记。
 */
export function splitMentionSegments(value: string, ranges: MentionRange[]): MentionSegment[] {
  const segments: MentionSegment[] = [];
  let cursor = 0;
  for (const range of ranges) {
    if (range.start < cursor) continue;
    if (range.start > cursor) segments.push({ text: value.slice(cursor, range.start), mention: false });
    segments.push({ text: value.slice(range.start, range.end), mention: true, reference: range.reference });
    cursor = range.end;
  }
  if (cursor < value.length) segments.push({ text: value.slice(cursor), mention: false });
  if (segments.length === 0) segments.push({ text: '', mention: false });
  return segments;
}

/**
 * 光标处（或紧邻左侧）的引用标记。
 *
 * <p>用于「退格一次删掉整个 `@名称`」：命中条件放宽到「光标在 token 内，或正好在 token/其后空格之后」。
 */
export function mentionSegmentAt(
  value: string,
  caret: number,
  references: AgentReference[],
): MentionRange | null {
  if (caret <= 0) return null;
  return mentionRanges(value, references)
    .find((range) => caret > range.start && caret <= range.endWithSpace) ?? null;
}

/** 删除光标左侧的整个引用标记（含其后的空格）；未命中返回 null，交给浏览器默认退格行为。 */
export function removeMentionSegment(
  value: string,
  caret: number,
  references: AgentReference[],
): { value: string; caret: number; reference: AgentReference } | null {
  const range = mentionSegmentAt(value, caret, references);
  if (!range) return null;
  return {
    value: `${value.slice(0, range.start)}${value.slice(range.endWithSpace)}`,
    caret: range.start,
    reference: range.reference,
  };
}

/**
 * 生成不与目录内已有 token 冲突的标记。
 *
 * <p>同名资料（两个同名文档、两个同名会话）必须能用不同 token 区分，否则 `indexOf` 定位与
 * 后端的名字解析都会指向错误的一条；冲突时追加 `#2`、`#3`。
 */export function uniqueMentionToken(name: string, references: AgentReference[]): string {
  const base = `@${name.trim() || '未命名'}`;
  const taken = new Set(references.map((reference) => reference.token));
  if (!taken.has(base)) return base;
  for (let index = 2; index < 100; index += 1) {
    const candidate = `${base}#${index}`;
    if (!taken.has(candidate)) return candidate;
  }
  return `${base}#${Date.now()}`;
}

export function countReferences(references: AgentReference[]) {
  return {
    total: references.length,
    scene: references.filter((reference) => reference.kind === 'scene').length,
    conversation: references.filter((reference) => reference.kind === 'conversation').length,
  };
}

/** 引用额度是否已满（超限时应置灰菜单项）。 */
export function referenceQuotaExceeded(
  references: AgentReference[],
  kind: AgentReference['kind'],
): boolean {
  const counts = countReferences(references);
  if (counts.total >= MENTION_LIMITS.total) return true;
  if (kind === 'scene') return counts.scene >= MENTION_LIMITS.scene;
  if (kind === 'conversation') return counts.conversation >= MENTION_LIMITS.conversation;
  return false;
}

export function sceneReference(
  scene: { id: string; sceneNumber: number },
  references: AgentReference[],
): AgentReference {
  const name = `分镜${scene.sceneNumber}`;
  return {
    kind: 'scene',
    id: scene.id,
    token: uniqueMentionToken(name, references),
    name,
    sceneNumber: scene.sceneNumber,
  };
}

export function conversationReference(
  conversation: Pick<AgentConversation, 'id' | 'title' | 'messageCount' | 'summaryStatus'>,
  references: AgentReference[],
): AgentReference {
  const title = (conversation.title || '未命名').trim();
  // 标题自带书名号时不再包一层（否则出现《《分镜重做》》）
  const display = /^《.*》$/.test(title) ? title : `《${title}》`;
  const name = `对话${display}`;
  return {
    kind: 'conversation',
    id: conversation.id,
    token: uniqueMentionToken(name, references),
    name,
    conversationTitle: conversation.title,
    messageCount: conversation.messageCount,
    summaryStatus: conversation.summaryStatus,
  };
}

/** 项目库媒体（图片/视频/音频/文档）：文档由后端按 mediaId 转存成会话资产后再读取（方案 §4.2）。 */
export function libraryMediaReference(
  media: { assetId: string; mediaId?: string; url: string; name: string; mimeType?: string; sizeBytes?: number },
  references: AgentReference[],
): AgentReference {
  return {
    kind: 'library_file',
    id: media.assetId,
    token: uniqueMentionToken(media.name, references),
    name: media.name,
    assetId: media.assetId,
    mediaId: media.mediaId,
    url: media.url,
    mimeType: media.mimeType,
    sizeBytes: media.sizeBytes,
  };
}

/**
 * 会话内文件（**只用于本地文件上传后**的形态：`/api/agent/upload` 返回的会话资产）。
 *
 * <p>只有落在 `/api/files/files/` 的会话资产才可被 `read_document` 读到；项目库文件不走这里，
 * 它用 `libraryMediaReference`（带 mediaId），由后端转存后再读（方案 §4.2）。
 */
export function localFileReference(
  file: { assetId: string; url: string; name: string; mimeType?: string; sizeBytes?: number },
  references: AgentReference[],
): AgentReference {
  return {
    kind: 'local_file',
    id: file.assetId,
    token: uniqueMentionToken(file.name, references),
    name: file.name,
    assetId: file.assetId,
    url: file.url,
    mimeType: file.mimeType,
    sizeBytes: file.sizeBytes,
  };
}
