import { describe, expect, it, vi } from 'vitest';
import {
  probeVideoRequest,
  processVideoRequest,
  type VideoProcessingEngine,
  type VideoProcessingRuntime,
} from './videoProcessingService';

const createHarness = (output = new Uint8Array([1, 2, 3])) => {
  const files = new Map<string, Uint8Array>();
  const calls: string[][] = [];
  const probeCalls: string[] = [];
  const deleted: string[] = [];
  const engine: VideoProcessingEngine = {
    writeFile: vi.fn(async (name, data) => { files.set(name, data); }),
    exec: vi.fn(async (args) => {
      calls.push(args);
      files.set(args.at(-1)!, output);
    }),
    readFile: vi.fn(async (name) => {
      if (!files.has(name)) throw new Error(`missing ${name}`);
      return files.get(name)!;
    }),
    probe: vi.fn(async (args) => {
      probeCalls.push(args);
      return [
        'Input #0, mov,mp4,m4a,3gp,3g2,mj2, from \'probe-input.mp4\':',
        '  Duration: 00:00:12.50, start: 0.000000, bitrate: 1000 kb/s',
        '  Stream #0:0: Video: h264, yuv420p, 1920x1080, 29.97 fps, 29.97 tbr',
        '  Stream #0:1: Audio: aac, 48000 Hz, stereo, fltp',
      ].join('\n');
    }),
    deleteFile: vi.fn(async (name) => { deleted.push(name); files.delete(name); }),
  };
  const runtime: VideoProcessingRuntime = {
    createId: () => 'test-id',
    getEngine: async () => engine,
    runExclusive: async (task) => task(),
  };
  return { runtime, engine, calls, probeCalls, deleted };
};

describe('video processing service', () => {
  it('writes the input, runs the command, reads the result, and cleans temporary files', async () => {
    const harness = createHarness();
    const progress = vi.fn();

    const result = await processVideoRequest({
      type: 'flip',
      source: new Blob(['video'], { type: 'video/mp4' }),
      fileName: 'source.mp4',
    }, {
      runtime: harness.runtime,
      onProgress: progress,
    });

    expect(harness.calls).toHaveLength(1);
    expect(harness.calls[0].join(' ')).toContain('hflip');
    expect(harness.deleted).toHaveLength(3);
    expect(result.blob.type).toBe('video/mp4');
    expect(await result.blob.arrayBuffer()).toEqual(new Uint8Array([1, 2, 3]).buffer);
    expect(progress).toHaveBeenCalledWith(0);
    expect(progress).toHaveBeenCalledWith(1);
  });

  it('returns PNG output for frame extraction', async () => {
    const harness = createHarness(new Uint8Array([9, 8]));
    const result = await processVideoRequest({
      type: 'extractFrame',
      source: new Blob(['video'], { type: 'video/webm' }),
      fileName: 'source.webm',
      position: { kind: 'first' },
    }, { runtime: harness.runtime });

    expect(result.blob.type).toBe('image/png');
    expect(harness.calls[0].join(' ')).toContain('-frames:v 1');
  });

  it('reads video dimensions, frame rate, duration, and audio presence', async () => {
    const harness = createHarness();
    const metadata = await probeVideoRequest(
      new Blob(['video'], { type: 'video/mp4' }),
      'source.mp4',
      { runtime: harness.runtime },
    );

    expect(metadata).toMatchObject({
      width: 1920,
      height: 1080,
      fps: 29.97,
      durationSeconds: 12.5,
      hasAudio: true,
    });
    expect(harness.deleted).toHaveLength(1);
  });

  it('uses the blob mime type when a stale filename has the wrong extension', async () => {
    const harness = createHarness();
    await probeVideoRequest(
      new Blob(['video'], { type: 'video/webm' }),
      'stale-name.mp4',
      { runtime: harness.runtime },
    );

    expect(harness.probeCalls[0]).toBe('probe-input.webm');
  });

  it('retries tail extraction farther from the end when the first seek has no frame', async () => {
    const harness = createHarness();
    let attempt = 0;
    harness.engine.exec = vi.fn(async (args) => {
      attempt += 1;
      harness.calls.push(args);
      if (attempt > 1) harness.engine.writeFile(args.at(-1)!, new Uint8Array([7, 7]));
    });

    const result = await processVideoRequest({
      type: 'extractFrame',
      source: new Blob(['video'], { type: 'video/mp4' }),
      fileName: 'source.mp4',
      position: { kind: 'last' },
      durationSeconds: 10,
    }, { runtime: harness.runtime });

    expect(harness.calls).toHaveLength(2);
    expect(harness.calls[0].join(' ')).toContain('-ss 9.8');
    expect(harness.calls[1].join(' ')).toContain('-ss 9.5');
    expect(result.blob.size).toBe(2);
  });

  it('cleans temporary files and preserves the cancellation error', async () => {
    const harness = createHarness();
    harness.engine.exec = vi.fn(async () => {
      throw new DOMException('cancelled', 'AbortError');
    });

    await expect(processVideoRequest({
      type: 'speed',
      source: new Blob(['video'], { type: 'video/mp4' }),
      fileName: 'source.mp4',
      factor: 1.2,
    }, { runtime: harness.runtime })).rejects.toMatchObject({ name: 'AbortError' });
    expect(harness.deleted).toHaveLength(3);
  });
});
