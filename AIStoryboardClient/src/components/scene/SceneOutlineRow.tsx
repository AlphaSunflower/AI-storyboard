import type { SceneResponse } from '../../api/projects';

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

function StatusDot({ label, status }: { label: string; status: string | undefined }) {
  const tone = statusTone(status);
  return (
    <span
      title={`${label}：${status || '未生成'}`}
      aria-label={`${label}状态`}
      className="scene-row__dot"
      style={{
        background: TONE_COLOR[tone],
        opacity: tone === 'idle' ? 0.45 : 1,
      }}
    />
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
  const paramLine = sceneParamLine(scene);
  return (
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
        <StatusDot label="图片" status={scene.imageStatus} />
        <StatusDot label="视频" status={scene.videoStatus} />
      </span>
    </button>
  );
}
