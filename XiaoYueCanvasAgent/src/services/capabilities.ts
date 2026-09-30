import type {
  CapabilityDescriptor,
  JobAdapter,
  JobAdapterStatus,
  JobAssetDescriptor,
  JobEvent,
  JobRequest,
  JobSnapshot,
} from '../types';
import { bootstrapJobAuth, getJobToken, getStreamTicket } from './jobAuth';

export const LOCAL_CAPABILITIES: CapabilityDescriptor[] = [
  { id: 'image.crop', label: '裁剪', category: 'local', available: true },
  { id: 'image.split', label: '快速切分', category: 'local', available: true },
  { id: 'image.pixels', label: '像素调整', category: 'local', available: true },
  { id: 'image.annotation', label: '标注', category: 'local', available: true },
  { id: 'video.import', label: '导入视频', category: 'local', available: true },
  { id: 'video.playlist', label: '视频片段编排', category: 'local', available: true },
  { id: 'video.frame', label: '提取视频帧', category: 'local', available: true },
  { id: 'video.flip', label: '水平翻转', category: 'local', available: true },
  { id: 'video.color', label: '视频调色', category: 'local', available: true },
  { id: 'video.speed', label: '视频变速', category: 'local', available: true },
  { id: 'video.merge', label: '视频合并', category: 'local', available: true },
];

export const REMOTE_CAPABILITIES: CapabilityDescriptor[] = [
  { id: 'agent.request', label: '设计助理请求', category: 'remote', available: false, unavailableReason: '未配置执行服务' },
  { id: 'image.generate', label: '生图', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.expand', label: '扩图', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.erase', label: '擦除', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.redraw', label: '重绘', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.enhance', label: '增强', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.cutout', label: '抠图', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.angle', label: '多角度', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'image.relight', label: '重新布光', category: 'remote', available: false, unavailableReason: '未配置图像执行服务' },
  { id: 'video.generate', label: '视频生成', category: 'remote', available: false, unavailableReason: '未配置视频执行服务' },
  { id: 'video.analyze', label: '视频解析', category: 'remote', available: false, unavailableReason: '未配置视频执行服务' },
];

export const CAPABILITIES = [...LOCAL_CAPABILITIES, ...REMOTE_CAPABILITIES];

// dev 靠 vite proxy 转发 /api/canvas → localhost:9090；生产靠网关同域
const endpoint = '/api/canvas';
const requestTimeoutMs = 120_000;

const withTimeout = async (input: RequestInfo | URL, init: RequestInit = {}, externalSignal?: AbortSignal) => {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), requestTimeoutMs);
  const onAbort = () => controller.abort();
  if (externalSignal?.aborted) controller.abort();
  else externalSignal?.addEventListener('abort', onAbort, { once: true });
  const headers = new Headers(init.headers as HeadersInit | undefined);
  const token = getJobToken() ?? await bootstrapJobAuth();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  try {
    return await fetch(input, { credentials: 'include', ...init, headers, signal: controller.signal });
  } finally {
    window.clearTimeout(timer);
    externalSignal?.removeEventListener('abort', onAbort);
  }
};

const errorMessage = async (response: Response) => {
  try {
    const body = await response.json() as { message?: string };
    if (body.message) return body.message;
  } catch {
    // An empty or non-JSON response falls back to the HTTP status below.
  }
  return `执行服务返回 ${response.status}`;
};

const normalizeEvent = (data: string, jobId: string, fallbackType?: JobEvent['type'], fallbackEventId?: string): JobEvent | null => {
  try {
    const event = JSON.parse(data) as Partial<JobEvent>;
    const type = event.type ?? fallbackType;
    if (!type) return null;
    return { ...event, type, jobId: event.jobId ?? jobId, eventId: event.eventId ?? fallbackEventId ?? undefined } as JobEvent;
  } catch {
    return null;
  }
};

export class HttpJobAdapter implements JobAdapter {
  constructor(private readonly baseUrl: string) {}

