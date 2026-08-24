import { useEffect, useState } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, Modal } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { tokens } from '../../tokens';
import type { GatewayModelOption } from '../../api/agent';

/**
 * 卡片生成参数选择器（human_input / video_plan 卡片复用）：
 * 模型选择 + 参数联动（尺寸/质量/分辨率/时长/画幅/生成个数，来自网关模型 params 能力）；
 * 默认选中 LLM 推荐值（recommended），旁示推荐理由（reasons），用户可修改。
 * 无 models（网关不可用/未配置）时返回 null，卡片行为与旧版一致。
 */

const PARAM_META = [
  { key: 'resolution', label: '分辨率', field: 'resolutions', defaultField: 'resolutionDefault' },
  { key: 'duration', label: '时长(秒)', field: 'durations', defaultField: 'durationDefault' },
  { key: 'aspectRatio', label: '画幅', field: 'aspectRatios', defaultField: 'aspectRatioDefault' },
  { key: 'size', label: '尺寸', field: 'sizes', defaultField: 'sizeDefault' },
  { key: 'quality', label: '质量', field: 'qualities', defaultField: 'qualityDefault' },
  { key: 'n', label: '生成个数', field: 'n', defaultField: 'nDefault' },
] as const;

type ParamLists = Record<string, { options: string[]; default?: string }>;

