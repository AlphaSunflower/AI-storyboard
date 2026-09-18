export interface VideoColorParams {
  brightness: number;
  contrast: number;
  saturation: number;
  temperature: number;
  hue: number;
}

export type VideoFramePosition =
  | { kind: 'first' }
  | { kind: 'last' }
  | { kind: 'custom'; timestampSeconds: number };

export type VideoProcessCommandRequest =
  | {
      type: 'extractFrame';
      inputName: string;
      outputName: string;
      position: VideoFramePosition;
      durationSeconds?: number;
    }
  | {
      type: 'flip';
      inputName: string;
      outputName: string;
    }
  | {
      type: 'color';
      inputName: string;
      outputName: string;
      params: VideoColorParams;
    }
  | {
      type: 'speed';
      inputName: string;
      outputName: string;
      factor: number;
    }
  | {
      type: 'merge';
      clipNames: string[];
      outputName: string;
      width: number;
      height: number;
      fps: number;
      audio: boolean[];
    };

export interface VideoProcessCommand {
  args: string[];
  outputName: string;
}

export const EMPTY_VIDEO_COLOR_PARAMS: VideoColorParams = {
  brightness: 0,
  contrast: 0,
  saturation: 0,
  temperature: 0,
  hue: 0,
};

const clamp = (value: number, min: number, max: number) =>
  Math.max(min, Math.min(max, Number.isFinite(value) ? value : 0));

const decimal = (value: number, precision = 4) =>
  Number(value.toFixed(precision)).toString();

export function parseFrameTimestamp(input: string, durationSeconds?: number): number {
  const value = input.trim();
  if (!value) throw new Error('请输入时间');
  if (value.startsWith('-')) throw new Error('时间不能小于 0');

  let seconds: number;
  if (value.includes(':')) {
    const parts = value.split(':');
    if (parts.length !== 2) throw new Error('时间格式应为 mm:ss.s 或秒数');
    const minutes = Number(parts[0]);
    const remainder = Number(parts[1]);
    if (!Number.isInteger(minutes) || minutes < 0 || !Number.isFinite(remainder)) {
      throw new Error('时间格式应为 mm:ss.s 或秒数');
    }
    if (remainder < 0 || remainder >= 60) throw new Error('秒数必须小于 60');
    seconds = minutes * 60 + remainder;
  } else {
    seconds = Number(value);
    if (!Number.isFinite(seconds)) throw new Error('时间格式应为 mm:ss.s 或秒数');
  }

  if (seconds < 0) throw new Error('时间不能小于 0');
  if (durationSeconds !== undefined && Number.isFinite(durationSeconds) && seconds > durationSeconds + 1e-6) {
    throw new Error('指定时间不能超过视频时长');
  }
  return Number(seconds.toFixed(3));
}

export function formatFrameTimestamp(seconds: number): string {
  const safe = Math.max(0, Number.isFinite(seconds) ? seconds : 0);
  const rounded = Math.round(safe * 10) / 10;
  const minutes = Math.floor(rounded / 60);
  const remainder = rounded - minutes * 60;
  return `${String(minutes).padStart(2, '0')}:${remainder.toFixed(1).padStart(4, '0')}`;
}

export function normalizeVideoColorParams(params: VideoColorParams): VideoColorParams {
  return {
    brightness: clamp(params.brightness, -100, 100),
    contrast: clamp(params.contrast, -100, 100),
    saturation: clamp(params.saturation, -100, 100),
    temperature: clamp(params.temperature, -100, 100),
    hue: clamp(params.hue, -180, 180),
  };
}

export function colorVideoFilter(input: VideoColorParams): string {
  const params = normalizeVideoColorParams(input);
  const brightness = params.brightness / 100;
  const contrast = 1 + params.contrast / 100;
  const saturation = 1 + params.saturation / 100;
  const temperature = params.temperature / 100;
  const red = temperature * 0.3;
  const green = temperature * 0.08;
  const blue = temperature * -0.3;
  return [
    `eq=brightness=${decimal(brightness)}:contrast=${decimal(contrast)}:saturation=${decimal(saturation)}`,
    `hue=h=${decimal(params.hue)}`,
    `colorbalance=rs=${decimal(red)}:gs=${decimal(green)}:bs=${decimal(blue)}`,
  ].join(',');
}

