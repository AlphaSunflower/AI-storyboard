/**
 * 视频抽帧：浏览器端离屏 video 定位 + canvas 出图，得到 PNG，再交给调用方上传/落库。
 * 全流程不依赖后端处理服务，因此线上部署无需额外转码能力。
 */

export type VideoFramePosition = 'first' | 'last' | 'custom';

export interface VideoFrameTarget {
  position: VideoFramePosition;
  /** 仅 position === 'custom' 时生效 */
  customSeconds?: number;
}

/** 尾帧不能正好停在 duration 上（部分浏览器的最后一帧取不到），回退一帧的时长 */
const LAST_FRAME_BACKOFF_SECONDS = 0.05;

export const FRAME_POSITION_LABELS: Record<VideoFramePosition, string> = {
  first: '首帧',
  last: '尾帧',
  custom: '自定义帧',
};

export function frameLabel(position: VideoFramePosition): string {
  return FRAME_POSITION_LABELS[position];
}

/** 目标时间点：首帧 0、尾帧 duration 回退、自定义钳制在可抽帧区间内 */
export function resolveFrameTimestamp(
  position: VideoFramePosition,
  durationSeconds: number,
  customSeconds = 0,
): number {
  const duration = Number.isFinite(durationSeconds) && durationSeconds > 0 ? durationSeconds : 0;
  const safeMax = Math.max(0, duration - LAST_FRAME_BACKOFF_SECONDS);
  if (position === 'first') return 0;
  if (position === 'last') return safeMax;
  const custom = Number.isFinite(customSeconds) ? customSeconds : 0;
  return Math.min(safeMax, Math.max(0, custom));
}

export function frameFileName(label: string, extension = 'png'): string {
  const base = label
    .replace(/[\\/:*?"<>|\s]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return `${base || 'video-frame'}.${extension}`;
}

/** 对话里承载抽帧结果的 markdown 图片行 */
export function frameMessageContent(label: string, url: string): string {
  return `![${label}](${url})`;
}

const LOAD_TIMEOUT_MS = 15000;

function waitFor(video: HTMLVideoElement, event: string, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      video.removeEventListener(event, onDone);
      video.removeEventListener('error', onError);
      signal?.removeEventListener('abort', onAbort);
    };
    const onDone = () => {
      cleanup();
      resolve();
    };
    const onError = () => {
      cleanup();
      reject(new Error('视频加载失败，无法抽取帧'));
    };
    const onAbort = () => {
      cleanup();
      reject(new DOMException('已取消抽帧', 'AbortError'));
    };
    if (signal?.aborted) {
      onAbort();
      return;
    }
    video.addEventListener(event, onDone, { once: true });
    video.addEventListener('error', onError, { once: true });
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

async function seekTo(video: HTMLVideoElement, seconds: number): Promise<void> {
  if (Number.isFinite(video.currentTime) && Math.abs(video.currentTime - seconds) < 0.001 && video.readyState >= 2) {
    return;
  }
  const seeked = waitFor(video, 'seeked');
  video.currentTime = seconds;
  await seeked;
}

function prepareVideo(fileUrl: string): HTMLVideoElement {
  const video = document.createElement('video');
  video.preload = 'auto';
  video.muted = true;
  video.playsInline = true;
  const crossOrigin = (() => {
    try {
      return new URL(fileUrl, window.location.href).origin === window.location.origin ? null : 'anonymous';
    } catch {
      return null;
    }
  })();
  if (crossOrigin) video.crossOrigin = crossOrigin;
  return video;
}

/** 只读取元数据拿视频时长（自定义帧滑块的取值范围） */
export async function probeVideoDurationSeconds(fileUrl: string, signal?: AbortSignal): Promise<number> {
  const video = prepareVideo(fileUrl);
  let timer = 0;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = window.setTimeout(
      () => reject(new Error('视频加载超时，无法抽取帧')),
      LOAD_TIMEOUT_MS,
    );
  });
  try {
    video.src = fileUrl;
    video.load();
    await Promise.race([waitFor(video, 'loadedmetadata', signal), timeout]);
    return Number.isFinite(video.duration) ? video.duration : 0;
  } finally {
    window.clearTimeout(timer);
    video.removeAttribute('src');
    video.load();
  }
}

/**
 * 抽取目标帧为 PNG。首帧/尾帧由时长推导，自定义帧钳制在可抽帧区间内。
 * 跨源媒体必须显式带 crossOrigin，否则画布被污染后 toBlob 会抛 SecurityError。
 */
export async function extractVideoFrame(
  fileUrl: string,
  target: VideoFrameTarget,
  signal?: AbortSignal,
): Promise<Blob> {
  const video = prepareVideo(fileUrl);
  let timer = 0;
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = window.setTimeout(
      () => reject(new Error('视频加载超时，无法抽取帧')),
      LOAD_TIMEOUT_MS,
    );
  });
  try {
    video.src = fileUrl;
    video.load();
    await Promise.race([waitFor(video, 'loadeddata', signal), timeout]);
    const duration = Number.isFinite(video.duration) ? video.duration : 0;
    await seekTo(video, resolveFrameTimestamp(target.position, duration, target.customSeconds));
    const width = video.videoWidth;
    const height = video.videoHeight;
    if (!width || !height) throw new Error('视频尺寸不可用，无法抽取帧');
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('当前环境不支持画布绘图，无法抽取帧');
    context.drawImage(video, 0, 0, width, height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'));
    if (!blob) throw new Error('抽取帧失败');
    return blob;
  } finally {
    window.clearTimeout(timer);
    video.removeAttribute('src');
    video.load();
  }
}
