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
  ok: '#22a06b',
  run: '#c47b16',
  fail: '#d14b4b',
  idle: 'var(--color-muted-soft)',
};

function StatusDot({ label, status }: { label: string; status: string | undefined }) {
  const tone = statusTone(status);
  return (
    <span
      title={`${label}：${status || '未生成'}`}
      aria-label={`${label}状态`}
      style={{
        width: 7,
        height: 7,
        borderRadius: '50%',
        background: TONE_COLOR[tone],
        display: 'inline-block',
        opacity: tone === 'idle' ? 0.5 : 1,
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
      style={{
        display: 'grid',
        gridTemplateColumns: '22px 34px minmax(0, 1fr) auto',
        gap: 8,
        alignItems: 'center',
        width: '100%',
        padding: '6px 8px',
        marginBottom: 4,
        border: isSelected ? '1px solid var(--color-primary)' : '1px solid transparent',
        borderLeft: `3px solid ${isSelected ? 'var(--color-primary)' : 'transparent'}`,
        borderRadius: 'var(--rounded-sm)',
        background: isSelected ? 'var(--color-surface-card)' : 'transparent',
        opacity: dragging ? 0.5 : 1,
        cursor: 'pointer',
        textAlign: 'left',
        font: 'inherit',
        color: 'inherit',
        transition: 'background 0.15s, border-color 0.15s',
      }}
      onMouseEnter={(event) => {
        if (!isSelected) event.currentTarget.style.background = 'var(--color-surface-soft)';
      }}
      onMouseLeave={(event) => {
        if (!isSelected) event.currentTarget.style.background = 'transparent';
      }}
    >
      <span
        style={{
          fontSize: 11,
          color: 'var(--color-muted)',
          fontVariantNumeric: 'tabular-nums',
          textAlign: 'right',
        }}
      >
        {scene.sceneNumber}
      </span>

      <span
        style={{
          width: 34,
          height: 24,
          borderRadius: 4,
          overflow: 'hidden',
          background: 'var(--color-hairline)',
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
        }}
      >
        {scene.imageUrl ? (
          <img
            src={scene.imageUrl}
            alt=""
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
          />
        ) : (
          <span style={{ fontSize: 9, color: 'var(--color-muted-soft)' }}>无图</span>
        )}
      </span>

      <span style={{ display: 'grid', gap: 1, minWidth: 0 }}>
        <span
          style={{
            fontSize: 12,
            color: 'var(--color-ink)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {sceneSummary(scene)}
        </span>
        {paramLine && (
          <span style={{ fontSize: 10, color: 'var(--color-muted-soft)' }}>{paramLine}</span>
        )}
      </span>

      <span style={{ display: 'inline-flex', gap: 5, alignItems: 'center' }}>
        {scene.stale && (
          <span
            title="画面在这镜出图/出视频之后被改过，媒体可能已过期"
            style={{
              fontSize: 9,
              lineHeight: 1.4,
              padding: '0 3px',
              borderRadius: 3,
              border: '1px solid color-mix(in srgb, #c47b16 45%, transparent)',
              color: '#c47b16',
              whiteSpace: 'nowrap',
            }}
          >
            过期
          </span>
        )}
        <StatusDot label="图片" status={scene.imageStatus} />
        <StatusDot label="视频" status={scene.videoStatus} />
      </span>
    </button>
  );
}