function parseParamLists(model?: GatewayModelOption): ParamLists {
  const out: ParamLists = {};
  if (!model?.params) return out;
  let p: Record<string, unknown>;
  try {
    p = JSON.parse(model.params) as Record<string, unknown>;
  } catch { return out; }
  for (const meta of PARAM_META) {
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

function initialValues(lists: ParamLists, recommended: Record<string, string>, prefix: string): Record<string, string> {
  const init: Record<string, string> = {};
  for (const [key, v] of Object.entries(lists)) {
    const rec = recommended[prefix + key];
    init[prefix + key] = rec && v.options.includes(rec) ? rec : (v.default ?? v.options[0]);
  }
  return init;
}

interface Props {
  models: GatewayModelOption[];
  recommended?: Record<string, string>;
  reasons?: Record<string, string>;
  onParamsChange: (params: Record<string, string>) => void;
  keyPrefix?: string;
  label?: string;
}

export function ParamSelector({ models, recommended = {}, reasons = {}, onParamsChange, keyPrefix = '', label = '生成参数' }: Props) {
  const [model, setModel] = useState(() => {
    const rec = recommended[keyPrefix + 'model'];
    if (rec && models.some((m) => m.value === rec)) return rec;
    return models[0]?.value ?? '';
  });
  const [pickOpen, setPickOpen] = useState(false);
  const current = models.find((m) => m.value === model);
  const paramLists = parseParamLists(current);
  const [selected, setSelected] = useState<Record<string, string>>(() =>
    initialValues(parseParamLists(models[0]), recommended, keyPrefix));

  useEffect(() => {
    setSelected(initialValues(paramLists, recommended, keyPrefix));
  }, [model, paramLists, recommended, keyPrefix]);

  useEffect(() => {
    onParamsChange({ ...selected, [keyPrefix + 'model']: model });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, model]);

  if (!models.length) return null;
  const visibleMeta = PARAM_META.filter((m) => paramLists[m.key]);

  return (
    <View style={s.wrap}>
      <Text style={s.title}>{label}（已按推荐选择，可直接确认或修改）</Text>
      <View style={s.row}>
        <Text style={s.col}>模型</Text>
        <TouchableOpacity style={s.pickerBtn} onPress={() => setPickOpen(true)}>
          <Text style={s.pickerText}>{current?.label ?? model}</Text>
          <Ionicons name="chevron-down" size={12} color={tokens.colors.textCaption} />
        </TouchableOpacity>
        {reasons[keyPrefix + 'model'] ? <Text style={s.reason}>{reasons[keyPrefix + 'model']}</Text> : null}
      </View>
      {visibleMeta.map((meta) => (
        <View key={meta.key} style={s.row}>
          <Text style={s.col}>{meta.label}</Text>
          <View style={s.chips}>
            {paramLists[meta.key].options.map((o) => (
              <TouchableOpacity
                key={o}
                style={[s.chip, selected[keyPrefix + meta.key] === o && s.chipActive]}
                onPress={() => setSelected((prev) => ({ ...prev, [keyPrefix + meta.key]: o }))}
              >
                <Text style={[s.chipText, selected[keyPrefix + meta.key] === o && s.chipTextActive]}>{o}</Text>
              </TouchableOpacity>
            ))}
          </View>
        </View>
      ))}
      {visibleMeta.some((m) => reasons[keyPrefix + m.key]) && (
        <Text style={s.reasonBlock}>
          {visibleMeta.filter((m) => reasons[keyPrefix + m.key]).map((m) => `${m.label}：推荐 ${reasons[keyPrefix + m.key]}`).join(' · ')}
        </Text>
      )}

      <Modal visible={pickOpen} transparent animationType="fade" onRequestClose={() => setPickOpen(false)}>
        <TouchableOpacity style={s.pickBackdrop} activeOpacity={1} onPress={() => setPickOpen(false)}>
          <View style={s.pickCard} onStartShouldSetResponder={() => true}>
            <Text style={s.pickTitle}>选择模型</Text>
            {models.map((m) => (
              <TouchableOpacity key={m.value} style={[s.pickItem, model === m.value && s.pickItemActive]} onPress={() => { setModel(m.value); setPickOpen(false); }}>
                <Text style={[s.pickItemText, model === m.value && s.pickItemTextActive]}>{m.label}</Text>
              </TouchableOpacity>
            ))}
            <TouchableOpacity style={s.pickCancel} onPress={() => setPickOpen(false)}>
              <Text style={s.pickCancelText}>取消</Text>
            </TouchableOpacity>
          </View>
        </TouchableOpacity>
      </Modal>
    </View>
  );
}

const s = StyleSheet.create({
  wrap: { marginBottom: 10, padding: 10, backgroundColor: tokens.colors.canvas, borderRadius: 8, borderWidth: 1, borderColor: tokens.colors.hairline },
  title: { fontSize: 11, color: tokens.colors.textSecondary, marginBottom: 8, letterSpacing: 1 },
  row: { alignItems: 'flex-start', marginBottom: 6 },
  col: { color: tokens.colors.textSecondary, fontSize: 12, marginBottom: 4 },
  pickerBtn: { height: 34, flexDirection: 'row', alignItems: 'center', borderWidth: 1, borderColor: tokens.colors.hairline, borderRadius: 8, paddingHorizontal: 10, backgroundColor: tokens.colors.white },
  pickerText: { fontSize: 13, color: tokens.colors.ink, flex: 1 },
  pickerArrow: { fontSize: 10, color: tokens.colors.textCaption },
  reason: { fontSize: 11, color: tokens.colors.primary, marginLeft: 8, flexShrink: 1 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  chip: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 6, backgroundColor: tokens.colors.surface },
  chipActive: { backgroundColor: tokens.colors.primary },
  chipText: { fontSize: 12, color: tokens.colors.textSecondary },
  chipTextActive: { color: tokens.colors.white, fontWeight: tokens.fontWeight.medium },
  reasonBlock: { fontSize: 11, color: tokens.colors.primary, lineHeight: 16 },
  pickBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'center', padding: 32 },
  pickCard: { backgroundColor: tokens.colors.white, borderRadius: 12, padding: 16 },
  pickTitle: { fontSize: tokens.fontSize.base, fontWeight: tokens.fontWeight.semibold, color: tokens.colors.ink, marginBottom: 12 },
  pickItem: { height: 44, justifyContent: 'center', paddingHorizontal: 12, borderRadius: 8, marginBottom: 4 },
  pickItemActive: { backgroundColor: tokens.colors.surface },
  pickItemText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary },
  pickItemTextActive: { color: tokens.colors.primary, fontWeight: tokens.fontWeight.semibold },
  pickCancel: { height: 44, alignItems: 'center', justifyContent: 'center', marginTop: 8 },
  pickCancelText: { fontSize: tokens.fontSize.base, color: tokens.colors.textSecondary },
});
