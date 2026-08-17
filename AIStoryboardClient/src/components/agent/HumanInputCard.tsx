import { useState } from 'react';
import { useAgentStore, type HumanInputInfo } from '../../stores/agentStore';
import { AgentParamSelector } from './AgentParamSelector';
import { assetUrl } from '../../config';

/**
 * 人工确认卡片（human_input 事件）：渲染 actions 选项按钮；
 * id=custom 的「自定义输入」选项点击后展开内联输入框，用户可输入选项之外的想法。
 * 后端下发 models 时渲染模型/参数选择器（如图片确认卡片可选模型/尺寸），提交时携带所选。
 * 后端下发 assets 时渲染资产勾选列表（默认全选），asset-confirm 提交携带勾选 ID、asset-skip 携带空数组。
 */
export function HumanInputCard({ info }: { info: HumanInputInfo }) {
  const submitHumanInput = useAgentStore((s) => s.submitHumanInput);
  const streaming = useAgentStore((s) => s.streaming);
  const expired = info.expirationTime > 0 && Date.now() / 1000 > info.expirationTime;
  // 自定义输入展开态：点「自定义输入」按钮展开内联输入框，确认后 submitHumanInput('custom', text)
  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState('');
  // 卡片参数选择器的当前选择（模型/尺寸等；无选择器时为 {}）
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});
  // aisplit 分镜卡片：图片/视频两个分区选择器的提交值（键带 image*/video* 前缀）
  const mergeParams = (p: Record<string, string>) => setSelectedParams((prev) => ({ ...prev, ...p }));
  // 分区选择器模式：后端下发 imageModels/videoModels 时渲染图片+视频两组；否则保持原单选择器逻辑
  const hasSplitSelectors = !!((info.imageModels && info.imageModels.length > 0) || (info.videoModels && info.videoModels.length > 0));
  // 资产勾选状态：默认全选（Set<assetId>）
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
  // 资产卡片按钮提交：asset-confirm 携带勾选 ID；asset-skip 携带空数组（不使用资产）
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

  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 10 }}>
      <div style={{ maxWidth: '82%', padding: 12, borderRadius: 12, background: 'white', border: '1px solid var(--color-hairline)', boxShadow: '0 2px 8px rgba(20,20,19,0.06)', textAlign: 'left' }}>
        <div style={{ fontSize: 11, color: 'var(--color-muted)', marginBottom: 6, letterSpacing: 1 }}>需要您确认</div>
        <div style={{ fontSize: 13, color: 'var(--color-ink)', lineHeight: 1.6, marginBottom: 10, whiteSpace: 'pre-wrap' }}>
          {info.formContent || '请确认是否继续？'}
        </div>
        {/* 资产勾选列表：默认全选，取消勾选则不投入；提交走 asset-confirm / asset-skip */}
        {info.assets && info.assets.length > 0 && (
          <div style={{ marginBottom: 10, border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)', padding: 8 }}>
            {info.assets.map((a) => (
              <label
                key={a.id}
                style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 0', cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1 }}
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
                    style={{ width: 28, height: 28, borderRadius: 6, objectFit: 'cover', border: '1px solid var(--color-hairline)' }}
                  />
                )}
                <span style={{ fontSize: 13, color: 'var(--color-ink)' }}>{a.name}</span>
                <span style={{ fontSize: 11, color: 'var(--color-muted)', background: 'var(--color-surface)', padding: '1px 6px', borderRadius: 4 }}>
                  {typeLabel(a.type)}
                </span>
              </label>
            ))}
          </div>
        )}
        {/* 模型/参数选择器：aisplit 分镜卡片渲染图片+视频两组（LLM 推荐预选+理由）；其余卡片保持单选择器 */}
        {hasSplitSelectors ? (
          <div style={{ marginBottom: 10 }}>
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
          <div style={{ fontSize: 12, color: 'var(--color-warning)' }}>确认已过期，请重新发起对话</div>
        ) : customOpen ? (
          <div>
            <input
              autoFocus
              value={customText}
              onChange={(e) => setCustomText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && customText.trim() && !streaming) {
                  submitHumanInput('custom', customText.trim(), selectedParams);
                }
              }}
              placeholder="输入你的想法…"
              style={{
                width: '100%', boxSizing: 'border-box', padding: '6px 10px',
                border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
                fontSize: 13, outline: 'none', marginBottom: 8,
              }}
            />
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                disabled={streaming || !customText.trim()}
                onClick={() => submitHumanInput('custom', customText.trim(), selectedParams)}
                style={{
                  padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-primary)', color: 'white', fontSize: 13,
                  cursor: streaming || !customText.trim() ? 'not-allowed' : 'pointer',
                  opacity: streaming || !customText.trim() ? 0.6 : 1,
                }}
              >
                确认输入
              </button>
              <button
                disabled={streaming}
                onClick={() => { setCustomOpen(false); setCustomText(''); }}
                style={{
                  padding: '6px 16px', border: '1px solid var(--color-hairline)',
                  borderRadius: 'var(--rounded-md)', background: 'transparent',
                  color: 'var(--color-muted)', fontSize: 13,
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
                }}
              >
                取消
              </button>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {info.actions.map((a) => (
              <button
                key={a.id}
                disabled={streaming}
                onClick={() => handleActionClick(a)}
                style={{
                  padding: '6px 16px', border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-primary)', color: 'white', fontSize: 13,
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
                }}
              >
                {a.title}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
