import { FFmpeg } from '@ffmpeg/ffmpeg';
import { fetchFile } from '@ffmpeg/util';
import coreURL from '@ffmpeg/core?url';
import wasmURL from '@ffmpeg/core/wasm?url';
import { createId } from '../utils/id';
import {
  buildVideoProcessCommand,
  type VideoColorParams,
  type VideoFramePosition,
  type VideoProcessCommandRequest,
} from '../canvas/videoProcessing';

export interface VideoProcessingEngine {
  writeFile: (name: string, data: Blob | Uint8Array) => Promise<unknown>;
  exec: (
    args: string[],
    options?: {
      signal?: AbortSignal;
      onProgress?: (progress: number) => void;
    },
  ) => Promise<unknown>;
  readFile: (name: string) => Promise<Uint8Array | string>;
  deleteFile: (name: string) => Promise<unknown>;
  probe: (inputName: string) => Promise<string>;
}

export interface VideoProcessingRuntime {
  createId: () => string;
  getEngine: () => Promise<VideoProcessingEngine>;
  runExclusive: <T>(task: () => Promise<T>) => Promise<T>;
}

export interface VideoClipInput {
  source: Blob;
  fileName: string;
  width: number;
  height: number;
  fps: number;
  hasAudio: boolean;
  durationSeconds: number;
}

export type VideoProcessRequest =
  | {
      type: 'extractFrame';
      source: Blob;
      fileName: string;
      position: VideoFramePosition;
      durationSeconds?: number;
    }
  | {
      type: 'flip';
      source: Blob;
      fileName: string;
    }
  | {
      type: 'color';
      source: Blob;
      fileName: string;
      params: VideoColorParams;
    }
  | {
      type: 'speed';
      source: Blob;
      fileName: string;
      factor: number;
    }
  | {
      type: 'merge';
      clips: VideoClipInput[];
    };

export interface VideoProcessResult {
  blob: Blob;
  fileName: string;
}

export interface VideoProcessOptions {
  runtime?: VideoProcessingRuntime;
  signal?: AbortSignal;
  onProgress?: (progress: number) => void;
}

let sharedEngine: FFmpeg | null = null;
let sharedEnginePromise: Promise<FFmpeg> | null = null;
let exclusiveQueue: Promise<unknown> = Promise.resolve();

const getSharedEngine = () => {
  if (!sharedEngine) {
    const instance = new FFmpeg();
    sharedEngine = instance;
    sharedEnginePromise = instance.load({ coreURL, wasmURL })
      .then(() => instance)
      .catch((error) => {
        releaseSharedEngine();
        throw error;
      });
  }
  return sharedEnginePromise!;
};

const releaseSharedEngine = () => {
  if (sharedEngine) {
    try {
      sharedEngine.terminate();
    } catch {
      // The worker may already be gone after a cancelled core call.
    }
  }
  sharedEngine = null;
  sharedEnginePromise = null;
};