export function buildMergeFilterGraph(
  clips: Array<{ hasAudio: boolean; durationSeconds?: number }>,
  width: number,
  height: number,
  fps: number,
): string {
  const safeWidth = Math.max(2, Math.round(width / 2) * 2);
  const safeHeight = Math.max(2, Math.round(height / 2) * 2);
  const safeFps = Math.max(1, Math.min(120, Number.isFinite(fps) ? fps : 30));
  const chains = clips.flatMap((clip, index) => {
    const duration = Math.max(0.001, clip.durationSeconds ?? 1);
    const video = `[${index}:v]scale=${safeWidth}:${safeHeight}:force_original_aspect_ratio=decrease,pad=${safeWidth}:${safeHeight}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=${decimal(safeFps)}[v${index}]`;
    const audio = clip.hasAudio
      ? `[${index}:a]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[a${index}]`
      : `anullsrc=channel_layout=stereo:sample_rate=48000,atrim=duration=${decimal(duration, 3)}[a${index}]`;
    return [video, audio];
  });
  const concat = `${clips.map((_, index) => `[v${index}][a${index}]`).join('')}concat=n=${clips.length}:v=1:a=1[vout][aout]`;
  return [...chains, concat].join(';');
}

export function buildVideoProcessCommand(request: VideoProcessCommandRequest): VideoProcessCommand {
  if (request.type === 'extractFrame') {
    if (request.position.kind === 'last') {
      const durationSeconds = Number(request.durationSeconds);
      const seekSeconds = Number.isFinite(durationSeconds) && durationSeconds > 0
        ? Math.max(0, durationSeconds - 0.2)
        : null;
      return {
        outputName: request.outputName,
        args: seekSeconds === null
          ? [
              '-sseof', '-0.2',
              '-i', request.inputName,
              '-frames:v', '1',
              '-an',
              '-y', request.outputName,
            ]
          : [
              '-ss', decimal(seekSeconds, 3),
              '-i', request.inputName,
              '-frames:v', '1',
              '-an',
              '-y', request.outputName,
            ],
      };
    }
    const timestamp = request.position.kind === 'first' ? 0 : request.position.timestampSeconds;
    return {
      outputName: request.outputName,
      args: [
        '-i', request.inputName,
        '-ss', decimal(timestamp, 3),
        '-frames:v', '1',
        '-an',
        '-y', request.outputName,
      ],
    };
  }

  if (request.type === 'flip') {
    return {
      outputName: request.outputName,
      args: [
        '-i', request.inputName,
        '-vf', 'hflip',
        '-map', '0:v:0',
        '-map', '0:a?',
        '-c:v', 'libx264',
        '-preset', 'veryfast',
        '-crf', '20',
        '-pix_fmt', 'yuv420p',
        '-c:a', 'copy',
        '-movflags', '+faststart',
        '-y', request.outputName,
      ],
    };
  }

  if (request.type === 'color') {
    return {
      outputName: request.outputName,
      args: [
        '-i', request.inputName,
        '-vf', colorVideoFilter(request.params),
        '-map', '0:v:0',
        '-map', '0:a?',
        '-c:v', 'libx264',
        '-preset', 'veryfast',
        '-crf', '20',
        '-pix_fmt', 'yuv420p',
        '-c:a', 'copy',
        '-movflags', '+faststart',
        '-y', request.outputName,
      ],
    };
  }

  if (request.type === 'speed') {
    const factor = clamp(request.factor, 0.5, 2);
    return {
      outputName: request.outputName,
      args: [
        '-i', request.inputName,
        '-vf', `setpts=PTS/${decimal(factor)}`,
        '-map', '0:v:0',
        '-map', '0:a?',
        '-c:v', 'libx264',
        '-preset', 'veryfast',
        '-crf', '20',
        '-pix_fmt', 'yuv420p',
        '-af', `atempo=${decimal(factor)}`,
        '-c:a', 'aac',
        '-b:a', '192k',
        '-movflags', '+faststart',
        '-y', request.outputName,
      ],
    };
  }

  const clips = request.clipNames.map((_, index) => ({
    hasAudio: request.audio[index] ?? false,
  }));
  const filter = buildMergeFilterGraph(clips, request.width, request.height, request.fps);
  return {
    outputName: request.outputName,
    args: [
      ...request.clipNames.flatMap((name) => ['-i', name]),
      '-filter_complex', filter,
      '-map', '[vout]',
      '-map', '[aout]',
      '-c:v', 'libx264',
      '-preset', 'veryfast',
      '-crf', '20',
      '-pix_fmt', 'yuv420p',
      '-c:a', 'aac',
      '-b:a', '192k',
      '-movflags', '+faststart',
      '-y', request.outputName,
    ],
  };
}
