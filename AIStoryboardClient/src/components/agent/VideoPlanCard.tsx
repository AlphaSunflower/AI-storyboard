import { useMemo, useState } from 'react';
import { useAgentStore, type VideoPlanInfo } from '../../stores/agentStore';
import { assetUrl } from '../../config';
import { AgentParamSelector } from './AgentParamSelector';
import { planCardPrefill } from '../../lib/generationPreferences';
import { Video } from 'lucide-react';

/** 图生视频方案确认卡片（后端 video_plan 事件）：视觉模型看图设计的方案，确认后生成 */
export function VideoPlanCard({
  info,
  submitted = false,
  archived = false,
  submittedActionId,
  submittedActionTitle,
}: {
  info: VideoPlanInfo;
  submitted?: boolean;
  archived?: boolean;
  submittedActionId?: string;
  submittedActionTitle?: string;
}) {
  const submitVideoPlan = useAgentStore((s) => s.submitVideoPlan);
  const skipCurrentHITL = useAgentStore((s) => s.skipCurrentHITL);
  const streaming = useAgentStore((s) => s.streaming);
  // 生成偏好：自定义模式下按偏好预选视频参数（键无前缀，与该卡的 AgentParamSelector 对齐）
  const generationPreferences = useAgentStore((s) => s.generationPreferences);
  const videoPrefill = useMemo(
    () => planCardPrefill(generationPreferences, 'video', info.models, ''),
    [generationPreferences, info.models],
  );
  // 卡片参数选择器的当前选择（模型/分辨率/时长/画幅；无选择器时为 {}）
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});
  // 自定义输入展开态：点「✍ 自定义输入」按钮展开内联输入框
  const [customOpen, setCustomOpen] = useState(false);
  const [customText, setCustomText] = useState('');

  return (
    <div style={{ display: 'flex', justifyContent: 'flex-start', marginBottom: 10 }}>
      <div
        style={{
          maxWidth: '82%', padding: 20, borderRadius: 16,
          background: 'var(--color-panel)', border: '1px solid var(--color-hairline)',
          boxShadow: '0 2px 8px rgba(20,20,19,0.06)', textAlign: 'left',
          maxHeight: 'min(56vh, 560px)', overflowY: 'auto', overflowX: 'hidden',
          scrollbarWidth: 'thin',
          opacity: archived ? 0.68 : 1,
          pointerEvents: archived ? 'none' : undefined,
        }}
      >
        <div style={{ fontSize: 13, color: 'var(--color-muted)', marginBottom: 8, letterSpacing: 1, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <Video size={13} strokeWidth={1.8} /> 视频方案已生成
          {submitted && submittedActionTitle && (
            <span style={{ color: 'var(--color-primary)', fontWeight: 500 }}>（已选择：{submittedActionTitle}）</span>
          )}
        </div>
        {/* 参考图缩略图 + 参数行 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
          {info.picUrl && (
            <img
              src={assetUrl(info.picUrl)}
              alt="参考图"
              style={{ width: 56, height: 56, borderRadius: 8, objectFit: 'cover', border: '1px solid var(--color-hairline)' }}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          )}
          <div style={{ fontSize: 11, color: 'var(--color-muted)', lineHeight: 1.6 }}>
            <div>时长 {info.duration} 秒 · 图生视频</div>
            <div>（首帧=你上传的参考图）</div>
          </div>
        </div>
        {/* 方案文本：完整展示，超出 8 行截断（50~120 字的视频方案 8 行足够） */}
        <div
          style={{
            fontSize: 15, color: 'var(--color-ink)', lineHeight: 1.7,
            marginBottom: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
            maxHeight: 420, overflowY: 'auto',
          }}
        >
          {info.message}
        </div>
        {/* 模型/参数选择器（后端下发 models 时渲染；推荐值默认选中，用户可改；自定义输入模式隐藏） */}
        {info.models && info.models.length > 0 && !customOpen && (
          <AgentParamSelector
            models={info.models}
            recommended={info.recommended}
            reasons={info.reasons}
            initialValue={videoPrefill}
            onParamsChange={setSelectedParams}
          />
        )}
        {/* 自定义输入框：点「✍ 自定义输入」后展开 */}
        {customOpen ? (
          <div style={{ marginBottom: 8 }}>
            <input
              autoFocus
              value={customText}
              onChange={(e) => setCustomText(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && customText.trim() && !streaming) {
                  submitVideoPlan('custom', selectedParams, customText.trim());
                }
              }}
              placeholder="输入你对视频的想法…"
              style={{
                width: '100%', boxSizing: 'border-box', padding: '6px 10px',
                border: '1px solid var(--color-hairline)', borderRadius: 'var(--rounded-md)',
                fontSize: 13, outline: 'none', marginBottom: 8,
              }}
            />
            <div style={{ display: 'flex', gap: 8 }}>
              <button
                disabled={streaming || !customText.trim()}
                onClick={() => submitVideoPlan('custom', selectedParams, customText.trim())}
                style={{
                  padding: '10px 18px', border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'var(--color-primary)', color: 'var(--color-on-primary)', fontSize: 15,
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
                  padding: '10px 18px', border: '1px solid var(--color-hairline)',
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
          <>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 8 }}>
              {info.actions.map((a) => {
                const primary = a.id === 'generate_video';
                const selected = a.id === submittedActionId;
                return (
                  <button
                    key={a.id}
                    disabled={streaming || submitted}
                    onClick={() => {
                      if (a.id === 'custom') { setCustomOpen(true); return; }
                      submitVideoPlan(a.id, selectedParams);
                    }}
                    style={{
                      padding: '10px 18px', borderRadius: 'var(--rounded-md)', fontSize: 15,
                      background: selected ? 'var(--color-primary)' : primary ? 'var(--color-primary)' : 'var(--color-panel)',
                      color: selected || primary ? 'var(--color-on-primary)' : 'var(--color-muted)',
                      border: primary ? 'none' : '1px solid var(--color-hairline)',
                      cursor: streaming || submitted ? 'not-allowed' : 'pointer', opacity: streaming || submitted ? 0.6 : 1,
                      width: '100%', textAlign: 'center',
                    }}
                  >
                    {a.title}
                    {selected && ' ✓'}
                  </button>
                );
              })}
            </div>
            {!submitted && (
              <button
                disabled={streaming}
                onClick={skipCurrentHITL}
                style={{
                  padding: '6px 12px', border: 'none', borderRadius: 'var(--rounded-md)',
                  background: 'transparent', color: 'var(--color-muted)', fontSize: 12,
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
                  width: '100%', textAlign: 'center',
                }}
              >
                跳过，换个话题
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}
