import { useEffect, useRef, useState } from 'react';
import { ArrowLeft } from 'lucide-react';
import { useProjectStore } from '../../stores/projectStore';
import { IMAGE_QUALITIES, IMAGE_SIZES, assetUrl, isSeedreamImageModel } from '../../config';
import { getSceneImageUrls } from '../../lib/sceneImages';
import { sceneApi } from '../../api/scenes';
import { RefineImageModal } from './RefineImageModal';
import { resolveVideoPreset } from '../common/VideoPresetSelector';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '../ui/select';
import './MobileScenePreview.css';

interface MobileScenePreviewProps {
  onBack: () => void;
}

type PromptField = 'scriptContent' | 'imagePrompt' | 'videoPrompt';
type PromptDraft = Record<PromptField, string>;
type PromptSaveState = 'idle' | 'saving' | 'saved' | 'error';

const EMPTY_PROMPTS: PromptDraft = {
  scriptContent: '',
  imagePrompt: '',
  videoPrompt: '',
};

/** 手机端分镜详情：提示词、生成参数、预览与生成集中在一个任务流里。 */
export function MobileScenePreview({ onBack }: MobileScenePreviewProps) {
  const {
    scenes,
    selectedSceneId,
    generateImage,
    generateVideo,
    setSceneParams,
    imageModelOptions,
    videoModelOptions,
    imageModel: globalImageModel,
    videoModel: globalVideoModel,
    imageSize: globalImageSize,
    imageQuality: globalImageQuality,
    imageN: globalImageN,
    videoPreset,
    currentProject,
    updateSceneInStore,
  } = useProjectStore();
  const scene = scenes.find((item) => item.id === selectedSceneId);
  const generatingImage = useProjectStore((state) => (selectedSceneId ? !!state.generatingImage[selectedSceneId] : false));
  const generatingVideo = useProjectStore((state) => (selectedSceneId ? !!state.generatingVideo[selectedSceneId] : false));

  const [activeTab, setActiveTab] = useState<'image' | 'video'>('image');
  const [refineTarget, setRefineTarget] = useState<string | null>(null);
  const [imageDraft, setImageDraft] = useState<Record<string, unknown>>({});
  const [videoDraft, setVideoDraft] = useState<Record<string, unknown>>({});
  const [promptDraft, setPromptDraft] = useState<PromptDraft>(() => promptDraftFromScene(scene));
  const [promptSaveState, setPromptSaveState] = useState<PromptSaveState>('idle');
  const [paramSaveState, setParamSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const promptSceneIdRef = useRef<string | null>(scene?.id ?? null);

  useEffect(() => {
    if (!scene) {
      promptSceneIdRef.current = null;
      setPromptDraft(EMPTY_PROMPTS);
      return;
    }
    if (promptSceneIdRef.current === scene.id) return;
    promptSceneIdRef.current = scene.id;
    setPromptDraft(promptDraftFromScene(scene));
    setImageDraft({});
    setVideoDraft({});
    setPromptSaveState('idle');
    setParamSaveState('idle');
  }, [scene]);

  if (!scene) {
    return (
      <div className="mobile-scene-detail mobile-scene-detail--empty">
        未选中分镜
      </div>
    );
  }

  const imageList = getSceneImageUrls(scene);
  const effectiveImageModel = effectiveString(imageDraft, 'imageModel', scene.imageModel, globalImageModel);
  const effectiveVideoModel = effectiveString(videoDraft, 'videoModel', scene.videoModel, globalVideoModel);
  const imageParams = parseParams(imageModelOptions.find((model) => model.value === effectiveImageModel));
  const videoParams = parseParams(videoModelOptions.find((model) => model.value === effectiveVideoModel));
  const seedreamImage = isSeedreamImageModel(effectiveImageModel);
  const preset = resolveVideoPreset(videoPreset);

  const sizeOptions = imageParams.sizes?.length ? imageParams.sizes : [...IMAGE_SIZES];
  const qualityOptions = seedreamImage ? [] : (imageParams.qualities?.length ? imageParams.qualities : [...IMAGE_QUALITIES]);
  const imageCountOptions = getCountOptions(imageParams.nRange);
  const durationOptions = videoParams.durations?.length ? videoParams.durations : [4, 6, 8];
  const resolutionOptions = videoParams.resolutions?.length ? videoParams.resolutions : ['768P', '2K'];
  const aspectOptions = videoParams.aspectRatios?.length ? videoParams.aspectRatios : ['16:9', '9:16', '4:3', '1:1'];

  const effectiveImageSize = effectiveString(imageDraft, 'imageSize', scene.imageSize, imageParams.sizeDefault || globalImageSize);
  const effectiveImageQuality = effectiveString(imageDraft, 'imageQuality', scene.imageQuality, imageParams.qualityDefault || globalImageQuality);
  const effectiveImageN = effectiveNumber(imageDraft.imageN, scene.imageN, imageParams.nRange?.default, globalImageN, 1);
  const effectiveVideoDuration = effectiveNumber(videoDraft.duration, scene.duration, videoParams.durationDefault, parseInt(preset.duration, 10), 6);
  const effectiveVideoResolution = effectiveString(videoDraft, 'videoResolution', scene.videoResolution, videoParams.resolutionDefault || preset.resolution || '768P');
  const effectiveVideoAspect = effectiveString(videoDraft, 'videoAspectRatio', scene.videoAspectRatio, videoParams.aspectRatioDefault || preset.aspectRatio || '16:9');

  const dirtyParams = activeTab === 'image' ? imageDraft : videoDraft;
  const activePromptField: PromptField = activeTab === 'image' ? 'imagePrompt' : 'videoPrompt';
  const activePrompt = promptDraft[activePromptField];
  const generating = activeTab === 'image' ? generatingImage : generatingVideo;

  const persistPrompt = async (field: PromptField): Promise<boolean> => {
    const value = promptDraft[field].trim();
    const original = (scene[field] || '').trim();
    if (value === original) {
      setPromptSaveState('saved');
      return true;
    }

    setPromptSaveState('saving');
    try {
      await sceneApi.update(scene.id, { [field]: value });
      updateSceneInStore(scene.id, { [field]: value });
      setPromptDraft((current) => ({ ...current, [field]: value }));
      setPromptSaveState('saved');
      return true;
    } catch {
      setPromptSaveState('error');
      return false;
    }
  };

  const persistParams = async (draft: Record<string, unknown>): Promise<boolean> => {
    if (!Object.keys(draft).length) return true;
    setParamSaveState('saving');
    try {
      await setSceneParams(scene.id, draft);
      if (activeTab === 'image') setImageDraft({});
      else setVideoDraft({});
      setParamSaveState('saved');
      return true;
    } catch {
      setParamSaveState('error');
      return false;
    }
  };

  const persistParamChange = async (kind: 'image' | 'video', key: string, value: unknown) => {
    setParamSaveState('saving');
    try {
      await setSceneParams(scene.id, { [key]: value });
      if (kind === 'image') setImageDraft((current) => removeDraftKey(current, key));
      else setVideoDraft((current) => removeDraftKey(current, key));
      setParamSaveState('saved');
    } catch {
      setParamSaveState('error');
      alert('生成参数保存失败，请重试');
    }
  };

  const changeImageParam = (key: string, value: unknown) => {
    setImageDraft((current) => ({ ...current, [key]: value }));
    updateSceneInStore(scene.id, { [key]: value });
    void persistParamChange('image', key, value);
  };

  const changeVideoParam = (key: string, value: unknown) => {
    setVideoDraft((current) => ({ ...current, [key]: value }));
    updateSceneInStore(scene.id, { [key]: value });
    void persistParamChange('video', key, value);
  };

  const handleGenerate = async () => {
    const prompt = promptDraft[activePromptField].trim();
    if (!prompt) {
      alert(activeTab === 'image' ? '请先填写图片提示词' : '请先填写视频提示词');
      return;
    }

    const [promptSaved, paramsSaved] = await Promise.all([
      persistPrompt(activePromptField),
      persistParams(dirtyParams),
    ]);
    if (!promptSaved) {
      alert('提示词保存失败，请重试');
      return;
    }
    if (!paramsSaved) {
      alert('生成参数保存失败，请重试');
      return;
    }

    try {
      if (activeTab === 'image') {
        await generateImage(scene.id, prompt, effectiveImageModel);
      } else {
        await generateVideo(scene.id, prompt, effectiveVideoModel);
      }
    } catch (error) {
      const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
        || (error instanceof Error ? error.message : '生成失败');
      alert(message);
    }
  };

  const promptStatus = promptSaveState === 'saving'
    ? '保存中'
    : promptSaveState === 'saved'
      ? '已保存'
      : promptSaveState === 'error'
        ? '保存失败'
        : '';
  const paramStatus = paramSaveState === 'saving'
    ? '保存中'
    : paramSaveState === 'saved'
      ? '已保存'
      : paramSaveState === 'error'
        ? '保存失败'
        : '';

  return (
    <div className="mobile-scene-detail">
      <header className="mobile-scene-detail__header">
        <button type="button" className="mobile-scene-detail__back" onClick={onBack} aria-label="返回分镜列表">
          <ArrowLeft size={20} strokeWidth={1.8} />
        </button>
        <div className="mobile-scene-detail__heading">
          <strong>分镜 {scene.sceneNumber}</strong>
          <span>{currentProject?.name || '分镜脚本'}</span>
        </div>
        <span aria-hidden="true" />
      </header>

      <main className="mobile-scene-detail__content">
        <section className="mobile-scene-detail__intro">
          <div>
            <h1>分镜详情</h1>
          </div>
          <div className="mobile-scene-detail__statuses" aria-label="生成状态">
            <span className={statusClass('image', scene.imageStatus)}>
              {scene.imageStatus === 'generating' ? '图片生成中' : scene.imageStatus === 'completed' ? '图片已生成' : scene.imageStatus === 'failed' ? '图片失败' : '图片未生成'}
            </span>
            <span className={statusClass('video', scene.videoStatus)}>
              {scene.videoStatus === 'generating' ? '视频生成中' : scene.videoStatus === 'completed' ? '视频已生成' : scene.videoStatus === 'failed' ? '视频失败' : '视频未生成'}
            </span>
          </div>
        </section>

        <section className="mobile-scene-detail__section">
          <div className="mobile-scene-detail__section-head">
            <div>
              <h2>分镜内容</h2>
            </div>
            <span className={`mobile-scene-detail__save-state${promptSaveState === 'error' ? ' is-error' : ''}`}>{promptStatus}</span>
          </div>
          <textarea
            id="mobile-scene-script"
            className="mobile-scene-detail__textarea"
            value={promptDraft.scriptContent}
            onChange={(event) => setPromptDraft((current) => ({ ...current, scriptContent: event.target.value }))}
            onBlur={() => void persistPrompt('scriptContent')}
            placeholder="输入分镜描述..."
            rows={4}
          />
        </section>

        <section className="mobile-scene-detail__section mobile-scene-detail__generation">
          <div className="mobile-scene-detail__section-head">
            <div>
              <h2>生成设置</h2>
            </div>
            <span className={`mobile-scene-detail__save-state${paramSaveState === 'error' ? ' is-error' : ''}`}>{paramStatus}</span>
          </div>

          <div className="mobile-scene-detail__tabs" role="tablist" aria-label="生成类型">
            {(['image', 'video'] as const).map((tab) => (
              <button
                key={tab}
                type="button"
                role="tab"
                aria-selected={activeTab === tab}
                className={activeTab === tab ? 'is-active' : ''}
                onClick={() => setActiveTab(tab)}
              >
                {tab === 'image' ? '图片' : '视频'}
              </button>
            ))}
          </div>

          <div className="mobile-scene-detail__field">
            <label htmlFor={`mobile-${activeTab}-prompt`}>
              {activeTab === 'image' ? '图片提示词' : '视频提示词'}
            </label>
            <textarea
              id={`mobile-${activeTab}-prompt`}
              className="mobile-scene-detail__textarea mobile-scene-detail__textarea--prompt"
              value={activePrompt}
              onChange={(event) => setPromptDraft((current) => ({ ...current, [activePromptField]: event.target.value }))}
              onBlur={() => void persistPrompt(activePromptField)}
              placeholder={activeTab === 'image' ? '输入图片生成提示词...' : '输入视频生成提示词...'}
              rows={5}
            />
          </div>

          {activeTab === 'image' ? (
            <div className="mobile-scene-detail__params">
              <div className="mobile-scene-detail__param is-wide">
                <label>模型</label>
                <Select
                  value={effectiveImageModel}
                  itemToStringLabel={(value) => imageModelOptions.find((model) => model.value === value)?.label ?? value}
                  onValueChange={(value) => { if (value) changeImageParam('imageModel', value); }}
                >
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {imageModelOptions.map((model) => <SelectItem key={model.value} value={model.value}>{model.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="mobile-scene-detail__param">
                <label>尺寸</label>
                <Select value={effectiveImageSize} itemToStringLabel={(value) => value} onValueChange={(value) => { if (value) changeImageParam('imageSize', value); }}>
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {sizeOptions.map((size) => <SelectItem key={size} value={size}>{size}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              {!seedreamImage && (
                <div className="mobile-scene-detail__param">
                  <label>质量</label>
                  <Select value={effectiveImageQuality} itemToStringLabel={(value) => value} onValueChange={(value) => { if (value) changeImageParam('imageQuality', value); }}>
                    <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {qualityOptions.map((quality) => <SelectItem key={quality} value={quality}>{quality}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
              )}
              <div className="mobile-scene-detail__param">
                <label>数量</label>
                <Select value={String(effectiveImageN)} itemToStringLabel={(value) => `${value} 张`} onValueChange={(value) => changeImageParam('imageN', Number(value))}>
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {imageCountOptions.map((count) => <SelectItem key={count} value={String(count)}>{count} 张</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </div>
          ) : (
            <div className="mobile-scene-detail__params">
              <div className="mobile-scene-detail__param is-wide">
                <label>模型</label>
                <Select
                  value={effectiveVideoModel}
                  itemToStringLabel={(value) => videoModelOptions.find((model) => model.value === value)?.label ?? value}
                  onValueChange={(value) => { if (value) changeVideoParam('videoModel', value); }}
                >
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {videoModelOptions.map((model) => <SelectItem key={model.value} value={model.value}>{model.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="mobile-scene-detail__param">
                <label>时长</label>
                <Select value={String(effectiveVideoDuration)} itemToStringLabel={(value) => `${value} 秒`} onValueChange={(value) => changeVideoParam('duration', Number(value))}>
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {durationOptions.map((duration) => <SelectItem key={duration} value={String(duration)}>{duration} 秒</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="mobile-scene-detail__param">
                <label>分辨率</label>
                <Select value={effectiveVideoResolution} itemToStringLabel={(value) => value} onValueChange={(value) => { if (value) changeVideoParam('videoResolution', value); }}>
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {resolutionOptions.map((resolution) => <SelectItem key={resolution} value={resolution}>{resolution}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div className="mobile-scene-detail__param is-wide">
                <label>画幅</label>
                <Select value={effectiveVideoAspect} itemToStringLabel={(value) => value} onValueChange={(value) => { if (value) changeVideoParam('videoAspectRatio', value); }}>
                  <SelectTrigger className="mobile-scene-detail__select"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {aspectOptions.map((aspect) => <SelectItem key={aspect} value={aspect}>{aspect}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
            </div>
          )}

          <div className="mobile-scene-detail__media">
            {activeTab === 'image' ? (
              imageList.length ? (
                <div className={`mobile-scene-detail__media-grid${imageList.length > 1 ? ' is-multiple' : ''}`}>
                  {imageList.map((url, index) => (
                    <img
                      key={`${url}-${index}`}
                      src={assetUrl(url)}
                      alt={`分镜 ${scene.sceneNumber} 图片 ${index + 1}`}
                      loading="lazy"
                      className={index === 0 ? 'is-featured' : ''}
                    />
                  ))}
                </div>
              ) : (
                <div className="mobile-scene-detail__placeholder">暂无图片，使用当前设置开始生成。</div>
              )
            ) : (
              scene.videoUrl ? (
                <video src={assetUrl(scene.videoUrl)} controls playsInline />
              ) : (
                <div className="mobile-scene-detail__placeholder">暂无视频，使用当前设置开始生成。</div>
              )
            )}
          </div>
        </section>
      </main>

      <footer className="mobile-scene-detail__actions">
        {activeTab === 'image' && imageList.length > 0 && (
          <button
            type="button"
            className="mobile-scene-detail__action mobile-scene-detail__action--secondary"
            onClick={() => setRefineTarget(imageList[0])}
          >
            完善图片
          </button>
        )}
        <button
          type="button"
          className="mobile-scene-detail__action mobile-scene-detail__action--primary"
          onClick={() => void handleGenerate()}
          disabled={generating}
        >
          {generating
            ? activeTab === 'image' ? '图片生成中...' : '视频生成中...'
            : activeTab === 'image'
              ? imageList.length ? '重新生成图片' : '生成图片'
              : scene.videoUrl ? '重新生成视频' : '生成视频'}
        </button>
      </footer>

      {refineTarget && (
        <RefineImageModal
          imageUrl={refineTarget}
          sceneId={scene.id}
          onClose={() => setRefineTarget(null)}
        />
      )}
    </div>
  );
}

function effectiveString(draft: Record<string, unknown>, key: string, sceneValue: string | undefined, fallback: string): string {
  const draftValue = draft[key];
  if (typeof draftValue === 'string' && draftValue) return draftValue;
  if (typeof sceneValue === 'string' && sceneValue) return sceneValue;
  return fallback;
}

function promptDraftFromScene(scene?: { scriptContent?: string; imagePrompt?: string; videoPrompt?: string }): PromptDraft {
  if (!scene) return EMPTY_PROMPTS;
  return {
    scriptContent: scene.scriptContent || '',
    imagePrompt: scene.imagePrompt || '',
    videoPrompt: scene.videoPrompt || '',
  };
}

function removeDraftKey(draft: Record<string, unknown>, key: string): Record<string, unknown> {
  if (!(key in draft)) return draft;
  const next = { ...draft };
  delete next[key];
  return next;
}

function effectiveNumber(...values: unknown[]): number {
  const numeric = values.find((value) => typeof value === 'number' && value > 0);
  return typeof numeric === 'number' ? numeric : 1;
}

function getCountOptions(range?: { min?: number; max?: number }): number[] {
  const min = Math.max(1, range?.min ?? 1);
  const max = Math.max(min, Math.min(range?.max ?? 4, 8));
  return Array.from({ length: max - min + 1 }, (_, index) => min + index);
}

function statusClass(kind: 'image' | 'video', status: string): string {
  return `mobile-scene-detail__status mobile-scene-detail__status--${kind}${status === 'completed' ? ' is-complete' : status === 'failed' ? ' is-error' : ''}`;
}

function parseParams(model: { params?: unknown } | undefined): {
  sizes?: string[];
  sizeDefault?: string;
  qualities?: string[];
  qualityDefault?: string;
  nRange?: { min?: number; max?: number; default?: number };
  durations?: number[];
  durationDefault?: number;
  resolutions?: string[];
  resolutionDefault?: string;
  aspectRatios?: string[];
  aspectRatioDefault?: string;
} {
  const out: Record<string, unknown> = {};
  if (!model?.params) return out;
  let params: Record<string, unknown>;
  try {
    params = typeof model.params === 'string' ? JSON.parse(model.params) : model.params;
  } catch {
    return out;
  }
  const arr = (key: string) => Array.isArray(params[key]) ? (params[key] as unknown[]).map(String) : undefined;
  out.sizes = arr('sizes');
  out.sizeDefault = params.sizeDefault != null ? String(params.sizeDefault) : undefined;
  out.qualities = arr('qualities');
  out.qualityDefault = params.qualityDefault != null ? String(params.qualityDefault) : undefined;
  if (params.n && typeof params.n === 'object') {
    const range = params.n as Record<string, number>;
    if (range.min != null || range.max != null || range.default != null) {
      out.nRange = { min: range.min, max: range.max, default: range.default };
    }
  }
  if (Array.isArray(params.durations)) out.durations = (params.durations as unknown[]).map(Number);
  out.durationDefault = params.durationDefault != null ? Number(params.durationDefault) : undefined;
  out.resolutions = arr('resolutions');
  out.resolutionDefault = params.resolutionDefault != null ? String(params.resolutionDefault) : undefined;
  out.aspectRatios = arr('aspectRatios');
  out.aspectRatioDefault = params.aspectRatioDefault != null ? String(params.aspectRatioDefault) : undefined;
  return out;
}
