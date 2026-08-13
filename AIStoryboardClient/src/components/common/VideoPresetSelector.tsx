import { useEffect } from 'react';
import { VIDEO_PRESETS, type VideoModelParams, type VideoPreset } from '../../config';
import { useProjectStore } from '../../stores/projectStore';

interface VideoPresetSelectorProps {
  value: string;
  onChange: (value: string) => void;
}

/**
 * 按 durations × aspectRatios 组合生成 preset 列表（label 文案与静态 VIDEO_PRESETS 一致：
 * N秒 横/竖屏；resolution 默认档 768P（MiniMax 仅支持 768P/2K），竖屏画幅 9:16/3:4 用 720x1280）。
 */
export function buildPresets(durations: number[], aspectRatios: string[]): VideoPreset[] {
  const presets: VideoPreset[] = [];
  for (const d of durations) {
    for (const a of aspectRatios) {
      const vertical = a === '9:16' || a === '3:4';
      presets.push({
        value: `${d}s-${a}`,
        label: `${d}秒 ${vertical ? '竖屏' : '横屏'}`,
        seconds: String(d),
        duration: String(d),
        size: vertical ? '720x1280' : '1280x720',
        resolution: '768P',
        aspectRatio: a,
      });
    }
  }
  return presets;
}

/**
 * 将 preset value（静态 VIDEO_PRESETS 或动态 `${d}s-${a}`）解析为完整 preset 对象；
 * 供生成流程（createProject/generateScript/generateVideo）取 aspectRatio/duration，
 * 避免动态组合 preset 回退到静态 DEFAULT_VIDEO_PRESET 导致参数错配。
 */
export function resolveVideoPreset(value: string): VideoPreset {
  const staticPreset = VIDEO_PRESETS.find((p) => p.value === value);
  if (staticPreset) return staticPreset;
  // 动态组合 preset：value 形如 "6s-16:9" / "4s-9:16"
  const dashIdx = value.indexOf('-');
  const seconds = dashIdx > 0 ? value.slice(0, dashIdx) : '6';
  const aspectRatio = dashIdx > 0 ? value.slice(dashIdx + 1) : '16:9';
  const vertical = aspectRatio === '9:16' || aspectRatio === '3:4';
  return {
    value,
    label: `${seconds}秒 ${vertical ? '竖屏' : '横屏'}`,
    seconds,
    duration: seconds,
    size: vertical ? '720x1280' : '1280x720',
    resolution: '768P',
    aspectRatio,
  };
}

export function VideoPresetSelector({ value, onChange }: VideoPresetSelectorProps) {
  // 当前生视频模型参数能力（网关下发 params；未配置时回退静态 VIDEO_PRESETS）
  const videoModel = useProjectStore((s) => s.videoModel);
  const videoModelOptions = useProjectStore((s) => s.videoModelOptions);
  const setVideoPreset = useProjectStore((s) => s.setVideoPreset);

  const videoParams = videoModelOptions.find((m) => m.value === videoModel)?.params as VideoModelParams | undefined;
  const presets = videoParams?.durations?.length && videoParams?.aspectRatios?.length
    ? buildPresets(videoParams.durations, videoParams.aspectRatios)
    : VIDEO_PRESETS;

  // 默认 preset：durationDefault + aspectRatioDefault 匹配项，匹配不到取第一个
  const defaultPreset =
    (videoParams?.durationDefault && videoParams?.aspectRatioDefault
      ? presets.find(
          (p) => p.duration === String(videoParams.durationDefault) && p.aspectRatio === videoParams.aspectRatioDefault
        )
      : undefined) ?? presets[0];

  // 切换模型/选项集变化后，当前值不在集合内 → 重置为默认 preset（受控组件，在 effect 中同步 store）
  useEffect(() => {
    if (defaultPreset && !presets.some((p) => p.value === value)) {
      setVideoPreset(defaultPreset.value);
    }
  }, [presets, value, defaultPreset, setVideoPreset]);

  return (
    <select
      value={value}
      onChange={(e) => onChange(e.target.value)}
      style={{
        width: '100%',
        fontSize: 12,
        padding: '8px 10px',
        borderRadius: 'var(--rounded-sm)',
        border: '1px solid var(--color-hairline)',
        background: 'white',
        color: 'var(--color-ink)',
        boxSizing: 'border-box' as React.CSSProperties['boxSizing'],
        fontFamily: 'inherit',
        outline: 'none',
        cursor: 'pointer',
        appearance: 'auto' as React.CSSProperties['appearance'],
        paddingRight: 28,
      }}
    >
      {presets.map((p) => (
        <option key={p.value} value={p.value}>
          {p.label}
        </option>
      ))}
    </select>
  );
}
