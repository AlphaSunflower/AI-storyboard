import { describe, expect, it } from 'vitest';
import { frameFileName, frameLabel, frameMessageContent, resolveFrameTimestamp } from './videoFrameExtract';

describe('resolveFrameTimestamp', () => {
  it('takes the first frame at zero', () => {
    expect(resolveFrameTimestamp('first', 12)).toBe(0);
  });

  it('backs off the last frame from the duration', () => {
    expect(resolveFrameTimestamp('last', 12)).toBeCloseTo(11.95);
  });

  it('clamps a custom position into the seekable range', () => {
    expect(resolveFrameTimestamp('custom', 10, 4)).toBe(4);
    expect(resolveFrameTimestamp('custom', 10, 99)).toBeCloseTo(9.95);
    expect(resolveFrameTimestamp('custom', 10, -3)).toBe(0);
    expect(resolveFrameTimestamp('custom', 10, Number.NaN)).toBe(0);
  });

  it('degrades to zero when the duration is unknown', () => {
    expect(resolveFrameTimestamp('last', Number.NaN)).toBe(0);
    expect(resolveFrameTimestamp('custom', 0, 5)).toBe(0);
  });
});

describe('frameLabel / frameFileName / frameMessageContent', () => {
  it('labels the three positions in Chinese', () => {
    expect(frameLabel('first')).toBe('首帧');
    expect(frameLabel('last')).toBe('尾帧');
    expect(frameLabel('custom')).toBe('自定义帧');
  });

  it('builds a safe png file name', () => {
    expect(frameFileName('首帧')).toBe('首帧.png');
    expect(frameFileName('a/b:c')).toBe('a-b-c.png');
    expect(frameFileName('   ')).toBe('video-frame.png');
  });

  it('wraps the extracted frame as a markdown image line', () => {
    expect(frameMessageContent('尾帧', '/api/files/images/frame.png'))
      .toBe('![尾帧](/api/files/images/frame.png)');
  });
});
