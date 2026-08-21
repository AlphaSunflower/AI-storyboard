import { useState } from 'react';
import { useProjectStore } from '../../stores/projectStore';
import { assetUrl } from '../../config';
import { DS } from '../agent/ChatComposer';
import { RefineImageModal } from './RefineImageModal';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';

interface MobileScenePreviewProps {
  onBack: () => void;
}

const labelStyle: React.CSSProperties = {
  display: 'block', fontSize: 12, color: 'var(--color-muted)', marginBottom: 4,
};
const selectStyle: React.CSSProperties = {
  padding: '6px 10px', borderRadius: 8,
  border: '1px solid var(--color-hairline)',
  fontSize: 12, background: 'white', color: 'var(--color-ink)',
  outline: 'none', cursor: 'pointer', width: '100%',
};

/** 手机端全屏分镜预览（含参数配置 + 完善图片） */
export function MobileScenePreview({ onBack }: MobileScenePreviewProps) {
  const {
    scenes, selectedSceneId, generateImage, generateVideo, setSceneParams,
    imageModelOptions, videoModelOptions, imageModel: globalImageModel, videoModel: globalVideoModel,
  } = useProjectStore();
  const scene = scenes.find((s) => s.id === selectedSceneId);
  const generatingImage = useProjectStore((s) => (selectedSceneId ? !!s.generatingImage[selectedSceneId] : false));
  const generatingVideo = useProjectStore((s) => (selectedSceneId ? !!s.generatingVideo[selectedSceneId] : false));

  const [activeTab, setActiveTab] = useState<'image' | 'video'>('image');
  const [refineTarget, setRefineTarget] = useState<string | null>(null);
  const [showParams, setShowParams] = useState(false);

  // 图片参数本地草稿
  const [imageDraft, setImageDraft] = useState<Record<string, unknown>>({});
  const [videoDraft, setVideoDraft] = useState<Record<string, unknown>>({});

  // 解析模型 params
  const effImageModel = (imageDraft.imageModel as string) || scene?.imageModel || globalImageModel;
  const effVideoModel = (videoDraft.videoModel as string) || scene?.videoModel || globalVideoModel;
  const imageModelData = imageModelOptions.find((m) => m.value === effImageModel);
  const videoModelData = videoModelOptions.find((m) => m.value === effVideoModel);

  const imageParams = parseParams(imageModelData);
  const videoParams = parseParams(videoModelData);
  const sizeOptions = imageParams.sizes?.length ? imageParams.sizes : ['1024x1024', '1536x1024', '1024x1536'];
  const qualityOptions = imageParams.qualities?.length ? imageParams.qualities : ['standard', 'hd'];
  const effImageSize = (imageDraft.imageSize as string) || scene?.imageSize || imageParams.sizeDefault || '1024x1024';
  const effImageQuality = (imageDraft.imageQuality as string) || scene?.imageQuality || imageParams.qualityDefault || 'standard';
  const durationOptions = videoParams.durations?.length ? videoParams.durations : [4, 6, 8];
  const resolutionOptions = videoParams.resolutions?.length ? videoParams.resolutions : ['768P', '2K'];
  const aspectOptions = videoParams.aspectRatios?.length ? videoParams.aspectRatios : ['16:9', '9:16', '4:3', '1:1'];
  const effVideoDuration = (videoDraft.duration as number) || scene?.duration || videoParams.durationDefault || 6;
  const effVideoResolution = (videoDraft.videoResolution as string) || scene?.videoResolution || videoParams.resolutionDefault || '768P';
  const effVideoAspect = (videoDraft.videoAspectRatio as string) || scene?.videoAspectRatio || videoParams.aspectRatioDefault || '16:9';

  // 多图列表
  const imageList = scene
    ? (scene.imageUrls ? scene.imageUrls.split(',').filter(Boolean) : (scene.imageUrl ? [scene.imageUrl] : []))
    : [];

  const commitAndGenerate = async (type: 'image' | 'video') => {
    if (!scene) return;
    const draft = type === 'image' ? imageDraft : videoDraft;
    const clear = type === 'image' ? () => setImageDraft({}) : () => setVideoDraft({});
    if (Object.keys(draft).length > 0) {
      await setSceneParams(scene.id, draft);
      clear();
    }
    if (type === 'image') {
      const prompt = scene.imagePrompt || '';
      if (!prompt.trim()) { alert('请先填写生图提示词'); return; }
      await generateImage(scene.id, prompt, effImageModel);
    } else {
      const prompt = scene.videoPrompt || '';
      if (!prompt.trim()) { alert('请先填写生视频提示词'); return; }
      await generateVideo(scene.id, prompt, effVideoModel);
    }
  };

  if (!scene) {
    return (
      <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: DS.textCaption, fontSize: 13 }}>
        未选中分镜
      </div>
    );
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: 'white' }}>
      {/* 顶栏 */}
      <div style={{
        padding: '10px 12px', borderBottom: `1px solid ${DS.border}`,
        display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0,
      }}>
        <button onClick={onBack} style={{
          width: 36, height: 36, border: 'none', background: 'transparent',
          borderRadius: 8, cursor: 'pointer', display: 'flex',
          alignItems: 'center', justifyContent: 'center', color: DS.ink,
        }}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M15 18l-6-6 6-6" /></svg>
        </button>
        <span style={{ fontSize: 16, fontWeight: 600, color: DS.ink, flex: 1 }}>
          分镜 {scene.sceneNumber}
        </span>
        {/* 参数配置开关 */}
        <button onClick={() => setShowParams(!showParams)} style={{
          width: 36, height: 36, border: 'none', background: showParams ? 'var(--color-surface-card)' : 'transparent',
          borderRadius: 8, cursor: 'pointer', display: 'flex',
          alignItems: 'center', justifyContent: 'center', color: showParams ? 'var(--color-primary)' : DS.textSecondary,
        }} title="生成参数">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-2 2 2 2 0 01-2-2v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83 0 2 2 0 010-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 01-2-2 2 2 0 012-2h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 010-2.83 2 2 0 012.83 0l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 012-2 2 2 0 012 2v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 0 2 2 0 010 2.83l-.06.06A1.65 1.65 0 0019.32 9a1.65 1.65 0 001.51 1H21a2 2 0 012 2 2 2 0 01-2 2h-.09a1.65 1.65 0 00-1.51 1z" /></svg>
        </button>
      </div>

      {/* 参数面板（可折叠） */}
      {showParams && (
        <div style={{
          padding: '10px 12px', borderBottom: `1px solid ${DS.border}`,
          background: 'var(--color-canvas)', display: 'flex', flexDirection: 'column', gap: 8,
          flexShrink: 0, maxHeight: '40vh', overflowY: 'auto',
        }}>
          <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--color-muted)' }}>
            {activeTab === 'image' ? '图片生成参数' : '视频生成参数'}
          </span>
          {activeTab === 'image' ? (
            <>
              <div>
                <label style={labelStyle}>模型</label>
                <Select value={effImageModel} onValueChange={(v) => { if (v) setImageDraft((d) => ({ ...d, imageModel: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {imageModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label style={labelStyle}>尺寸</label>
                <Select value={effImageSize} onValueChange={(v) => { if (v) setImageDraft((d) => ({ ...d, imageSize: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {sizeOptions.map((s) => <SelectItem key={s} value={s}>{s}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label style={labelStyle}>质量</label>
                <Select value={effImageQuality} onValueChange={(v) => { if (v) setImageDraft((d) => ({ ...d, imageQuality: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {qualityOptions.map((q) => <SelectItem key={q} value={q}>{q}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </>
          ) : (
            <>
              <div>
                <label style={labelStyle}>模型</label>
                <Select value={effVideoModel} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, videoModel: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {videoModelOptions.map((m) => <SelectItem key={m.value} value={m.value}>{m.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label style={labelStyle}>时长</label>
                <Select value={String(effVideoDuration)} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, duration: Number(v) })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {durationOptions.map((d) => <SelectItem key={d} value={String(d)}>{d}秒</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label style={labelStyle}>分辨率</label>
                <Select value={effVideoResolution} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, videoResolution: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {resolutionOptions.map((r) => <SelectItem key={r} value={r}>{r}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label style={labelStyle}>画幅</label>
                <Select value={effVideoAspect} onValueChange={(v) => { if (v) setVideoDraft((d) => ({ ...d, videoAspectRatio: v })); }}>
                  <SelectTrigger style={selectStyle}><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {aspectOptions.map((a) => <SelectItem key={a} value={a}>{a}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </>
          )}
        </div>
      )}

      {/* 内容区 */}
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 12 }}>
        {/* 剧本内容 */}
        {scene.scriptContent && (
          <div style={{
            padding: 10, borderRadius: 10, background: 'var(--color-canvas)',
            fontSize: 13, color: 'var(--color-body)', lineHeight: 1.6, marginBottom: 12,
          }}>{scene.scriptContent}</div>
        )}

        {/* Tab 切换 */}
        <div style={{ display: 'flex', gap: 4, marginBottom: 12 }}>
          {(['image', 'video'] as const).map((tab) => (
            <button key={tab} onClick={() => setActiveTab(tab)} style={{
              padding: '6px 14px', fontSize: 12, fontWeight: 500,
              border: 'none', borderRadius: 8, cursor: 'pointer',
              background: activeTab === tab ? 'var(--color-surface-card)' : 'transparent',
              color: activeTab === tab ? DS.ink : DS.textCaption,
            }}>{tab === 'image' ? '图片' : '视频'}</button>
          ))}
        </div>

        {/* 图片 tab */}
        {activeTab === 'image' && (
          <div>
            {imageList.length > 0 ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {imageList.map((url, i) => (
                  <img key={i} src={assetUrl(url)} alt={`分镜${scene.sceneNumber}-图${i + 1}`}
                    style={{ width: '100%', borderRadius: 10, objectFit: 'contain', maxHeight: 400 }} />
                ))}
              </div>
            ) : (
              <p style={{ color: DS.textCaption, fontSize: 13, textAlign: 'center', padding: 20 }}>暂无图片</p>
            )}
            {scene.imagePrompt && (
              <p style={{ fontSize: 12, color: DS.textCaption, marginTop: 8, lineHeight: 1.5 }}>
                提示词：{scene.imagePrompt}
              </p>
            )}
            <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
              <button
                onClick={() => commitAndGenerate('image')}
                disabled={generatingImage}
                style={{
                  flex: 1, height: 40, border: 'none', borderRadius: 10,
                  background: DS.brand, color: 'white', fontSize: 14, fontWeight: 500,
                  cursor: generatingImage ? 'not-allowed' : 'pointer', opacity: generatingImage ? 0.5 : 1,
                }}
              >{generatingImage ? '生成中...' : imageList.length ? '重新生成' : '生成图片'}</button>
              {imageList.length > 0 && (
                <button
                  onClick={() => setRefineTarget(imageList[0])}
                  style={{
                    height: 40, padding: '0 16px', border: `1px solid ${DS.brand}`,
                    borderRadius: 10, background: 'transparent', color: DS.brand,
                    fontSize: 14, fontWeight: 500, cursor: 'pointer',
                  }}
                >完善</button>
              )}
            </div>
          </div>
        )}

        {/* 视频 tab */}
        {activeTab === 'video' && (
          <div>
            {scene.videoUrl ? (
              <video src={assetUrl(scene.videoUrl)} controls
                style={{ width: '100%', borderRadius: 10, maxHeight: 400 }} />
            ) : (
              <p style={{ color: DS.textCaption, fontSize: 13, textAlign: 'center', padding: 20 }}>暂无视频</p>
            )}
            {scene.videoPrompt && (
              <p style={{ fontSize: 12, color: DS.textCaption, marginTop: 8, lineHeight: 1.5 }}>
                提示词：{scene.videoPrompt}
              </p>
            )}
            <button
              onClick={() => commitAndGenerate('video')}
              disabled={generatingVideo}
              style={{
                marginTop: 12, width: '100%', height: 40, border: 'none', borderRadius: 10,
                background: DS.brand, color: 'white', fontSize: 14, fontWeight: 500,
                cursor: generatingVideo ? 'not-allowed' : 'pointer', opacity: generatingVideo ? 0.5 : 1,
              }}
            >{generatingVideo ? '生成中...' : scene.videoUrl ? '重新生成' : '生成视频'}</button>
          </div>
        )}
      </div>

      {/* 完善图片弹窗 */}
      {refineTarget && scene && (
        <RefineImageModal
          imageUrl={refineTarget}
          sceneId={scene.id}
          onClose={() => setRefineTarget(null)}
        />
      )}
    </div>
  );
}

/** 解析模型 params JSON（与 PreviewPanel.parseParams 同逻辑） */
function parseParams(model: { params?: unknown } | undefined): {
  sizes?: string[]; sizeDefault?: string;
  qualities?: string[]; qualityDefault?: string;
  durations?: number[]; durationDefault?: number;
  resolutions?: string[]; resolutionDefault?: string;
  aspectRatios?: string[]; aspectRatioDefault?: string;
} {
  const out: Record<string, unknown> = {};
  if (!model?.params) return out;
  let p: Record<string, unknown>;
  try {
    p = typeof model.params === 'string' ? JSON.parse(model.params) : model.params;
  } catch { return out; }
  const arr = (k: string) => Array.isArray(p[k]) ? (p[k] as unknown[]).map(String) : undefined;
  out.sizes = arr('sizes');
  out.sizeDefault = p.sizeDefault != null ? String(p.sizeDefault) : undefined;
  out.qualities = arr('qualities');
  out.qualityDefault = p.qualityDefault != null ? String(p.qualityDefault) : undefined;
  if (p.durations && Array.isArray(p.durations)) out.durations = (p.durations as unknown[]).map(Number);
  out.durationDefault = p.durationDefault != null ? Number(p.durationDefault) : undefined;
  out.resolutions = arr('resolutions');
  out.resolutionDefault = p.resolutionDefault != null ? String(p.resolutionDefault) : undefined;
  out.aspectRatios = arr('aspectRatios');
  out.aspectRatioDefault = p.aspectRatioDefault != null ? String(p.aspectRatioDefault) : undefined;
  return out;
}
