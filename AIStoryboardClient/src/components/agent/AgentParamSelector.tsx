import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import type { GatewayModelOption } from '../../api/ai';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import { Image as ImageIcon, Video } from 'lucide-react';

/**
 * 卡片生成参数选择器（human_input / video_plan 卡片复用）：
 * 新逻辑按网关 model.params.spec（自描述参数 schema）通用渲染——enum→下拉 / range→数字 / bool→开关 /
 * string→文本 / object→嵌套分组；show:false 参数只带默认值不渲染；model/prompt 绝不渲染；
 * 嵌套参数以点路径扁平化为 Record<string,string>（如 style.style_type），提交给后端。
 * 无 spec（旧模型/网关不可用）→ 回退原 PARAM_META 硬编码渲染（零回归）。
 * 默认选中 LLM 推荐值（recommended），旁示推荐理由（reasons），用户可修改。
 */

interface Props {
  models: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  onParamsChange: (params: Record<string, string>) => void;
  /** 推荐/提交键前缀（aisplit 分镜卡片图片/视频分区用，如 "image"/"video"；默认 '' 零回归） */
  keyPrefix?: string;
}

/** spec 参数项（对应网关 params_schema.request[]） */
interface SpecParam {
  key: string;
  label?: string;
  type?: string;                        // enum/range/number/bool/string/object/array/arrayofobject
  options?: string[];
  min?: number;
  max?: number;
  default?: unknown;
  show?: boolean;                       // 缺省 true
  fields?: SpecParam[];
}

const LEGACY_PARAM_META = [
  { key: 'resolution', label: '分辨率', field: 'resolutions', defaultField: 'resolutionDefault' },
  { key: 'duration', label: '时长(秒)', field: 'durations', defaultField: 'durationDefault' },
  { key: 'aspectRatio', label: '画幅', field: 'aspectRatios', defaultField: 'aspectRatioDefault' },
  { key: 'size', label: '尺寸', field: 'sizes', defaultField: 'sizeDefault' },
  { key: 'quality', label: '质量', field: 'qualities', defaultField: 'qualityDefault' },
  { key: 'n', label: '生成个数', field: 'n', defaultField: 'nDefault' },
] as const;

type LegacyParamLists = Record<string, { options: string[]; default?: string }>;