const actualRuntime: VideoProcessingRuntime = {
  createId,
  getEngine: async () => {
    const ffmpeg = await getSharedEngine();
    return {
      writeFile: async (name, data) => {
        await ffmpeg.writeFile(name, data instanceof Blob ? await fetchFile(data) : data);
      },
      exec: async (args, options) => {
        const onProgress = (event: { progress: number }) => options?.onProgress?.(event.progress);
        const logs: string[] = [];
        const onLog = (event: { message: string }) => {
          logs.push(event.message);
          if (logs.length > 24) logs.shift();
        };
        const abort = () => releaseSharedEngine();
        ffmpeg.on('progress', onProgress);
        ffmpeg.on('log', onLog);
        options?.signal?.addEventListener('abort', abort, { once: true });
        try {
          const code = await ffmpeg.exec(args, -1, { signal: options?.signal });
          if (options?.signal?.aborted) throw new DOMException('视频处理已取消', 'AbortError');
          if (code !== 0) throw new Error(`FFmpeg 处理失败（退出码 ${code}）`);
        } catch (error) {
          if (options?.signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
            throw error;
          }
          const detail = logs
            .filter(Boolean)
            .filter((line) => !/^\s*$/.test(line))
            .slice(-4)
            .join(' ');
          const message = error instanceof Error ? error.message : String(error || '');
          throw new Error(`FFmpeg 处理失败${detail ? `：${detail}` : `：${message || '未知错误'}`}`);
        } finally {
          ffmpeg.off('progress', onProgress);
          ffmpeg.off('log', onLog);
          options?.signal?.removeEventListener('abort', abort);
        }
      },
      readFile: async (name) => ffmpeg.readFile(name),
      deleteFile: async (name) => {
        await ffmpeg.deleteFile(name).catch(() => false);
      },
      probe: async (inputName) => {
        const logs: string[] = [];
        const onLog = (event: { message: string }) => {
          logs.push(event.message);
          if (logs.length > 80) logs.shift();
        };
        ffmpeg.on('log', onLog);
        try {
          await ffmpeg.exec(['-hide_banner', '-i', inputName]).catch(() => undefined);
          const text = logs.join('\n');
          if (!text.trim()) throw new Error('无法读取视频元数据');
          return text;
        } finally {
          ffmpeg.off('log', onLog);
        }
      },
    };
  },
  runExclusive: <T>(task: () => Promise<T>) => {
    const run = exclusiveQueue.then(task, task);
    exclusiveQueue = run.then(() => undefined, () => undefined);
    return run;
  },
};

const extensionOf = (fileName: string, fallback: string) => {
  const match = /\.([a-z0-9]{1,8})$/i.exec(fileName);
  return match ? match[1].toLowerCase() : fallback;
};

const inputExtension = (source: Blob, fileName: string) => {
  const mime = source.type.toLowerCase().split(';')[0];
  if (mime === 'video/webm') return 'webm';
  if (mime === 'video/quicktime') return 'mov';
  if (mime === 'video/mp4') return 'mp4';
  return extensionOf(fileName, 'mp4');
};

const outputNameFor = (request: VideoProcessRequest, id: string) => {
  if (request.type === 'extractFrame') return `frame-${id}.png`;
  return `${request.type}-${id}.mp4`;
};

export interface VideoProbeResult {
  width: number;
  height: number;
  fps: number;
  durationSeconds: number;
  hasAudio: boolean;
}

