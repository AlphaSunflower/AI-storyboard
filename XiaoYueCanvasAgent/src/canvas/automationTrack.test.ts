import { describe, expect, it } from 'vitest';
import { evaluateAutomationTrack } from './automationTrack';

const base = {
  mode: 'auto' as const,
  dependenciesReady: true,
  configFingerprint: 'config-1',
  runFingerprint: 'config-1',
  paused: false,
  phase: 'idle' as const,
};

describe('evaluateAutomationTrack', () => {
  it('does not run the same configuration twice', () => {
    expect(evaluateAutomationTrack({ ...base, phase: 'done' })).toMatchObject({
      phase: 'done',
      shouldStart: false,
    });
  });

  it('waits for dependencies before becoming ready', () => {
    expect(evaluateAutomationTrack({ ...base, dependenciesReady: false })).toMatchObject({
      phase: 'idle',
      shouldStart: false,
    });
    expect(evaluateAutomationTrack({ ...base, runFingerprint: undefined })).toMatchObject({
      phase: 'ready',
      shouldStart: true,
    });
  });

  it('honors manual mode and pause points', () => {
    expect(evaluateAutomationTrack({ ...base, mode: 'manual', runFingerprint: undefined })).toMatchObject({
      phase: 'ready',
      shouldStart: false,
    });
    expect(evaluateAutomationTrack({ ...base, paused: true, runFingerprint: undefined })).toMatchObject({
      phase: 'paused',
      shouldStart: false,
    });
  });

  it('marks stale before starting a changed downstream configuration', () => {
    expect(evaluateAutomationTrack({ ...base, phase: 'stale', runFingerprint: undefined })).toMatchObject({
      phase: 'ready',
      shouldStart: true,
    });
  });
});
