import { useState } from 'react';
import { useAgentStore, type VideoPlanInfo } from '../stores/agentStore';
import { assetUrl } from '../config';
import { AgentParamSelector } from './AgentParamSelector';

/** 图生视频方案确认卡片(后端 video_plan 事件):视觉模型看图设计的方案,确认后生成 */
export function VideoPlanCard({ info }: { info: VideoPlanInfo }) {
  const submitVideoPlan = useAgentStore((s) => s.submitVideoPlan);
  const streaming = useAgentStore((s) => s.streaming);
  // 卡片参数选择器的当前选择(模型/分辨率/时长/画幅;无选择器时为 {})
  const [selectedParams, setSelectedParams] = useState<Record<string, string>>({});

  return (
    <div className="flex justify-start mb-7">
      <div className="w-full text-left" style={{
        padding: 26, borderRadius: 18,
        background: 'white', border: '1px solid var(--color-border)',
        boxShadow: '0 1px 6px rgba(20,20,19,0.04)',
      }}>
        <div className="flex items-center gap-2 mb-4">
          <span style={{ fontSize: 16 }}>📹</span>
          <span className="text-[15px] font-semibold" style={{ color: 'var(--color-muted)' }}>视频方案已生成</span>
        </div>
        {/* 参考图缩略图 + 参数行 */}
        <div className="flex items-center gap-3 mb-4">
          {info.picUrl && (
            <img
              src={assetUrl(info.picUrl)}
              alt="参考图"
              style={{ width: 72, height: 72, borderRadius: 12, objectFit: 'cover', border: '1px solid var(--color-border)' }}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          )}
          <div style={{ fontSize: 15, color: 'var(--color-muted)', lineHeight: 1.7 }}>
            <div>时长 {info.duration} 秒 · 图生视频</div>
            <div>（首帧=你上传的参考图）</div>
          </div>
        </div>
        {/* 方案文本:完整展示,超出 8 行截断(50~120 字的视频方案 8 行足够) */}
        <div style={{
          fontSize: 17, color: 'var(--color-ink)', lineHeight: 1.75,
          marginBottom: 16, whiteSpace: 'pre-wrap', maxHeight: 240, overflow: 'hidden',
        }}>
          {info.message}
        </div>
        {/* 模型/参数选择器(后端下发 models 时渲染;推荐值默认选中,用户可改) */}
        {info.models && info.models.length > 0 && (
          <AgentParamSelector
            models={info.models}
            recommended={info.recommended}
            reasons={info.reasons}
            onParamsChange={setSelectedParams}
          />
        )}
        <div className="flex gap-3">
          {info.actions.map((a) => {
            const primary = a.id === 'generate_video';
            return (
              <button
                key={a.id}
                disabled={streaming}
                onClick={() => submitVideoPlan(a.id, selectedParams)}
                style={{
                  padding: '13px 20px', borderRadius: 12, fontSize: 16,
                  fontWeight: primary ? 500 : 400,
                  background: primary ? 'var(--color-primary)' : 'white',
                  color: primary ? 'var(--color-on-primary)' : 'var(--color-muted)',
                  border: primary ? 'none' : '1px solid var(--color-border)',
                  cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.6 : 1,
                }}
              >
                {a.title}
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