/** 解析 model.props.params（JSON 字符串或对象）→ 各参数的可用枚举与默认值（旧版 fallback） */
function parseLegacyParamLists(model?: GatewayModelOption): LegacyParamLists {
  const out: LegacyParamLists = {};
  if (!model?.params) return out;
  let p: Record<string, unknown>;
  try {
    p = typeof model.params === 'string' ? (JSON.parse(model.params) as Record<string, unknown>) : (model.params as Record<string, unknown>);
  } catch {
    return out;
  }
  for (const meta of LEGACY_PARAM_META) {
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

/** 解析 spec（model.params.spec.request[]）；无 spec 返回空数组 */
function parseSpec(model?: GatewayModelOption): SpecParam[] {
  if (!model?.params) return [];
  let p: Record<string, unknown>;
  try {
    p = typeof model.params === 'string' ? (JSON.parse(model.params) as Record<string, unknown>) : (model.params as Record<string, unknown>);
  } catch {
    return [];
  }
  let spec = p['spec'] as Record<string, unknown> | undefined;
  if (typeof spec === 'string') {
    try { spec = JSON.parse(spec) as Record<string, unknown>; } catch { return []; }
  }
  const req = spec?.['request'];
  return Array.isArray(req) ? (req as SpecParam[]) : [];
}

/** 递归把 spec 参数展开成「点路径 key → 初始值」扁平 Map（推荐 > 默认 > 首选项；show:false 也带上默认） */
function flattenInit(params: SpecParam[], prefix: string, recommended: Record<string, string>, out: Record<string, string>) {
  for (const prm of params || []) {
    const key = prefix + prm.key;
    const show = prm.show !== false;
    if (prm.type === 'object' && prm.fields) {
      flattenInit(prm.fields, key + '.', recommended, out);
      continue;
    }
    const rec = recommended[key];
    let def: string | undefined;
    if (prm.type === 'enum') def = Array.isArray(prm.options) && prm.options.length ? String(prm.default ?? prm.options[0]) : String(prm.default ?? '');
    else if (prm.default !== undefined && prm.default !== null) def = String(prm.default);
    const val = rec ?? def;
    // show:false 参数：仍带默认识别，不渲染（渲染层靠 show 过滤）
    if (val !== undefined && val !== '') out[key] = val;
    else if (!show && prm.default !== undefined && prm.default !== null) out[key] = String(prm.default);
  }
}

/** initValues：走 spec 时的初始扁平值 + 模型值 */
function initSpecValues(spec: SpecParam[], recommended: Record<string, string>, prefix: string): Record<string, string> {
  const out: Record<string, string> = {};
  flattenInit(spec, prefix, recommended, out);
  return out;
}

/** 旧版 initialValues：LLM 推荐 > 模型默认 > 首选项 */
function initLegacyValues(lists: LegacyParamLists, recommended: Record<string, string>, prefix: string): Record<string, string> {
  const init: Record<string, string> = {};
  for (const [key, v] of Object.entries(lists)) {
    const rec = recommended[prefix + key];
    init[prefix + key] = rec && v.options.includes(rec) ? rec : (v.default ?? v.options[0]);
  }
  return init;
}

export function AgentParamSelector({ models, recommended = {}, reasons = {}, onParamsChange, keyPrefix = '' }: Props) {
  const [model, setModel] = useState(() => {
    const rec = recommended[keyPrefix + 'model'];
    if (rec && models.some((m) => m.value === rec)) return rec;
    return models[0]?.value ?? '';
  });
  const current = useMemo(() => models.find((m) => m.value === model), [models, model]);
  const spec = useMemo(() => parseSpec(current), [current]);
  const hasSpec = spec.length > 0;
  const legacyLists = useMemo(() => (!hasSpec ? parseLegacyParamLists(current) : {}), [current, hasSpec]);

  const [selected, setSelected] = useState<Record<string, string>>(() =>
    hasSpec
      ? initSpecValues(spec, recommended, keyPrefix)
      : initLegacyValues(parseLegacyParamLists(models[0]), recommended, keyPrefix));

  useEffect(() => {
    setSelected(hasSpec
      ? initSpecValues(spec, recommended, keyPrefix)
      : initLegacyValues(legacyLists, recommended, keyPrefix));
  }, [model, spec, legacyLists, recommended, keyPrefix, hasSpec]);

  useEffect(() => {
    onParamsChange({ ...selected, [keyPrefix + 'model']: model });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, model]);

  if (!models.length) return null;

  const setVal = (key: string, v: string) => setSelected((s) => ({ ...s, [key]: v }));

  const selectStyle: CSSProperties = {
    padding: '4px 8px', borderRadius: 6, border: '1px solid var(--color-hairline)',
    fontSize: 12, background: 'white', color: 'var(--color-ink)', outline: 'none',
  };
  const labelStyle: CSSProperties = { color: 'var(--color-muted)', width: 72, flexShrink: 0, fontSize: 12 };

  // ===== spec 驱动的参数行 =====
  const renderSpecRow = (prm: SpecParam, prefix: string) => {
    if (prm.show === false) return null; // 只带默认值，不渲染
    const key = prefix + prm.key;
    const value = selected[key] ?? '';
    const reason = reasons[key];
    if ((prm.type === 'object' || prm.type === 'arrayofobject') && prm.fields) {
      return (
        <div key={key} style={{ display: 'flex', flexDirection: 'column', gap: 6, borderLeft: '2px solid var(--color-hairline)', paddingLeft: 8 }}>
          <span style={{ color: 'var(--color-muted)', fontSize: 11 }}>{prm.label || prm.key}{prm.type === 'arrayofobject' ? '（列表项，按一项填写）' : ''}</span>
          {(prm.fields || []).map((f) => renderSpecRow(f, key + '.'))}
        </div>
      );
    }
    const control = (() => {
      if (prm.type === 'enum') {
        const opts = (prm.options || []).map(String);
        return (
          <Select value={value} onValueChange={(v) => v && setVal(key, v)}>
            <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
            <SelectContent>
              {opts.map((o) => <SelectItem key={o} value={o}>{o}</SelectItem>)}
            </SelectContent>
          </Select>
        );
      }
      if (prm.type === 'bool') {
        return (
          <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 }}>
            <input type="checkbox" checked={value === 'true'} onChange={(e) => setVal(key, e.target.checked ? 'true' : 'false')} />
            {value === 'true' ? '开' : '关'}
          </label>
        );
      }
      if (prm.type === 'array') {
        return (
          <textarea
            style={{ ...selectStyle, flex: 1, minHeight: 48, fontFamily: 'monospace', fontSize: 12 }}
            value={value}
            placeholder="每行一个 URL 或逗号分隔"
            onChange={(e) => setVal(key, e.target.value)}
          />
        );
      }
      return (
        <input
          style={{ ...selectStyle, width: 'auto', flex: 1 }}
          type={prm.type === 'range' || prm.type === 'number' ? 'number' : 'text'}
          min={prm.type === 'range' ? prm.min : undefined}
          max={prm.type === 'range' ? prm.max : undefined}
          value={value}
          placeholder={prm.type === 'range' ? `${prm.min ?? ''}~${prm.max ?? ''}` : ''}
          onChange={(e) => setVal(key, e.target.value)}
        />
      );
    })();

    return (
      <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={labelStyle}>{prm.label || prm.key}</span>
        {control}
        {reason && <span style={{ color: 'var(--color-primary)', fontSize: 11 }}>推荐：{reason}</span>}
      </div>
    );
  };

  // ===== 旧版 PARAM_META 驱动 =====
  const legacyVisible = LEGACY_PARAM_META.filter((m) => legacyLists[m.key]);

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
          <span style={labelStyle}>模型</span>
          <Select value={model} onValueChange={(v) => v && setModel(v)}>
            <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
            <SelectContent>
              {models.map((m) => <SelectItem key={m.value} value={m.value}>{m.label || m.value}</SelectItem>)}
            </SelectContent>
          </Select>
          {reasons[keyPrefix + 'model'] && <span style={{ color: 'var(--color-primary)', fontSize: 11 }}>{reasons[keyPrefix + 'model']}</span>}
        </div>

        {hasSpec ? spec.map((p) => renderSpecRow(p, keyPrefix)) : legacyVisible.map((meta) => {
          const key = keyPrefix + meta.key;
          const value = selected[key] ?? '';
          const reason = reasons[key];
          return (
            <div key={meta.key} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={labelStyle}>{meta.label}</span>
              <Select value={value} onValueChange={(v) => v && setVal(key, v)}>
                <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {legacyLists[meta.key].options.map((o) => <SelectItem key={o} value={o}>{o}</SelectItem>)}
                </SelectContent>
              </Select>
              {reason && (<span style={{ color: 'var(--color-primary)', fontSize: 11 }}>推荐：{reason}</span>)}
            </div>
          );
        })}
      </div>
    </div>
  );
}
