import type { SceneResponse } from '../../api/projects';
import { ContextMenu } from '../common/ContextMenu';
import { notify } from '../../stores/toastStore';
import { useProjectStore } from '../../stores/projectStore';
import { Eye, Film, Image as ImageIcon, Trash2 } from 'lucide-react';

/** 摘要取剧本首句，截断到 40 字——一屏能看 20+ 镜，比卡片流密度高 3~4 倍。 */
export function sceneSummary(scene: SceneResponse): string {
  const raw = (scene.scriptContent || '').trim();
  if (!raw) return '（未填写画面）';
  const first = raw.split(/[。！？\n]/)[0]?.trim() || raw;
  return first.length > 40 ? `${first.slice(0, 40)}…` : first;
}

/** 参数摘要：只显示有值的项，空值不占位。 */
export function sceneParamLine(scene: SceneResponse): string {
  const parts = [scene.shotType, scene.cameraMovement].filter(
    (value): value is string => typeof value === 'string' && value.trim().length > 0,
  );
  if (scene.duration && scene.duration > 0) parts.push(`${scene.duration}s`);
  return parts.join(' · ');
}

type StatusTone = 'ok' | 'run' | 'fail' | 'idle';

export function statusTone(status: string | undefined): StatusTone {
  switch (status) {
    case 'completed':
      return 'ok';
    case 'generating':
      return 'run';
    case 'failed':
      return 'fail';
    default:
      return 'idle';
  }
}

const TONE_COLOR: Record<StatusTone, string> = {
  ok: 'var(--color-success)',
  run: 'var(--color-warning)',
  fail: 'var(--color-error)',
  idle: 'var(--color-muted-soft)',
};

const STATUS_LABEL: Record<string, string> = {
  completed: '已生成',
  generating: '生成中',
  failed: '生成失败',
  pending: '未生成',
};

/**
 * 只在真的有状态时出现：待生成/未生成不画灰点。
 * 灰色双点既没有信息量，又会被误认成「更多」按钮。
 */
function SceneStatusMark({ kind, status }: { kind: 'image' | 'video'; status: string | undefined }) {
  if (!status || status === 'pending' || statusTone(status) === 'idle') return null;
  const tone = statusTone(status);
  const label = STATUS_LABEL[status] ?? status;
  const Icon = kind === 'image' ? ImageIcon : Film;
  const kindLabel = kind === 'image' ? '图片' : '视频';
  return (
    <span
      aria-label={`${kindLabel}状态`}
      title={`${kindLabel}：${label}`}
      style={{ display: 'inline-flex', alignItems: 'center', color: TONE_COLOR[tone] }}
    >
      <Icon size={12} strokeWidth={2} />
    </span>
  );
}

/**
 * 分镜大纲表：一行一镜。
 *
 * <p>为什么不是卡片：卡片一屏只能放 4~5 镜，改一句要滚很久；而一镜真正需要"扫一眼"的只有
 * 镜号、一句画面、参数、生成状态。缩略图保留，是因为它决定"这镜要不要重做"。
 */
export function SceneOutlineRow({
  scene,
  isSelected,
  onSelect,
  dragging = false,
}: {
  scene: SceneResponse;
  isSelected: boolean;
  onSelect: () => void;
  dragging?: boolean;
}) {
  const deleteScene = useProjectStore((s) => s.deleteScene);
  const paramLine = sceneParamLine(scene);

  const removeScene = async () => {
    try {
      await deleteScene(scene.id);
      notify({ title: '已删除分镜', description: `分镜 ${scene.sceneNumber}` });
    } catch (err) {
      notify({
        title: '删除分镜失败',
        description: err instanceof Error ? err.message : undefined,
        variant: 'error',
      });
    }
  };

  return (
    <ContextMenu
      items={[
        { label: '在预览中打开', icon: <Eye size={13} strokeWidth={1.8} />, onClick: onSelect },
        {
          label: '删除分镜',
          icon: <Trash2 size={13} strokeWidth={1.8} />,
          danger: true,
          dividerBefore: true,
          onClick: () => { void removeScene(); },
        },
      ]}
    >
      <button
        type="button"
        onClick={onSelect}
        aria-current={isSelected ? 'true' : undefined}
        aria-label={`分镜 ${scene.sceneNumber}: ${sceneSummary(scene)}`}
        className={`scene-row${isSelected ? ' is-selected' : ''}${dragging ? ' is-dragging' : ''}`}
        style={{ opacity: dragging ? 0.55 : 1 }}
      >
        <span className="scene-row__index">{scene.sceneNumber}</span>

        <span className="scene-row__thumb">
          {scene.imageUrl ? (
            <img src={scene.imageUrl} alt="" />
          ) : (
            '无图'
          )}
        </span>

        <span className="scene-row__body">
          <span className="scene-row__title">{sceneSummary(scene)}</span>
          {paramLine && <span className="scene-row__meta">{paramLine}</span>}
        </span>

        <span className="scene-row__status">
          {scene.stale && (
            <span className="scene-row__stale" title="画面在这镜出图/出视频之后被改过，媒体可能已过期">
              过期
            </span>
          )}
          <SceneStatusMark kind="image" status={scene.imageStatus} />
          <SceneStatusMark kind="video" status={scene.videoStatus} />
        </span>
      </button>
    </ContextMenu>
  );
}
