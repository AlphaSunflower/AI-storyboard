import type { GenerationRecord } from '../types';

export interface StoryboardPlanButtonState {
  busy: boolean;
  disabled: boolean;
  label: string;
  progress: number;
}

export function resolveStoryboardPlanButton(
  record: GenerationRecord | null | undefined,
  submitting: boolean,
  hasScript: boolean,
): StoryboardPlanButtonState {
  if (submitting) {
    return { busy: true, disabled: true, label: '提交中…', progress: 0 };
  }
  if (record?.status === 'queued') {
    return { busy: true, disabled: true, label: '排队中…', progress: 0 };
  }
  if (record?.status === 'running') {
    const progress = Math.round(Math.max(0, Math.min(1, record.progress ?? 0)) * 100);
    return {
      busy: true,
      disabled: true,
      label: progress > 0 ? `生成中 ${progress}%` : '生成中…',
      progress,
    };
  }
  const label = record?.status === 'completed'
    ? '重新生成分镜'
    : record?.status === 'failed' || record?.status === 'interrupted'
      ? '重试生成分镜'
      : '生成结构化分镜';
  return { busy: false, disabled: !hasScript, label, progress: 0 };
}
