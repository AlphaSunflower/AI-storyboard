import client from './client';
import { BACKEND_URL } from '../config';
import type { GatewayModelOption } from './ai';

export interface AgentConversation {
  id: string;
  userId: string;
  projectId: string;
  title: string;
  difyConversationId: string | null;
  status: 'active' | 'archived';
  createdAt: string;
  updatedAt: string;
}

export interface AgentMessage {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant';
  content: string;
  difyMessageId: string | null;
  createdAt: string;
}

export interface AgentAsset {
  id: string;
  conversationId: string | null;
  type: 'image' | 'video' | 'reference';
  url: string;
  prompt: string | null;
  model: string | null;
  status: string;
  taskId: string | null;
  error: string | null;
  createdAt: string;
}

export interface AgentPage<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

/** 资产选择卡片的资产项（human_input 事件 assets 字段） */
export interface AssetOption {
  id: string;
  name: string;
  type: string; // character / prop / scene
  image?: string; // 主图路径（/api/files/...）
}

export interface SseEvent {
  type: 'message' | 'workflow' | 'human_input' | 'message_end' | 'confirm_result' | 'video_plan' | 'task_accepted' | 'error';
  content?: string;
  title?: string;
  status?: string;
  formToken?: string;
  taskId?: string;
  formContent?: string;
  actions?: { id: string; title: string }[];
  expirationTime?: number;
  // 卡片模型/参数选项（后端下发网关模型列表，含各模型参数能力；无配置时缺失）
  models?: GatewayModelOption[];
  // aisplit 分镜确认卡片：图片/视频两组模型列表（分区参数选择器用）
  imageModels?: GatewayModelOption[];
  videoModels?: GatewayModelOption[];
  // LLM 推荐的生成参数值与推荐理由（video 链方案生成时输出）
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  messageId?: string;
  sceneCount?: number;
  // confirm_result 事件字段（生成结果看图确认卡片）
  kind?: 'script' | 'image' | 'video';
  url?: string;
  assetId?: string;
  // confirm_result 多图结果（n>1 时后端下发）
  urls?: string[];
  assetIds?: string[];
  // video_plan 事件字段（图生视频方案确认卡片）
  planToken?: string;
  duration?: number;
  picUrl?: string;
  // 资产选择卡片（human_input 事件带 assets 时渲染勾选列表）
  assets?: AssetOption[];
  code?: string;
  message?: string;
}

/** 视频异步任务状态（GET /api/agent/tasks/{taskId} 轮询结果） */
export interface VideoTaskStatus {
  taskId: string;
  assetId: string;
  status: 'queued' | 'running' | 'completed' | 'failed' | 'unknown';
  url: string;
  error: string;
}

// ── 会话 ──────────────────────────────────────────────

