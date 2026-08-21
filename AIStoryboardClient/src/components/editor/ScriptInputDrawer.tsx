import { useState, useRef } from 'react';
import { DS } from '../agent/ChatComposer';
import { useProjectStore } from '../../stores/projectStore';
import { useAgentStore } from '../../stores/agentStore';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';

interface ScriptInputDrawerProps {
  onClose: () => void;
}

const labelStyle: React.CSSProperties = {
  display: 'block', fontSize: 12, color: 'var(--color-muted)', marginBottom: 4,
};
const sharedInputStyle: React.CSSProperties = {
  width: '100%', padding: '8px 10px', borderRadius: 'var(--rounded-md)',
  border: '1px solid var(--color-hairline)', fontSize: 13,
  background: 'white', color: 'var(--color-body)', outline: 'none',
};

/** 手机端右侧剧本输入抽屉（80vw, max 360px）：含模型选择 */
export function ScriptInputDrawer({ onClose }: ScriptInputDrawerProps) {
  const {
    currentProject, generateScript, createProject, videoPreset,
    imageModel, videoModel, understandingModel,
    imageModelOptions, videoModelOptions, understandingModelOptions,
    setImageModel, setVideoModel, setUnderstandingModel,
  } = useProjectStore();
  const agentGeneratedScenes = useAgentStore((s) => s.agentGeneratedScenes);

  const [scriptText, setScriptText] = useState('');
  const [generating, setGenerating] = useState(false);
  const [refImages, setRefImages] = useState<string[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleRefImages = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    const valid = files.filter((f) => f.size <= 10 * 1024 * 1024).slice(0, 3 - refImages.length);
    Promise.all(valid.map((f) => new Promise<string>((r) => {
      const reader = new FileReader();
      reader.onloadend = () => r(reader.result as string);
      reader.readAsDataURL(f);
    }))).then((uris) => setRefImages((p) => [...p, ...uris]));
    e.target.value = '';
  };

  const handleGenerate = async () => {
    if (!scriptText.trim() || generating) return;
    setGenerating(true);
    try {
      let projectId = currentProject?.id;
      if (!projectId) {
        const preset = resolveVideoPreset(videoPreset);
        const p = await createProject('未命名项目', 'movie', preset.aspectRatio);
        projectId = p.id;
      }
      const preset = resolveVideoPreset(videoPreset);
      await generateScript(
        projectId, scriptText, 'movie', preset.aspectRatio, undefined,
        refImages.length ? understandingModel : undefined,
        refImages.length ? refImages : undefined,
      );
      onClose();
    } catch { /* store 已处理 */ } finally {
      setGenerating(false);
    }
  };

  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, zIndex: 100, background: 'rgba(0,0,0,0.35)' }}>
        <div onClick={(e) => e.stopPropagation()} style={{
          position: 'absolute', top: 0, right: 0, bottom: 0,
          width: '80vw', maxWidth: 360, background: 'white',
          display: 'flex', flexDirection: 'column',
          boxShadow: '-4px 0 24px rgba(0,0,0,0.12)',
          animation: 'scriptDrawerIn 0.25s ease-out',
        }}>
          {/* 头部 */}
          <div style={{
            padding: '14px 16px', borderBottom: `1px solid ${DS.border}`,
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <span style={{ fontSize: 16, fontWeight: 600, color: DS.ink }}>剧本输入</span>
            <button onClick={onClose} style={{
              width: 32, height: 32, border: 'none', background: 'transparent',
              borderRadius: 8, cursor: 'pointer', fontSize: 18, color: DS.textSecondary,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>✕</button>
          </div>

          {/* 内容 */}
          <div style={{ flex: 1, padding: 16, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 14 }}>
            {/* 理解模型 */}
            <div>
              <label style={labelStyle}>理解模型（分析参考图）</label>
              <Select value={understandingModel} onValueChange={(v) => v && setUnderstandingModel(v)}>
                <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {understandingModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>

            {/* 生图模型 */}
            <div>
              <label style={labelStyle}>生图模型</label>
              <Select value={imageModel} onValueChange={(v) => v && setImageModel(v)}>
                <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {imageModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>

            {/* 生视频模型 */}
            <div>
              <label style={labelStyle}>生视频模型</label>
              <Select value={videoModel} onValueChange={(v) => v && setVideoModel(v)}>
                <SelectTrigger style={{ ...sharedInputStyle, cursor: 'pointer', width: '100%' }}><SelectValue /></SelectTrigger>
                <SelectContent>
                  {videoModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                </SelectContent>
              </Select>
            </div>

            {/* 参考图上传 */}
            <div>
              <div onClick={() => fileInputRef.current?.click()} style={{
                padding: '8px 10px', borderRadius: 10,
                border: '1px dashed var(--color-hairline)', cursor: 'pointer',
                background: 'var(--color-canvas)', fontSize: 12, color: DS.textCaption,
              }}>
                + 上传参考图（可选，最多 3 张）
              </div>
              <input ref={fileInputRef} type="file" accept="image/*" multiple hidden onChange={handleRefImages} />
              {refImages.length > 0 && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
                  {refImages.map((uri, i) => (
                    <div key={i} style={{ position: 'relative' }}>
                      <img src={uri} style={{ width: 56, height: 56, objectFit: 'contain', borderRadius: 6, background: 'var(--color-surface-soft)', border: '1px solid var(--color-hairline)' }} />
                      <span onClick={() => setRefImages((p) => p.filter((_, j) => j !== i))} style={{
                        position: 'absolute', top: -5, right: -5,
                        background: 'var(--color-error)', color: 'white',
                        borderRadius: '50%', width: 16, height: 16, fontSize: 11,
                        display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
                      }}>×</span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* 剧本输入 */}
            <textarea
              value={scriptText}
              onChange={(e) => setScriptText(e.target.value)}
              placeholder="输入剧本内容，AI 将自动生成分镜脚本..."
              disabled={agentGeneratedScenes}
              style={{
                flex: 1, minHeight: 120, padding: 12,
                border: `1px solid ${DS.border}`, borderRadius: 12,
                fontSize: 14, lineHeight: 1.6, color: DS.ink,
                resize: 'none', outline: 'none', fontFamily: 'inherit',
                background: agentGeneratedScenes ? '#f5f5f5' : 'white',
              }}
            />
            {agentGeneratedScenes && (
              <p style={{ fontSize: 12, color: DS.textCaption, margin: 0 }}>
                智能体已生成分镜，手动输入暂时禁用
              </p>
            )}
          </div>

          {/* 底部按钮 */}
          <div style={{ padding: '12px 16px', borderTop: `1px solid ${DS.border}` }}>
            <button
              onClick={handleGenerate}
              disabled={!scriptText.trim() || generating || agentGeneratedScenes}
              style={{
                width: '100%', height: 44, border: 'none', borderRadius: 12,
                background: DS.brand, color: 'white', fontSize: 15, fontWeight: 600,
                cursor: !scriptText.trim() || generating || agentGeneratedScenes ? 'not-allowed' : 'pointer',
                opacity: !scriptText.trim() || generating || agentGeneratedScenes ? 0.45 : 1,
              }}
            >{generating ? '生成中...' : '生成分镜脚本'}</button>
          </div>
        </div>
        <style>{`@keyframes scriptDrawerIn { from { transform: translateX(100%); } to { transform: translateX(0); } }`}</style>
      </div>
    </>
  );
}
