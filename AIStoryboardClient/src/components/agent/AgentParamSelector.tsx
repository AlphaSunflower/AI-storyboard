import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import type { GatewayModelOption } from '../../api/ai';

/**
 * 卡片生成参数选择器（human_input / video_plan 卡片复用）：
 * 模型下拉 + 参数联动（尺寸/质量/分辨率/时长/画幅，来自网关模型 params 能力）；
 * 默认选中 LLM 推荐值（recommended），旁示推荐理由（reasons），用户可修改。
 * 无 models（网关不可用/未配置）时返回 null，卡片行为与旧版一致。
 */

interface Props {
  models: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  onParamsChange: (params: Record<string, string>) => void;
}

/** 参数元信息：key=提交键名，field=网关 params 中的枚举数组字段，defaultField=默认值字段 */
const PARAM_META = [
  { key: 'resolution', label: '分辨率', field: 'resolutions', defaultField: 'resolutionDefault' },
  { key: 'duration', label: '时长(秒)', field: 'durations', defaultField: 'durationDefault' },
  { key: 'aspectRatio', label: '画幅', field: 'aspectRatios', defaultField: 'aspectRatioDefault' },
  { key: 'size', label: '尺寸', field: 'sizes', defaultField: 'sizeDefault' },
  { key: 'quality', label: '质量', field: 'qualities', defaultField: 'qualityDefault' },
] as const;

type ParamLists = Record<string, { options: string[]; default?: string }>;

/** 解析模型 params（JSON 字符串或对象）→ 各参数的可用枚举与默认值 */
function parseParamLists(model?: GatewayModelOption): ParamLists {
  const out: ParamLists = {};
  if (!model?.params) return out;
  let p: Record<string, unknown>;
  try {
    p = typeof model.params === 'string' ? (JSON.parse(model.params) as Record<string, unknown>) : (model.params as Record<string, unknown>);
  } catch {
    return out;
  }
  for (const meta of PARAM_META) {
    const arr = p[meta.field];
    if (Array.isArray(arr) && arr.length > 0) {
      out[meta.key] = {
        options: arr.map(String),
        default: p[meta.defaultField] != null ? String(p[meta.defaultField]) : undefined,
      };
    }
  }
  return out;
}

/** 计算某模型的参数初始值：LLM 推荐 > 模型默认 > 首选项 */
function initialValues(lists: ParamLists, recommended: Record<string, string>): Record<string, string> {
  const init: Record<string, string> = {};
  for (const [key, v] of Object.entries(lists)) {
    const rec = recommended[key];
    init[key] = rec && v.options.includes(rec) ? rec : (v.default ?? v.options[0]);
  }
  return init;
}

export function AgentParamSelector({ models, recommended = {}, reasons = {}, onParamsChange }: Props) {
  // 模型：默认 LLM 推荐（在选项中）否则首个
  const [model, setModel] = useState(() => {
    const rec = recommended.model;
    if (rec && models.some((m) => m.value === rec)) return rec;
    return models[0]?.value ?? '';
  });
  const current = useMemo(() => models.find((m) => m.value === model), [models, model]);
  const paramLists = useMemo(() => parseParamLists(current), [current]);
  const [selected, setSelected] = useState<Record<string, string>>(() =>
    initialValues(parseParamLists(models[0]), recommended));

  // 模型切换：参数重置为该模型推荐/默认值（旧参数如 2K 不适配新模型时清掉）
  useEffect(() => {
    setSelected(initialValues(paramLists, recommended));
  }, [model, paramLists, recommended]);

  // 选择变化 → 上报全量参数（模型 + 各参数）
  useEffect(() => {
    onParamsChange({ ...selected, model });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, model]);

  if (!models.length) return null;
  const visibleMeta = PARAM_META.filter((m) => paramLists[m.key]);

  const selectStyle: CSSProperties = {
    padding: '4px 8px', borderRadius: 6, border: '1px solid var(--color-hairline)',
    fontSize: 12, background: 'white', color: 'var(--color-ink)', outline: 'none',
  };

  return (
    <div
      style={{
        marginBottom: 10, padding: '8px 10px', background: '#faf9f5',
        borderRadius: 8, border: '1px solid var(--color-hairline)', fontSize: 12,
      }}
    >
      <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1 }}>
        生成参数（已按推荐选择，可直接确认或修改）
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: 'var(--color-muted)', width: 64, flexShrink: 0 }}>模型</span>
          <select value={model} onChange={(e) => setModel(e.target.value)} style={selectStyle}>
            {models.map((m) => (
              <option key={m.value} value={m.value}>{m.label || m.value}</option>
            ))}
          </select>
          {reasons.model && <span style={{ color: 'var(--color-primary)', fontSize: 11 }}>✨ {reasons.model}</span>}
        </div>
        {visibleMeta.map((meta) => (
          <div key={meta.key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ color: 'var(--color-muted)', width: 64, flexShrink: 0 }}>{meta.label}</span>
            <select
              value={selected[meta.key] ?? ''}
              onChange={(e) => setSelected((s) => ({ ...s, [meta.key]: e.target.value }))}
              style={selectStyle}
            >
              {paramLists[meta.key].options.map((o) => (
                <option key={o} value={o}>{o}</option>
              ))}
            </select>
            {reasons[meta.key] && (
              <span style={{ color: 'var(--color-primary)', fontSize: 11 }}>✨ 推荐：{reasons[meta.key]}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
