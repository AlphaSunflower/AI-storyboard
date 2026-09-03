import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import type { GatewayModelOption } from '../../api/ai';
import { Image as ImageIcon, Video } from 'lucide-react';

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
  /** 推荐/提交键前缀（aisplit 分镜卡片图片/视频分区用，如 "image"/"video"；默认 '' 零回归） */
  keyPrefix?: string;
  /** 初始参数（键带前缀，如 videoResolution/imageModel）：优先于模型默认/推荐，用于回填分镜已存配置 */
  initialValue?: Record<string, string>;
  /** 静默模式（SceneReviewCard 用）：仅在用户真正改动时上报，不做默认预选；打开/切换图视不发保存请求 */
  silent?: boolean;
}

/** 参数元信息：key=提交键名，field=网关 params 中的枚举数组字段，defaultField=默认值字段 */
const PARAM_META = [
  { key: 'resolution', label: '分辨率', field: 'resolutions', defaultField: 'resolutionDefault' },
  { key: 'duration', label: '时长(秒)', field: 'durations', defaultField: 'durationDefault' },
  { key: 'aspectRatio', label: '画幅', field: 'aspectRatios', defaultField: 'aspectRatioDefault' },
  { key: 'size', label: '尺寸', field: 'sizes', defaultField: 'sizeDefault' },
  { key: 'quality', label: '质量', field: 'qualities', defaultField: 'qualityDefault' },
  { key: 'n', label: '生成个数', field: 'n', defaultField: 'nDefault' },
] as const;

type ParamLists = Record<string, { options: string[]; default?: string }>;

/** 小写参数键 → 驼峰（如 size → Size, aspectRatio → AspectRatio），与后端字段/SceneReviewCard.buildPayload 对齐 */
function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/** 兼容大小写取推荐/理由：优先带前缀大写键，回退原始小写键（后端 recommended/reasons 键大小写不确定）。 */
function pick(map: Record<string, string> | undefined, fullKey: string, lowerKey: string): string | undefined {
  return map?.[fullKey] ?? map?.[lowerKey];
}

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
    // 生成个数：网关 params 的 n 是 {min,max,default} 对象（非枚举数组），特判为连续整数范围
    if (meta.key === 'n') {
      const nObj = p['n'];
      if (nObj && typeof nObj === 'object' && !Array.isArray(nObj)) {
        const o = nObj as { min?: number; max?: number; default?: number };
        const lo = o.min ?? 1;
        const hi = o.max ?? o.min ?? 1;
        const opts = Array.from({ length: Math.max(0, hi - lo + 1) }, (_, i) => String(lo + i));
        if (opts.length) out['n'] = { options: opts, default: o.default != null ? String(o.default) : opts[0] };
      }
      continue;
    }
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

/** 计算某模型的参数初始值：LLM 推荐 > 模型默认 > 首选项；override（分镜已存配置）对有效选项优先。
 *  silent=true 时仅采用 override（分镜已存配置），未存字段留空，不预选默认值。 */
function initialValues(
  lists: ParamLists,
  recommended: Record<string, string>,
  prefix: string,
  override: Record<string, string> = {},
  silent = false,
): Record<string, string> {
  const init: Record<string, string> = {};
  for (const [key, v] of Object.entries(lists)) {
    const fullKey = prefix + cap(key);
    const rec = pick(recommended, fullKey, prefix + key);
    let val = '';
    const ov = override[fullKey];
    if (ov && v.options.includes(ov)) {
      val = ov;
    } else if (!silent) {
      val = rec && v.options.includes(rec) ? rec : (v.default ?? v.options[0]);
    }
    if (val) init[fullKey] = val;
  }
  return init;
}