  async getStatus(): Promise<JobAdapterStatus> {
    try {
      const response = await withTimeout(`${this.baseUrl}/health`, { headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error(await errorMessage(response));
      const body = await response.json() as { label?: string };
      return { connected: true, label: body.label ?? '执行服务已连接', endpoint: this.baseUrl };
    } catch (error) {
      return {
        connected: false,
        label: error instanceof DOMException && error.name === 'AbortError' ? '执行服务连接超时' : '执行服务不可用',
        endpoint: this.baseUrl,
      };
    }
  }

  async getCapabilities(signal?: AbortSignal) {
    const response = await withTimeout(`${this.baseUrl}/capabilities`, { headers: { Accept: 'application/json' } }, signal);
    if (!response.ok) throw new Error(await errorMessage(response));
    const remote = await response.json() as CapabilityDescriptor[];
    if (!Array.isArray(remote)) throw new Error('执行服务能力响应无效');
    return [...LOCAL_CAPABILITIES, ...remote.map((item) => ({ ...item, category: 'remote' as const }))];
  }

  async submit(request: JobRequest, signal?: AbortSignal) {
    const response = await withTimeout(`${this.baseUrl}/jobs`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    }, signal);
    if (!response.ok) throw new Error(await errorMessage(response));
    const body = await response.json() as { jobId?: string };
    if (!body.jobId) throw new Error('执行服务没有返回任务 ID');
    return { jobId: body.jobId };
  }

  async getJob(jobId: string): Promise<JobSnapshot> {
    const response = await withTimeout(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) throw new Error(await errorMessage(response));
    return await response.json() as JobSnapshot;
  }

  subscribe(jobId: string, listener: (event: JobEvent) => void) {
    const base = `${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/events`;
    let source: EventSource | null = null;
    let disposed = false;
    const eventTypes: JobEvent['type'][] = [
      'job.queued',
      'job.started',
      'job.progress',
      'agent.message',
      'storyboard.outline.ready',
      'storyboard.preview',
      'storyboard.created',
      'asset.created',
      'job.completed',
      'job.failed',
      'job.cancelled',
    ];
    const listeners: Array<readonly [JobEvent['type'], EventListener]> = [];
    void getStreamTicket(new URL(base, window.location.origin).pathname).then((ticket) => {
      if (disposed || !ticket) {
        if (!disposed) listener({ type: 'job.failed', jobId, message: '任务事件流鉴权失败' });
        return;
      }
      source = new EventSource(`${base}?ticket=${encodeURIComponent(ticket)}`);
      eventTypes.forEach((type) => {
        const handler = (event: MessageEvent<string>) => {
          const parsed = normalizeEvent(event.data, jobId, type, event.lastEventId);
          if (parsed) listener(parsed);
        };
        source?.addEventListener(type, handler as EventListener);
        listeners.push([type, handler as EventListener]);
      });
      if (!source) return;
      source.onmessage = (event) => {
        const parsed = normalizeEvent(event.data, jobId, undefined, event.lastEventId);
        if (parsed) listener(parsed);
      };
      source.onerror = () => {
        if (source?.readyState === EventSource.CLOSED) {
          listener({ type: 'job.failed', jobId, message: '任务事件流已断开' });
        }
      };
    });
    return () => {
      disposed = true;
      listeners.forEach(([type, handler]) => source?.removeEventListener(type, handler));
      source?.close();
    };
  }

  async cancel(jobId: string) {
    const response = await withTimeout(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/cancel`, { method: 'POST' });
    if (!response.ok) throw new Error(await errorMessage(response));
  }

  async fetchAsset(asset: JobAssetDescriptor) {
    const base = new URL(`${this.baseUrl}/`, window.location.origin);
    const target = asset.downloadUrl
      ? new URL(asset.downloadUrl, base)
      : new URL(`assets/${encodeURIComponent(asset.id)}`, base);
    if (target.origin !== base.origin) throw new Error('执行服务返回了跨域素材地址，请通过执行服务同源代理下载');
    const url = target.toString();
    const response = await withTimeout(url, { headers: { Accept: asset.mimeType } });
    if (!response.ok) throw new Error(await errorMessage(response));
    const declaredBytes = Number(response.headers.get('Content-Length') ?? 0);
    if (declaredBytes > 256 * 1024 * 1024) throw new Error('执行服务返回的单个素材超过 256 MB');
    const blob = await response.blob();
    if (blob.size > 256 * 1024 * 1024) throw new Error('执行服务返回的单个素材超过 256 MB');
    const mimeType = blob.type || asset.mimeType;
    if (!['image/png', 'image/jpeg', 'image/webp', 'video/mp4', 'video/webm', 'video/quicktime'].includes(mimeType)) {
      throw new Error(`执行服务返回了不受支持的素材格式：${mimeType || '未知'}`);
    }
    return new File([blob], asset.name, { type: mimeType });
  }

  async sendToolResult(jobId: string, result: import('../types').AgentToolResult) {
    const response = await withTimeout(`${this.baseUrl}/jobs/${encodeURIComponent(jobId)}/tool-results`, {
      method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: JSON.stringify(result),
    });
    if (!response.ok) throw new Error(await errorMessage(response));
  }

  async listConversations(projectId: string) {
    const response = await withTimeout(`${this.baseUrl}/conversations?projectId=${encodeURIComponent(projectId)}`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) throw new Error(await errorMessage(response));
    return await response.json() as Array<{ id: string; title: string; status: string; updatedAt: string }>;
  }

  async getConversation(projectId: string) {
    const response = await withTimeout(`${this.baseUrl}/conversations`, {
      method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: JSON.stringify({ projectId }),
    });
    if (!response.ok) throw new Error(await errorMessage(response));
    return await response.json();
  }

  async getConversationMessages(conversationId: string) {
    const response = await withTimeout(`${this.baseUrl}/conversations/${encodeURIComponent(conversationId)}/messages`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) throw new Error(await errorMessage(response));
    return await response.json();
  }
}

export const jobAdapter: JobAdapter = new HttpJobAdapter(endpoint);