export const agentApi = {
  listConversations: (projectId: string) =>
    client.get<{ data: AgentConversation[] }>('/agent/conversations', { params: { projectId } }),

  createConversation: (projectId: string, title?: string) =>
    client.post<{ data: AgentConversation }>('/agent/conversations', { projectId, title }),

  updateConversation: (id: string, data: { title?: string; status?: string }) =>
    client.patch<{ data: AgentConversation }>(`/agent/conversations/${id}`, data),

  deleteConversation: (id: string) =>
    client.delete(`/agent/conversations/${id}`),

  // 清空会话聊天记录（删消息 + 重置 AI 上下文；会话与资产保留）
  clearMessages: (id: string) =>
    client.delete(`/agent/conversations/${id}/messages`),

  listMessages: (id: string) =>
    client.get<{ data: AgentMessage[] }>(`/agent/conversations/${id}/messages`),

  listAssets: (id: string, page = 1, size = 20) =>
    client.get<{ data: AgentPage<AgentAsset> }>(`/agent/conversations/${id}/assets`, { params: { page, size } }),

  deleteAsset: (assetId: string) =>
    client.delete(`/agent/assets/${assetId}`),

  uploadImage: (file: File, conversationId?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (conversationId) form.append('conversationId', conversationId);
    return client.post<{ data: { url: string } }>('/agent/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  // 满意完成：清空 Dify 会话的 storage_pic_talk 变量（生成结果确认卡片「满意完成」按钮）
  confirmDone: (conversationId: string) =>
    client.post<{ data: boolean }>(`/agent/conversations/${conversationId}/confirm-done`),

  // 视频异步任务状态（task_accepted 后前端 5s 轮询，取 status/url/error）
  getVideoTaskStatus: (taskId: string) =>
    client.get<{ data: VideoTaskStatus }>(`/agent/tasks/${taskId}`),

  // 获取会话的待处理 checkpoint（页面刷新恢复 HITL 卡片用）
  getPendingCheckpoint: (conversationId: string) =>
    client.get<{ data: Record<string, unknown> | null }>(`/agent/conversations/${conversationId}/pending-checkpoint`),
};

/**
 * 流式语音识别：录音中实时推 PCM 裸流（16kHz mono 16bit）到 /agent/stt/stream，
 * SSE 收 partial（识别中文本，实时打字机）/ final（最终文本）。
 * 返回 { push, close, cancel }：push 推 PCM 块；close 关流结束识别；cancel 中断。
 */
export function sttStream(onPartial: (text: string) => void, onFinal: (text: string) => void) {
  // 前端直连 vosk-server（本机开发：ws://localhost:2700；生产部署时应走后端代理并加鉴权）
  // 不用 HTTP 流式上传：Chrome 对流式 body（fetch/XHR）强制 HTTP/2，对 HTTP/1.1 的
  // localhost（Vite/Tomcat）会 ALPN 协商失败（ERR_ALPN_NEGOTIATION_FAILED）。
  // WebSocket 握手是 HTTP/1.1 Upgrade，推流用 WS 帧，无此限制。
  const VOSK_WS_URL = (import.meta as { env?: Record<string, string> }).env?.VITE_VOSK_WS_URL ?? 'ws://localhost:2700';
  const ws = new WebSocket(VOSK_WS_URL);
  let opened = false;
  let closed = false;
  const pending: Int16Array[] = []; // 连接建立前缓冲 PCM

  ws.onopen = () => {
    opened = true;
    // 显式下发 config 更稳（vosk 默认即 16k）
    ws.send(JSON.stringify({ config: { sample_rate: 16000 } }));
    // 补发连接建立前的缓冲
    for (const p of pending) ws.send(p.buffer as ArrayBuffer);
    pending.length = 0;
  };

  // 从 vosk 消息中提取 partial/text 字段值
  const extractText = (msg: string): string => {
    const m = msg.match(/"partial"\s*:\s*"([^"]*)"|"text"\s*:\s*"([^"]*)"/);
    return m ? (m[1] ?? m[2] ?? '') : '';
  };

  ws.onmessage = (e) => {
    const msg = String(e.data);
    if (msg.includes('"partial"')) {
      onPartial(extractText(msg));
    } else if (msg.includes('"text"')) {
      onFinal(extractText(msg));
    }
  };
  ws.onerror = () => { /* 连接错误：静默，录音中无提示 */ };
  ws.onclose = () => { /* 关闭：正常结束 */ };

  return {
    /** 推送 PCM 块（16kHz mono 16bit Int16Array） */
    push: (pcm: Int16Array) => {
      if (closed) return;
      if (!opened) { pending.push(pcm); return; }
      try { ws.send(pcm.buffer as ArrayBuffer); } catch { /* 连接已关 */ }
    },
    /** 停止录音：发 eof 结束识别（vosk 回 final 后自行关闭） */
    close: () => {
      if (closed) return;
      closed = true;
      if (opened) {
        // 注意空格：vosk-server 精确字符串匹配 '{"eof" : 1}'，无空格会当音频数据
        try { ws.send('{"eof" : 1}'); } catch { /* ignore */ }
      } else {
        try { ws.close(); } catch { /* ignore */ }
      }
    },
    /** 中断：直接关闭连接 */
    cancel: () => {
      if (closed) return;
      closed = true;
      try { ws.close(); } catch { /* ignore */ }
    },
  };
}

/** 流式发送消息。onEvent 收到裁剪后的 SseEvent。返回 Promise（流结束/出错时 resolve） */
export async function streamChat(
  conversationId: string,
  content: string,
  picUrl: string | undefined,
  onEvent: (e: SseEvent) => void,
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ content, picUrl: picUrl ?? '' }),
  });
  await consumeSse(res, onEvent);
}

/** HITL 表单提交并续流 */
export async function submitForm(
  conversationId: string,
  formToken: string,
  taskId: string,
  action: string,
  onEvent: (e: SseEvent) => void,
  customText?: string,
  params?: Record<string, string>,
  assetIds?: string[],
): Promise<void> {
  const token = localStorage.getItem('accessToken') ?? '';
  const res = await fetch(`${BACKEND_URL}/api/agent/conversations/${conversationId}/form/submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ formToken, taskId, action, content: customText ?? '', params: params ?? {}, assetIds: assetIds ?? [] }),
  });
  await consumeSse(res, onEvent);
}

/** 通用 SSE 读取：逐行解析 event:/data: {...}，断行缓冲 */
async function consumeSse(res: Response, onEvent: (e: SseEvent) => void): Promise<void> {
  if (!res.ok) {
    let detail = '';
    try { detail = (await res.json()).message ?? ''; } catch { /* ignore */ }
    throw new Error(detail || `请求失败 (${res.status})`);
  }
  if (!res.body) throw new Error('响应无数据流');
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() ?? '';
      for (const part of parts) {
        const lines = part.split('\n');
        const eventLine = lines.find((l) => l.startsWith('event:'));
        const dataLine = lines.find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        const eventName = eventLine ? eventLine.slice(6).trim() : '';
        try {
          const data = JSON.parse(dataLine.slice(5).trim()) as SseEvent;
          // 注意展开顺序：data 在前、type 在后（TS2783：避免 SseEvent.type 覆盖 event: 行解析结果）
          onEvent({ ...data, type: eventName } as SseEvent);
        } catch { /* 忽略坏帧 */ }
      }
    }
  } finally {
    reader.releaseLock();
  }
}
