import { describe, expect, it } from 'vitest';
import {
  buildMergeFilterGraph,
  buildVideoProcessCommand,
  formatFrameTimestamp,
  normalizeVideoColorParams,
  parseFrameTimestamp,
} from './videoProcessing';

describe('video frame timestamps', () => {
  it('accepts decimal seconds and minute-second notation', () => {
    expect(parseFrameTimestamp('5')).toBe(5);
    expect(parseFrameTimestamp('5.25')).toBe(5.25);
    expect(parseFrameTimestamp('01:05.5')).toBe(65.5);
    expect(parseFrameTimestamp(' 00:05 ')).toBe(5);
  });

  it('rejects malformed or out-of-range timestamps', () => {
    expect(() => parseFrameTimestamp('-1')).toThrow('时间不能小于 0');
    expect(() => parseFrameTimestamp('1:60')).toThrow('秒数必须小于 60');
    expect(() => parseFrameTimestamp('abc')).toThrow('时间格式');
    expect(() => parseFrameTimestamp('11', 10)).toThrow('不能超过视频时长');
  });

  it('formats a canonical frame label', () => {
    expect(formatFrameTimestamp(0)).toBe('00:00.0');
    expect(formatFrameTimestamp(5.24)).toBe('00:05.2');
    expect(formatFrameTimestamp(65.96)).toBe('01:06.0');
  });
});

describe('video command filters', () => {
  it('builds first, last, and exact frame extraction commands', () => {
    expect(buildVideoProcessCommand({
      type: 'extractFrame',
      inputName: 'input.mp4',
      outputName: 'first.png',
      position: { kind: 'first' },
    }).args.join(' ')).toContain('-ss 0');

    expect(buildVideoProcessCommand({
      type: 'extractFrame',
      inputName: 'input.mp4',
      outputName: 'last.png',
      position: { kind: 'last' },
      durationSeconds: 10,
    }).args.join(' ')).toContain('-ss 9.8');

    const exact = buildVideoProcessCommand({
      type: 'extractFrame',
      inputName: 'input.mp4',
      outputName: 'frame.png',
      position: { kind: 'custom', timestampSeconds: 5.25 },
    });
    expect(exact.args.join(' ')).toContain('-ss 5.25');
    expect(exact.args).toContain('-frames:v');
  });

  it('normalizes and clamps color parameters', () => {
    expect(normalizeVideoColorParams({
      brightness: 120,
      contrast: -120,
      saturation: 35,
      temperature: -200,
      hue: 240,
    })).toEqual({
      brightness: 100,
      contrast: -100,
      saturation: 35,
      temperature: -100,
      hue: 180,
    });
  });

  it('builds a horizontal flip command while retaining audio', () => {
    const command = buildVideoProcessCommand({
      type: 'flip',
      inputName: 'input.webm',
      outputName: 'flipped.mp4',
    });
    const args = command.args.join(' ');
    expect(args).toContain('hflip');
    expect(args).toContain('-c:a copy');
    expect(args).toContain('libx264');
  });

  it('builds all five color filters', () => {
    const command = buildVideoProcessCommand({
      type: 'color',
      inputName: 'input.mp4',
      outputName: 'color.mp4',
      params: {
        brightness: 20,
        contrast: 10,
        saturation: -15,
        temperature: 25,
        hue: 30,
      },
    });
    const args = command.args.join(' ');
    expect(args).toContain('eq=brightness=');
    expect(args).toContain('hue=h=30');
    expect(args).toContain('colorbalance=');
    expect(args).toContain('libx264');
  });

  it('preserves audio pitch while changing speed', () => {
    const command = buildVideoProcessCommand({
      type: 'speed',
      inputName: 'input.mp4',
      outputName: 'speed.mp4',
      factor: 1.2,
    });
    const args = command.args.join(' ');
    expect(args).toContain('setpts=PTS/1.2');
    expect(args).toContain('atempo=1.2');
    expect(args).toContain('-c:a aac');
  });

  it('builds a merge filter with resolution normalization and a silent audio source', () => {
    const graph = buildMergeFilterGraph([
      { hasAudio: true },
      { hasAudio: false },
    ], 1920, 1080, 30);

    expect(graph).toContain('scale=1920:1080:force_original_aspect_ratio=decrease');
    expect(graph).toContain('pad=1920:1080');
    expect(graph).toContain('fps=30');
    expect(graph).toContain('anullsrc=channel_layout=stereo:sample_rate=48000');
    expect(graph).toContain('concat=n=2:v=1:a=1');
  });

  it('builds one input stream per merge clip', () => {
    const command = buildVideoProcessCommand({
      type: 'merge',
      clipNames: ['a.mp4', 'b.webm', 'c.mov'],
      outputName: 'merged.mp4',
      width: 1280,
      height: 720,
      fps: 24,
      audio: [true, false, true],
    });
    expect(command.args.filter((item) => item === '-i')).toHaveLength(3);
    expect(command.args.join(' ')).toContain('concat=n=3:v=1:a=1');
  });
});