const parseProbeLogs = (logs: string): VideoProbeResult => {
  const videoLine = logs.split(/\r?\n/).find((line) => /Stream #\d+:\d+.*Video:/.test(line));
  if (!videoLine) {
    const detail = logs.split(/\r?\n/).filter(Boolean).slice(-6).join(' ');
    throw new Error(`视频没有有效画面${detail ? `：${detail}` : ''}`);
  }
  const dimensions = /(\d{2,5})x(\d{2,5})/.exec(videoLine);
  const fpsMatch = /(\d+(?:\.\d+)?)\s*(?:fps|tbr)/i.exec(videoLine);
  const durationMatch = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(logs);
  const durationSeconds = durationMatch
    ? Number(durationMatch[1]) * 3600 + Number(durationMatch[2]) * 60 + Number(durationMatch[3])
    : 0;
  const width = Number(dimensions?.[1]) || 0;
  const height = Number(dimensions?.[2]) || 0;
  if (!width || !height) throw new Error('视频没有有效尺寸');
  return {
    width,
    height,
    fps: Number(fpsMatch?.[1]) || 30,
    durationSeconds,
    hasAudio: /Stream #\d+:\d+.*Audio:/.test(logs),
  };
};

export async function probeVideoRequest(
  source: Blob,
  fileName: string,
  options: Pick<VideoProcessOptions, 'runtime' | 'signal'> = {},
): Promise<VideoProbeResult> {
  const runtime = options.runtime ?? actualRuntime;
  if (options.signal?.aborted) throw new DOMException('视频处理已取消', 'AbortError');
  return runtime.runExclusive(async () => {
    const engine = await runtime.getEngine();
    const inputName = `probe-input.${inputExtension(source, fileName)}`;
    try {
      await engine.writeFile(inputName, source);
      return parseProbeLogs(await engine.probe(inputName));
    } finally {
      await engine.deleteFile(inputName).catch(() => undefined);
    }
  });
}

const fileInputs = (request: VideoProcessRequest) => {
  if (request.type === 'merge') {
    return request.clips.map((clip, index) => ({
      source: clip.source,
      tempName: `merge-input-${index}.${inputExtension(clip.source, clip.fileName)}`,
    }));
  }
  return [{
    source: request.source,
    tempName: `input.${inputExtension(request.source, request.fileName)}`,
  }];
};

export async function processVideoRequest(
  request: VideoProcessRequest,
  options: VideoProcessOptions = {},
): Promise<VideoProcessResult> {
  const runtime = options.runtime ?? actualRuntime;
  if (options.signal?.aborted) throw new DOMException('视频处理已取消', 'AbortError');

  return runtime.runExclusive(async () => {
    if (options.signal?.aborted) throw new DOMException('视频处理已取消', 'AbortError');
    const engine = await runtime.getEngine();
    const id = runtime.createId();
    const outputName = outputNameFor(request, id);
    const inputs = fileInputs(request);
    const temporaryNames = [...inputs.map((input) => input.tempName), outputName];
    try {
      for (const input of inputs) await engine.writeFile(input.tempName, input.source);
      options.onProgress?.(0);

      const baseCommandRequest: VideoProcessCommandRequest = request.type === 'merge'
        ? {
            type: 'merge',
            clipNames: inputs.map((input) => input.tempName),
            outputName,
            width: request.clips[0]?.width ?? 1280,
            height: request.clips[0]?.height ?? 720,
            fps: request.clips[0]?.fps ?? 30,
            audio: request.clips.map((clip) => clip.hasAudio),
          }
        : {
            ...request,
            inputName: inputs[0].tempName,
            outputName,
          };
      const commandRequests: VideoProcessCommandRequest[] =
        request.type === 'extractFrame'
        && request.position.kind === 'last'
        && Number.isFinite(request.durationSeconds)
        && Number(request.durationSeconds) > 0
          ? [
              ...[0.2, 0.5, 1, 2].map((offset) => ({
                ...(baseCommandRequest as Extract<VideoProcessCommandRequest, { type: 'extractFrame' }>),
                position: {
                  kind: 'custom' as const,
                  timestampSeconds: Math.max(0, Number(request.durationSeconds) - offset),
                },
              })),
              baseCommandRequest,
            ]
          : [baseCommandRequest];

      let output: Uint8Array | undefined;
      let lastError: unknown;
      for (const commandRequest of commandRequests) {
        const command = buildVideoProcessCommand(commandRequest);
        try {
          await engine.deleteFile(outputName).catch(() => undefined);
          await engine.exec(command.args, {
            signal: options.signal,
            onProgress: (progress) => options.onProgress?.(Math.max(0, Math.min(1, progress))),
          });
          const candidate = await engine.readFile(outputName);
          if (typeof candidate === 'string') throw new Error('FFmpeg 返回了无效的文本结果');
          output = candidate;
          break;
        } catch (error) {
          if (options.signal?.aborted) throw error;
          lastError = error;
        }
      }
      if (!output) throw lastError instanceof Error ? lastError : new Error('FFmpeg 未生成有效结果');
      options.onProgress?.(1);
      return {
        blob: new Blob([new Uint8Array(output)], { type: request.type === 'extractFrame' ? 'image/png' : 'video/mp4' }),
        fileName: outputName,
      };
    } catch (error) {
      if (options.signal?.aborted) throw new DOMException('视频处理已取消', 'AbortError');
      if (error instanceof Error && /aborted|terminate/i.test(error.message)) {
        throw new DOMException('视频处理已取消', 'AbortError');
      }
      throw error;
    } finally {
      await Promise.all(temporaryNames.map((name) => engine.deleteFile(name).catch(() => undefined)));
    }
  });
}