export function AgentParamSelector({
  models,
  recommended = {},
  reasons = {},
  onParamsChange,
  keyPrefix = '',
  initialValue,
  silent = false,
}: Props) {
  // 记录是否发生了用户真实改动（静默模式下只有在用户改动后才上报）
  const userChangedRef = useRef(false);
  // 模型：分镜已存配置(initialValue) > LLM 推荐 > 首个；静默模式下仅取分镜已存模型，无则不选
  const initialModel = (() => {
    const iv = initialValue?.[keyPrefix + 'Model'];
    if (iv && models.some((m) => m.value === iv)) return iv;
    const rec = pick(recommended, keyPrefix + 'Model', keyPrefix + 'model');
    if (rec && models.some((m) => m.value === rec)) return rec;
    // 优先网关 is_default 模型（带完整参数能力），再回退首个
    const def = models.find((m) => m.default === 'true' || m.default === true);
    if (def && models.some((m) => m.value === def.value)) return def.value;
    return models[0]?.value ?? '';
  })();
  const [model, setModel] = useState(initialModel);
  const current = useMemo(() => models.find((m) => m.value === model), [models, model]);
  const paramLists = useMemo(() => parseParamLists(current), [current]);
  const [selected, setSelected] = useState<Record<string, string>>(() =>
    initialValues(parseParamLists(models.find((m) => m.value === initialModel)), recommended, keyPrefix, initialValue, silent));

  // 模型切换：参数重置为该模型推荐/默认值（保留 initialValue 里对新模型仍有效的覆盖）
  useEffect(() => {
    if (!models.length) return;
    setSelected((prev) => {
      const base = initialValues(paramLists, recommended, keyPrefix, initialValue, silent);
      // 切换模型时保留用户在旧模型下调过的、且新模型仍支持的参数值（避免切模型后自定义参数丢失）
      for (const [k, v] of Object.entries(prev ?? {})) {
        if (!v) continue;
        const metaKey = k.slice(keyPrefix.length);
        const lowerKey = metaKey.charAt(0).toLowerCase() + metaKey.slice(1);
        const opts = paramLists[lowerKey]?.options;
        if (opts && opts.includes(v)) base[k] = v;
      }
      return base;
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model]);

  // 选择变化 → 上报全量参数（模型 + 各参数，键带前缀）
  useEffect(() => {
    if (!models.length) return;
    if (silent && !userChangedRef.current) return;
    onParamsChange({ ...selected, [keyPrefix + 'Model']: model });
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
      <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        {keyPrefix === 'image' ? (<><ImageIcon size={12} strokeWidth={1.8} /> 图片生成参数</>) : keyPrefix === 'video' ? (<><Video size={12} strokeWidth={1.8} /> 视频生成参数</>) : '生成参数'}
        （已按推荐选择，可直接确认或修改）
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: 'var(--color-muted)', width: 64, flexShrink: 0 }}>模型</span>
          <select value={model} onChange={(e) => { if (e.target.value) { userChangedRef.current = true; setModel(e.target.value); } }} style={selectStyle}>
            {models.map((m) => <option key={m.value} value={m.value}>{m.label || m.value}</option>)}
          </select>
          {pick(reasons, keyPrefix + 'Model', keyPrefix + 'model') && <span style={{ color: 'var(--color-primary)', fontSize: 11 }}>{pick(reasons, keyPrefix + 'Model', keyPrefix + 'model')}</span>}
        </div>
        {visibleMeta.map((meta) => (
          <div key={meta.key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ color: 'var(--color-muted)', width: 64, flexShrink: 0 }}>{meta.label}</span>
            <select value={selected[keyPrefix + cap(meta.key)] ?? ''} onChange={(e) => { if (e.target.value) { userChangedRef.current = true; setSelected((s) => ({ ...s, [keyPrefix + cap(meta.key)]: e.target.value })); } }} style={selectStyle}>
              {paramLists[meta.key].options.map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
            {pick(reasons, keyPrefix + cap(meta.key), keyPrefix + meta.key) && (
              <span style={{ color: 'var(--color-primary)', fontSize: 11 }}>推荐：{pick(reasons, keyPrefix + cap(meta.key), keyPrefix + meta.key)}</span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
