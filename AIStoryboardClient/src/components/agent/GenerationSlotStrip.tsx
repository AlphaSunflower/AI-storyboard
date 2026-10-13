import { useMemo } from 'react';
import { ChevronRight } from 'lucide-react';
import type { AgentTaskSlotView, AgentTaskView } from '../../api/agent';
import { useAgentStore } from '../../stores/agentStore';
import { assetUrl } from '../../config';
import { RefineFrame, type RefineFrameStatus } from '../common/RefineFrame';
import { ChatMediaActions } from './ChatMediaActions';
import './GenerationSlotStrip.css';

const SLOT_WIDTH = 200;
const VIDEO_SLOT_WIDTH = 288;

interface SlotFrame {
  key: string;
  status: RefineFrameStatus;
  label: string;
  image?: string;
  video?: string;
  width: number;
  aspectRatio: string;
  onRetry?: () => void;
}

function slotStatus(slot: AgentTaskSlotView): RefineFrameStatus {
  if (slot.status === 'completed') return 'complete';
  if (slot.status === 'failed' || slot.status === 'cancelled') return 'error';
  if (slot.status === 'running') return 'generating';
  return 'queued';
}

function slotLabel(status: RefineFrameStatus, progress: number): string {
  if (status === 'error') return '失败';
  if (status === 'complete') return '已完成';
  if (status === 'generating') return progress > 0 ? `生成中 ${progress}%` : '生成中';
  return '排队中';
}

function slotFrames(task: AgentTaskView, retry: () => void): SlotFrame[] {
  const progress = Math.max(0, Math.min(100, Math.round(task.progress ?? 0)));
  const onRetry = task.canRetry ? retry : undefined;

  if (task.slots?.length) {
    return task.slots.map((slot) => {
      const status = slotStatus(slot);
      return {
        key: `${task.taskId}-${slot.index}`,
        status,
        label: slotLabel(status, progress),
        ...(slot.url ? { image: slot.url } : {}),
        width: SLOT_WIDTH,
        aspectRatio: '1 / 1',
        ...(onRetry ? { onRetry } : {}),
      };
    });
  }

  if (task.kind === 'video') {
    const url = task.resultUrls?.[0] ?? '';
    const terminal = ['completed', 'partial', 'failed', 'cancelled'].includes(task.status);
    const status: RefineFrameStatus = url
      ? 'complete'
      : terminal ? 'error' : task.status === 'running' ? 'generating' : 'queued';
    return [{
      key: task.taskId,
      status,
      label: slotLabel(status, progress),
      ...(url ? { video: url } : {}),
      width: VIDEO_SLOT_WIDTH,
      aspectRatio: '16 / 9',
      ...(onRetry ? { onRetry } : {}),
    }];
  }

  return [];
}

/**
 * 生成结果的横向预置位条：有几张图/几个视频就铺几格，
 * 未完成的格子用 RefineFrame 占位并显示进度，完成就地换成结果。
 */
export function GenerationSlotStrip({
  taskIds,
  onOpen,
  onOpenMedia,
}: {
  taskIds: string[];
  onOpen?: () => void;
  onOpenMedia?: (url: string) => void;
}) {
  const tasks = useAgentStore((state) => state.tasks);
  const retryTask = useAgentStore((state) => state.retryTask);

  const frames = useMemo(
    () => taskIds.flatMap((taskId) => {
      const task = tasks[taskId];
      return task ? slotFrames(task, () => { void retryTask(taskId); }) : [];
    }),
    [retryTask, taskIds, tasks],
  );

  if (frames.length === 0) return null;

  return (
    <div className="gen-slot-strip" role="list" aria-label="生成结果">
      {frames.map((frame) => (
        <div className="gen-slot" role="listitem" key={frame.key}>
          <RefineFrame
            status={frame.status}
            width={frame.width}
            aspectRatio={frame.aspectRatio}
            radius={12}
            labels={frame.status === 'complete' ? undefined : { generating: frame.label, queued: frame.label, error: frame.label }}
            hideAfter={900}
            {...(frame.onRetry ? { onRetry: frame.onRetry } : {})}
          >
            {frame.image && (
              <span className="gen-slot__media">
                <img
                  src={assetUrl(frame.image)}
                  alt=""
                  loading="lazy"
                  onClick={() => onOpenMedia?.(frame.image as string)}
                />
                <ChatMediaActions
                  kind="image"
                  src={assetUrl(frame.image)}
                  source={frame.image}
                  alt="生成图片"
                />
              </span>
            )}
            {frame.video && (
              <span className="gen-slot__media">
                <video src={assetUrl(frame.video)} controls preload="metadata" playsInline />
                <ChatMediaActions
                  kind="video"
                  src={assetUrl(frame.video)}
                  source={frame.video}
                />
              </span>
            )}
          </RefineFrame>
        </div>
      ))}
      {onOpen && (
        <button type="button" className="gen-slot-strip__more" onClick={onOpen}>
          <span>查看全部</span>
          <ChevronRight size={14} strokeWidth={1.9} />
        </button>
      )}
    </div>
  );
}
