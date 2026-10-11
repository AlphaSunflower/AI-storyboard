import { describe, expect, it } from 'vitest';
import { shouldHidePendingCardMessage } from './pendingCardMessage';

describe('shouldHidePendingCardMessage', () => {
  it('hides an empty pending HITL placeholder', () => {
    expect(shouldHidePendingCardMessage({
      content: '',
      metadata: {
        kind: 'hitl',
        hitl: {
          formContent: '请确认方案',
          actions: [],
          pending: true,
        },
      },
    })).toBe(true);
  });

  it('hides a pending card body that only duplicates the card copy', () => {
    expect(shouldHidePendingCardMessage({
      content: '请确认方案',
      metadata: {
        kind: 'hitl',
        hitl: {
          formContent: '请确认方案',
          actions: [],
          pending: true,
        },
      },
    })).toBe(true);
    expect(shouldHidePendingCardMessage({
      content: '视频方案正文',
      metadata: {
        kind: 'video-plan',
        hitl: { formContent: '视频方案正文', actions: [], pending: true },
        videoPlan: {
          message: '视频方案正文',
          duration: 8,
          actions: [],
        },
      },
    })).toBe(true);
  });

  it('keeps independent plan text above the pending card', () => {
    expect(shouldHidePendingCardMessage({
      content: '## 独立的方案正文',
      metadata: {
        kind: 'hitl',
        hitl: {
          formContent: '请选择方案',
          actions: [],
          pending: true,
        },
      },
    })).toBe(false);
  });
});
