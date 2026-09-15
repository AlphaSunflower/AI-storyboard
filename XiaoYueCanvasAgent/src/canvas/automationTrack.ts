import type { AutomationPhase } from './graphContracts';

export interface AutomationTrackInput {
  mode: 'manual' | 'auto';
  dependenciesReady: boolean;
  configFingerprint: string;
  runFingerprint?: string;
  paused: boolean;
  phase: AutomationPhase;
}

export interface AutomationTrackDecision {
  phase: AutomationPhase;
  shouldStart: boolean;
}

export function evaluateAutomationTrack(input: AutomationTrackInput): AutomationTrackDecision {
  if (input.phase === 'running' || input.phase === 'queued') {
    return { phase: input.phase, shouldStart: false };
  }
  if (input.phase === 'done' && input.runFingerprint === input.configFingerprint) {
    return { phase: 'done', shouldStart: false };
  }
  if (!input.dependenciesReady) {
    return { phase: 'idle', shouldStart: false };
  }
  if (input.paused) {
    return { phase: 'paused', shouldStart: false };
  }
  if (input.runFingerprint === input.configFingerprint) {
    return { phase: input.phase === 'failed' ? 'failed' : 'done', shouldStart: false };
  }
  return {
    phase: 'ready',
    shouldStart: input.mode === 'auto',
  };
}
