import { useState, useRef } from 'react';
import gsap from 'gsap';
import { useGSAP } from '@gsap/react';
import { useAgentStore, type HumanInputInfo } from '../stores/agentStore';
import { cn } from '../lib/utils';
import { AgentParamSelector } from './AgentParamSelector';
import { assetUrl } from '../config';
import { Clock, AlertCircle } from 'lucide-react';

/**
 * 人工确认卡片(human_input 事件):渲染 actions 选项按钮;
 * id=custom 的「自定义输入」选项点击后展开内联输入框,用户可输入选项之外的想法。
 * 后端下发 models 时渲染模型/参数选择器(如图片确认卡片可选模型/尺寸),提交时携带所选。
 * 后端下发 assets 时渲染资产勾选列表(默认全选),asset-confirm 提交携带勾选 ID、asset-skip 携带空数组。
 */
export function HumanInputCard({ info }: { info: HumanInputInfo }) {
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const streaming = useAgentStore((s) => s.streaming);
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;
  // 自定义输入展开态:点「自定义输入」按钮展开内联输入框,确认后 submitHumanInput('custom', text)
  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState('');
  // 卡片参数选择器的当前选择(模型/尺寸等;无选择器时为 {})
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});
  // aisplit 分镜卡片:图片/视频两个分区选择器的提交值(键带 image*/video* 前缀)
  const mergeParams = (p: Record<string, string>) => setSelectedParams((prev) => ({ ...prev, ...p }));
  // 分区选择器模式:后端下发 imageModels/videoModels 时渲染图片+视频两组;否则保持原单选择器逻辑
  const hasSplitSelectors = !!((info.imageModels && info.imageModels.length > 0) || (info.videoModels && info.videoModels.length > 0));
  // 资产勾选状态:默认全选(Set<assetId>)
  const [selectedAssets, setSelectedAssets] = useState<Set<string>>(
    () => new Set((info.assets ?? []).map((a) => a.id)),
  );
  const toggleAsset = (id: string) =>
    setSelectedAssets((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const typeLabel = (t: string) => (t === 'character' ? '人物' : t === 'prop' ? '道具' : t === 'scene' ? '场景' : t);
  // 资产卡片按钮提交:asset-confirm 携带勾选 ID;asset-skip 携带空数组(不使用资产)
  const handleActionClick = (a: { id: string; title: string }) => {
    if (a.id === 'asset-confirm') {
      submitHumanInput(a.id, undefined, selectedParams, Array.from(selectedAssets));
    } else if (a.id === 'asset-skip') {
      submitHumanInput(a.id, undefined, selectedParams, []);
    } else if (a.id === 'custom') {
      setCustomOpen(true);
    } else {
      submitHumanInput(a.id, undefined, selectedParams);
    }
  };
  const cardRef = useRef<HTMLDivElement>(null);

  useGSAP(() => {
    if (!cardRef.current) return;
    gsap.fromTo(cardRef.current, { y: 8, opacity: 0 }, { y: 0, opacity: 1, duration: 0.25, ease: 'power2.out' });
  }, { scope: cardRef });

  return (
    <div className="flex justify-start mb-7">
      <div ref={cardRef} className="w-full text-left" style={{
        padding: 26, borderRadius: 18,
        background: 'white', border: '1px solid var(--color-border)',
        boxShadow: '0 1px 6px rgba(20,20,19,0.04)',
      }}>
        <div className="flex items-center gap-2 mb-4">
          <Clock size={16} style={{ color: 'var(--color-accent-amber)' }} />
          <span className="text-[15px] font-semibold" style={{ color: 'var(--color-accent-amber)' }}>需要您确认</span>
        </div>
        <div className="text-[18px] leading-[1.75] mb-5 whitespace-pre-wrap" style={{ color: 'var(--color-ink)' }}>
          {info.formContent || '请确认是否继续？'}
        </div>

        {/* 资产勾选列表:默认全选,取消勾选则不投入;提交走 asset-confirm / asset-skip */}
        {info.assets && info.assets.length > 0 && (
          <div style={{ marginBottom: 12, border: '1px solid var(--color-border)', borderRadius: 10, padding: 10 }}>
            {info.assets.map((a) => (
              <label
                key={a.id}
                className="flex items-center gap-2.5"
                style={{ padding: '5px 0', cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1 }}
              >
                <input
                  type="checkbox"
                  checked={selectedAssets.has(a.id)}
                  disabled={streaming}
                  onChange={() => toggleAsset(a.id)}
                  style={{ accentColor: 'var(--color-primary)', cursor: 'inherit' }}
                />
                {a.image && (
                  <img
                    src={assetUrl(a.image)}
                    alt={a.name}
                    style={{ width: 30, height: 30, borderRadius: 8, objectFit: 'cover', border: '1px solid var(--color-border)' }}
                  />
                )}
                <span style={{ fontSize: 15, color: 'var(--color-ink)' }}>{a.name}</span>
                <span style={{ fontSize: 13, color: 'var(--color-muted)', background: 'var(--color-surface-soft)', padding: '2px 8px', borderRadius: 6 }}>
                  {typeLabel(a.type)}
                </span>
              </label>
            ))}
          </div>
        )}

        {/* 模型/参数选择器:aisplit 分镜卡片渲染图片+视频两组(LLM 推荐预选+理由);其余卡片保持单选择器 */}
        {hasSplitSelectors ? (
          <div style={{ marginBottom: 12 }}>
            {info.imageModels && info.imageModels.length > 0 && (
              <AgentParamSelector
                keyPrefix="image"
                models={info.imageModels}
                recommended={info.recommended}
                reasons={info.reasons}
                onParamsChange={mergeParams}
              />
            )}
            {info.videoModels && info.videoModels.length > 0 && (
              <AgentParamSelector
                keyPrefix="video"
                models={info.videoModels}
                recommended={info.recommended}
                reasons={info.reasons}
                onParamsChange={mergeParams}
              />
            )}
          </div>
        ) : info.models && info.models.length > 0 && !customOpen ? (
          <AgentParamSelector
            models={info.models}
            recommended={info.recommended}
            reasons={info.reasons}
            onParamsChange={setSelectedParams}
          />
        ) : null}

        {expired ? (
          <div className="flex items-center gap-2 text-[15px]" style={{ color: 'var(--color-warning)' }}>
            <AlertCircle size={16} /> 确认已过期，请重新发起对话
          </div>
        ) : customOpen ? (
          <div>
            <input autoFocus value={customText} onChange={(e) => setCustomText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && customText.trim() && !streaming) {
                  submitHumanInput('custom', customText.trim(), selectedParams);
                }
              }}
              placeholder="输入你的想法…"
              className="w-full px-4 py-2.5 text-[16px] outline-none mb-3 rounded-[10px]"
              style={{ border: '1px solid var(--color-border)', color: 'var(--color-ink)', background: 'white' }} />
            <div className="flex gap-2.5">
              <button disabled={streaming || !customText.trim()}
                onClick={() => submitHumanInput('custom', customText.trim(), selectedParams)}
                className="px-5 py-2.5 text-[15px] font-medium rounded-[10px] transition-all"
                style={{ background: 'var(--color-primary)', color: 'var(--color-on-primary)', opacity: streaming || !customText.trim() ? 0.5 : 1 }}>
                确认
              </button>
              <button disabled={streaming} onClick={() => { setCustomOpen(false); setCustomText(''); }}
                className="px-5 py-2.5 text-[15px] rounded-[10px] transition-all"
                style={{ border: '1px solid var(--color-border)', background: 'white', color: 'var(--color-muted)' }}>
                取消
              </button>
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {info.actions.map((a) => (
              <button key={a.id} disabled={streaming}
                onClick={() => handleActionClick(a)}
                className={cn('w-full px-5 py-4 text-[17px] rounded-[12px] text-left font-medium transition-all active:scale-[0.98]')}
                style={{
                  background: a.id === 'custom' ? 'white' : 'var(--color-primary)',
                  color: a.id === 'custom' ? 'var(--color-body)' : 'var(--color-on-primary)',
                  border: a.id === 'custom' ? '1px solid var(--color-border)' : 'none',
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.5 : 1,
                }}>
                {a.title}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
