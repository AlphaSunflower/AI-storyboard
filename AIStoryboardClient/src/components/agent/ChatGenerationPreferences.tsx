import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Sparkles, SlidersHorizontal } from 'lucide-react';
import { useAgentStore } from '../../stores/agentStore';
import { useProjectStore } from '../../stores/projectStore';
import type { ModelOption } from '../../config';
import {
  readModelCapability,
  type ClarityParam,
  type MediaGenerationPreference,
  type MediaKind,
  type ModelPreferenceCapability,
} from '../../lib/generationPreferences';
import { useAbovePopoverPlacement } from './abovePopover';
import GlideSelect from '../ui/GlideSelect';
import SquishSwitch from '../ui/SquishSwitch';
import './ChatGenerationPreferences.css';

/**
 * 输入卡「生成偏好」入口（仿即梦）：默认「自动」由智能体决定，关掉变「自定义」，
 * 图片 / 视频各自可选比例（智能 = 交给智能体）、清晰度、模型；底部独立开关
 * 「忽略对话中的生成方案」控制对话里的生成方案确认卡是否跳过。
 */
const PANEL_WIDTH = 344;

function isDefaultModel(model: ModelOption): boolean {
  return model.default === true || model.default === 'true';
}

export function ChatGenerationPreferences({ disabled = false }: { disabled?: boolean }) {
  const { generationPreferences, updateGenerationPreferences } = useAgentStore();
  const imageModels = useProjectStore((state) => state.imageModelOptions);
  const videoModels = useProjectStore((state) => state.videoModelOptions);
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<MediaKind>('image');
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const placement = useAbovePopoverPlacement(open, buttonRef, { gap: 10, width: PANEL_WIDTH });

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (rootRef.current?.contains(target)) return;
      if (panelRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  const models = tab === 'image' ? imageModels : videoModels;
  const preference = tab === 'image' ? generationPreferences.image : generationPreferences.video;

  const modelOptions = useMemo(
    () =>
      models.map((model) => ({
        value: model.value,
        label: model.label || model.value,
        ...(isDefaultModel(model) ? { tag: '默认' } : {}),
      })),
    [models],
  );

  // 存的是已下架模型时回落到网关默认模型（无默认则第一个）
  const effectiveModel = useMemo(() => {
    if (preference.model && models.some((model) => model.value === preference.model)) {
      return preference.model;
    }
    return models.find(isDefaultModel)?.value ?? models[0]?.value ?? '';
  }, [models, preference.model]);

  const capability: ModelPreferenceCapability | null = useMemo(() => {
    const model = models.find((item) => item.value === effectiveModel);
    return readModelCapability(model, tab);
  }, [models, effectiveModel, tab]);

  const effectiveClarity = useMemo(() => {
    const options = capability?.clarity?.options ?? [];
    if (preference.clarity && options.includes(preference.clarity)) return preference.clarity;
    return capability?.clarity?.default || options[0] || '';
  }, [capability, preference.clarity]);

  const effectiveRatio = useMemo(() => {
    if (!preference.aspectRatio) return '';
    return (capability?.ratioOptions ?? []).includes(preference.aspectRatio) ? preference.aspectRatio : '';
  }, [capability, preference.aspectRatio]);

  const patchMedia = (kind: MediaKind, patch: Partial<MediaGenerationPreference>) => {
    updateGenerationPreferences((previous) => ({
      ...previous,
      [kind]: { ...previous[kind], ...patch },
    }));
  };

  const setAuto = (auto: boolean) => {
    updateGenerationPreferences((previous) => ({ ...previous, auto }));
  };

  const setIgnorePlan = (ignoreGenerationPlan: boolean) => {
    updateGenerationPreferences((previous) => ({ ...previous, ignoreGenerationPlan }));
  };

  const setRatio = (aspectRatio: string) => patchMedia(tab, { aspectRatio });

  const setClarity = (clarity: string) => {
    patchMedia(tab, { clarity, clarityParam: (capability?.clarity?.param ?? '') as ClarityParam | '' });
  };

  const setModel = (model: string) => {
    const next = readModelCapability(models.find((item) => item.value === model), tab);
    const keepClarity = !!preference.clarity && !!next?.clarity?.options.includes(preference.clarity);
    const keepRatio = !!preference.aspectRatio && !!next?.ratioOptions.includes(preference.aspectRatio);
    patchMedia(tab, {
      model,
      clarity: keepClarity ? preference.clarity : '',
      clarityParam: keepClarity ? ((next?.clarity?.param ?? '') as ClarityParam | '') : '',
      aspectRatio: keepRatio ? preference.aspectRatio : '',
    });
  };

  const locked = generationPreferences.auto || disabled;
  const ratioOptions = capability?.ratioOptions ?? [];
  const clarityOptions = capability?.clarity?.options ?? [];
  const clarityLabel = capability?.clarity?.label ?? '清晰度';

  return (
    <>
      <div ref={rootRef} style={{ position: 'relative', minWidth: 0 }}>
        <button
          ref={buttonRef}
          type="button"
          aria-haspopup="dialog"
          aria-expanded={open}
          aria-label={`生成偏好：${generationPreferences.auto ? '自动' : '自定义'}`}
          disabled={disabled}
          onClick={() => setOpen((value) => !value)}
          className="chat-generation-prefs__trigger"
          data-open={open ? '' : undefined}
          data-custom={generationPreferences.auto ? undefined : ''}
        >
          <SlidersHorizontal size={15} strokeWidth={1.8} />
          <span>{generationPreferences.auto ? '自动' : '自定义'}</span>
        </button>
      </div>
      {open && placement && createPortal(
        <div
          ref={panelRef}
          role="dialog"
          aria-label="生成偏好"
          className="chat-generation-prefs__panel"
          style={{
            position: 'fixed',
            left: placement.left,
            bottom: placement.bottom,
            width: PANEL_WIDTH,
            maxHeight: Math.max(220, placement.maxHeight),
          }}
        >
          <div className="chat-generation-prefs__head">
            <span className="chat-generation-prefs__title">生成偏好</span>
            <span className="chat-generation-prefs__auto">
              <span>自动</span>
              <SquishSwitch
                checked={generationPreferences.auto}
                onChange={setAuto}
                disabled={disabled}
                ariaLabel="自动生成偏好"
                width={44}
                height={24}
                radius={12}
                speed={60}
                stretch={30}
              />
            </span>
          </div>

          <div className="chat-generation-prefs__tabs" role="tablist" aria-label="媒体类型">
            {(['image', 'video'] as const).map((kind) => (
              <button
                key={kind}
                type="button"
                role="tab"
                aria-selected={tab === kind}
                className="chat-generation-prefs__tab"
                data-active={tab === kind ? '' : undefined}
                onClick={() => setTab(kind)}
              >
                {kind === 'image' ? '图片' : '视频'}
              </button>
            ))}
          </div>

          <div className="chat-generation-prefs__section" data-locked={locked ? '' : undefined}>
            <div className="chat-generation-prefs__section-title">选择比例</div>
            <div className="chat-generation-prefs__ratios">
              <button
                type="button"
                className="chat-generation-prefs__ratio"
                data-active={effectiveRatio === '' ? '' : undefined}
                disabled={locked}
                aria-label="智能比例"
                onClick={() => setRatio('')}
              >
                <Sparkles size={16} strokeWidth={1.8} />
                <span>智能</span>
              </button>
              {ratioOptions.map((ratio) => {
                const [w, h] = ratio.split(':').map((part) => Number(part));
                const scale = w > 0 && h > 0 ? Math.min(1, 1 / Math.max(w / h, h / w)) : 1;
                return (
                  <button
                    key={ratio}
                    type="button"
                    className="chat-generation-prefs__ratio"
                    data-active={effectiveRatio === ratio ? '' : undefined}
                    disabled={locked}
                    aria-label={`比例 ${ratio}`}
                    onClick={() => setRatio(ratio)}
                  >
                    <span
                      className="chat-generation-prefs__ratio-shape"
                      style={w > 0 && h > 0 ? { width: Math.round(20 * w * scale), height: Math.round(20 * h * scale) } : undefined}
                      aria-hidden="true"
                    />
                    <span>{ratio}</span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="chat-generation-prefs__section" data-locked={locked ? '' : undefined}>
            <div className="chat-generation-prefs__section-title">其他设置</div>
            <div className="chat-generation-prefs__selects">
              <GlideSelect
                options={modelOptions}
                value={effectiveModel}
                onChange={setModel}
                disabled={locked || modelOptions.length === 0}
                ariaLabel="生成模型"
                placeholder="默认模型"
                size="sm"
                menuWidth={200}
                placement="top"
              />
              <GlideSelect
                options={clarityOptions.map((value) => ({ value, label: value }))}
                value={effectiveClarity}
                onChange={setClarity}
                disabled={locked || clarityOptions.length === 0}
                ariaLabel={clarityLabel}
                placeholder={clarityLabel}
                size="sm"
                menuWidth={160}
                placement="top"
                align="right"
              />
            </div>
          </div>

          <div className="chat-generation-prefs__foot">
            <span>忽略对话中的生成方案</span>
            <SquishSwitch
              checked={generationPreferences.ignoreGenerationPlan}
              onChange={setIgnorePlan}
              disabled={disabled}
              ariaLabel="忽略对话中的生成方案"
              width={44}
              height={24}
              radius={12}
              speed={60}
              stretch={30}
            />
          </div>
        </div>,
        document.body,
      )}
    </>
  );
}
